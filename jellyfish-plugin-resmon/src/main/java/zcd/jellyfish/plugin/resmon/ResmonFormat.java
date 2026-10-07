package zcd.jellyfish.plugin.resmon;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 资源数字与名称的呈现辅助：字节数、百分比、时长、时刻、名称截断。
 * <p>
 * <b>为什么单独立一个类而不是让各处自己拼字符串</b>：面板与 {@code /resmon} 的明细必须用同一套
 * 口径——两处各写一次「保留几位小数」，用户就会在同一个数字上看到 {@code 1.4G} 与 {@code 1536M}
 * 两种答案，而它们看起来都像真的。这里因此只放纯函数，不含任何状态与 I/O，
 * 也因此最容易被单测钉死边界。
 * <p>
 * <b>宽度目标</b>：面板落在侧栏里，而侧栏宽度按内容宽度算（外壳的规则是「内容宽 + 边框」
 * 夹进 {@code [20, 终端宽/4]}），因此这里的输出刻意压缩在 5 个字符以内
 * （{@code 1.4G} / {@code 210K} / {@code 12%}），为的是面板不必靠外壳折行。
 * <p>
 * 无状态，可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonFormat {

    /** 数值不可用时的占位：刻意用一个 ASCII 字符，全角符号的显示宽度在终端之间不一致。 */
    static final String UNKNOWN = "-";

    /** 名称被截断时补的省略号。 */
    private static final String ELLIPSIS = "\u2026";

    /** 一档单位对应的字节数。 */
    private static final long KILO = 1024L;

    /** 字节单位，从千到拍。 */
    private static final String[] UNITS = {"K", "M", "G", "T", "P"};

    /** 每秒的毫秒数。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** 私有构造器：工具类不可实例化。 */
    private ResmonFormat() {
    }

    /**
     * 格式化字节数。
     * <p>
     * 大于等于 10 的值取整、小于 10 的保留一位小数，因此最长 4 个字符 + 单位：
     * {@code 890B} / {@code 1.4K} / {@code 24M} / {@code 1.4G}。
     *
     * @param value 字节数，负数表示不可用
     * @return 显示文本，保证非 {@code null}
     */
    static String bytes(long value) {
        if (value < 0) {
            return UNKNOWN;
        }
        if (value < KILO) {
            return value + "B";
        }
        double scaled = value;
        int index = 0;
        while (index < UNITS.length - 1 && scaled >= KILO * KILO) {
            scaled /= KILO;
            index++;
        }
        scaled /= KILO;
        return number(scaled) + UNITS[index];
    }

    /**
     * 格式化百分比（入参已是 0..100 的数值）。
     * <p>
     * {@code NaN} 与负数一样按「不可用」处理：{@code Math.round(NaN)} 会安静地返回 0，
     * 于是「这项数据还没有」会被显示成 {@code 0%}——那是最坏的一种错，因为它看起来完全正常。
     *
     * @param value 百分比，负数或 NaN 表示不可用
     * @return 显示文本（如 {@code 25%}），保证非 {@code null}
     */
    static String percent(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return UNKNOWN;
        }
        return Math.round(value) + "%";
    }

    /**
     * 格式化时长。
     *
     * @param millis 毫秒数，负数表示不可用
     * @return 显示文本（如 {@code 320ms} / {@code 1.2s} / {@code 1h2m}），保证非 {@code null}
     */
    static String duration(long millis) {
        if (millis < 0) {
            return UNKNOWN;
        }
        if (millis < MILLIS_PER_SECOND) {
            return millis + "ms";
        }
        double seconds = millis / (double) MILLIS_PER_SECOND;
        if (seconds < 60) {
            return number(seconds) + "s";
        }
        long totalSeconds = millis / MILLIS_PER_SECOND;
        if (totalSeconds < 3600L) {
            return (totalSeconds / 60) + "m" + (totalSeconds % 60) + "s";
        }
        long totalMinutes = totalSeconds / 60;
        if (totalMinutes < 1440L) {
            return (totalMinutes / 60) + "h" + (totalMinutes % 60) + "m";
        }
        long totalHours = totalMinutes / 60;
        return (totalHours / 24) + "d" + (totalHours % 24) + "h";
    }

    /**
     * 格式化时刻（秒级）。
     *
     * @param epochMillis 毫秒时间戳
     * @return {@code HH:mm:ss}
     */
    static String clock(long epochMillis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(epochMillis));
    }

    /**
     * 格式化完整时间戳（命令明细里用）。
     *
     * @param epochMillis 毫秒时间戳
     * @return {@code yyyy-MM-dd HH:mm:ss}
     */
    static String timestamp(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(epochMillis));
    }

    /**
     * 按显示宽度截断文本，超长时以省略号收尾。
     * <p>
     * <b>为什么插件要做宽度计算</b>：{@code baseDir} 下的一级子项名是用户给的（可能是任意长度的
     * 目录名，也可能含中日韩字符），而面板落在侧栏里、侧栏宽度又由内容宽度反推——名字多长一分，
     * 消息区就少一分。契约禁止插件用宽度做<b>对齐</b>（那是外壳的事），但把内容<b>限制在预算内</b>
     * 是插件自己的责任，否则一个长目录名就能把面板撑宽。
     * <p>
     * 按显示宽度而不是字符数算，否则全中文目录名会被算短一半。
     *
     * @param text     文本，可为 {@code null}
     * @param maxWidth 允许的最大显示宽度，小于 1 时按 1 处理
     * @return 截断后的文本，保证非 {@code null}
     */
    static String clip(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(1, maxWidth);
        if (displayWidth(text) <= limit) {
            return text;
        }
        StringBuilder clipped = new StringBuilder();
        int width = 0;
        int budget = limit - 1;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            int charWidth = isWide(character) ? 2 : 1;
            if (width + charWidth > budget) {
                break;
            }
            clipped.append(character);
            width += charWidth;
        }
        return clipped.append(ELLIPSIS).toString();
    }

    /**
     * 计算文本的终端显示宽度：中日韩等全角字符占两列。
     *
     * @param text 文本，可为 {@code null}
     * @return 显示列数
     */
    static int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += isWide(text.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    /**
     * 判断一个字符在终端里是否占两列。
     *
     * @param character 字符
     * @return 占两列返回 {@code true}
     */
    private static boolean isWide(char character) {
        return character >= 0x1100 && (character <= 0x115F
                || character == 0x2329 || character == 0x232A
                || (character >= 0x2E80 && character <= 0xA4CF && character != 0x303F)
                || (character >= 0xAC00 && character <= 0xD7A3)
                || (character >= 0xF900 && character <= 0xFAFF)
                || (character >= 0xFE30 && character <= 0xFE6F)
                || (character >= 0xFF00 && character <= 0xFF60)
                || (character >= 0xFFE0 && character <= 0xFFE6));
    }

    /**
     * 把一个非负数值压成「最多 4 个字符」的文本。
     *
     * @param value 非负数值
     * @return 小于 10 时保留一位小数（整数部分去掉 {@code .0}），否则四舍五入取整
     */
    private static String number(double value) {
        if (value < 10) {
            String text = String.format(Locale.ROOT, "%.1f", value);
            return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
        }
        return String.valueOf(Math.round(value));
    }
}
