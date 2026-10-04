package zcd.jellyfish.plugin.sparkline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 一条指标的时间序列：固定容量、只看最近若干个采样点。
 * <p>
 * <b>为什么容量就是图宽</b>：序列的唯一用途是画那一行图，而图上只放得下这么多个点。
 * 多留的样本只会在内存里躺到会话结束，而人眼本来也读不出「第 12 个点之前」的形状——
 * 要更长的时间跨度，靠的是更少的采样（例如按事件而不是按帧），不是更深的缓冲。
 * <p>
 * <b>两种刻度，差别只在归一化</b>：{@link Scale#RATIO} 的值域天生是 {@code [0, 1]}
 * （命中率、失败率），因此固定用满量程——「一直是 60%」画出来是中等高度，这才对得上人的直觉；
 * {@link Scale#RELATIVE} 的值域无上界（token 数），只能用窗口内的最大值当上界，
 * 于是「一直是 60000」会被画成满格。<b>这是刻意的</b>：本行只表达形状，量级由行尾那个数字负责。
 * <p>
 * <b>线程安全</b>：写发生在事件派发线程，读发生在渲染线程，因此全部方法都是 {@code synchronized}。
 * 临界区只是一次双端队列操作，与「面板处理器必须快」不冲突。
 *
 * @author zcd
 */
final class MetricSeries {

    /**
     * 归一化的刻度口径。
     */
    enum Scale {

        /** 值域固定为 {@code [0, 1]}：命中率、失败率这类比率。 */
        RATIO,

        /** 值域无上界：用窗口内最大值当上界，只表达相对形状。 */
        RELATIVE
    }

    /** 采样点，旧的在头、新的在尾。 */
    private final Deque<Double> values = new ArrayDeque<Double>();

    /** 容量（= 图宽），超出即从头部淘汰。 */
    private final int capacity;

    /** 本条线的刻度口径。 */
    private final Scale scale;

    /**
     * 构造序列。
     *
     * @param capacity 容量，小于 1 时按 1 处理
     * @param scale    刻度口径，不可为 {@code null}
     */
    MetricSeries(int capacity, Scale scale) {
        this.capacity = Math.max(1, capacity);
        this.scale = scale;
    }

    /**
     * 追加一个采样点，并淘汰超出容量的最旧一个。
     *
     * @param value 采样值
     */
    synchronized void push(double value) {
        values.addLast(value);
        while (values.size() > capacity) {
            values.removeFirst();
        }
    }

    /**
     * 取全部采样点，按时间升序。
     *
     * @return 采样点列表（最多 {@link #capacity} 个），保证非 {@code null}
     */
    synchronized List<Double> values() {
        return new ArrayList<Double>(values);
    }

    /**
     * 取最新一个采样点。
     *
     * @return 最新值；一个点也没有时为 {@code null}
     */
    synchronized Double latest() {
        return values.peekLast();
    }

    /**
     * 判断是否一个采样点也没有。
     *
     * @return 没有采样点时返回 {@code true}
     */
    synchronized boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * 取刻度口径。
     *
     * @return 刻度口径，保证非 {@code null}
     */
    Scale scale() {
        return scale;
    }
}
