package zcd.jellyfish.plugin.sparkline;

import java.util.List;

/**
 * 一个会话的三条线与它们的采样规则。
 * <p>
 * <b>三条线为什么各采各的</b>：缓存与上下文规模只在<b>模型调用</b>上有意义，而「失败」还包括工具调用。
 * 若强行让三条线共用一个时间轴，就得为「一次工具调用该往缓存线上记什么」编一个值——
 * 那个值没有意义，而图上会多出一段假的平线。因此三条线各自按自己关心的事件采样，
 * <b>横向并不对齐</b>：同一列不代表同一时刻。这不是缺陷，而是「一行一个指标」这个形态的必然结果。
 * <b>代价是要说清楚</b>：图上不能做跨行的横向对比（本插件也没打算让人这么用）。
 * <p>
 * <b>失败线记的是滑动窗口里的失败占比，不是单次成败</b>：单次成败画出来是二值脉冲——
 * 要么贴底要么封顶，中间什么都没有，读不出「越来越密」这个真正要看的形状。窗口让一次偶发失败
 * 只抬高一格，而连续失败会迅速爬到满格。
 * <p>
 * <b>窗口未满时分母取实际次数</b>：第一次调用就失败时，失败率是 {@code 1/1 = 100%}。
 * 用固定分母会把它稀释成 {@code 1/8 = 12.5%}，看着温和但不诚实——那一刻确实「只有一次调用，
 * 而它失败了」。
 * <p>
 * 线程安全：写来自事件派发线程、读来自渲染线程，因此全部读写方法都是 {@code synchronized}。
 *
 * @author zcd
 */
final class SessionSeries {

    /** 缓存命中率线，值域是 {@code [0, 1]}。 */
    private final MetricSeries cache;

    /** 失败率线，值域是 {@code [0, 1]}。 */
    private final MetricSeries failure;

    /** 上下文规模线（单次调用的输入 token），值域无上界。 */
    private final MetricSeries input;

    /** 最近若干次调用事件的成败，环形复用。 */
    private final boolean[] outcomes;

    /** 环形写指针。 */
    private int cursor;

    /** 已经写进环形缓冲的条数（用于「窗口未满时按实际次数算」）。 */
    private int recorded;

    /**
     * 构造会话序列。
     *
     * @param graphWidth    图宽（同时也是每条线的容量），小于 1 时按 1 处理
     * @param failureWindow 失败率滑窗大小，小于 1 时按 1 处理
     */
    SessionSeries(int graphWidth, int failureWindow) {
        this.cache = new MetricSeries(graphWidth, MetricSeries.Scale.RATIO);
        this.failure = new MetricSeries(graphWidth, MetricSeries.Scale.RATIO);
        this.input = new MetricSeries(graphWidth, MetricSeries.Scale.RELATIVE);
        this.outcomes = new boolean[Math.max(1, failureWindow)];
    }

    /**
     * 记一次模型调用。
     * <p>
     * 用量缺失时不推缓存线与输入线的采样点：厂商没返回用量时那是「未知」而不是「0」，
     * 画一个 0 会在图上长出一段假的塌陷。失败线照记——成败是内核判定的事实，与厂商报不报用量无关。
     *
     * @param success          本次调用是否成功
     * @param promptTokens     输入 token 总数，可为 {@code null}（未知）
     * @param cacheReadTokens  命中缓存的输入 token，可为 {@code null}（未知）
     */
    synchronized void onLlmCall(boolean success, Integer promptTokens, Integer cacheReadTokens) {
        if (promptTokens != null && promptTokens > 0) {
            cache.push(cacheReadTokens == null ? 0.0 : (double) cacheReadTokens / promptTokens);
            input.push(promptTokens.doubleValue());
        }
        record(success);
    }

    /**
     * 记一次工具调用。
     * <p>
     * 只影响失败率：工具调用没有「缓存命中」与「上下文规模」可言。
     *
     * @param success 本次调用是否成功
     */
    synchronized void onToolCall(boolean success) {
        record(success);
    }

    /**
     * 取缓存命中率线。
     *
     * @return 序列，保证非 {@code null}
     */
    synchronized MetricSeries cache() {
        return cache;
    }

    /**
     * 取失败率线。
     *
     * @return 序列，保证非 {@code null}
     */
    synchronized MetricSeries failure() {
        return failure;
    }

    /**
     * 取上下文规模线。
     *
     * @return 序列，保证非 {@code null}
     */
    synchronized MetricSeries input() {
        return input;
    }

    /**
     * 判断一个采样点也没有。
     *
     * @return 三条线都为空时返回 {@code true}
     */
    synchronized boolean isEmpty() {
        return cache.isEmpty() && failure.isEmpty() && input.isEmpty();
    }

    /**
     * 取一次快照，供渲染一次性读完三条线。
     * <p>
     * 分开取会让三条线来自三个时刻——虽然横向本来就不对齐，但「读到一半被事件插进来」
     * 还是会把同一帧的读值撕成两份。
     *
     * @return 快照，保证非 {@code null}
     */
    synchronized Snapshot snapshot() {
        return new Snapshot(cache.values(), failure.values(), input.values());
    }

    /**
     * 记一次调用事件的成败，并据此推一个失败率采样点。
     *
     * @param success 本次是否成功
     */
    private void record(boolean success) {
        outcomes[cursor] = success;
        cursor = (cursor + 1) % outcomes.length;
        recorded = Math.min(recorded + 1, outcomes.length);
        failure.push(failureRate());
    }

    /**
     * 算当前窗口的失败占比。
     *
     * @return 失败占比，窗口里一次都没记时返回 0
     */
    private double failureRate() {
        if (recorded == 0) {
            return 0.0;
        }
        int failures = 0;
        for (int i = 0; i < recorded; i++) {
            if (!outcomes[i]) {
                failures++;
            }
        }
        return (double) failures / recorded;
    }

    /**
     * 一次读到的三条线快照。
     * <p>
     * 不可变，可安全跨线程传递。
     */
    static final class Snapshot {

        /** 缓存命中率采样点，按时间升序。 */
        private final List<Double> cache;

        /** 失败率采样点，按时间升序。 */
        private final List<Double> failure;

        /** 上下文规模采样点，按时间升序。 */
        private final List<Double> input;

        /**
         * 构造快照。
         *
         * @param cache   缓存线采样点
         * @param failure 失败线采样点
         * @param input   输入线采样点
         */
        private Snapshot(List<Double> cache, List<Double> failure, List<Double> input) {
            this.cache = cache;
            this.failure = failure;
            this.input = input;
        }

        /**
         * 取缓存线。
         *
         * @return 采样点，保证非 {@code null}
         */
        List<Double> getCache() {
            return cache;
        }

        /**
         * 取失败线。
         *
         * @return 采样点，保证非 {@code null}
         */
        List<Double> getFailure() {
            return failure;
        }

        /**
         * 取输入线。
         *
         * @return 采样点，保证非 {@code null}
         */
        List<Double> getInput() {
            return input;
        }
    }
}
