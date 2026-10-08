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
 * <b>读取失败按「读不出来」处理</b>：会话标识为空或会话已不存在时内核会抛 {@link JellyfishException}，
 * 此时既没有会话也就没有工具调用，按关闭返回不会放宽任何东西。但<b>读不到与确定关闭不是一回事</b>：
 * 会话还在、条目却读不出来时，若当作「关闭」，一道按模式收窄的授权就会在插件出故障的一瞬间
 * <b>静默消失</b>，现场没有任何痕迹。因此这里给的是三态（{@link Switch}），把取舍留给调用方——
 * 权限拦截那侧按「开着」处理（fail-closed），展示那侧按「没开」处理。
 * <p>
 * <b>判定要沿父链</b>：子代理跑在内核派生的独立会话上、它自己没有这条条目，只看本会话的话
 * 「父会话开着 plan、模型借 {@code task} 派子代理去写文件」就能绕过。详见
 * {@link #switchOf(String)}。
 * <p>
 * 无状态（状态全在内核侧），可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanState {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlanState.class);

    /**
     * 开关的三态。
     * <p>
     * 「读不出来」<b>不是</b>第三种业务状态，而是对调用方的一条要求：它必须自己决定往哪一侧倒。
     * 权限类的判断往「开着」倒（宁可多拦），展示类的判断往「没开」倒（不喊）。
     */
    enum Switch {

        /** 确定开着。 */
        ON,

        /** 确定没开。 */
        OFF,

        /** 读不出来：不能当作「没开」，因为那等于让收窄静默失效。 */
        UNKNOWN
    }

    /** 条目的 key（不含 owner 前缀，由内核补）。 */
    static final String KEY_ENABLED = "enabled";

    /** 条目里的字段名，值是一个布尔。 */
    private static final String FIELD_ENABLED = "enabled";

    /** 沿父链最多回溯多少层：一个查询不该依赖「父链不成环」这个假设。 */
    private static final int MAX_ANCESTOR_DEPTH = 16;

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
     * 判断某个会话（或它所在的会话树）处于哪种开关状态。
     * <p>
     * <b>为什么沿父链查</b>：子代理跑在内核派生的独立会话上，它自己没有这条扩展条目——只看本会话的话，
     * 「父会话开着 plan、模型借 {@code task} 派个子代理去写文件」就能绕过「只看不改」。而子代理的
     * 会话是内核派出来的，父链由内核如实给出（{@link PluginContext#parentSessionId(String)}）。
     * <p>
     * <b>沿链取「或」</b>：链上任何一层开着就算开着。这与「模式属于会话树」的语义一致——
     * 用户开 plan 时想约束的是这次工作，而不是「只有我自己亲手调的工具」。
     * <p>
     * <b>链上任何一层读不出来就整条链读不出来</b>：后面那几层里可能正开着，说「没开」是不负责任的；
     * 而「父链成环 / 深到超出上限」同样按读不出来处理——查不全就不放宽任何东西。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return {@link Switch#ON} / {@link Switch#OFF} / {@link Switch#UNKNOWN}，保证非 {@code null}
     */
    Switch switchOf(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            // 没有会话就没有开关：这是「确定没开」，不是「读不出来」
            return Switch.OFF;
        }
        String current = sessionId;
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH; depth++) {
            Switch here = switchHere(current);
            if (here == Switch.ON) {
                return Switch.ON;
            }
            if (here == Switch.UNKNOWN) {
                return Switch.UNKNOWN;
            }
            current = context.parentSessionId(current);
            if (current == null) {
                // 走到链头了：沿链都没有开
                return Switch.OFF;
            }
        }
        // 到上限还没走到链头：后面的层里可能开着，按「读不出来」处理
        LOG.warn("plan 开关的父链超过回溯上限，按读不出来处理: sessionId={} depth={}", sessionId,
                Integer.valueOf(MAX_ANCESTOR_DEPTH));
        return Switch.UNKNOWN;
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
     * 只看本会话自己的开关。
     * <p>
     * <b>读条目失败（含会话已不存在）按「读不出来」返回</b>：不在这里分辨「会话没了」与「上下文坏了」——
     * 调用方拿到的是同一条信息「我判不出来」，怎么倒由它自己决定。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 本会话的三态，保证非 {@code null}
     */
    private Switch switchHere(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Switch.OFF;
        }
        List<SessionExtensionEntry> entries;
        try {
            entries = context.extensionEntries(sessionId);
        } catch (JellyfishException e) {
            LOG.warn("读取 plan 开关失败，按读不出来处理: sessionId={}", sessionId, e);
            return Switch.UNKNOWN;
        }
        for (SessionExtensionEntry entry : entries) {
            if (!fullKey.equals(entry.getKey())) {
                continue;
            }
            // 条目值保证非 null（构造器把 null 与空表都归一成空表），因此只需看字段在不在
            Object enabled = entry.getValue().get(FIELD_ENABLED);
            if (enabled instanceof Boolean) {
                return ((Boolean) enabled).booleanValue() ? Switch.ON : Switch.OFF;
            }
            // 条目在、值却不认识（手改过会话文件、字段被删、或来自另一个版本的插件）：判不出开着没有，
            // 按读不出来处理比按「关」处理安全——后者会让限制静默消失
            LOG.warn("plan 开关取值无法识别，按读不出来处理: sessionId={} value={}", sessionId, enabled);
            return Switch.UNKNOWN;
        }
        // 没有本插件的那条条目：从来没有开过
        return Switch.OFF;
    }
}
