package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManagementJvmProbe} 的单元测试：对一个真实运行的 JVM 采样。
 * <p>
 * 这里不做 mock：被测的正是「JMX 到底会回答什么」。这些断言只钉「必然成立的事实」
 * （堆已用大于零、至少有一个线程、类加载数大于零），因为具体数值随机器与运行参数变化——
 * 钉死数字的断言会在别人的机器上红，而那不代表功能坏了。
 * <p>
 * 不验死锁检测：构造真死锁要么污染测试进程、要么需要另起一个 JVM，代价远大于它带来的信心。
 *
 * @author zcd
 */
@DisplayName("JMX 采样")
class ManagementJvmProbeTest {

    @Test
    @DisplayName("能读到堆、非堆、线程、类加载与运行时长")
    void probe_should_read_basicFacts() {
        JvmStats stats = new ManagementJvmProbe().probe();

        assertTrue(stats.capturedAtMillis() > 0L);
        assertTrue(stats.uptimeMillis() > 0L);
        assertTrue(stats.heapUsedBytes() > 0L);
        assertTrue(stats.heapCommittedBytes() > 0L);
        assertTrue(stats.nonHeapUsedBytes() > 0L);
        assertTrue(stats.threadCount() >= 1L);
        assertTrue(stats.peakThreadCount() >= stats.threadCount());
        assertTrue(stats.loadedClassCount() > 0L);
        assertTrue(stats.availableProcessors() >= 1);
    }

    @Test
    @DisplayName("元空间这类内存池能按名字取到")
    void probe_should_read_memoryPools() {
        JvmStats stats = new ManagementJvmProbe().probe();

        assertTrue(stats.metaspaceUsedBytes() > 0L, "HotSpot 应当报出 Metaspace");
        assertTrue(stats.codeCacheUsedBytes() >= 0L);
    }

    @Test
    @DisplayName("首次采样没有可比窗口，GC 增量与占比都是「零 / 不可用」")
    void probe_should_reportZeroDelta_onFirstSample() {
        JvmStats first = new ManagementJvmProbe().probe();

        assertEquals(0L, first.gcCount());
        assertEquals(0L, first.gcTimeMillis());
        assertEquals(0L, first.gcElapsedMillis());
        assertEquals(JvmStats.UNKNOWN_PERCENT, first.gcPercent());
    }

    @Test
    @DisplayName("第二次采样报出真实的时间窗口，因此 GC 占比可用")
    void probe_should_measure_elapsedBetweenSamples() throws InterruptedException {
        ManagementJvmProbe probe = new ManagementJvmProbe();
        probe.probe();
        Thread.sleep(20L);

        JvmStats second = probe.probe();

        assertTrue(second.gcElapsedMillis() >= 10L, "两次采样之间应有可测的间隔");
        assertTrue(second.gcPercent() >= 0.0);
    }

    @Test
    @DisplayName("没有死锁时死锁线程数为零")
    void probe_should_reportNoDeadlock() {
        JvmStats stats = new ManagementJvmProbe().probe();

        assertEquals(0L, stats.deadlockedThreadCount());
    }

    @Test
    @DisplayName("文件描述符与 CPU 在支持的平台上给出可用的读数或无数据标记，不给假的零")
    void probe_should_report_fdAndCpu_consistently() {
        JvmStats stats = new ManagementJvmProbe().probe();

        if (stats.maxFileDescriptorCount() > 0L) {
            assertTrue(stats.openFileDescriptorCount() >= 0L);
        } else {
            assertEquals(JvmStats.UNKNOWN, stats.maxFileDescriptorCount());
        }
        assertTrue(stats.processCpuPercent() == JvmStats.UNKNOWN_PERCENT || stats.processCpuPercent() >= 0.0);
        assertTrue(stats.systemCpuPercent() == JvmStats.UNKNOWN_PERCENT || stats.systemCpuPercent() >= 0.0);
    }
}
