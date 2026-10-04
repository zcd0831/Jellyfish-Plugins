package zcd.jellyfish.plugin.sparkline;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 块字符画的渲染与数值格式化。
 * <p>
 * <b>为什么画文本图形不算「绕过框架」</b>：插件负责的是「显示什么」，外壳负责「怎么显示」。
 * 把一串数值拼成图形属于前者——它与插件拼一行「待办 2/5」是同一类工作。而 api 的
 * {@code UiSegmentKind} 词汇表刻意不收 {@code PROGRESS} 这类「要让外壳反过来解析文本」的种类，
 * 正是因为「数值由插件给、外壳从中读出数字」会立刻产生两个真源。这里的图形因此是
 * <b>纯文本段</b>，外壳照常按显示宽度折行与截断，不参与任何解析。
 * <p>
 * <b>块字符是 1 列宽</b>：{@code U+2581..U+2588} 不在内核 {@code DisplayWidth.isWide} 判定的
 * 宽字符范围内，因此在终端里占 1 列——把宽窄算错会让整行错位，而中文是本项目的主力场景。
 * <p>
 * <b>只表达形状，不表达刻度</b>：图上没有坐标轴、没有基线，两行之间也不可比高低。
 * 因此每一行都必须把当前值与单位写在旁边，否则图好看但没法用。
 * <p>
 * 无状态，只提供静态方法。
 *
 * @author zcd
 */
final class SparklineRender {

    /** 八档块字符：下标即档位，0 最矮、7 最高。 */
    private static final char[] BLOCKS = {'\u2581', '\u2582', '\u2583', '\u2584', '\u2585', '\u2586', '\u2587',
            '\u2588'};

    /** 采样点不足时左侧用的占位字符：历史还没攒够，留白比硬画一个最低档诚实。 */
    private static final char NO_DATA = ' ';

    /** 千的倍数。 */
    private static final double THOUSAND = 1000.0;

    /** 百万的倍数。 */
    private static final double MILLION = 1000000.0;

    /** 不足一万时小数位有意义的分界。 */
    private static final double TEN_THOUSAND = 10000.0;

    /** 工具类，禁止实例化。 */
    private SparklineRender() {
    }

    /**
     * 把一条序列画成一行块字符。
     * <p>
     * 采样点不足 {@code width} 个时左侧留白：图从右边长出来，表示「这些点还没有」。
     * 右对齐而不是左对齐，是因为「最新的一格」必须永远在同一个位置——否则读图时还要先数一遍。
     *
     * @param values 采样点，按时间升序，可为 {@code null} 或空
     * @param width  图宽（字符数），小于 1 时按 1 处理
     * @param scale  刻度口径，不可为 {@code null}
     * @return 定长 {@code width} 的一行字符，保证非 {@code null}
     */
    static String render(List<Double> values, int width, MetricSeries.Scale scale) {
        int length = Math.max(1, width);
        char[] row = new char[length];
        Arrays.fill(row, NO_DATA);
        if (values == null || values.isEmpty()) {
            return new String(row);
        }
        int count = Math.min(length, values.size());
        List<Double> tail = values.subList(values.size() - count, values.size());
        double upper = upperBoundOf(tail, scale);
        int offset = length - count;
        for (int i = 0; i < count; i++) {
            row[offset + i] = BLOCKS[levelOf(tail.get(i), upper)];
        }
        return new String(row);
    }

    /**
     * 把比率格式化成百分比文本。
     *
     * @param value 比率，可为 {@code null}
     * @return 形如 {@code 62%} 的文本；无值时返回 {@code --}
     */
    static String percent(Double value) {
        if (value == null) {
            return "--";
        }
        long rounded = Math.round(value * 100.0);
        return Math.max(0L, Math.min(100L, rounded)) + "%";
    }

    /**
     * 把 token 数格式化成便于一眼读出的短文本。
     * <p>
     * 一位小数只用在「一万以下」：{@code 9.8k} 的量级差异有意义，而 {@code 123.4k} 里那位小数
     * 既读不出来又占宽度。
     *
     * @param value token 数，可为 {@code null}
     * @return 形如 {@code 872} / {@code 9.8k} / {@code 120k} / {@code 1.2M} 的文本；无值时返回 {@code --}
     */
    static String tokens(Double value) {
        if (value == null) {
            return "--";
        }
        double amount = Math.max(0.0, value);
        if (amount < THOUSAND) {
            return String.valueOf(Math.round(amount));
        }
        if (amount < MILLION) {
            double thousands = amount / THOUSAND;
            return thousands < TEN_THOUSAND / THOUSAND
                    ? oneDecimal(thousands) + "k"
                    : Math.round(thousands) + "k";
        }
        return oneDecimal(amount / MILLION) + "M";
    }

    /**
     * 取归一化上界。
     *
     * @param values 参与本行的采样点
     * @param scale  刻度口径
     * @return 上界，保证不小于 0
     */
    private static double upperBoundOf(List<Double> values, MetricSeries.Scale scale) {
        if (scale == MetricSeries.Scale.RATIO) {
            // 比率天生有满量程：用窗口最大值当上界会把「一直是 60%」画成满格
            return 1.0;
        }
        double max = 0.0;
        for (Double value : values) {
            if (value != null && value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * 把一个值映射成 0..7 的档位。
     * <p>
     * 上界为 0（整行都是 0）时统一落到最低档：此时「相对高低」没有意义，画成满格会谎报「很多」。
     *
     * @param value 采样值，可为 {@code null}（按 0 处理）
     * @param upper 归一化上界
     * @return 档位，保证在 {@code [0, 7]} 内
     */
    private static int levelOf(Double value, double upper) {
        if (value == null || upper <= 0.0) {
            return 0;
        }
        double ratio = value / upper;
        int level = (int) Math.floor(ratio * (BLOCKS.length - 1) + 0.5);
        return Math.max(0, Math.min(BLOCKS.length - 1, level));
    }

    /**
     * 保留一位小数。
     *
     * @param value 数值
     * @return 形如 {@code 9.8} 的文本
     */
    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
