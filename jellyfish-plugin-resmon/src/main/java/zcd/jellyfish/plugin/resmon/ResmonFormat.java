package zcd.jellyfish.plugin.resmon;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 资源数字的呈现辅助：字节数、百分比、时长、时刻与占用项标签的格式化。
 * <p>
 * <b>为什么单独立一个类而不是让各处自己拼字符串</b>：面板、状态栏式的速览与 {@code /resmon} 的
 * 明细必须用同一套口径——两处各写一次「保留几位小数」，用户就会在同一个数字上看到
 * {@code 1.4G} 与 {@code 1536M} 两种答案，而它们看起来都像真的。这里因此只放纯函数，
 * 不含任何状态与 I/O，也因此最容易被单测钉死边界。
 * <p>
 * <b>宽度目标</b>：面板会落在侧栏里，而侧栏宽度按内容宽度算（外壳的规则是「内容宽 + 边框」
 * 夹进 {@code [20, 终端宽/4]}），因此这里的输出刻意压缩在 4 个字符以内
 * （{@code 1.4G} / {@code 210K} / {@code 12%}），为的是面板不必靠外壳折行。
 * <p>
 * 无状态，可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonFormat {

    /** 数值不可用时的占位：刻意用一个 ASCII 字符，全角符号的显示宽度在终端之间不一致。 */
    static final String UNKNOWN = "-";

    /** 一档单位对应的字节数。 */
    private static final long KILO = 1024L;

    /** 字节单位，从千到拍。 */
    private static final String[] UNITS = {"K", "M", "G", "T", "P"};

    /** 每秒的毫秒数。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** 面板上用的短标签：两个汉字，宽度可控是首要考虑。 */
    private static final Map<String, String> SHORT_LABELS = shortLabels();

    /** 命令明细里用的标签：不受行宽限制，因此可以写全。 */
    private static final Map<String, String> LABELS = labels();

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
     * 于是「这项数据还没有」会被显示成 {@code 0%}——那是最坏的一种错，
     * 因为它看起来完全正常。
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
     * 取占用项的完整标签（命令明细用）。
     *
     * @param key 占用项键名
     * @return 标签；未知键原样返回，便于新目录在补标签前也看得见
     */
    static String label(String key) {
        String label = LABELS.get(key);
        return label == null ? key : label;
    }

    /**
     * 取占用项的短标签（面板用）。
     *
     * @param key 占用项键名
     * @return 两个汉字的短标签；未知键回退到完整标签
     */
    static String shortLabel(String key) {
        String label = SHORT_LABELS.get(key);
        return label == null ? label(key) : label;
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

    /**
     * 构造面板用的短标签映射。
     *
     * @return 不可变映射
     */
    private static Map<String, String> shortLabels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(UsageKeys.SESSIONS, "会话");
        labels.put(UsageKeys.TOOL_OUTPUTS, "输出");
        labels.put(UsageKeys.PLUGINS, "插件");
        labels.put(UsageKeys.TODOS, "待办");
        labels.put(UsageKeys.GATEWAY, "网关");
        labels.put(UsageKeys.LOG, "日志");
        return Collections.unmodifiableMap(labels);
    }

    /**
     * 构造命令明细用的完整标签映射。
     *
     * @return 不可变映射
     */
    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(UsageKeys.SESSIONS, "会话文件");
        labels.put(UsageKeys.TOOL_OUTPUTS, "工具输出");
        labels.put(UsageKeys.PLUGINS, "插件 jar");
        labels.put(UsageKeys.TODOS, "待办");
        labels.put(UsageKeys.GATEWAY, "脚本网关");
        labels.put(UsageKeys.LOG, "TUI 日志");
        return Collections.unmodifiableMap(labels);
    }
}
