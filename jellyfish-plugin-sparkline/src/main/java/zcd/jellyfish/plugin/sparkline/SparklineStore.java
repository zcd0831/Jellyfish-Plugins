package zcd.jellyfish.plugin.sparkline;

import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 采样台账：把内核事件翻成每个会话的那几条线。
 * <p>
 * <b>为什么只在事件回调里建桶、读路径绝不建</b>：面板处理器挂在渲染线程上，契约要求它<b>纯只读</b>。
 * 若让它「查不到就顺手建一个」，渲染线程就在写共享状态了——而那个桶永远不会有数据（有数据必然先有事件），
 * 于是它只是一次没意义的写入。读路径因此只 {@code get}，查不到就当作「这个会话还没有可画的趋势」。
 * <p>
 * <b>按会话分桶，且子代理天然分离</b>：事件基类自带 {@code sessionId}，而子代理的模型调用记在
 * <b>子代理自己的会话</b>上（内核在归集用量到父会话时刻意不重发那批用量事件，见
 * {@code SessionManager.publishUsage}），因此一个会话的图讲的就是它自己的调用，不会把子代理的账
 * 重复计入。代价是「连同子代理的整棵子树烧了多少」在这里看不到——那是 {@code /usage} 的事。
 * <p>
 * <b>不持久化</b>：趋势是进程内的观测量，会话恢复后从空白重新开始。落盘会让「这个会话的趋势」
 * 变成一个需要定义「哪些旧点该留」的状态，而它的价值（看最近几轮是不是在恶化）本来就是当下的。
 * <p>
 * <b>会话关闭即清桶</b>：不清的话，长进程里每开一个会话就多留一桶采样点，而它再也不会被读到。
 * <p>
 * 线程安全：桶表用 {@link ConcurrentHashMap}，单桶内部的读写由 {@link SessionSeries} 自己守。
 *
 * @author zcd
 */
final class SparklineStore {

    /** 会话标识 → 它的三条线。 */
    private final ConcurrentHashMap<String, SessionSeries> series =
            new ConcurrentHashMap<String, SessionSeries>();

    /** 图宽，同时是每条线的容量。 */
    private final int graphWidth;

    /** 失败率滑窗大小。 */
    private final int failureWindow;

    /**
     * 构造台账。
     *
     * @param graphWidth    图宽，小于 1 时按 1 处理
     * @param failureWindow 失败率滑窗大小，小于 1 时按 1 处理
     */
    SparklineStore(int graphWidth, int failureWindow) {
        this.graphWidth = Math.max(1, graphWidth);
        this.failureWindow = Math.max(1, failureWindow);
    }

    /**
     * 记一次成功的模型调用。
     *
     * @param event 事件，可为 {@code null}
     */
    void onLlmCallCompleted(LlmCallCompletedEvent event) {
        if (event == null) {
            return;
        }
        SessionSeries bucket = bucketOf(event.getSessionId());
        if (bucket == null) {
            return;
        }
        TokenUsageSnapshot usage = event.getUsage();
        bucket.onLlmCall(true,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCacheReadTokens());
    }

    /**
     * 记一次失败的模型调用。
     * <p>
     * 用量字段无从取值（失败的调用没有用量可言），因此只推进失败率线。
     *
     * @param event 事件，可为 {@code null}
     */
    void onLlmCallFailed(LlmCallFailedEvent event) {
        if (event == null) {
            return;
        }
        SessionSeries bucket = bucketOf(event.getSessionId());
        if (bucket != null) {
            bucket.onLlmCall(false, null, null);
        }
    }

    /**
     * 记一次工具调用。
     *
     * @param event 事件，可为 {@code null}
     */
    void onToolCallCompleted(ToolCallCompletedEvent event) {
        if (event == null) {
            return;
        }
        SessionSeries bucket = bucketOf(event.getSessionId());
        if (bucket != null) {
            bucket.onToolCall(event.isSuccess());
        }
    }

    /**
     * 会话关闭时丢掉它的采样点。
     *
     * @param event 事件，可为 {@code null}
     */
    void onSessionClosed(SessionClosedEvent event) {
        if (event == null || event.getSessionId() == null) {
            return;
        }
        series.remove(event.getSessionId());
    }

    /**
     * 取某个会话已有的采样点，不创建。
     * <p>
     * 供渲染线程调用，因此刻意不带任何写操作。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 该会话的序列；没有数据时返回 {@code null}
     */
    SessionSeries existing(String sessionId) {
        return sessionId == null ? null : series.get(sessionId);
    }

    /**
     * 取某个会话的桶，没有就建一个。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 序列；会话标识为空白时返回 {@code null}
     */
    private SessionSeries bucketOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionSeries existingBucket = series.get(sessionId);
        if (existingBucket != null) {
            return existingBucket;
        }
        // computeIfAbsent：并发事件可能同时给同一个会话建桶，而两次 put 会让先到的采样点凭空消失
        return series.computeIfAbsent(sessionId, ignored -> new SessionSeries(graphWidth, failureWindow));
    }

    /**
     * 丢掉全部采样点，供插件停止时调用。
     */
    void clear() {
        series.clear();
    }
}
