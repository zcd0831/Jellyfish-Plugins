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
 * <b>为什么路径要在这里再声明一份</b>：这是本插件唯一无法自己求真的信息。Jellyfish 没有统一的
 * 「家目录」工具类，落盘位置是各模块各自硬编码 {@code ~/.jellyfish/...} 的约定；插件又拿不到会话与
 * 工作目录，约定里也明确禁止插件自行读 {@code jellyfish.json}。因此缺省值取「内核与官方插件都在用的
 * 那几个约定路径」，用户若改过 {@code react.toolOutput.dir}、session-file 的 {@code sessionDir}
 * 或 {@code plugins.roots}，就在这里写一份对齐——这是本插件唯一的已知代价，
 * javadoc 与 README 都要写清，不能让用户以为自己看见的是权威数字。
 * <p>
 * 日志那一项还多一层：内核的 TUI 日志路径由系统属性 {@code jellyfish.log.file} 决定
 * （见 {@code log4j2-tui.xml}），那个属性<b>不会</b>出现在配置段里，因此改过它的用户只能靠
 * {@link #KEY_LOG_FILE} 手工对齐。
 * <p>
 * <b>越界的配置当场报错，而不是静默兜底</b>：采样间隔填成 {@code 1} 会让每秒推几十次界面失效 * （外壳每个插件来源的信箱只有几十格，满了丢最新），这属于配置错误，应当在插件启动时就以
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

    /** JVM 采样间隔键。 */
    static final String KEY_SAMPLE_INTERVAL = "sampleIntervalMillis";

    /** 磁盘扫描间隔键。 */
    static final String KEY_DISK_INTERVAL = "diskScanIntervalMillis";

    /** 目录遍历深度上限键。 */
    static final String KEY_WALK_MAX_DEPTH = "walkMaxDepth";

    /** 面板开关键。 */
    static final String KEY_PANEL = "panel";

    /** 自动刷新开关键。 */
    static final String KEY_AUTO_REFRESH = "autoRefresh";

    /** 告警开关键。 */
    static final String KEY_ALERTS = "alerts";

    /** 会话目录键。 */
    static final String KEY_SESSIONS_DIR = "sessionsDir";

    /** 工具输出目录键。 */
    static final String KEY_TOOL_OUTPUTS_DIR = "toolOutputsDir";

    /** 插件目录键。 */
    static final String KEY_PLUGINS_DIR = "pluginsDir";

    /** 待办目录键。 */
    static final String KEY_TODOS_DIR = "todosDir";

    /** 脚本网关目录键。 */
    static final String KEY_GATEWAY_DIR = "gatewayDir";

    /** 日志文件键。 */
    static final String KEY_LOG_FILE = "logFile";

    /** 堆使用率告警阈值键。 */
    static final String KEY_ALERT_HEAP_PERCENT = "alertHeapPercent";

    /** 分区使用率告警阈值键。 */
    static final String KEY_ALERT_DISK_PERCENT = "alertDiskPercent";

    /** 文件描述符使用率告警阈值键。 */
    static final String KEY_ALERT_FD_PERCENT = "alertFdPercent";

    /** 会话目录缺省值：session-file 插件的 {@code sessionDir} 与之同源。 */
    static final String DEFAULT_SESSIONS_DIR = "~/.jellyfish/sessions";

    /** 工具输出目录缺省值：内核 {@code rt.toolOutput.dir} 与之同源。 */
    static final String DEFAULT_TOOL_OUTPUTS_DIR = "~/.jellyfish/tool-outputs";

    /** 插件目录缺省值：{@code config.json} 的 {@code plugins.roots} 与之同源。 */
    static final String DEFAULT_PLUGINS_DIR = "~/.jellyfish/plugins";

    /** 待办目录缺省值：todo 插件的 {@code todoDir} 与之同源。 */
    static final String DEFAULT_TODOS_DIR = "~/.jellyfish/todos";

    /** 脚本网关目录缺省值：{@code jellyfish-script} 的抽取目录。 */
    static final String DEFAULT_GATEWAY_DIR = "~/.jellyfish/gateway";

    /** 日志文件缺省值：内核 TUI 模式的 log4j2 落点。 */
    static final String DEFAULT_LOG_FILE = "~/.jellyfish/jellyfish-tui.log";

    /** JVM 采样间隔缺省值（毫秒）。 */
    static final long DEFAULT_SAMPLE_INTERVAL_MILLIS = 2000L;

    /** 磁盘扫描间隔缺省值（毫秒）：递归遍历比读 JMX 贵得多，缺省拉开两个数量级。 */
    static final long DEFAULT_DISK_INTERVAL_MILLIS = 60000L;

    /** 目录遍历深度缺省值。 */
    static final int DEFAULT_WALK_MAX_DEPTH = DirSizer.DEFAULT_MAX_DEPTH;

    /** 采样间隔下界（毫秒）：低于它会开始丢外壳贡献。 */
    static final long MIN_SAMPLE_INTERVAL_MILLIS = 500L;

    /** 磁盘扫描间隔下界（毫秒）。 */
    static final long MIN_DISK_INTERVAL_MILLIS = 5000L;

    /** 堆使用率告警阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_HEAP_PERCENT = 85.0;

    /** 分区使用率告警阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_DISK_PERCENT = 90.0;

    /** 文件描述符使用率告警阈值缺省值（百分比）。 */
    static final double DEFAULT_ALERT_FD_PERCENT = 85.0;

    /** 百分之一百。 */
    private static final double FULL_PERCENT = 100.0;

    /** JVM 采样间隔。 */
    private final long sampleIntervalMillis;

    /** 磁盘扫描间隔。 */
    private final long diskScanIntervalMillis;

    /** 目录遍历深度上限。 */
    private final int walkMaxDepth;

    /** 是否注册面板。 */
    private final boolean panel;

    /** 是否在采样后主动推送界面失效。 */
    private final boolean autoRefresh;

    /** 是否在面板与命令里标出越阈值的项。 */
    private final boolean alerts;

    /** 会话目录。 */
    private final Path sessionsDir;

    /** 工具输出目录。 */
    private final Path toolOutputsDir;

    /** 插件目录。 */
    private final Path pluginsDir;

    /** 待办目录。 */
    private final Path todosDir;

    /** 脚本网关目录。 */
    private final Path gatewayDir;

    /** 日志文件。 */
    private final Path logFile;

    /** 堆使用率告警阈值。 */
    private final double alertHeapPercent;

    /** 分区使用率告警阈值。 */
    private final double alertDiskPercent;

    /** 文件描述符使用率告警阈值。 */
    private final double alertFdPercent;

    /**
     * 构造配置。
     *
     * @param values            已解析的配置段，不可为 {@code null}
     */
    private PluginConfig(Map<String, Object> values) {
        this.sampleIntervalMillis = longValue(values, KEY_SAMPLE_INTERVAL,
                DEFAULT_SAMPLE_INTERVAL_MILLIS, MIN_SAMPLE_INTERVAL_MILLIS);
        this.diskScanIntervalMillis = longValue(values, KEY_DISK_INTERVAL,
                DEFAULT_DISK_INTERVAL_MILLIS, MIN_DISK_INTERVAL_MILLIS);
        this.walkMaxDepth = (int) longValue(values, KEY_WALK_MAX_DEPTH,
                DEFAULT_WALK_MAX_DEPTH, DirSizer.MIN_MAX_DEPTH);
        this.panel = booleanValue(values, KEY_PANEL, true);
        this.autoRefresh = booleanValue(values, KEY_AUTO_REFRESH, true);
        this.alerts = booleanValue(values, KEY_ALERTS, true);
        this.sessionsDir = pathValue(values, KEY_SESSIONS_DIR, DEFAULT_SESSIONS_DIR);
        this.toolOutputsDir = pathValue(values, KEY_TOOL_OUTPUTS_DIR, DEFAULT_TOOL_OUTPUTS_DIR);
        this.pluginsDir = pathValue(values, KEY_PLUGINS_DIR, DEFAULT_PLUGINS_DIR);
        this.todosDir = pathValue(values, KEY_TODOS_DIR, DEFAULT_TODOS_DIR);
        this.gatewayDir = pathValue(values, KEY_GATEWAY_DIR, DEFAULT_GATEWAY_DIR);
        this.logFile = pathValue(values, KEY_LOG_FILE, DEFAULT_LOG_FILE);
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
     * 关掉它只是「不再标出」：面板会少掉告警行、命令输出会少掉告警段，
     * 各项读数本身照旧显示——阈值是提醒，不是数据。
     *
     * @return 标出返回 {@code true}
     */
    boolean alerts() {
        return alerts;
    }

    /**
     * 获取会话目录。
     *
     * @return 目录路径
     */
    Path sessionsDir() {
        return sessionsDir;
    }

    /**
     * 获取工具输出目录。
     *
     * @return 目录路径
     */
    Path toolOutputsDir() {
        return toolOutputsDir;
    }

    /**
     * 获取插件目录。
     *
     * @return 目录路径
     */
    Path pluginsDir() {
        return pluginsDir;
    }

    /**
     * 获取待办目录。
     *
     * @return 目录路径
     */
    Path todosDir() {
        return todosDir;
    }

    /**
     * 获取脚本网关目录。
     *
     * @return 目录路径
     */
    Path gatewayDir() {
        return gatewayDir;
    }

    /**
     * 获取日志文件。
     *
     * @return 文件路径
     */
    Path logFile() {
        return logFile;
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
