package zcd.jellyfish.plugin.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件工具的路径策略：把「这个路径在不在允许范围内」翻成一次权限裁定。
 * <p>
 * <b>为什么在插件侧而不是内核</b>：内核不认识「path」这种工具参数语义，把「哪些参数是路径」
 * 写进核心策略会让内核被迫认识每一种工具的参数表。而权限扩展点（{@code PermissionCheckRequest}）
 * 本来就带着 {@code toolName} 与 {@code arguments}，插件侧完全判得出来；{@code PermissionVerdict}
 * 只有「无异议 / 要审批 / 拒绝」三态（没有「放行」），因此这里无论怎么配都只能收紧，核心策略的结论
 * 不会被放宽。
 * <p>
 * <b>默认只管写、且只允许工作目录</b>：开箱就挡住「往 {@code /etc}、往主目录别处写」，而读不设限——
 * 「读工作目录外的文件」是常规需求（读别的仓库、读配置、读内核回灌的工具结果），默认拦它会把
 * 正常用法一起挡掉。要按读收窄就显式配 {@code read} 段。
 * <p>
 * <b>允许清单里的路径按与工具完全相同的规则解析</b>（见 {@link ToolPaths#resolve(String)}：相对路径
 * 按进程工作目录、行首 {@code ~} 按用户主目录），因此 {@code "."} 就是工作目录、{@code "~/.jellyfish"}
 * 就是状态目录——不为配置另造一套路径语法，也就不会出现「同一个写法在配置与工具里含义不同」。
 * <p>
 * <b>关闭某方向的门就用 {@code outside: "allow"}</b>：那一档的裁定恒为「无异议」，门等于不存在。
 * 不为它另加一个 {@code enabled} 开关——同一件事两种写法迟早会不一致。
 * <p>
 * 不可变（允许清单在构造时解析完毕），可安全跨线程传递。
 *
 * @author zcd
 */
final class PathPolicy {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PathPolicy.class);

    /** 告警来源，供用户定位是哪一段配置。 */
    static final String SOURCE = "plugins.configurations.jellyfish-plugin-tools.pathPolicy";

    /** 工具参数里承载路径的键名：本插件的文件工具统一用它。 */
    static final String PATH_ARGUMENT = "path";

    /** 路径取不到时的缺省值：与文件工具自己的缺省一致（{@code list_dir} / {@code grep_files} 用它）。 */
    private static final String DEFAULT_PATH = ".";

    /** 允许清单的缺省值：只允许工作目录。用 {@code "."} 而不是工作目录的绝对路径，与用户写法同源。 */
    private static final List<String> DEFAULT_ALLOW = Collections.unmodifiableList(
            Collections.singletonList(DEFAULT_PATH));

    /** 工作目录之外的路径缺省如何处理。 */
    private static final Outside DEFAULT_OUTSIDE = Outside.ASK;

    /**
     * 工作目录之外的路径如何处理。
     */
    enum Outside {
        /** 无异议：交内核的核心策略判（相当于门不存在）。 */
        ALLOW,
        /** 升级为人工审批；没有审批者（如 {@code -cli}）时按拒绝处理。 */
        ASK,
        /** 直接拒绝。 */
        DENY
    }

    /**
     * 一个方向的规则：允许的目录 + 范围之外怎么处理。
     */
    private static final class Rule {

        /** 允许的目录，已解析成绝对路径并规范化。 */
        private final List<Path> allow;

        /** 范围之外的裁定。 */
        private final Outside outside;

        /**
         * 构造规则。
         *
         * @param allow   允许的目录，非空
         * @param outside 范围之外的裁定
         */
        private Rule(List<Path> allow, Outside outside) {
            this.allow = allow;
            this.outside = outside;
        }

        /**
         * 取允许清单的展示文本，供裁定理由使用。
         *
         * @return 逗号分隔的路径
         */
        private String describe() {
            StringBuilder builder = new StringBuilder();
            for (Path path : allow) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(path);
            }
            return builder.toString();
        }
    }

    /** 读方向的规则，{@code null} 表示该方向不设限。 */
    private final Rule read;

    /** 写方向的规则，恒非 {@code null}（默认只允许工作目录）。 */
    private final Rule write;

    /**
     * 构造策略。
     *
     * @param read  读方向规则，可为 {@code null}
     * @param write 写方向规则，不可为 {@code null}
     */
    private PathPolicy(Rule read, Rule write) {
        this.read = read;
        this.write = write;
    }

    /**
     * 从插件配置解析策略。
     * <p>
     * 配置段缺失、取值非法一律回退缺省并告警，<b>不抛异常</b>：配置写错不该让整只工具插件起不来
     * （那会把「一个字段拼错了」升级成「模型忽然没有文件工具」）。
     *
     * @param context 插件上下文，可为 {@code null}（为 {@code null} 时按缺省策略且不告警）
     * @return 策略
     */
    static PathPolicy from(PluginContext context) {
        Map<String, Object> values = section(context);
        Object raw = values.get("pathPolicy");
        if (raw == null) {
            return defaults();
        }
        if (!(raw instanceof Map)) {
            warn(context, "pathPolicy 必须是对象，实际是 " + raw + "；已按缺省策略处理");
            return defaults();
        }
        Map<?, ?> section = (Map<?, ?>) raw;
        return new PathPolicy(readRule(context, section.get("read")), writeRule(context, section.get("write")));
    }

    /**
     * 构造缺省策略：写限工作目录、读不设限。
     *
     * @return 策略
     */
    private static PathPolicy defaults() {
        return new PathPolicy(null, new Rule(Collections.singletonList(ToolPaths.resolve(DEFAULT_PATH)),
                DEFAULT_OUTSIDE));
    }

    /**
     * 解析读方向的规则。
     * <p>
     * 读方向缺省<b>不设限</b>：为 {@code null} 时该分支恒返回「无异议」。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     配置值
     * @return 规则；未配置时返回 {@code null}
     */
    private static Rule readRule(PluginContext context, Object raw) {
        if (raw == null) {
            return null;
        }
        return rule(context, raw, "read");
    }

    /**
     * 解析写方向的规则。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     配置值
     * @return 规则，保证非 {@code null}
     */
    private static Rule writeRule(PluginContext context, Object raw) {
        if (raw == null) {
            return defaults().write;
        }
        return rule(context, raw, "write");
    }

    /**
     * 解析一个方向的规则。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     配置值
     * @param name    方向名，用于告警定位
     * @return 规则，保证非 {@code null}
     */
    private static Rule rule(PluginContext context, Object raw, String name) {
        if (!(raw instanceof Map)) {
            warn(context, name + " 必须是对象，实际是 " + raw + "；已按缺省处理");
            return new Rule(Collections.singletonList(ToolPaths.resolve(DEFAULT_PATH)), DEFAULT_OUTSIDE);
        }
        Map<?, ?> values = (Map<?, ?>) raw;
        return new Rule(allowList(context, values.get("allow"), name), outside(context, values.get("outside"), name));
    }

    /**
     * 解析允许清单。
     * <p>
     * 空白项被丢弃；全部为空（或未配）时回退缺省。解析失败（路径非法）的项同样丢弃并告警——
     * 保留它没有意义（拿不到可比对的路径），而丢弃的方向是「这家目录不再被允许」，偏严不偏松。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     配置值
     * @param name    方向名，用于告警定位
     * @return 已解析的目录列表，保证非空
     */
    private static List<Path> allowList(PluginContext context, Object raw, String name) {
        if (raw == null) {
            return Collections.singletonList(ToolPaths.resolve(DEFAULT_PATH));
        }
        if (!(raw instanceof List)) {
            warn(context, name + ".allow 必须是数组，实际是 " + raw + "；已按缺省 "
                    + DEFAULT_ALLOW + " 处理");
            return Collections.singletonList(ToolPaths.resolve(DEFAULT_PATH));
        }
        List<Path> resolved = new ArrayList<Path>();
        for (Object item : (List<?>) raw) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                resolved.add(ToolPaths.resolve(text));
            } catch (RuntimeException e) {
                warn(context, name + ".allow 里的路径无法解析（" + text + "）：" + e + "；已忽略该条");
            }
        }
        if (resolved.isEmpty()) {
            warn(context, name + ".allow 里没有可用的路径；已按缺省 " + DEFAULT_ALLOW + " 处理");
            return Collections.singletonList(ToolPaths.resolve(DEFAULT_PATH));
        }
        return Collections.unmodifiableList(resolved);
    }

    /**
     * 解析「范围之外怎么处理」。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     配置值
     * @param name    方向名，用于告警定位
     * @return 裁定档位
     */
    private static Outside outside(PluginContext context, Object raw, String name) {
        if (raw == null) {
            return DEFAULT_OUTSIDE;
        }
        String text = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        for (Outside candidate : Outside.values()) {
            if (candidate.name().equals(text)) {
                return candidate;
            }
        }
        warn(context, name + ".outside 只能是 allow / ask / deny（实际 " + raw + "）；已按缺省 "
                + DEFAULT_OUTSIDE + " 处理");
        return DEFAULT_OUTSIDE;
    }

    /**
     * 取本插件的配置段。
     *
     * @param context 插件上下文，可为 {@code null}
     * @return 配置段，保证非 {@code null}
     */
    private static Map<String, Object> section(PluginContext context) {
        if (context == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> configuration = context.configuration();
            return configuration == null ? Collections.<String, Object>emptyMap() : configuration;
        } catch (RuntimeException e) {
            LOG.warn("读取工具插件配置失败，按缺省路径策略处理: {}", e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * 发出配置告警：事件给界面，日志给事后排查。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(PluginContext context, String message) {
        LOG.warn("工具插件配置告警: {}", message);
        if (context == null) {
            return;
        }
        try {
            context.emit(new ConfigWarningEvent(SOURCE, message));
        } catch (RuntimeException e) {
            // 告警通道本身失败不该让配置解析失败
            LOG.debug("发布配置告警事件失败: {}", e.toString());
        }
    }

    /**
     * 对一次工具调用给出裁定。
     *
     * @param access    工具的路径访问性质
     * @param toolName  工具名，用于裁定理由
     * @param arguments 工具参数，可为 {@code null}
     * @return 裁定，只可能是「无异议 / 要审批 / 拒绝」三种
     */
    PermissionVerdict verdict(PathAccess access, String toolName, Map<String, Object> arguments) {
        Rule rule = ruleFor(access);
        if (rule == null || rule.outside == Outside.ALLOW) {
            return PermissionVerdict.abstain();
        }
        Path target = targetOf(arguments);
        if (target == null || ToolPaths.insideAny(target, rule.allow)) {
            return PermissionVerdict.abstain();
        }
        String reason = toolName + " 的目标在工作目录之外（" + target + "）：允许的路径是 "
                + rule.describe();
        return rule.outside == Outside.DENY ? PermissionVerdict.deny(reason) : PermissionVerdict.ask(reason);
    }

    /**
     * 取该访问性质对应的规则。
     *
     * @param access 访问性质
     * @return 规则；该方向不设限或该工具不碰路径时返回 {@code null}
     */
    private Rule ruleFor(PathAccess access) {
        if (access == PathAccess.WRITE) {
            return write;
        }
        if (access == PathAccess.READ) {
            return read;
        }
        return null;
    }

    /**
     * 取本次调用实际要访问的路径。
     *
     * @param arguments 工具参数，可为 {@code null}
     * @return 已解析的绝对路径；参数里取不到路径时返回 {@code null}
     */
    private static Path targetOf(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get(PATH_ARGUMENT);
        if (!(raw instanceof String)) {
            // 路径参数缺失或不是字符串：工具自己会报「参数不合法」，那是比「权限不允许」准确得多的错。
            // 而且没有路径就没有可判的对象，这里放行不会放过任何一次真实的文件访问
            return null;
        }
        String text = ((String) raw).trim();
        return ToolPaths.resolve(text.isEmpty() ? DEFAULT_PATH : text);
    }
}
