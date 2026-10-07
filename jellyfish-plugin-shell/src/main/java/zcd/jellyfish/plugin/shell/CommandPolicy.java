package zcd.jellyfish.plugin.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 命令策略：前缀白名单（默认拒绝）、可信命令表（免审批）与命令分类器（弱），三者都只收紧、不放宽。
 * <p>
 * <b>三只旋钮回答两个问题</b>：
 * <ul>
 *     <li><b>白名单</b>（{@code allowedCommands}）是<b>默认拒绝</b>。它是给「没有人在场」的模式
 *     （{@code -cli} / {@code -server}，那两个模式里 {@code askTools} 等于禁用）准备的安全网：
 *     配置了它，就只放行列出来的那些前缀。它回答<b>「能不能跑」</b>。</li>
 *     <li><b>可信表</b>（{@code commandPolicy.trustedCommands}）是<b>免审批</b>：命中即无异议，
 *     不弹批准框。它回答<b>「要不要问人」</b>。</li>
 *     <li><b>分类器</b>同样回答「要不要问人」：它把只读查询判成无异议，让人不必每次点批准，
 *     其余命令升级为人工审批。它不拒绝任何东西。</li>
 * </ul>
 * <b>白名单与后两者是两件事</b>：配了白名单不等于白名单里的命令免审批——白名单只解除默认拒绝，
 * 命令仍要过可信表与分类器，命中不了就问人。这正是「把 {@code mvn test} 放进白名单却仍被弹框」
 * 的原因，不是缺陷。
 * <p>
 * <b>可信表与只读表（{@code readOnlyCommands}）为什么不合并</b>：只读表的语义承诺是
 * 「这条命令不改东西」，而免审批的诉求常常落在 {@code mvn test} / {@code git commit} 这类
 * <b>确实会改东西</b>的命令上。逼用户把它们写进只读表，等于让他对系统说谎，
 * 而这份谎话会掩盖「我为什么信任这条命令」。两个键的<b>审批口径相同</b>（都不打扰），语义各归其位。
 * <p>
 * <b>分类器不是安全边界，这一点必须写清楚</b>：它按命令原文的前缀匹配，
 * {@code FOO=bar cmd}、{@code $(...)}、别名、{@code sh -c} 嵌套都能绕过它。
 * 它的价值在于避免用户因为嫌烦而把 {@code shell} 从 {@code askTools} 里整个拿掉——
 * 那才是真正的风险。真正的边界是审批本身加上白名单。
 * <p>
 * <b>刻意保守的三条</b>：{@code find} 不在只读表里（{@code find -delete}）、
 * {@code git fetch} / {@code git push} 不在（会改远端与本地 ref）、
 * {@code npm test} / {@code mvn test} 不在（执行仓库里的任意代码）。这三个都是「看起来无害」的
 * 典型误判点。
 * <p>
 * <b>这三条免审批的唯一正当路径是可信表</b>：内置表的保守是有理由的，不该被放宽；而用户确实
 * 要在某个项目里反复跑 {@code mvn test} 时，把这份判断写进 {@code trustedCommands} 是显式、
 * 可审计的——比把它塞进只读表精确，也比把它算进内置只读表诚实。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class CommandPolicy {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CommandPolicy.class);

    /** 命令分类结果。 */
    enum Classification {

        /** 只读查询：不打扰人。 */
        READ_ONLY,

        /** 明确的灾难形状：直接拒绝。 */
        DENIED,

        /** 其余：升级为人工审批。 */
        OTHER
    }

    /** 配置来源，用于告警定位。 */
    private static final String SOURCE = "plugins.configurations.jellyfish-plugin-shell";

    /**
     * 内置只读命令表。
     * <p>
     * 单 token 条目匹配该命令的全部子命令；带空格的条目只匹配该二元前缀。
     */
    static final List<String> DEFAULT_READ_ONLY_COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "ls", "cat", "head", "tail", "wc", "pwd", "echo", "which", "type", "date", "uname",
            "whoami", "id", "df", "du", "ps", "stat", "file",
            "git status", "git log", "git diff", "git show", "git branch", "git remote", "git rev-parse",
            "npm ls", "pnpm ls"));

    /**
     * 内置拒绝形状。
     * <p>
     * 只留「几乎没有合法用途」的几条：这类名单一旦扩张，就会开始挡掉正常运维动作，
     * 而它挡不住的部分本来也挡不住（一个 {@code sh -c} 就能改写形状）。
     */
    static final List<String> DEFAULT_DENIED_PATTERNS = Collections.unmodifiableList(Arrays.asList(
            "rm -rf /", "rm -fr /", "mkfs", "of=/dev/", ":(){"));

    /** 是否启用分类器。 */
    private final boolean enabled;

    /** 白名单，空列表表示不启用。 */
    private final List<String> allowedCommands;

    /** 可信命令表，命中即免审批；空列表表示不启用。 */
    private final List<String> trustedCommands;

    /** 只读命令表。 */
    private final List<String> readOnlyCommands;

    /** 拒绝形状列表。 */
    private final List<String> deniedPatterns;

    /**
     * 构造策略。
     *
     * @param enabled          是否启用分类器
     * @param allowedCommands  白名单
     * @param trustedCommands  可信命令表
     * @param readOnlyCommands 只读命令表
     * @param deniedPatterns   拒绝形状列表
     */
    private CommandPolicy(boolean enabled, List<String> allowedCommands, List<String> trustedCommands,
                          List<String> readOnlyCommands, List<String> deniedPatterns) {
        this.enabled = enabled;
        this.allowedCommands = allowedCommands;
        this.trustedCommands = trustedCommands;
        this.readOnlyCommands = readOnlyCommands;
        this.deniedPatterns = deniedPatterns;
    }

    /**
     * 从配置解析策略。
     *
     * @param context         插件上下文（仅用于发告警），可为 {@code null}
     * @param raw             命令策略配置段，可为 {@code null}
     * @param allowedCommands 白名单（来自顶层配置键）
     * @return 策略，保证非 {@code null}
     */
    static CommandPolicy from(PluginContext context, Object raw, List<String> allowedCommands) {
        if (raw != null && !(raw instanceof Map)) {
            warn(context, "commandPolicy 必须是对象，实际是 " + raw + "，已按缺省策略处理");
        }
        Map<String, Object> values = mapOf(raw);
        return new CommandPolicy(
                booleanValue(values.get("enabled"), true),
                Collections.unmodifiableList(new ArrayList<String>(allowedCommands)),
                PluginConfig.stringListOf(context, values.get("trustedCommands"), "commandPolicy.trustedCommands"),
                extend(DEFAULT_READ_ONLY_COMMANDS, PluginConfig.stringListOf(context, values.get("readOnlyCommands"),
                        "commandPolicy.readOnlyCommands")),
                extend(DEFAULT_DENIED_PATTERNS, PluginConfig.stringListOf(context, values.get("deniedPatterns"),
                        "commandPolicy.deniedPatterns")));
    }

    /**
     * 读取命令策略配置段，非映射一律视为缺省。
     *
     * @param raw 原始值，可为 {@code null}
     * @return 配置段，保证非 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Collections.<String, Object>emptyMap();
    }

    /**
     * 把用户声明的条目追加在内置表之后。
     *
     * @param defaults 内置表
     * @param extra    用户追加项
     * @return 合并结果，保证非 {@code null}
     */
    private static List<String> extend(List<String> defaults, List<String> extra) {
        if (extra.isEmpty()) {
            return defaults;
        }
        List<String> merged = new ArrayList<String>(defaults.size() + extra.size());
        merged.addAll(defaults);
        merged.addAll(extra);
        return Collections.unmodifiableList(merged);
    }

    /**
     * 读取布尔配置。
     *
     * @param raw      原始值
     * @param fallback 缺省值
     * @return 布尔值
     */
    private static boolean booleanValue(Object raw, boolean fallback) {
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    /**
     * 发出配置告警。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(PluginContext context, String message) {
        LOG.warn("shell 插件配置告警: {}", message);
        if (context == null) {
            return;
        }
        try {
            context.emit(new ConfigWarningEvent(SOURCE, message));
        } catch (RuntimeException e) {
            LOG.debug("发布配置告警事件失败: {}", e.toString());
        }
    }

    /**
     * 给出这次命令调用的权限裁定。
     * <p>
     * 顺序即优先级：白名单（默认拒绝）→ 拒绝形状 → 可信表（免审批）→ 只读 → 其余问人。
     * 白名单、可信表与拒绝形状<b>都不受 {@code commandPolicy.enabled} 影响</b>：那个开关关掉的是
     * 「分类器」这个便利机制，不是用户明确声明的约束。
     * <p>
     * <b>可信表排在拒绝形状之后是刻意的</b>：把 {@code rm} 写进可信表，{@code rm -rf /} 依旧被拒——
     * 免审批回答的是「要不要问人」，不是「连灾难形状也放行」。
     *
     * @param command 命令原文，不可为 {@code null}
     * @return 权限裁定，保证非 {@code null}
     */
    PermissionVerdict verdict(String command) {
        if (!allowedCommands.isEmpty() && !matchesPrefix(allowedCommands, command)) {
            return PermissionVerdict.deny("命令不在 allowedCommands 白名单内：" + firstToken(command));
        }
        String denied = matchedDeniedPattern(command);
        if (denied != null) {
            return PermissionVerdict.deny("命令匹配拒绝形状：" + denied);
        }
        if (matchesPrefix(trustedCommands, command)) {
            return PermissionVerdict.abstain();
        }
        if (!enabled) {
            return PermissionVerdict.abstain();
        }
        if (classify(command) == Classification.READ_ONLY) {
            return PermissionVerdict.abstain();
        }
        return PermissionVerdict.ask("执行 shell 命令需要确认：" + firstToken(command));
    }

    /**
     * 判断命令是否命中某个前缀表。
     * <p>
     * 白名单、可信表与只读表的匹配粒度一致：允许写 {@code git status} 这种两 token 形式
     * （精确匹配前两个 token），也允许写单 token（匹配该命令本身）。
     *
     * @param table   前缀表，不可为 {@code null}
     * @param command 命令原文
     * @return 命中返回 {@code true}
     */
    private static boolean matchesPrefix(List<String> table, String command) {
        String first = firstToken(command);
        String firstTwo = firstTwoTokens(command);
        for (String prefix : table) {
            if (prefix.indexOf(' ') < 0 ? prefix.equals(first) : prefix.equals(firstTwo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取可信命令表条目数，供启动台账展示。
     *
     * @return 条目数
     */
    int trustedCommandCount() {
        return trustedCommands.size();
    }

    /**
     * 分类命令原文。
     *
     * @param command 命令原文，不可为 {@code null}
     * @return 分类结果，保证非 {@code null}
     */
    Classification classify(String command) {
        if (matchedDeniedPattern(command) != null) {
            return Classification.DENIED;
        }
        return matchesPrefix(readOnlyCommands, command) ? Classification.READ_ONLY : Classification.OTHER;
    }

    /**
     * 找出命中的拒绝形状。
     *
     * @param command 命令原文
     * @return 命中的形状；未命中返回 {@code null}
     */
    private String matchedDeniedPattern(String command) {
        if (command == null) {
            return null;
        }
        for (String pattern : deniedPatterns) {
            if (command.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 取命令的第一个 token。
     * <p>
     * 前缀形如 {@code VAR=value} 时返回它本身（而不是它后面的那个词）：那是一个<b>保守</b>选择——
     * 环境变量前缀可以让真正的命令是任意东西，因此这种形状一律落到「其余」那一类去问人。
     *
     * @param command 命令原文，可为 {@code null}
     * @return 第一个 token；没有 token 时返回空串
     */
    private static String firstToken(String command) {
        String[] tokens = tokens(command);
        return tokens.length == 0 ? "" : tokens[0];
    }

    /**
     * 取命令的前两个 token。
     *
     * @param command 命令原文，可为 {@code null}
     * @return 以单个空格连接的前两个 token；不足两个时返回已有的部分
     */
    private static String firstTwoTokens(String command) {
        String[] tokens = tokens(command);
        if (tokens.length == 0) {
            return "";
        }
        return tokens.length == 1 ? tokens[0] : tokens[0] + " " + tokens[1];
    }

    /**
     * 按空白拆分命令原文。
     *
     * @param command 命令原文，可为 {@code null}
     * @return token 数组，保证非 {@code null}
     */
    private static String[] tokens(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new String[0];
        }
        return command.trim().split("\\s+");
    }
}
