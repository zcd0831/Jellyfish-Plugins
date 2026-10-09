package zcd.jellyfish.script;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议帧写出器的单元测试：验证「写不阻塞调用方、顺序不乱、积压有上限、写失败立刻回报、关闭后线程不残留」。
 * <p>
 * 用内存假进程而不是真管道：这里要验的是<b>写出策略</b>（谁在什么线程上写、写不进去时谁先知道），
 * 而真实管道能不能承载逐行 JSON 由 {@link CommonsExecScriptProcessTest} 单独覆盖。
 * 用真管道反而验不准——想让它写满，得先塞满 64 KiB 的内核缓冲区。
 *
 * @author zcd
 */
@DisplayName("协议帧写出器")
class FrameWriterTest {

    /** 被测写出器，用例结束时统一关闭。 */
    private FrameWriter writer;

    /** 假进程。 */
    private BlockingProcess process;

    /**
     * 关闭写出器，避免用例失败时留下线程。
     */
    @AfterEach
    void tearDown() {
        if (writer != null) {
            process.release();
            writer.close(2000);
            writer = null;
        }
    }

    @Test
    @DisplayName("进程写不动时 send 应立即返回，而不是跟着阻塞")
    void send_should_returnImmediately_when_processWriteBlocks() {
        writer = newWriter(new AtomicReference<JellyfishException>());
        process.blockWrites();

        long startedAt = System.currentTimeMillis();
        writer.send("{\"id\":1,\"method\":\"invoke\"}");
        long elapsed = System.currentTimeMillis() - startedAt;

        // 卡住的是写线程，不是调用者——这正是「写不受调用截止时间约束」被修好的那一半
        assertTrue(elapsed < 1000L, "send 阻塞了 " + elapsed + " ms");
        // 而它确实卡住了（不是悄悄写失败了）：假进程能报出「有人在写、但出不去」
        assertTrue(process.awaitBlocked(), "写线程没有进到那次写里");
    }

    @Test
    @DisplayName("写出顺序应与提交顺序一致")
    void send_should_keepOrder() {
        writer = newWriter(new AtomicReference<JellyfishException>());

        writer.send("a");
        writer.send("b");
        writer.send("c");

        assertEquals(Arrays.asList("a", "b", "c"), process.awaitWritten(3));
    }

    @Test
    @DisplayName("积压到上限时应立刻失败，而不是让帧无限堆在内存里")
    void send_should_fail_when_queueIsFull() {
        writer = newWriter(new AtomicReference<JellyfishException>());
        process.blockWrites();

        int accepted = 0;
        JellyfishException failure = null;
        for (int index = 0; index < FrameWriter.CAPACITY * 2 && failure == null; index++) {
            try {
                writer.send("frame-" + index);
                accepted++;
            } catch (JellyfishException e) {
                // 写线程可能已经把队首那一帧取走了，因此「第几个被拒」不确定——只要求它一定被拒
                failure = e;
            }
        }

        assertNotNull(failure, "队列没有满：已接受 " + accepted + " 帧");
        assertTrue(failure.getMessage().contains("积压"), failure.getMessage());
        assertTrue(accepted >= FrameWriter.CAPACITY,
                "上限应至少容纳 " + FrameWriter.CAPACITY + " 帧，实际只接受 " + accepted + " 帧");
    }

    @Test
    @DisplayName("写失败应立即回报，并让后续 send 立刻失败")
    void send_should_reportFailure_when_processWriteFails() {
        AtomicReference<JellyfishException> reported = new AtomicReference<JellyfishException>();
        writer = newWriter(reported);
        process.failWrites();

        writer.send("boom");

        JellyfishException failure = awaitReported(reported);
        assertTrue(failure.getMessage().contains("管道"), failure.getMessage());
        JellyfishException next = assertThrows(JellyfishException.class, () -> writer.send("after"));
        assertEquals(failure, next, "后续 send 应直接拿到同一个失败，而不是另行入队");
    }

    @Test
    @DisplayName("关闭后写线程应停下，且不再接受新的帧")
    void close_should_stopWriterThread() {
        writer = newWriter(new AtomicReference<JellyfishException>());
        writer.send("a");
        process.awaitWritten(1);

        writer.close(2000);

        assertFalse(writer.isWriterAlive(), "写线程没有停下");
        assertThrows(JellyfishException.class, () -> writer.send("b"));
    }

    /**
     * 构造被测写出器。
     *
     * @param reported 写失败的回报槽位
     * @return 写出器
     */
    private FrameWriter newWriter(AtomicReference<JellyfishException> reported) {
        process = new BlockingProcess();
        return new FrameWriter(process, reported::set);
    }

    /**
     * 等写失败的回报到达。
     * <p>
     * 用轮询而不是闩锁：失败是在写线程上被发现的，用例只能等它出现——把闩锁塞进被测代码里
     * 就成了「为了测试改生产代码」。
     *
     * @param reported 回报槽位
     * @return 收到的失败
     */
    private static JellyfishException awaitReported(AtomicReference<JellyfishException> reported) {
        for (int attempt = 0; attempt < 100; attempt++) {
            JellyfishException failure = reported.get();
            if (failure != null) {
                return failure;
            }
            sleep();
        }
        assertNotNull(reported.get(), "写失败没有被回报");
        return null;
    }

    /**
     * 睡一小会儿再查。
     */
    private static void sleep() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 可被要求「写不动」「写就报错」的假进程。
     */
    private static final class BlockingProcess implements ScriptProcess {

        /** 已写出的帧，按写出顺序。 */
        private final LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<String>();

        /** 阻塞写用的闸门。 */
        private final CountDownLatch gate = new CountDownLatch(1);

        /** 「已经进到一次写里」的信号。 */
        private final CountDownLatch blocked = new CountDownLatch(1);

        /** 是否让写挂住。 */
        private volatile boolean blockWrites;

        /** 是否让写失败。 */
        private volatile boolean failWrites;

        @Override
        public void send(String line) {
            if (blockWrites) {
                blocked.countDown();
                if (!awaitGate()) {
                    throw new JellyfishException("写被中断");
                }
            }
            if (failWrites) {
                throw new JellyfishException("管道断了（模拟进程已消失）");
            }
            sent.add(line);
        }

        /**
         * 等「已经进到一次写里、但出不去」发生。
         *
         * @return 观察到返回 {@code true}；超时返回 {@code false}
         */
        private boolean awaitBlocked() {
            try {
                return blocked.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void close(long graceMillis, long killMillis) {
            // 真进程的关闭会拆掉管道，于是阻塞中的写以失败告终；假进程用同一手段叫醒写线程
            release();
        }

        /**
         * 让写挂住。
         */
        private void blockWrites() {
            blockWrites = true;
        }

        /**
         * 让写失败。
         */
        private void failWrites() {
            failWrites = true;
        }

        /**
         * 放行阻塞中的写。
         */
        private void release() {
            gate.countDown();
        }

        /**
         * 等待闸门放行。
         *
         * @return 放行返回 {@code true}；超时返回 {@code false}
         */
        private boolean awaitGate() {
            try {
                return gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * 等假进程写出指定帧数。
         *
         * @param count 期望帧数
         * @return 已写出的帧（不足则用例失败）
         */
        private List<String> awaitWritten(int count) {
            List<String> written = new ArrayList<String>();
            for (int index = 0; index < count; index++) {
                String line = poll();
                assertNotNull(line, "只等到 " + written.size() + " 帧，期望 " + count + " 帧");
                written.add(line);
            }
            assertNull(sent.peek(), "还多写了帧: " + sent);
            return written;
        }

        /**
         * 阻塞地取一帧。
         *
         * @return 帧文本；超时返回 {@code null}
         */
        private String poll() {
            try {
                return sent.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
