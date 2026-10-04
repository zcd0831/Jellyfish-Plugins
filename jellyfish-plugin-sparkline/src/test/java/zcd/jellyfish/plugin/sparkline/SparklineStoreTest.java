package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SparklineStore} 的单元测试：按会话分桶、读路径不创建、会话关闭清桶。
 *
 * @author zcd
 */
@DisplayName("采样台账")
class SparklineStoreTest {

    /** 被测台账。 */
    private SparklineStore store;

    @BeforeEach
    void setUp() {
        store = new SparklineStore(8, 8);
    }

    @Test
    @DisplayName("只记本会话的每一次模型调用")
    void onLlmCallCompleted_should_recordUsageOfThatSession() {
        store.onLlmCallCompleted(llmCall("s1", 2000, 1500));

        SessionSeries series = store.existing("s1");
        assertNotNull(series);
        assertEquals(0.75, series.cache().latest(), 1e-9);
        assertEquals(2000.0, series.input().latest(), 1e-9);
        // 别的会话不该因此长出一个桶
        assertNull(store.existing("s2"));
    }

    @Test
    @DisplayName("失败的模型调用只推进失败率线")
    void onLlmCallFailed_should_onlyAffectFailureLine() {
        store.onLlmCallFailed(new LlmCallFailedEvent("s1", "deepseek", "chat", 400, "bad field"));

        SessionSeries series = store.existing("s1");
        assertNotNull(series);
        assertTrue(series.cache().isEmpty());
        assertTrue(series.input().isEmpty());
        assertEquals(1.0, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("工具成败也进失败率线：缓存与上下文与它无关")
    void onToolCallCompleted_should_onlyAffectFailureLine() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", false, 12L, "exit 1", "s1"));

        SessionSeries series = store.existing("s1");
        assertNotNull(series);
        assertTrue(series.cache().isEmpty());
        assertEquals(1.0, series.failure().latest(), 1e-9);
    }

    @Test
    @DisplayName("读路径不创建桶：渲染线程不许写共享状态")
    void existing_should_notCreateBucket() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));

        assertNull(store.existing("never-seen"));
        assertNull(store.existing(null));
        // 再一次确认：上面两次读没有留下任何痕迹
        assertNull(store.existing("never-seen"));
    }

    @Test
    @DisplayName("没有会话标识的事件一律忽略，不建桶")
    void events_should_ignoreBlankSession() {
        store.onLlmCallCompleted(llmCall(null, 100, 1));
        store.onLlmCallFailed(new LlmCallFailedEvent(null, null, null, 0, null));
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, null));
        store.onSessionClosed(new SessionClosedEvent(null, null, 0));

        assertNull(store.existing(null));
    }

    @Test
    @DisplayName("会话关闭时清掉它的采样点：长进程里不能每开一个会话就多留一桶")
    void onSessionClosed_should_dropBucket() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));
        assertNotNull(store.existing("s1"));

        store.onSessionClosed(new SessionClosedEvent("s1", "coder", 3));

        assertNull(store.existing("s1"));
    }

    @Test
    @DisplayName("停止时清空全部采样点")
    void clear_should_dropEverything() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));

        store.clear();

        assertNull(store.existing("s1"));
    }

    @Test
    @DisplayName("事件为 null 时无事发生，不抛错")
    void events_should_tolerateNull() {
        store.onLlmCallCompleted(null);
        store.onLlmCallFailed(null);
        store.onToolCallCompleted(null);
        store.onSessionClosed(null);

        assertNull(store.existing("s1"));
    }

    /**
     * 造一个模型调用完成事件。
     *
     * @param sessionId 会话标识
     * @param prompt    输入 token
     * @param cacheRead 命中缓存的 token
     * @return 事件
     */
    private static LlmCallCompletedEvent llmCall(String sessionId, Integer prompt, Integer cacheRead) {
        return new LlmCallCompletedEvent(sessionId, "deepseek", "chat",
                new TokenUsageSnapshot(prompt, 10, prompt, cacheRead, 0));
    }
}
