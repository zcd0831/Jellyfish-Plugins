package zcd.jellyfish.plugin.plan;

import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.util.List;
import java.util.Locale;

/**
 * {@code /plan [on|off]} 命令：查看或切换当前会话的 plan 模式。
 * <p>
 * <b>开关按会话归属</b>：同一个进程里可以一个会话开着 plan、另一个开着正常模式，切会话时各自记着——
 * 这与「模式属于会话」的语义一致，也是把状态放进会话扩展条目的直接结果。
 * <p>
 * <b>为什么不提供第三个取值</b>：内核此前的 {@code /mode} 有 {@code plan}/{@code normal} 两个取值，
 * 那是「模式枚举」；这里只有一个布尔开关，因此对外只有 {@code on}/{@code off}，
 * 少一层「模式名为 normal 的常态」的概念负担。
 * <p>
 * <b>无参时给出候选</b>：与内核其它「有取值可选」的命令一致，外壳据此渲染二级选择页；
 * 候选由 {@link PlanOptions#choices(boolean)} 生成，执行路径与候选路径共用同一份文案。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanCommand implements ExtensionHandler<CommandRequest, CommandResult> {

    /** 命令名，同时是注册路由键。 */
    static final String COMMAND_NAME = "plan";

    /** 开启取值。 */
    static final String VALUE_ON = "on";

    /** 关闭取值。 */
    static final String VALUE_OFF = "off";

    /** 用法文案：参数不合法与参数过多时共用。 */
    private static final String USAGE = "用法：/plan [on|off]";

    /** plan 开关存取。 */
    private final PlanState state;

    /**
     * 构造命令处理器。
     *
     * @param state plan 开关存取，不可为 {@code null}
     */
    PlanCommand(PlanState state) {
        this.state = state;
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            // 开关挂在会话上，没有会话就没有可切换的对象
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        List<String> tokens = request.getArguments().getTokens();
        if (tokens.size() > 1) {
            return CommandResult.error(USAGE);
        }
        if (tokens.isEmpty()) {
            PlanState.Switch mode = state.switchOf(sessionId);
            if (mode == PlanState.Switch.UNKNOWN) {
                // 不能报「已关闭」：那会让用户以为限制不在了，而权限拦截那侧正按「开着」处理；
                // 也不能硬报「已开启」，因为它到底开没开确实不知道。给了候选，用户可用 /plan off 重置
                return CommandResult.choices(
                        "当前 plan 模式：开关读不出来（白名单外的工具会被拒绝）", PlanOptions.choices(false));
            }
            boolean enabled = mode == PlanState.Switch.ON;
            return CommandResult.choices("当前 plan 模式：" + (enabled ? "已开启" : "已关闭"),
                    PlanOptions.choices(enabled));
        }
        String value = tokens.get(0).toLowerCase(Locale.ROOT);
        if (VALUE_ON.equals(value)) {
            state.set(sessionId, true);
            return CommandResult.ok("已开启 plan 模式（仅白名单内的工具可用）。");
        }
        if (VALUE_OFF.equals(value)) {
            state.set(sessionId, false);
            return CommandResult.ok("已关闭 plan 模式。");
        }
        return CommandResult.error(USAGE);
    }
}
