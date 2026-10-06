package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiskReport} 的单元测试：合计、分区比例、增长速度与「按体积排序后的第一项」。
 * <p>
 * 增长速度是本插件唯一「需要两份数据才能算出来」的量，也最容易算错（除零、单位、采样间隔太短），
 * 因此单独把它的边界（没有上一次、间隔为零、变小了）一一列出来。
 *
 * @author zcd
 */
@DisplayName("磁盘报告")
class DiskReportTest {

    @Test
    @DisplayName("空报告表示还没扫过，各派生值都是不可用")
    void empty_should_not_be_scanned() {
        DiskReport report = DiskReport.empty();

        assertFalse(report.scanned());
        assertTrue(report.usages().isEmpty());
        assertEquals(-1.0, report.partitionUsedPercent());
        assertEquals(-1L, report.growthBytesPerHour());
    }

    @Test
    @DisplayName("合计由各子项相加，不由调用方传")
    void of_should_sum_usages() {
        DiskReport report = DiskReport.of(1000L, usages(1024L, 2048L), -1L, -1L, -1L, -1L);

        assertEquals(3072L, report.totalBytes());
        assertTrue(report.scanned());
    }

    @Test
    @DisplayName("分区已用比例按总量与可用量算")
    void partitionUsedPercent_should_compute() {
        // 100G 里剩 25G → 已用 75%
        long total = 100L * 1024L * 1024L * 1024L;
        long usable = 25L * 1024L * 1024L * 1024L;

        DiskReport report = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), total, usable, -1L, -1L);

        assertEquals(75.0, report.partitionUsedPercent(), 0.001);
    }

    @Test
    @DisplayName("分区容量不可用时比例报不可用，而不是 0")
    void partitionUsedPercent_should_be_unknown_when_capacity_missing() {
        DiskReport report = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), -1L, -1L, -1L, -1L);

        assertEquals(-1.0, report.partitionUsedPercent());
    }

    @Test
    @DisplayName("增长速度按两次扫描折算成每小时")
    void growthBytesPerHour_should_extrapolate() {
        // 半小时涨了 6M → 每小时 12M
        long halfHour = 1800000L;
        DiskReport previous = DiskReport.of(1000L, usages(0L), -1L, -1L, -1L, -1L);
        DiskReport current = DiskReport.of(1000L + halfHour, usages(6L * 1024L * 1024L), -1L, -1L,
                previous.totalBytes(), previous.capturedAtMillis());

        assertEquals(12L * 1024L * 1024L, current.growthBytesPerHour());
    }

    @Test
    @DisplayName("目录变小了报负增长：那说明有人清理过")
    void growthBytesPerHour_should_be_negative_when_shrinking() {
        DiskReport current = DiskReport.of(3600000L, usages(1024L), -1L, -1L, 2048L, 1000L);

        assertEquals(-1024L, current.growthBytesPerHour());
    }

    @Test
    @DisplayName("没有上一次扫描或间隔非正时不报增长")
    void growthBytesPerHour_should_be_unknown_without_previous() {
        assertTrue(DiskReport.of(1000L, usages(1024L), -1L, -1L, -1L, -1L).growthBytesPerHour() < 0L);
        // 间隔为 0：同一时刻的两次扫描，折算每小时会把任意小的抖动放大成天文数字
        assertTrue(DiskReport.of(1000L, usages(1024L), -1L, -1L, 512L, 1000L).growthBytesPerHour() < 0L);
    }

    @Test
    @DisplayName("按名字取子项，取不到返回 null")
    void usageOf_should_find_by_name() {
        DiskReport report = DiskReport.of(1000L, usages(1024L), -1L, -1L, -1L, -1L);

        assertEquals(1024L, report.usageOf("a0").bytes());
        assertNull(report.usageOf("nope"));
    }

    @Test
    @DisplayName("子项列表不可被外部修改")
    void usages_should_be_immutable() {
        DiskReport report = DiskReport.of(1000L, usages(1L, 2L), -1L, -1L, -1L, -1L);

        assertThrows(UnsupportedOperationException.class,
                () -> report.usages().add(new PathUsage("x", Paths.get("/tmp/x"), 0L, 0L, 0L, true)));
    }

    /**
     * 造一组占用结果，名字为 {@code a0}、{@code a1}……
     *
     * @param sizes 各子项的体积
     * @return 占用结果列表，保证非 {@code null}
     */
    private static List<PathUsage> usages(long... sizes) {
        List<PathUsage> list = new ArrayList<PathUsage>();
        for (int i = 0; i < sizes.length; i++) {
            list.add(new PathUsage("a" + i, Paths.get("/tmp/a" + i), sizes[i], 1L, 0L, true));
        }
        return list;
    }
}
