package zcd.jellyfish.plugin.plan;

import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /plan} 的只读候选查询：给出 on / off 两个取值并标出当前那个。
 * <p>
 * <b>为什么与命令执行分开</b>：候选查询要能在外壳「用户刚选中命令、还没决定参数」时回答，
 * 而执行一条命令是有副作用的（改会话状态）。内核把两条路径拆成两个扩展点与两个请求类型，
 * 本处理器因此只读地回一份取值清单。
 * <p>
 * <b>首页也给出候选</b>：那里没有会话，当前值按「关闭」算——总比回一片空白有用。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanOptions implements ExtensionHandler<CommandOptionRequest, CommandOptions> {

    /** plan 开关存取。 */
    private final PlanState state;

    /**
     * 构造候选处理器。
     *
     * @param state plan 开关存取，不可为 {@code null}
     */
    PlanOptions(PlanState state) {
        this.state = state;
    }

    @Override
    public CommandOptions handle(CommandOptionRequest request) {
        // 展示路径：判不出来时按「没开」渲染，别把 on 标成当前值
        return CommandOptions.of(choices(state.switchOf(request.getSessionId()) == PlanState.Switch.ON));
    }

    /**
     * 生成 on / off 两个候选，并标出当前生效的那个。
     *
     * @param enabled 当前是否已开启 plan
     * @return 候选清单，顺序为 on、off
     */
    static List<CommandChoice> choices(boolean enabled) {
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        choices.add(new CommandChoice(PlanCommand.VALUE_ON, PlanCommand.VALUE_ON,
                "只能使用白名单内的工具", enabled));
        choices.add(new CommandChoice(PlanCommand.VALUE_OFF, PlanCommand.VALUE_OFF,
                "不受 plan 限制", !enabled));
        return choices;
    }
}
