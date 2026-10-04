package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionSeries} 的单元测试：三条线各自的采样规则与失败率滑窗。
 * <p>
 * 这里守的是「图上的点从哪来」——采样错了，图画得再漂亮也是假的。
 *
 * @author zcd
 */
@DisplayName("会话采样")
class SessionSeriesTest {

    @Test
    @DisplayName("模型调用同时推缓存线与上下文线：命中率按总输入算")
    void onLlmCall_should_pushCacheAndInput() {
        SessionSeries series = new SessionSeries(8, 8);

        series.onLlmCall(true, 1000, 250);

        assertFalse(series.cache().isEmpty());
        assertEquals(0.25, series.cache().latest(), 1e-9);
        assertEquals(1000.0, series.input().latest(), 1e-9);
    }

    @Test
    @DisplayName("厂商没报用量时不推缓存线与上下文线：那是「未知」，不是「0」")
    void onLlmCall_should_skipCacheAndInputWhenUsageMissing() {
        SessionSeries series = new SessionSeries(8, 8);

        series.onLlmCall(true, null, null);

        assertTrue(series.cache().isEmpty());
        assertTrue(series.input().isEmpty());
        // 但失败率线照记：成败是内核判定的事实，与厂商报不报用量无关
        assertFalse(series.failure().isEmpty());
    }

    @Test
    @DisplayName("命中缓存字段缺失时按 0 命中算，而不是整条不推")
    void onLlmCall_should_treatMissingCacheReadAsZero() {
        SessionSeries series = new SessionSeries(8, 8);

        series.onLlmCall(true, 1000, null);

        assertEquals(0.0, series.cache().latest(), 1e-9);
    }

    @Test
    @DisplayName("工具调用只影响失败率：它没有缓存与上下文可言")
    void onToolCall_should_onlyAffectFailureLine() {
        SessionSeries series = new SessionSeries(8, 8);

        series.onToolCall(false);

        assertTrue(series.cache().isEmpty());
        assertTrue(series.input().isEmpty());
        assertEquals(1.0, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("窗口未满时按实际次数算：第一次就失败就是 100%")
    void failureRate_should_useActualCountUntilWindowFills() {
        SessionSeries series = new SessionSeries(8, 8);

        series.onToolCall(false);

        // 固定分母会把它稀释成 1/8，看着温和但不诚实
        assertEquals(1.0, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("失败率随窗口滚动下降：一次偶发失败会被后续成功逐步冲淡")
    void failureRate_should_decayAsSuccessesArrive() {
        SessionSeries series = new SessionSeries(16, 4);

        series.onToolCall(false);
        assertEquals(1.0, series.failure().latest(), 1e-9);

        for (int i = 0; i < 3; i++) {
            series.onToolCall(true);
        }

        // 窗口里 4 次调用、1 次失败
        assertEquals(0.25, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("连续失败会爬到满格：这正是「越来越密」要看的形状")
    void failureRate_should_reachOneOnConsecutiveFailures() {
        SessionSeries series = new SessionSeries(8, 8);

        for (int i = 0; i < 8; i++) {
            series.onToolCall(false);
        }

        assertEquals(1.0, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("每条线只留最近图宽个采样点：容量就是图宽")
    void series_should_keepOnlyGraphWidthSamples() {
        SessionSeries series = new SessionSeries(3, 8);

        for (int i = 0; i < 10; i++) {
            series.onToolCall(true);
        }

        assertEquals(3, series.failure().values().size());
    }

    @Test
    @DisplayName("一个采样点也没有时算空：面板据此不占区域")
    void isEmpty_should_beTrueBeforeAnyEvent() {
        assertTrue(new SessionSeries(8, 8).isEmpty());
    }
}
