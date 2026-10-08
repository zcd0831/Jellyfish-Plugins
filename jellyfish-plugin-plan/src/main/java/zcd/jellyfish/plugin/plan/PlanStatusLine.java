package zcd.jellyfish.plugin.plan;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;

/**
 * 状态栏贡献：plan 开启时在状态栏尾部追加一小段文本。
 * <p>
 * <b>为什么由插件来显示</b>：模式是插件的能力，内核的状态栏不再有「权限模式」这一格；
 * 外壳开着的状态栏拉取扩展点（{@code StatusLineContributionRequest}）本来就是给这类片段用的，
 * 追加而不是替换，因此与其它插件的片段共存。
 * <p>
 * <b>关闭时返回空贡献</b>：常态不值得占一列——内核字段已经占了大部分宽度，
 * 一个恒在的 {@code plan off} 只是噪音。这与此前状态栏恒显模式名的做法不同，是有意的收紧。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：本处理器只读内核内存里的会话扩展条目，不碰文件系统，
 * 因此可以放心被失效事件反复触发。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class PlanStatusLine implements ExtensionHandler<StatusLineContributionRequest, StatusLineContribution> {

    /** 开启时显示的片段文本，自带归属、极简。 */
    private static final String TEXT = "plan";

    /** plan 开关存取。 */
    private final PlanState state;

    /**
     * 构造状态栏处理器。
     *
     * @param state plan 开关存取，不可为 {@code null}
     */
    PlanStatusLine(PlanState state) {
        this.state = state;
    }

    @Override
    public StatusLineContribution handle(StatusLineContributionRequest request) {
        // 展示路径：只有「确定开着」才显示。判不出来时这里不喊（权限拦截那侧会拒绝并给出理由），
        // 让状态栏在一个查询异常上闪来闪去反而更难读
        return state.switchOf(request.getSessionId()) == PlanState.Switch.ON
                ? StatusLineContribution.of(TEXT)
                : StatusLineContribution.empty();
    }
}
