package zcd.jellyfish.plugin.plan;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.util.Objects;

/**
 * plan 模式的权限拦截：把白名单之外的每一次工具调用都拦下来。
 * <p>
 * <b>为什么走权限扩展点而不是「换一份工具清单」</b>：工具清单进的是缓存前缀里很靠前的位置，
 * 一旦按模式换集合，那一轮之后的前缀（连同全部历史）都会作废。权限拦截则让模型看到的清单逐字节不变，
 * 只是写操作在执行时被拒——被判拒的理由还会经工具结果回灌给它，比「清单里少了一个工具」信息更全。
 * <p>
 * <b>它是类型级贡献</b>（{@code PermissionCheckRequest} 的路由键恒为 {@code null}），
 * 因此每一次工具调用的权限检查都会进来，包括别的插件提供的工具。本处理器只根据
 * 「会话是否开了 plan」与「工具名是否在白名单里」表态，不区分工具来自谁——这正是 plan 模式的语义。
 * <p>
 * <b>只能收紧、不能放宽</b>：返回类型 {@link PermissionVerdict} 里没有 {@code ALLOW} 这一态，
 * 因此本插件无法把内核或别的插件做出的拒绝改回放行；plan 关闭时返回
 * {@link PermissionVerdict#abstain()}，核心策略的结论原样生效。
 * <p>
 * <b>处理器必须只读且快</b>：它跑在权限判定的同步路径上（每一轮、每次工具调用）。因此这里只读
 * 内核内存里的会话扩展条目与启动期解析好的白名单，不碰文件系统、不起进程。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanPermission implements ExtensionHandler<PermissionCheckRequest, PermissionVerdict> {

    /** plan 开关，按会话现读。 */
    private final PlanState state;

    /** 只读白名单与拒绝文案。 */
    private final PlanConfig config;

    /**
     * 构造拦截处理器。
     *
     * @param state  plan 开关存取，不可为 {@code null}
     * @param config 只读白名单配置，不可为 {@code null}
     */
    PlanPermission(PlanState state, PlanConfig config) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public PermissionVerdict handle(PermissionCheckRequest request) {
        PlanState.Switch mode = state.switchOf(request.getSessionId());
        if (mode == PlanState.Switch.OFF) {
            return PermissionVerdict.abstain();
        }
        String toolName = request.getToolName();
        if (config.allows(toolName)) {
            return PermissionVerdict.abstain();
        }
        // 白名单为空是合法配置（就是「一个都不许」），但用户多半是漏配，因此补一条每种配置只喊一次的告警
        config.warnIfWhitelistIsEmpty(toolName);
        if (mode == PlanState.Switch.UNKNOWN) {
            // 判不出开着没有就按开着处理：不这样，状态读不出来的那一刻「只看不改」会静默失效，
            // 而模型只看到工具照常放行、用户也不知道自己开着的那道限制已经不在了
            return PermissionVerdict.deny("无法确认 plan 模式的状态（本会话的 plan 开关读不出来），"
                    + "按开启处理：" + config.denialReason(toolName));
        }
        return PermissionVerdict.deny(config.denialReason(toolName));
    }
}
