package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertEvaluator} 的单元测试：重点是「只在上升沿报一次」这条状态机。
 * <p>
 * 采样每 2 秒一次，界面信箱每个插件来源只有几十格，因此这条规则不是优化而是必需：
 * 少一次去重，一条告警就能把自己刷掉。回落之后必须能再报——那往往是真正需要人处理的那一次。
 *
 * @author zcd
 */
@DisplayName("阈值告警判定")
class AlertEvaluatorTest {

    /** 被测判定器。 */
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_ALERT_HEAP_PERCENT, 85);
        values.put(PluginConfig.KEY_ALERT_DISK_PERCENT, 90);
        values.put(PluginConfig.KEY_ALERT_FD_PERCENT, 80);
        evaluator = new AlertEvaluator(PluginConfig.from(values));
    }

    @Test
    @DisplayName("越过阈值的第一次上报一条，之后持续越界不再报")
    void evaluate_should_fire_once_on_rising_edge() {
        List<Alert> first = evaluator.evaluate(heap(90.0), DiskReport.empty());
        assertEquals(1, first.size());
        assertEquals(AlertEvaluator.KEY_HEAP, first.get(0).key());

        assertEquals(0, evaluator.evaluate(heap(92.0), DiskReport.empty()).size());
        assertEquals(0, evaluator.evaluate(heap(99.0), DiskReport.empty()).size());
    }

    @Test
    @DisplayName("回落到阈值以下再越界时重新报：那一次往往是真问题")
    void evaluate_should_fire_again_after_recovery() {
        evaluator.evaluate(heap(90.0), DiskReport.empty());
        evaluator.evaluate(heap(50.0), DiskReport.empty());

        assertEquals(1, evaluator.evaluate(heap(95.0), DiskReport.empty()).size());
    }

    @Test
    @DisplayName("没到阈值不报，堆上限未知也不报")
    void evaluate_should_staySilent_belowThreshold() {
        assertEquals(0, evaluator.evaluate(heap(80.0), DiskReport.empty()).size());
        JvmStats noMax = JvmStats.builder().heap(999L, 999L, JvmStats.UNKNOWN).build();
        assertEquals(0, evaluator.evaluate(noMax, DiskReport.empty()).size());
    }

    @Test
    @DisplayName("文件描述符越界单独成一条告警")
    void evaluate_should_fire_on_fileDescriptors() {
        JvmStats stats = JvmStats.builder().fileDescriptors(900L, 1000L).build();

        List<Alert> alerts = evaluator.evaluate(stats, DiskReport.empty());

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_FD, alerts.get(0).key());
    }

    @Test
    @DisplayName("死锁只要出现就报，且与阈值无关")
    void evaluate_should_fire_on_deadlock() {
        JvmStats stats = JvmStats.builder().deadlocked(2L).build();

        List<Alert> alerts = evaluator.evaluate(stats, DiskReport.empty());

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_DEADLOCK, alerts.get(0).key());
        assertTrue(alerts.get(0).text().contains("2"));
    }

    @Test
    @DisplayName("分区越界单独成一条告警")
    void evaluate_should_fire_on_partition() {
        DiskReport disk = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), 100L, 5L, -1L, -1L);

        List<Alert> alerts = evaluator.evaluate(null, disk);

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_DISK, alerts.get(0).key());
        assertTrue(alerts.get(0).text().contains("95%"));
    }

    @Test
    @DisplayName("还没扫过盘的空报告不参与判定")
    void evaluate_should_ignore_unscanned_disk() {
        assertEquals(0, evaluator.evaluate(null, DiskReport.empty()).size());
    }

    @Test
    @DisplayName("没有 JVM 快照时也能只判磁盘")
    void evaluate_should_tolerate_missing_jvm() {
        DiskReport disk = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), 100L, 50L, -1L, -1L);

        assertEquals(0, evaluator.evaluate(null, disk).size());
    }

    @Test
    @DisplayName("告警文本带上实测读数，便于判断有多严重")
    void evaluate_should_include_reading_in_text() {
        Alert alert = evaluator.evaluate(heap(92.0), DiskReport.empty()).get(0);

        assertTrue(alert.text().contains("92%"), alert.text());
        assertTrue(alert.text().contains("85%"), alert.text());
    }

    /**
     * 造一个堆占用为指定百分比的快照。
     *
     * @param percent 百分比
     * @return 快照
     */
    private static JvmStats heap(double percent) {
        long max = 1000L;
        return JvmStats.builder().heap(Math.round(max * percent / 100.0), max, max).build();
    }
}
