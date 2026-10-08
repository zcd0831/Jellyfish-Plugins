package zcd.jellyfish.plugin.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolOutputSink;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 执行一次命令：两条计时器、终止链、取消响应，以及输出的捕获接线。
 * <p>
 * <b>三条计时/中断来源，同一个判定点</b>：墙上时钟超时、静默超时、用户取消。
 * 它们都在同一个等待循环里判定，因此「谁先到谁触发」只有一个真源——
 * 若把墙钟交给 {@code ExecuteWatchdog}、静默与取消留在循环里，同一次调用的终止就有两个发起方，
 * 事后没人能说清是哪一个动的手。这也是本类不使用 {@code ExecuteWatchdog} 的原因
 * （脚本插件不用它是因为场景相反：那里是长命进程，硬超时会杀掉正常的运行时）。
 * <p>
 * <b>轮询而不是阻塞等待</b>：等待切片只有 {@link #POLL_MILLIS} 毫秒，因此超时、静默与取消的
 * 判定精度都是这个量级，而代价只是每秒十次 {@code isAlive()}。
 * <p>
 * <b>为什么取消回调只发信号</b>：它可能在界面渲染线程上执行（用户按下 Esc 的那一刻），
 * 因此不能等待、不能递归查进程树；完整的终止链由等待循环在几十毫秒内接手。
 * <p>
 * 线程安全：{@link #run} 由工具调用线程（{@code react} 池）进入，多个会话可能并发调用；
 * {@link #killAll()} 由插件停止线程调用。这两者的交汇处是「在途集合」，它由一把锁护着
 * （见 {@link #adopt}）。
 *
 * @author zcd
 */
final class ShellProcessRunner {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ShellProcessRunner.class);

    /** 等待切片（毫秒）：超时与静默判定的精度。 */
    static final long POLL_MILLIS = 100L;

    /** 温和终止到强杀之间的宽限（毫秒）。 */
    static final long KILL_GRACE_MILLIS = 2000L;

    /** 强杀之后等待退出的毫秒数。 */
    static final long KILL_WAIT_MILLIS = 1000L;

    /** 收尾时等待输出泵结束的毫秒数。 */
    static final long PUMP_DRAIN_MILLIS = 2000L;

    /** 进程启动器。 */
    private final ShellProcessLauncher launcher;

    /**
     * 在途进程集合，供插件停止时一并终止。
     * <p>
     * <b>访问必须全部在 {@link #lock} 内</b>：它不是并发集合，而「入表」和「停止时取快照」
     * 这两件事本就必须是互斥的（见 {@link #run}）。
     */
    private final Set<ShellProcess> active = new HashSet<ShellProcess>();

    /** 「是否已停止」与「在途集合」的互斥锁。 */
    private final Object lock = new Object();

    /**
     * 是否已停止。
     * <p>
     * 停止之后不再接受新的在途进程：{@link #killAll()} 只扫一遍集合，而它在取快照之后
     * 就再也回不来了，因此这里必须自己把门关上（见 {@link #run}）。
     */
    private boolean closed;

    /**
     * 构造执行器。
     *
     * @param launcher 进程启动器，不可为 {@code null}
     */
    ShellProcessRunner(ShellProcessLauncher launcher) {
        this.launcher = launcher;
    }

    /**
     * 执行一次命令。
     * <p>
     * 本方法不抛「命令失败」：非零退出码、超时、被取消都是<b>结果</b>而不是异常，
     * 它们要以工具结果的形式回灌给模型。只有「命令根本起不来」（解释器缺失、目录不可访问）
     * 才作为异常抛出。
     * <p>
     * <b>进程一出生就要和「停止」对一次账</b>：{@link #killAll()} 是「立旗 + 取快照」，
     * 而本方法的「起进程」与「入表」之间本来有一段窗口——停在这段窗口里的进程，快照里没有它，
     * 于是谁也杀不到它。因此入表这个动作要拿着锁去问一句「停了没」：没停才入表（快照就一定能看到它），
     * 停了就当场自己收尾（见 {@link #abort}）。这段话的顺序不能反：先入表再问，
     * 或者在锁外问，都会把窗口重新打开。
     *
     * @param invocation 调用参数，不可为 {@code null}
     * @param sink       输出捕获通道，不可为 {@code null}
     * @param token      取消令牌，不可为 {@code null}
     * @return 执行结果，保证非 {@code null}
     * @throws JellyfishException 命令无法启动时抛出
     */
    ShellResult run(ShellInvocation invocation, ToolOutputSink sink, CancellationToken token) {
        ShellOutputCapture capture = new ShellOutputCapture(sink);
        long start = System.currentTimeMillis();
        ShellProcess process;
        try {
            process = launcher.launch(invocation, capture);
        } catch (IOException e) {
            throw new JellyfishException("命令无法启动: " + e.getMessage(), e);
        }
        if (!adopt(process)) {
            return abort(process, capture, start);
        }
        try {
            process.closeStdin();
            token.onCancel(process::destroy);
            ShellResult.Termination termination = awaitTermination(process, invocation, capture, token, start);
            if (termination != ShellResult.Termination.COMPLETED) {
                ProcessTrees.killTree(process, KILL_GRACE_MILLIS, KILL_WAIT_MILLIS);
            }
            return result(process, capture, termination, start);
        } finally {
            synchronized (lock) {
                active.remove(process);
            }
            // 先等输出泵结束再收尾：不等就会丢掉输出的最后一截，而那往往是命令失败的原因
            process.awaitOutput(PUMP_DRAIN_MILLIS);
            capture.close();
        }
    }

    /**
     * 把刚启动的进程记进在途集合。
     *
     * @param process 进程句柄，不可为 {@code null}
     * @return 已停止时返回 {@code false}（调用方必须立刻自己收尾），否则返回 {@code true}
     */
    private boolean adopt(ShellProcess process) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            active.add(process);
            return true;
        }
    }

    /**
     * 收尾一个「出生晚于停止」的进程。
     * <p>
     * 它的处境与别的失败都不同：{@link #killAll()} 已经取过快照、也再不会回来，因此
     * 这里必须自己把它连根拔掉——否则就正是「jellyfish 都退出了，那条命令还在跑」。
     * 已经拿到的输出照常回灌（{@code capture} 是在起进程之前就接好的），
     * 因为「它被杀之前打印了什么」恰恰是判断这次终止是否合理的东西。
     *
     * @param process 进程句柄
     * @param capture 输出捕获流
     * @param start   起始时刻
     * @return 「插件已停止」的结果，保证非 {@code null}
     */
    private static ShellResult abort(ShellProcess process, ShellOutputCapture capture, long start) {
        LOG.warn("插件已停止，就地终止刚启动的命令: pid={}", Long.valueOf(process.pid()));
        ProcessTrees.killTree(process, KILL_GRACE_MILLIS, KILL_WAIT_MILLIS);
        process.awaitOutput(PUMP_DRAIN_MILLIS);
        capture.close();
        return ShellResult.of(ShellResult.Termination.STOPPED, null,
                System.currentTimeMillis() - start, capture.isBinary(), capture.bytes());
    }

    /**
     * 终止全部在途进程。
     * <p>
     * 插件停止与内核关闭时调用：留着它们会变成孤儿进程，在用户看来就是「jellyfish 都退出了，
     * 那条命令还在跑」。
     * <p>
     * 先立旗再取快照，两步在同一把锁内，因此此后不会再有进程溜进集合；
     * 但<b>杀进程在锁外做</b>：杀一棵进程树要等 SIGTERM 的宽限期，占着锁会让正在收尾的
     * 调用线程排队等它。幂等。
     */
    void killAll() {
        Set<ShellProcess> snapshot;
        synchronized (lock) {
            closed = true;
            snapshot = new HashSet<ShellProcess>(active);
        }
        for (ShellProcess process : snapshot) {
            LOG.warn("插件停止，终止在途命令: pid={}", Long.valueOf(process.pid()));
            ProcessTrees.killTree(process, KILL_GRACE_MILLIS, KILL_WAIT_MILLIS);
        }
    }

    /**
     * 等待进程结束，并判定是哪种终止。
     *
     * @param process    进程句柄
     * @param invocation 调用参数
     * @param capture    输出捕获流（读它的最近输出时刻）
     * @param token      取消令牌
     * @param start      起始时刻
     * @return 终止原因
     */
    private static ShellResult.Termination awaitTermination(ShellProcess process, ShellInvocation invocation,
                                                            ShellOutputCapture capture, CancellationToken token,
                                                            long start) {
        long deadline = start + invocation.timeoutMillis();
        while (true) {
            if (token.isCancelled()) {
                return ShellResult.Termination.CANCELLED;
            }
            try {
                if (process.waitFor(POLL_MILLIS)) {
                    // 进程退出之后要再确认一次取消：取消回调会直接给子进程发信号，
                    // 因此「进程退出了」在取消场景下同样成立，先判退出会把取消误报成正常完成
                    return token.isCancelled() ? ShellResult.Termination.CANCELLED
                            : ShellResult.Termination.COMPLETED;
                }
            } catch (InterruptedException e) {
                // 谁中断了我们：按取消处理最保守——进程还活着，必须要终止
                Thread.currentThread().interrupt();
                return ShellResult.Termination.CANCELLED;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                return ShellResult.Termination.TIMEOUT;
            }
            long idle = invocation.idleTimeoutMillis();
            if (idle > 0 && now - capture.lastOutputAt() >= idle) {
                return ShellResult.Termination.IDLE_TIMEOUT;
            }
        }
    }

    /**
     * 组装执行结果。
     *
     * @param process     进程句柄
     * @param capture     输出捕获流
     * @param termination 终止原因
     * @param start       起始时刻
     * @return 结果
     */
    private static ShellResult result(ShellProcess process, ShellOutputCapture capture,
                                      ShellResult.Termination termination, long start) {
        Integer exitCode = null;
        if (termination == ShellResult.Termination.COMPLETED && !process.isAlive()) {
            exitCode = Integer.valueOf(process.exitValue());
        }
        return ShellResult.of(termination, exitCode, System.currentTimeMillis() - start,
                capture.isBinary(), capture.bytes());
    }
}
