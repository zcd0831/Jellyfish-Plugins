package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 协议帧的写出器：把「写」从调用线程挪到一条专属写线程上，并把积压量钉死。
 * <p>
 * <b>为什么不能直接在调用线程上写</b>：子进程的 stdin 是一根有界的管道（内核里通常 64 KiB）。
 * 网关是单线程的，它执行一次 {@code invoke} 期间不读 stdin，因此管道写满时 {@code write} 会
 * <b>阻塞到网关回到读循环为止</b>；而更坏的情况是它根本回不来（读循环自己死了）。
 * 那个阻塞发生在 {@code ScriptRpc} 进入等待之前，于是<b>调用超时对它完全无效</b>——
 * {@code ReAct} 的派发线程就被永久挂住了，按 Esc 也没有用（取消只翻标志，没人检查它）。
 * 这是本类存在的全部理由：让「写不进去」退化成「这次调用超时失败」，那是调用方能处理的形态。
 * <p>
 * <b>整行原子与顺序由这条线程保证</b>：队列是 FIFO，写方只有一条线程，因此两行内容不会交错、
 * 提交顺序与写出顺序一致——{@code ScriptRpc.Sender} 要求的那条契约在这里落地，
 * 而不需要协议层自己加锁。
 * <p>
 * <b>积压量有上限，而不是无界排队</b>：队列满了说明网关已经连续 {@value #CAPACITY} 帧没有读进去，
 * 那是「这一代已经不可用」而不是「稍等一下就好」。此时立刻失败（调用方拿到连接不可用的失败、
 * 通知由事件桥按「事件可丢」处理），比让帧无限堆在内存里更接近事实。
 * <p>
 * <b>写失败是异步发现的，因此必须回报</b>：进程没了或管道断了，只有写线程看得见。
 * 若不回报，等待应答的调用方就只会等到超时（表现是「调用变慢」而不是「连接坏了」），
 * 因此这里立刻把失败交给 {@code onFailure}，由网关唤醒这一代的全部等待者。
 * <p>
 * 线程安全：{@link #send(String)} 可来自任意线程。
 *
 * @author zcd
 */
final class FrameWriter {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(FrameWriter.class);

    /**
     * 队列容量。包私有供测试与文档引用同一个数。
     * <p>
     * 它只需要盖住「同一时刻在途的帧数」：一帧对应一个正在等待应答的调用，或事件桥队列里的一个槽位，
     * 两者都是有界的。{@value #CAPACITY} 帧对这两个来源都极宽。
     */
    static final int CAPACITY = 256;

    /** 目标进程。 */
    private final ScriptProcess process;

    /** 写失败时的回报入口。 */
    private final Consumer<JellyfishException> onFailure;

    /** 待写出的帧，FIFO。 */
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(CAPACITY);

    /** 是否已关闭。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 写线程。 */
    private final Thread thread;

    /** 已发现的写失败；{@code null} 表示还没有。 */
    private volatile JellyfishException failure;

    /**
     * 构造写出器并启动写线程。
     *
     * @param process   目标进程，不可为 {@code null}
     * @param onFailure 写失败时的回报入口，不可为 {@code null}
     */
    FrameWriter(ScriptProcess process, Consumer<JellyfishException> onFailure) {
        this.process = process;
        this.onFailure = onFailure;
        this.thread = new Thread(this::drain, "jellyfish-script-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * 把一帧交给写线程，立刻返回。
     * <p>
     * 本方法<b>不会阻塞在管道上</b>，因此调用方的截止时间仍然有效。
     *
     * @param line 帧文本，不可为 {@code null}
     * @throws JellyfishException 已经关闭、已发现写失败、或积压到上限时抛出
     */
    void send(String line) {
        JellyfishException broken = failure;
        if (broken != null) {
            // 写线程已经退出，再入队只会堆到上限才失败：这里立刻说清「这一代已经不可用」
            throw broken;
        }
        if (closed.get()) {
            throw new JellyfishException("脚本网关的写出行已关闭，无法发送协议帧");
        }
        if (!queue.offer(line)) {
            throw new JellyfishException("协议帧积压已达上限（" + CAPACITY
                    + " 帧），脚本网关未及时读取；请检查脚本是否卡住了 stdin");
        }
    }

    /**
     * 获取当前待写出的帧数，供诊断与测试。
     *
     * @return 待写出帧数
     */
    int pendingCount() {
        return queue.size();
    }

    /**
     * 判断写线程是否仍在运行，供测试断言「关闭之后没有线程残留」。
     *
     * @return 仍在运行返回 {@code true}
     */
    boolean isWriterAlive() {
        return thread.isAlive();
    }

    /**
     * 停止写线程，幂等。
     * <p>
     * <b>必须在目标进程被关掉之后调用</b>：写线程可能正阻塞在管道写上，而叫醒它的唯一办法是
     * 拆掉管道——那由 {@code ScriptProcess.close} 完成。本方法只负责「有界地等它退出」，
     * 等不到就如实告警（残留的是一条守护线程，不会拖住进程退出）。
     *
     * @param graceMillis 等待写线程退出的毫秒数
     */
    void close(long graceMillis) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(Math.max(0L, graceMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOG.warn("协议写线程未在 {} ms 内退出（管道可能仍然写不进去），已放弃等待", Long.valueOf(graceMillis));
        }
    }

    /**
     * 写线程主循环：逐帧写出，直到关闭或写失败。
     * <p>
     * 写失败即退出：它意味着这一代进程已经不可用，继续把队列里的帧喂给一个坏管道没有意义。
     */
    private void drain() {
        while (!closed.get()) {
            String line;
            try {
                // 带超时地取，而不是无限等：关闭时线程可能正卡在取之上，interrupt 能叫醒它，
                // 但这个超时让「关闭后必须停」这条不依赖于 interrupt 一定送达
                line = queue.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (line == null) {
                continue;
            }
            try {
                process.send(line);
            } catch (JellyfishException e) {
                fail(e);
                return;
            }
        }
    }

    /**
     * 记录写失败并回报。
     *
     * @param cause 失败原因
     */
    private void fail(JellyfishException cause) {
        failure = cause;
        LOG.warn("发送协议帧失败，这一代脚本网关不再可用: {}", cause.getMessage());
        try {
            onFailure.accept(cause);
        } catch (RuntimeException e) {
            // 回报失败不该让写线程再抛一次：失败已经记录了，调用方会从等待里被唤醒
            LOG.warn("回报协议写失败时出错: {}", e.toString());
        }
    }
}
