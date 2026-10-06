package zcd.jellyfish.plugin.resmon;

import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-plugin-resmon} 段解析成值对象。
 * <p>
 * <b>只有一个路径配置，而且它是最稳定的那一个</b>：本插件不逐个声明「会话目录在哪、待办目录在哪」，
 * 而是只声明一个根目录 {@code baseDir}（缺省 {@code ~/.jellyfish}），其余全部由扫描它的
 * 一级子项得出。理由有两条：
 * <ul>
 *     <li><b>不逐个猜别人的落盘位置</b>：那些目录是其它插件按各自配置写的（session-file 的
 *     {@code sessionDir}、todo 的 {@code todoDir}、脚本桥接的网关目录……）。逐个声明意味着
 *     一旦谁改了配置，本插件就会安静地报出错误的目录——而它自己拿不到工作目录，也不允许
 *     自行读 {@code jellyfish.json}，无法自证；</li>
 *     <li><b>新增的东西自动被看见</b>：新装一个插件、用户手工在下面建了个目录、脚本桥接换了
 *     抽取布局——都不需要改配置，下次扫描就报出来。</li>
 * </ul>
 * 代价是：若用户把某个插件的数据挪到 {@code ~/.jellyfish} 之外（例如
 * {@code react.toolOutput.dir} 指到别的盘），那一份就不会出现在这里。这属于「本插件监控的是
 * {@code ~/.jellyfish} 的占用」这条边界的自然结果，而不是配置没对齐。
 * <p>
 * <b>越界的配置当场报错，而不是静默兜底</b>：采样间隔填成 {@code 1} 会让每秒推几十次界面失效
 * （外壳每个插件来源的信箱只有几十格，满了丢最新），这属于配置错误，应当在插件启动时就以
 * 一条明确的错误暴露，而不是让用户去猜「面板为什么不动了」。
 * <p>
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的就是最终值；
 * 但 {@code ~} <b>没有</b>被展开（内核只在配置文件的路径段上做这件事），因此这里自己展开一次。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 统计根目录键。 */
    static final String KEY_BASE_DIR = "baseDir";

    /** JVM 采样间隔键。 */
    static final String KEY_SAMPLE_INTERVAL = "sampleIntervalMillis";

    /** 磁盘扫描间隔键。 */
    static final String KEY_DISK_INTERVAL = "diskScanIntervalMillis";

    /** 目录遍历深度上限键。 */
    static final String KEY_WALK_MAX_DEPTH = "walkMaxDepth";

    /** 面板上最多列出几个一级子项键。 */
    static final String KEY_DISK_ENTRIES = "diskEntries";

    /** 面板开关键。 */
    static final String KEY_PANEL = "panel";

    /** 自动刷新开关键。 */
    static final String KEY_AUTO_REFRESH = "autoRefresh";

    /** 阈值标出开关键。 */
    static final String KEY_ALERTS = "alerts";

    /** 堆使用率阈值键。 */
    static final String KEY_ALERT_HEAP_PERCENT = "alertHeapPercent";

    /** 分区使用率阈值键。 */
    static final String KEY_ALERT_DISK_PERCENT = "alertDiskPercent";

    /** 文件描述符使用率阈值键。 */
    static final String KEY_ALERT_FD_PERCENT = "alertFdPercent";

    /** 统计根目录缺省值：内核与官方插件约定俗成的全局数据目录。 */
    static final String DEFAULT_BASE_DIR = "~/.jellyfish";

    /** JVM 采样间隔缺省值（毫秒）。 */
    static final long DEFAULT_SAMPLE_INTERVAL_MILLIS = 2000L;

    /** 磁盘扫描间隔缺省值（毫秒）：递归遍历比读 JMX 贵得多，缺省拉开两个数量级。 */
    static final long DEFAULT_DISK_INTERVAL_MILLIS = 60000L;

    /** 目录遍历深度缺省值。 */
    static final int DEFAULT_WALK_MAX_DEPTH = DirSizer.DEFAULT_MAX_DEPTH;

    /** 面板上列出的子项数缺省值：超过它的按体积从大到小截断，命令明细不受限。 */
    static final int DEFAULT_DISK_ENTRIES = 12;

    /** 子项数下界：0 意味着这块面板什么都不显示，那不该靠配置来表达（关面板有 panel）。 */
    static final int MIN_DISK_ENTRIES = 1;

    /** 采样间隔下界（毫秒）：低于它会开始丢外壳贡献。 */
    static final long MIN_SAMPLE_INTERVAL_MILLIS = 500L;

    /** 磁盘扫描间隔下界（毫秒）。 */
    static final long MIN_DISK_INTERVAL_MILLIS = 5000L;

    /** 堆使用率阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_HEAP_PERCENT = 85.0;

    /** 分区使用率阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_DISK_PERCENT = 90.0;

    /** 文件描述符使用率阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_FD_PERCENT = 85.0;

    /** 百分之一百。 */
    private static final double FULL_PERCENT = 100.0;

    /** 统计根目录。 */
    private final Path baseDir;

    /** JVM 采样间隔。 */
    private final long sampleIntervalMillis;

    /** 磁盘扫描间隔。 */
    private final long diskScanIntervalMillis;

    /** 目录遍历深度上限。 */
    private final int walkMaxDepth;

    /** 面板上列出的子项数上限。 */
    private final int diskEntries;

    /** 是否注册面板。 */
    private final boolean panel;

    /** 是否在采样后主动推送界面失效。 */
    private final boolean autoRefresh;

    /** 是否标出越阈值的项。 */
    private final boolean alerts;

    /** 堆使用率告警阈值。 */
    private final double alertHeapPercent;

    /** 分区使用率告警阈值。 */
    private final double alertDiskPercent;

    /** 文件描述符使用率告警阈值。 */
    private final double alertFdPercent;

    /**
     * 构造配置。
     *
     * @param values 已解析的配置段，不可为 {@code null}
     */
    private PluginConfig(Map<String, Object> values) {
        this.baseDir = pathValue(values, KEY_BASE_DIR, DEFAULT_BASE_DIR);
        this.sampleIntervalMillis = longValue(values, KEY_SAMPLE_INTERVAL,
                DEFAULT_SAMPLE_INTERVAL_MILLIS, MIN_SAMPLE_INTERVAL_MILLIS);
        this.diskScanIntervalMillis = longValue(values, KEY_DISK_INTERVAL,
                DEFAULT_DISK_INTERVAL_MILLIS, MIN_DISK_INTERVAL_MILLIS);
        this.walkMaxDepth = (int) longValue(values, KEY_WALK_MAX_DEPTH,
                DEFAULT_WALK_MAX_DEPTH, DirSizer.MIN_MAX_DEPTH);
        this.diskEntries = (int) longValue(values, KEY_DISK_ENTRIES,
                DEFAULT_DISK_ENTRIES, MIN_DISK_ENTRIES);
        this.panel = booleanValue(values, KEY_PANEL, true);
        this.autoRefresh = booleanValue(values, KEY_AUTO_REFRESH, true);
        this.alerts = booleanValue(values, KEY_ALERTS, true);
        this.alertHeapPercent = percentValue(values, KEY_ALERT_HEAP_PERCENT, DEFAULT_ALERT_HEAP_PERCENT);
        this.alertDiskPercent = percentValue(values, KEY_ALERT_DISK_PERCENT, DEFAULT_ALERT_DISK_PERCENT);
        this.alertFdPercent = percentValue(values, KEY_ALERT_FD_PERCENT, DEFAULT_ALERT_FD_PERCENT);
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对、超出范围或路径无法展开时抛出
     */
    static PluginConfig from(Map<String, Object> configuration) {
        return new PluginConfig(configuration == null
                ? Collections.<String, Object>emptyMap() : configuration);
    }

    /**
     * 获取统计根目录。
     *
     * @return 目录路径
     */
    Path baseDir() {
        return baseDir;
    }

    /**
     * 获取 JVM 采样间隔。
     *
     * @return 毫秒数
     */
    long sampleIntervalMillis() {
        return sampleIntervalMillis;
    }

    /**
     * 获取磁盘扫描间隔。
     *
     * @return 毫秒数
     */
    long diskScanIntervalMillis() {
        return diskScanIntervalMillis;
    }

    /**
     * 获取目录遍历深度上限。
     *
     * @return 深度
     */
    int walkMaxDepth() {
        return walkMaxDepth;
    }

    /**
     * 获取面板上列出的子项数上限。
     *
     * @return 子项数
     */
    int diskEntries() {
        return diskEntries;
    }

    /**
     * 判断是否注册面板。
     *
     * @return 注册返回 {@code true}
     */
    boolean panel() {
        return panel;
    }

    /**
     * 判断是否主动推送界面失效。
     *
     * @return 推送返回 {@code true}
     */
    boolean autoRefresh() {
        return autoRefresh;
    }

    /**
     * 判断是否标出越阈值的项。
     * <p>
     * 关掉它只是「不再标出」：面板里那些行不再转警示档位、命令输出里不再有告警段，
     * 各项读数本身照旧显示——阈值是提醒，不是数据。
     *
     * @return 标出返回 {@code true}
     */
    boolean alerts() {
        return alerts;
    }

    /**
     * 获取堆使用率告警阈值。
     *
     * @return 百分比
     */
    double alertHeapPercent() {
        return alertHeapPercent;
    }

    /**
     * 获取分区使用率告警阈值。
     *
     * @return 百分比
     */
    double alertDiskPercent() {
        return alertDiskPercent;
    }

    /**
     * 获取文件描述符使用率告警阈值。
     *
     * @return 百分比
     */
    double alertFdPercent() {
        return alertFdPercent;
    }

    /**
     * 解析路径配置并展开 {@code ~}。
     *
     * @param values   配置段，不可为 {@code null}
     * @param key      键名，不可为 {@code null}
     * @param fallback 缺省值，不可为 {@code null}
     * @return 规范化绝对路径
     * @throws JellyfishException 值不是非空字符串，或无法展开 {@code ~} 时抛出
     */
    private static Path pathValue(Map<String, Object> values, String key, String fallback) {
        Object raw = values.get(key);
        String text = fallback;
        if (raw != null) {
            if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
                throw new JellyfishException(key + " 必须是非空字符串，实际为 " + raw);
            }
            text = ((String) raw).trim();
        }
        if (text.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                throw new JellyfishException("无法展开 " + key + " 的 ~ ：未取到 user.home");
            }
            text = home + text.substring(1);
        }
        return Paths.get(text).toAbsolutePath().normalize();
    }

    /**
     * 解析整数配置。
     *
     * @param values   配置段，不可为 {@code null}
     * @param key      键名，不可为 {@code null}
     * @param fallback 缺省值
     * @param min      合法下界
     * @return 解析结果
     * @throws JellyfishException 值不是整数或小于下界时抛出
     */
    private static long longValue(Map<String, Object> values, String key, long fallback, long min) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        long parsed;
        if (raw instanceof Number) {
            parsed = ((Number) raw).longValue();
        } else if (raw instanceof String) {
            try {
                parsed = Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw new JellyfishException(key + " 必须是整数，实际为 " + raw, e);
            }
        } else {
            throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
        }
        if (parsed < min) {
            throw new JellyfishException(key + " 不能小于 " + min + "，实际为 " + parsed);
        }
        return parsed;
    }

    /**
     * 解析百分比阈值配置。
     *
     * @param values   配置段，不可为 {@code null}
     * @param key      键名，不可为 {@code null}
     * @param fallback 缺省值
     * @return 解析结果
     * @throws JellyfishException 值不是 (0, 100] 内的数时抛出
     */
    private static double percentValue(Map<String, Object> values, String key, double fallback) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        double parsed;
        if (raw instanceof Number) {
            parsed = ((Number) raw).doubleValue();
        } else if (raw instanceof String) {
            try {
                parsed = Double.parseDouble(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw new JellyfishException(key + " 必须是数字，实际为 " + raw, e);
            }
        } else {
            throw new JellyfishException(key + " 必须是数字，实际为 " + raw);
        }
        if (parsed <= 0.0 || parsed > FULL_PERCENT) {
            throw new JellyfishException(key + " 必须在 (0, 100] 之间，实际为 " + parsed);
        }
        return parsed;
    }

    /**
     * 解析布尔配置。
     *
     * @param values   配置段，不可为 {@code null}
     * @param key      键名，不可为 {@code null}
     * @param fallback 缺省值
     * @return 解析结果
     * @throws JellyfishException 值不是布尔或 {@code true}/{@code false} 文本时抛出
     */
    private static boolean booleanValue(Map<String, Object> values, String key, boolean fallback) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new JellyfishException(key + " 必须是布尔值，实际为 " + raw);
    }
}
