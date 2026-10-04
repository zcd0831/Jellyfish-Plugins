package zcd.jellyfish.plugin.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本插件的配置：{@code plugins.configurations.jellyfish-plugin-plan} 段里的只读白名单。
 * <p>
 * <b>白名单就是「plan 模式下哪些工具可用」的全部答案</b>：用户写了哪些工具名，plan 下就只有哪些可用。
 * 工具提供方无法自称只读——{@code ToolDescriptor} 里没有「只读」这个字段，
 * 因此第三方进程（脚本作者、MCP server）都塞不进这份名单。
 * <p>
 * <b>不写就等于 plan 下全部不可用</b>（白名单语义）：集合为空时同样拒绝。这是刻意的——
 * 「用户没表态」与「用户不准」在这里是同一件事，而默认放行会让 plan 模式形同虚设。
 * <p>
 * <b>配置可疑只告警、不中断启动</b>：不是数组、含非字符串项都只发一条
 * {@link ConfigWarningEvent} 并跳过该项，与内核「配置好坏不阻断启动」的既有口径一致。
 * <p>
 * <b>为什么在这里缓存解析结果</b>：配置段是插件启动那一刻的快照（换配置要 {@code /reload}，
 * 那时本插件会整个重启），因此一次解析之后不再变化；拒绝文案与提示词块都复用同一份结果，
 * 避免每次判定都重新拼一遍字符串。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlanConfig.class);

    /** 只读白名单配置键，与内核此前的同名键逐字一致：用户的配置习惯不用改，只是位置挪进本插件的配置段。 */
    static final String KEY_READ_ONLY_TOOLS = "readOnlyTools";

    /** 配置段的完整路径，用于拒绝文案与告警：只写「仅允许只读工具」，用户不知道该改哪里。 */
    static final String CONFIGURATION_PATH = "plugins.configurations.jellyfish-plugin-plan." + KEY_READ_ONLY_TOOLS;

    /** 被拒文案里的「去哪儿声明」提示，与空白名单告警共用一份，避免两处说法漂移。 */
    static final String DECLARATION_HINT = "请在 " + CONFIGURATION_PATH + " 里声明 plan 模式下允许使用的工具名";

    /** 用户声明的只读工具名，保持配置里的顺序（拒绝文案里读起来稳定）。 */
    private final Set<String> readOnlyTools;

    /** 白名单的展示文本（逗号分隔），空白名单时为空串。 */
    private final String whitelistText;

    /** 告警来源标识，进 {@link ConfigWarningEvent} 便于归因。 */
    private final String source;

    /** 告警发布入口：由插件上下文提供（{@code PluginContext::emit}），本类不依赖插件上下文本身。 */
    private final Consumer<JellyfishEvent> warner;

    /** 是否已经就「白名单为空」告警过。 */
    private volatile boolean emptyWhitelistWarned;

    /**
     * 构造配置。
     *
     * @param readOnlyTools 只读工具名集合，保持配置顺序
     * @param source        告警来源标识
     * @param warner        告警发布入口
     */
    private PlanConfig(Set<String> readOnlyTools, String source, Consumer<JellyfishEvent> warner) {
        this.readOnlyTools = Collections.unmodifiableSet(readOnlyTools);
        this.whitelistText = String.join(", ", readOnlyTools);
        this.source = source;
        this.warner = warner;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @param source        告警来源标识，通常是插件标识
     * @param warner        告警发布入口，可为 {@code null}（不告警）
     * @return 配置值对象，保证非 {@code null}
     */
    static PlanConfig from(Map<String, Object> configuration, String source, Consumer<JellyfishEvent> warner) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap() : configuration;
        Set<String> names = parse(values.get(KEY_READ_ONLY_TOOLS), source, warner);
        LOG.info("plan 插件已装配: readOnlyTools={}", names);
        return new PlanConfig(names, source, warner);
    }

    /**
     * 判断工具是否在只读白名单里。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 在白名单里返回 {@code true}；{@code null} 恒为 {@code false}
     */
    boolean allows(String toolName) {
        return toolName != null && readOnlyTools.contains(toolName);
    }

    /**
     * 判断白名单是否为空。
     *
     * @return 未声明任何只读工具时返回 {@code true}
     */
    boolean isEmpty() {
        return readOnlyTools.isEmpty();
    }

    /**
     * 取被拒理由：点明「plan 下只能用哪些工具」以及「去哪儿声明」。
     *
     * @param toolName 被拒的工具名，进文案便于定位
     * @return 拒绝理由文本，保证非 {@code null}
     */
    String denialReason(String toolName) {
        if (readOnlyTools.isEmpty()) {
            return "plan 模式下只能使用只读工具：「" + toolName + "」被拒，且只读白名单为空"
                    + "（plan 下所有工具都会被拒）。" + DECLARATION_HINT;
        }
        return "plan 模式下只能使用只读工具：" + whitelistText + "（" + DECLARATION_HINT + "）";
    }

    /**
     * 取给模型看的白名单描述，供回合上下文使用。
     *
     * @return 白名单文本；为空时返回「（未声明任何工具）」这样的可读说明
     */
    String whitelistText() {
        return readOnlyTools.isEmpty() ? "（未声明任何工具，所有工具都会被拒绝）" : whitelistText;
    }

    /**
     * 在「白名单为空」时补一条配置告警。
     * <p>
     * <b>为什么只喊一次</b>：本判定挂在每一次被拒的工具调用上，无节流会让同一份配置重复刷屏，
     * 把真正需要看见的告警淹掉。配置段一变本插件就会重启，因此「一次」的作用域恰好是「一份配置」。
     * <p>
     * 告警失败不影响拒绝结论：可观测性不该让判定链失败。
     *
     * @param toolName 被拒的工具名，进告警文案便于定位
     */
    void warnIfWhitelistIsEmpty(String toolName) {
        if (!readOnlyTools.isEmpty() || emptyWhitelistWarned || warner == null) {
            return;
        }
        emptyWhitelistWarned = true;
        try {
            warner.accept(new ConfigWarningEvent(source, "plan 模式下工具「" + toolName
                    + "」被拒，且只读白名单为空（plan 下所有工具都会被拒）：" + DECLARATION_HINT));
        } catch (RuntimeException e) {
            LOG.warn("只读白名单为空的告警发布失败", e);
        }
    }

    /**
     * 解析只读工具名清单：跳过非法项并告警。
     *
     * @param declared 配置原值，可为 {@code null}
     * @param source   告警来源标识
     * @param warner   告警发布入口，可为 {@code null}
     * @return 去重后的工具名集合，保持声明顺序
     */
    private static Set<String> parse(Object declared, String source, Consumer<JellyfishEvent> warner) {
        Set<String> names = new LinkedHashSet<String>();
        if (declared == null) {
            return names;
        }
        if (!(declared instanceof Collection)) {
            warn(source, warner, KEY_READ_ONLY_TOOLS + " 应为工具名数组，实际为 "
                    + declared.getClass().getName() + "；已按空白名单处理");
            return names;
        }
        for (Object item : (Collection<?>) declared) {
            if (item instanceof String && !((String) item).trim().isEmpty()) {
                names.add(((String) item).trim());
            } else {
                warn(source, warner, KEY_READ_ONLY_TOOLS + " 含非空字符串以外的项，已跳过: " + item);
            }
        }
        return names;
    }

    /**
     * 发布一条配置告警。
     *
     * @param source 告警来源标识
     * @param warner 告警发布入口，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(String source, Consumer<JellyfishEvent> warner, String message) {
        if (warner == null) {
            return;
        }
        warner.accept(new ConfigWarningEvent(source, message));
    }

    @Override
    public String toString() {
        return "PlanConfig{readOnlyTools=" + readOnlyTools.size() + '}';
    }
}
