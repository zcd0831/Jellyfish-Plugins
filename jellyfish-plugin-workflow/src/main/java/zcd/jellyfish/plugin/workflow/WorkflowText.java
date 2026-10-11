package zcd.jellyfish.plugin.workflow;

/**
 * 面板文本的两个小工具：显示宽度与耗时文案。
 * <p>
 * <b>为什么插件要自己算宽度</b>：面板上的连线必须上下落在同一列（{@code ┬}/{@code ┤}/{@code ┘} 对不齐
 * 就等于没画），而插件既看不到终端有多少列（{@code PanelContributionRequest} 只给 sessionId），
 * 也拿不到外壳的 {@code DisplayWidth}。于是「一行能放几个节点」「名字截到多少列」这两件事只能由插件
 * 自己有个尺度——没有尺度，一个中文名字就能把整行顶到折行，而折行会让图形断开，还额外吃掉一行预算。
 * <p>
 * 这是对「处理器不得做界面计算」的一次<b>有意的偏离</b>，与 {@code jellyfish-plugin-resmon} 的
 * {@code ResmonFormat} 同一处、同一理由：插件不许用宽度做<b>对齐</b>（那是外壳的事），但把自己的内容
 * <b>限制在预算内</b>是插件自己的责任。按显示宽度（中日韩占两列）而不是字符数算，否则全中文名会被
 * 算短一半，连线的列位置随之全错。
 * <p>
 * <b>框线字符按 1 列算</b>：{@code ─ │ ┬ ┤ ┘ └} 落在 U+2500–U+257F，Unicode 的 East Asian Width
 * 把它们归为 Ambiguous，而外壳的 {@code DisplayWidth} 只覆盖 CJK / 全角 / Emoji 几个区，
 * 也把它们算成 1 列。两边判定一致，连线才对得齐——这条一致性是硬要求，改这里要连外壳一起改。
 * <p>
 * 无状态，可安全跨线程调用。
 *
 * @author zcd
 */
final class WorkflowText {

    /** 截断标记。 */
    static final String ELLIPSIS = "\u2026";

    /** 组合用附加符号起点（与其他字符合成一个显示宽度）。 */
    private static final int COMBINING_START = 0x0300;

    /** 组合用附加符号终点。 */
    private static final int COMBINING_END = 0x036F;

    /** 零宽字符区间起点。 */
    private static final int ZERO_WIDTH_START = 0x200B;

    /** 零宽字符区间终点。 */
    private static final int ZERO_WIDTH_END = 0x200F;

    /** 变体选择符起点。 */
    private static final int VARIATION_SELECTOR_START = 0xFE00;

    /** 变体选择符终点。 */
    private static final int VARIATION_SELECTOR_END = 0xFE0F;

    /**
     * 工具类，禁止实例化。
     */
    private WorkflowText() {
    }

    /**
     * 计算一段文本占用的终端列数。
     *
     * @param text 文本，可为 {@code null}
     * @return 显示列数；{@code null} 或空串返回 0
     */
    static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            width += widthOf(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /**
     * 按显示宽度截断文本，超长时以省略号收尾。
     * <p>
     * <b>切口不落在代理对中间</b>：切开一个代理对会让终端收到半个字符，显示成乱码，而宽度计算也会
     * 因此错位——面板上的连线随即对不齐。按码点推进就天然守住了这条。
     *
     * @param text     文本，可为 {@code null}
     * @param maxWidth 允许的最大显示宽度，小于 2 时按 2 处理（至少要放得下省略号）
     * @return 截断后的文本，保证非 {@code null}
     */
    static String clip(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(2, maxWidth);
        if (displayWidth(text) <= limit) {
            return text;
        }
        StringBuilder clipped = new StringBuilder();
        int width = 0;
        int budget = limit - displayWidth(ELLIPSIS);
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int charWidth = widthOf(codePoint);
            if (width + charWidth > budget) {
                break;
            }
            clipped.appendCodePoint(codePoint);
            width += charWidth;
            index += Character.charCount(codePoint);
        }
        return clipped.append(ELLIPSIS).toString();
    }

    /**
     * 把一段毫秒数写成界面上的耗时文案。
     * <p>
     * 三档就够：一分钟以内说秒（「已 38s」，用户关心的是它还在动）、一小时以内说分秒（「1m02s」）、
     * 再长说时分。不写毫秒、不做毫秒级跳动——它每帧都会被重新渲染，数字太细只会让人盯着一处闪。
     *
     * @param millis 毫秒数，负数按 0 处理
     * @return 文案，形如 {@code 38s} / {@code 1m02s} / {@code 2h05m}，保证非 {@code null}
     */
    static String elapsed(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m" + twoDigits(seconds % 60L) + "s";
        }
        return (minutes / 60L) + "h" + twoDigits(minutes % 60L) + "m";
    }

    /**
     * 把 0~59 的数补成两位。
     *
     * @param value 数值
     * @return 两位文本
     */
    private static String twoDigits(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }

    /**
     * 计算一个码点占用的列数。
     *
     * @param codePoint Unicode 码点
     * @return 列数：0（零宽）、1（窄）或 2（宽）
     */
    private static int widthOf(int codePoint) {
        if (isZeroWidth(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /**
     * 判断码点是否不占列。
     *
     * @param codePoint Unicode 码点
     * @return 不占列返回 {@code true}
     */
    private static boolean isZeroWidth(int codePoint) {
        if (codePoint >= COMBINING_START && codePoint <= COMBINING_END) {
            return true;
        }
        if (codePoint >= ZERO_WIDTH_START && codePoint <= ZERO_WIDTH_END) {
            return true;
        }
        if (codePoint >= VARIATION_SELECTOR_START && codePoint <= VARIATION_SELECTOR_END) {
            return true;
        }
        return codePoint == 0xFEFF;
    }

    /**
     * 判断码点是否占 2 列（East Asian Wide / Fullwidth）。
     * <p>
     * 区间与外壳的 {@code DisplayWidth} 保持一致（含 Emoji 常用区），因此两端对同一个名字算出的列数
     * 相同——这是连线能对齐的前提。框线字符刻意不在任何区间内：它们按 1 列处理。
     *
     * @param codePoint Unicode 码点
     * @return 占 2 列返回 {@code true}
     */
    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0x303E)
                || (codePoint >= 0x3041 && codePoint <= 0x33FF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xA000 && codePoint <= 0xA4CF)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE10 && codePoint <= 0xFE19)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F300 && codePoint <= 0x1F64F)
                || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)
                || (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
                || (codePoint >= 0x30000 && codePoint <= 0x3FFFD);
    }
}
