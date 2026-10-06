package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertEvaluator} 的单元测试：判据、阈值边界与「不可用不等于零」。
 * <p>
 * 判定器现在是无状态的——它回答「此刻有哪些项越界」，而不是「哪几项刚刚越界」。
 * 因此这里验的是集合内容，不再有「第二次不报」这类断言：持续越界只显示一份，是
 * 「同一时刻只渲染一份状态」这件事的自然结果，不需要状态机来保证。
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
    @DisplayName("没到阈值时不报")
    void evaluate_should_staySilent_when_belowThreshold() {
        assertEquals(0, evaluator.evaluate(heap(84.9), DiskReport.empty()).size());
    }

    @Test
    @DisplayName("阈值是「大于等于」：刚好等于就该报")
    void evaluate_should_fire_at_exactlyThreshold() {
        List<Alert> alerts = evaluator.evaluate(heap(85.0), DiskReport.empty());

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_HEAP, alerts.get(0).key());
    }

    @Test
    @DisplayName("堆上限未知（没有 -Xmx）不算越界：那不是零也不是满")
    void evaluate_should_ignore_heap_when_maxMissing() {
        JvmStats noMax = JvmStats.builder().heap(999L, 999L, JvmStats.UNKNOWN).build();

        assertEquals(0, evaluator.evaluate(noMax, DiskReport.empty()).size());
    }

    @Test
    @DisplayName("持续越界只产生一份：它是状态，不是每次采样一条事件")
    void evaluate_should_reportOnce_regardlessOfRepeats() {
        assertEquals(1, evaluator.evaluate(heap(90.0), DiskReport.empty()).size());
        assertEquals(1, evaluator.evaluate(heap(92.0), DiskReport.empty()).size());
        assertEquals(0, evaluator.evaluate(heap(50.0), DiskReport.empty()).size());
        assertEquals(1, evaluator.evaluate(heap(95.0), DiskReport.empty()).size());
    }

    @Test
    @DisplayName("文件描述符越界单独成一条")
    void evaluate_should_fire_on_fileDescriptors() {
        JvmStats stats = JvmStats.builder().fileDescriptors(800L, 1000L).build();

        List<Alert> alerts = evaluator.evaluate(stats, DiskReport.empty());

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_FD, alerts.get(0).key());
    }

    @Test
    @DisplayName("死锁只要出现就报，与阈值无关")
    void evaluate_should_fire_on_deadlock() {
        JvmStats stats = JvmStats.builder().deadlocked(2L).build();

        List<Alert> alerts = evaluator.evaluate(stats, DiskReport.empty());

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_DEADLOCK, alerts.get(0).key());
        assertTrue(alerts.get(0).panelText().contains("2"));
    }

    @Test
    @DisplayName("分区越界单独成一条")
    void evaluate_should_fire_on_partition() {
        DiskReport disk = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), 100L, 5L, -1L, -1L);

        List<Alert> alerts = evaluator.evaluate(null, disk);

        assertEquals(1, alerts.size());
        assertEquals(AlertEvaluator.KEY_DISK, alerts.get(0).key());
        assertTrue(alerts.get(0).detailText().contains("95%"));
    }

    @Test
    @DisplayName("顺序固定：堆 → 死锁 → 文件描述符 → 分区，让最不可恢复的排在前面")
    void evaluate_should_keep_priorityOrder() {
        JvmStats stats = JvmStats.builder()
                .heap(900L, 1000L, 1000L)
                .deadlocked(1L)
                .fileDescriptors(900L, 1000L)
                .build();
        DiskReport disk = DiskReport.of(1000L, Collections.<PathUsage>emptyList(), 100L, 5L, -1L, -1L);

        List<Alert> alerts = evaluator.evaluate(stats, disk);

        assertEquals(4, alerts.size());
        assertEquals(AlertEvaluator.KEY_HEAP, alerts.get(0).key());
        assertEquals(AlertEvaluator.KEY_DEADLOCK, alerts.get(1).key());
        assertEquals(AlertEvaluator.KEY_FD, alerts.get(2).key());
        assertEquals(AlertEvaluator.KEY_DISK, alerts.get(3).key());
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
    @DisplayName("面板短文本能塞进一行，明细文本带上读数与阈值")
    void evaluate_should_provide_both_texts() {
        Alert alert = evaluator.evaluate(heap(92.0), DiskReport.empty()).get(0);

        // 短文本要短：面板一行只有二十来列
        assertTrue(alert.panelText().length() <= 22, alert.panelText());
        assertTrue(alert.panelText().contains("92%"), alert.panelText());
        assertTrue(alert.detailText().contains("92%"), alert.detailText());
        assertTrue(alert.detailText().contains("85%"), alert.detailText());
        assertFalse(alert.detailText().contains("\n"), "告警文本必须单行");
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
