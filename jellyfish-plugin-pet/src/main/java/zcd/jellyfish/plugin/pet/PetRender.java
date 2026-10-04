package zcd.jellyfish.plugin.pet;

import java.util.Locale;

/**
 * 宠物面板的字符画与数值格式化。
 * <p>
 * <b>为什么画文本图形不算「绕过框架」</b>：插件负责「显示什么」，外壳负责「怎么显示」。
 * 把数值拼成一根条属于前者——它与插件拼一行「待办 2/5」是同一类工作，且成果是<b>纯文本段</b>，
 * 外壳照常按显示宽度折行与截断，不参与任何解析（api 的 {@code UiSegmentKind} 刻意不收
 * {@code PROGRESS} 这类「要外壳反过来解析文本」的种类，正是为了不产生第二份真源）。
 * <p>
 * <b>块字符与进度条字符都是 1 列宽</b>：{@code U+2591..U+2593} 与 {@code U+2581..U+2588}
 * 都不在内核 {@code DisplayWidth.isWide} 判定的宽字符范围内，因此在终端里各占 1 列。
 * <p>
 * <b>条只表达幅度，刻度由旁边的数字给出</b>：五格的条读得出「一半」「快满了」，
 * 读不出「1h47m」——所以这两样必须同时在场。
 * <p>
 * 无状态，只提供静态方法。
 *
 * @author zcd
 */
final class PetRender {

    /** 满格字符。 */
    private static final char FILLED = '\u2593';

    /** 空格字符。 */
    private static final char EMPTY = '\u2591';

    /** 每根条的格数。 */
    private static final int BAR_CELLS = 5;

    /** 一分钟的毫秒数。 */
    private static final long MINUTE = 60000L;

    /** 一小时的毫秒数。 */
    private static final long HOUR = 60L * MINUTE;

    /** 千的倍数。 */
    private static final double THOUSAND = 1000.0;

    /** 百万的倍数。 */
    private static final double MILLION = 1000000.0;

    /** 不足一万时小数位有意义的分界。 */
    private static final double TEN_THOUSAND = 10000.0;

    /** 工具类，禁止实例化。 */
    private PetRender() {
    }

    /**
     * 画一根条。
     * <p>
     * 比例先夹到 {@code [0, 1]} 再取整：负值会画出「欠格」，而超过 1 的值会让格子数溢出条宽。
     * 非零比例至少填满一格——「有一点点」与「一点也没有」在条上必须分得开，
     * 否则刚刚开始累积的那段会被读成没发生。
     *
     * @param ratio 比例，可为任意值（会被夹到 {@code [0, 1]}）
     * @return 定长 {@link #BAR_CELLS} 的条，保证非 {@code null}
     */
    static String bar(double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int filled = (int) Math.round(clamped * BAR_CELLS);
        if (clamped > 0.0 && filled == 0) {
            filled = 1;
        }
        StringBuilder builder = new StringBuilder(BAR_CELLS);
        for (int i = 0; i < BAR_CELLS; i++) {
            builder.append(i < filled ? FILLED : EMPTY);
        }
        return builder.toString();
    }

    /**
     * 把一段时长格式化成便于一眼读出的短文本。
     * <p>
     * 只给一个单位：{@code 2h14m} 里读得出「两小时出头」，而 {@code 134m} 要人自己换算。
     * 小时数为整时不补 {@code 0m}——那个零不提供信息，只占两列。
     *
     * @param millis 时长（毫秒），负值按 0 处理
     * @return 形如 {@code 30s} / {@code 45m} / {@code 2h14m} 的文本，保证非 {@code null}
     */
    static String duration(long millis) {
        long value = Math.max(0L, millis);
        if (value < MINUTE) {
            return value / 1000L + "s";
        }
        if (value < HOUR) {
            return value / MINUTE + "m";
        }
        long hours = value / HOUR;
        long minutes = (value % HOUR) / MINUTE;
        return minutes == 0L ? hours + "h" : hours + "h" + minutes + "m";
    }

    /**
     * 把 token 数格式化成便于一眼读出的短文本。
     * <p>
     * 一位小数只用在「一万以下」：{@code 9.8k} 的量级差异有意义，而 {@code 123.4k} 里那位小数
     * 既读不出来又占宽度。
     *
     * @param value token 数，负值按 0 处理
     * @return 形如 {@code 872} / {@code 9.8k} / {@code 120k} / {@code 1.2M} 的文本，保证非 {@code null}
     */
    static String tokens(long value) {
        double amount = Math.max(0L, value);
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
     * 保留一位小数。
     *
     * @param value 数值
     * @return 形如 {@code 9.8} 的文本
     */
    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
