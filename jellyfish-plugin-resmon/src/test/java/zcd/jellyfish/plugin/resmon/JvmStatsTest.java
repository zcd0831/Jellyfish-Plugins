package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JvmStats} 的单元测试：三个派生比例，以及「不可用」与「零」的区别。
 * <p>
 * 派生比例的边界都指向同一件事：分母没有意义时必须报不可用，而不是报一个看起来正常的 0%
 * ——「堆没有上限」打成「堆用了 0%」会让面板在最危险的那台机器上显示得最健康。
 *
 * @author zcd
 */
@DisplayName("JVM 快照")
class JvmStatsTest {

    @Test
    @DisplayName("建造者的初值是不可用，不是零")
    void builder_should_default_to_unknown() {
        JvmStats stats = JvmStats.builder().build();

        assertEquals(JvmStats.UNKNOWN, stats.heapUsedBytes());
        assertEquals(JvmStats.UNKNOWN, stats.heapMaxBytes());
        assertEquals(JvmStats.UNKNOWN, stats.metaspaceUsedBytes());
        assertEquals(JvmStats.UNKNOWN, stats.maxFileDescriptorCount());
        assertEquals(JvmStats.UNKNOWN_PERCENT, stats.processCpuPercent());
        assertEquals(JvmStats.UNKNOWN, stats.threadCount());
    }

    @Test
    @DisplayName("堆比例按已用比上限算")
    void heapPercent_should_compute() {
        JvmStats stats = JvmStats.builder().heap(512L, 1024L, 2048L).build();

        assertEquals(25.0, stats.heapPercent(), 0.001);
    }

    @Test
    @DisplayName("堆没有上限时报不可用，而不是 0%")
    void heapPercent_should_be_unknown_when_max_missing() {
        JvmStats stats = JvmStats.builder().heap(512L, 1024L, JvmStats.UNKNOWN).build();

        assertEquals(JvmStats.UNKNOWN_PERCENT, stats.heapPercent());
    }

    @Test
    @DisplayName("GC 占比按增量算，首次采样（无窗口）报不可用")
    void gcPercent_should_use_delta() {
        JvmStats stats = JvmStats.builder().gc(10L, 400L, 1L, 100L, 10000L).build();

        assertEquals(5.0, stats.gcPercent(), 0.001);
        assertEquals(11L, stats.gcCount());
        assertEquals(500L, stats.gcTimeMillis());
    }

    @Test
    @DisplayName("没有采样窗口时 GC 占比不可用")
    void gcPercent_should_be_unknown_on_first_sample() {
        JvmStats stats = JvmStats.builder().gc(0L, 0L, 0L, 0L, 0L).build();

        assertEquals(JvmStats.UNKNOWN_PERCENT, stats.gcPercent());
    }

    @Test
    @DisplayName("文件描述符比例按已开比上限算")
    void fdPercent_should_compute() {
        JvmStats stats = JvmStats.builder().fileDescriptors(210L, 8192L).build();

        assertEquals(2.56, stats.fdPercent(), 0.01);
    }

    @Test
    @DisplayName("平台不报文件描述符上限时比例不可用")
    void fdPercent_should_be_unknown_without_limits() {
        JvmStats stats = JvmStats.builder().fileDescriptors(JvmStats.UNKNOWN, JvmStats.UNKNOWN).build();

        assertEquals(JvmStats.UNKNOWN_PERCENT, stats.fdPercent());
    }

    @Test
    @DisplayName("内存池、线程、CPU 各字段按设置读回")
    void fields_should_round_trip() {
        JvmStats stats = JvmStats.builder()
                .capturedAt(1234L)
                .uptime(5678L)
                .nonHeap(96L)
                .memoryPools(72L, 12L, 8L)
                .threads(42L, 61L, 28L)
                .deadlocked(2L)
                .loadedClasses(6821L)
                .cpu(12.5, 34.0)
                .availableProcessors(8)
                .build();

        assertEquals(1234L, stats.capturedAtMillis());
        assertEquals(5678L, stats.uptimeMillis());
        assertEquals(72L, stats.metaspaceUsedBytes());
        assertEquals(12L, stats.compressedClassSpaceUsedBytes());
        assertEquals(8L, stats.codeCacheUsedBytes());
        assertEquals(42L, stats.threadCount());
        assertEquals(61L, stats.peakThreadCount());
        assertEquals(28L, stats.daemonThreadCount());
        assertEquals(2L, stats.deadlockedThreadCount());
        assertEquals(6821L, stats.loadedClassCount());
        assertEquals(12.5, stats.processCpuPercent(), 0.001);
        assertEquals(34.0, stats.systemCpuPercent(), 0.001);
        assertEquals(8, stats.availableProcessors());
        assertTrue(stats.toString().contains("42"));
    }
}
