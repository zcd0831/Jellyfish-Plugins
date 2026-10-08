package zcd.jellyfish.plugin.plan;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;

/**
 * 回合上下文：plan 开启时，把「当前处于计划模式」随<b>本轮用户消息</b>一起告诉模型。
 * <p>
 * <b>为什么需要说</b>：权限拦截只能让写操作失败，而模型看不到模式（工具清单逐字节不变），
 * 于是它会一次次去调一个注定被拒的工具，把整轮预算烧在重复失败上。提前告知能把这条弯路变成
 * 「先问用户要不要关掉 plan」。
 * <p>
 * <b>为什么走回合上下文而不是 system prompt</b>：模式会在会话中途切换，而 system prompt 是缓存前缀的
 * 第 0 个 token——放进去意味着每次 {@code /plan off} 都要作废整个请求连同全部历史。回合上下文是
 * append-only 的：只影响本轮新产生的那几个 token。
 * <p>
 * <b>关闭时返回空结果</b>：这是本扩展点为「无事可说」留的表达，内核不会为它追加任何东西，
 * 用户消息原样下发。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanTurnContext implements ExtensionHandler<TurnContextRequest, TurnContext> {

    /** plan 开关存取。 */
    private final PlanState state;

    /** 只读白名单，用于告诉模型「哪些还能用」。 */
    private final PlanConfig config;

    /**
     * 构造处理器。
     *
     * @param state  plan 开关存取，不可为 {@code null}
     * @param config 只读白名单配置，不可为 {@code null}
     */
    PlanTurnContext(PlanState state, PlanConfig config) {
        this.state = state;
        this.config = config;
    }

    @Override
    public TurnContext handle(TurnContextRequest request) {
        // 判不出来时也照说：拦截那侧同样按「开着」处理（fail-closed），两边口径必须一致，
        // 否则模型会去调一个注定被拒的工具，白跑一轮
        if (state.switchOf(request.getSessionId()) == PlanState.Switch.OFF) {
            return TurnContext.empty();
        }
        return TurnContext.of("【plan 模式】当前会话处于计划模式：只能使用白名单内的工具："
                + config.whitelistText() + "。需要改动文件或执行写操作前，请先让用户执行 /plan off。");
    }
}
