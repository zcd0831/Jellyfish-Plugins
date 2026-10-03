package zcd.jellyfish.plugin.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * plan 开关的存储：会话级状态，落在内核的会话扩展条目里。
 * <p>
 * <b>为什么用扩展条目而不是自己的文件</b>：开关的归属就是「某一个会话」，而内核已经有一处
 * 「插件挂在会话上、随会话落盘、插件之间互相看不见」的位置（{@code PluginContext} 的扩展条目）。
 * 自己写文件要另立格式、另做并发控制与清理，且会话删除时还得自己收尾——那正是待办插件不得不做的事，
 * 而本插件没有这个必要。
 * <p>
 * <b>因此开关随会话一起持久化、一起恢复</b>：{@code /resume} 回到一个开着 plan 的会话，
 * 它仍然是开着的——这与「模式属于会话」的语义一致。插件卸载也不删条目，装回来还能读到。
 * <p>
 * <b>读取失败按「关闭」处理</b>：会话标识为空或会话已不存在时内核会抛 {@link JellyfishException}，
 * 此时既没有会话也就没有工具调用，按关闭返回不会放宽任何东西；真正的错误（如上下文已失效）
 * 同样由内核在权限链上按「插件无异议」兜底，因此这里不再制造第二条失败路径。
 * <p>
 * 无状态（状态全在内核侧），可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanState {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlanState.class);

    /** 条目的 key（不含 owner 前缀，由内核补）。 */
    static final String KEY_ENABLED = "enabled";

    /** 条目里的字段名，值是一个布尔。 */
    private static final String FIELD_ENABLED = "enabled";

    /** 插件上下文：读写会话扩展条目的唯一入口。 */
    private final PluginContext context;

    /** 带 owner 前缀的完整 key，用于在读取时认出自己那一条。 */
    private final String fullKey;

    /**
     * 构造状态存取。
     *
     * @param context 插件上下文，不可为 {@code null}
     */
    PlanState(PluginContext context) {
        this.context = context;
        this.fullKey = context.pluginId() + PluginOwnerNamespace.SEPARATOR + KEY_ENABLED;
    }

    /**
     * 判断某个会话是否处于 plan 模式。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 开启返回 {@code true}；未设置、会话不存在或标识为空时返回 {@code false}
     */
    boolean isEnabled(String sessionId) {
        SessionExtensionEntry entry = entryOf(sessionId);
        if (entry == null) {
            return false;
        }
        Object value = entry.getValue().get(FIELD_ENABLED);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /**
     * 设置某个会话的 plan 开关。
     *
     * @param sessionId 会话标识，不可为空白
     * @param enabled   是否开启
     * @throws JellyfishException 会话不存在、标识为空白，或插件上下文已失效时抛出
     */
    void set(String sessionId, boolean enabled) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put(FIELD_ENABLED, Boolean.valueOf(enabled));
        context.putExtensionEntry(sessionId, KEY_ENABLED, value);
    }

    /**
     * 读取本插件挂在目标会话上的那一条扩展条目。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 条目；没有或读不到时返回 {@code null}
     */
    private SessionExtensionEntry entryOf(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        List<SessionExtensionEntry> entries;
        try {
            entries = context.extensionEntries(sessionId);
        } catch (JellyfishException e) {
            // 会话不存在（或上下文已失效）：没有会话就没有 plan 状态，按关闭处理
            LOG.debug("读取 plan 开关失败，按关闭处理: sessionId={} cause={}", sessionId, e.getMessage());
            return null;
        }
        for (SessionExtensionEntry entry : entries) {
            if (fullKey.equals(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }
}
