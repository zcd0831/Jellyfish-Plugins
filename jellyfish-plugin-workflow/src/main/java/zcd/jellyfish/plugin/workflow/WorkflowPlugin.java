package zcd.jellyfish.plugin.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.subagent.SubAgentPort;

/**
 * 官方编排插件：把「声明式编排」做成插件能力，内核只出原语、不解释 spec。
 * <p>
 * <b>它只占两个扩展点，且不需要新扩展点</b>：
 * <ul>
 *     <li>{@code workflow} 工具 → {@link ToolCallRequest}，模型提交 spec 的唯一入口；</li>
 *     <li>spec 的一个例句与选型规则 → {@link PromptContributionRequest}
 *     （schema 本身在工具名片里，见 {@link WorkflowGuidance}）。</li>
 * </ul>
 * <p>
 * <b>子代理从哪来</b>：{@link PluginContext#delegations()}。那是内核给插件的委派端口，
 * 与 {@code task} 工具走同一条代码路径，因此 governor、深度、取消、用量归集与归档的行为完全一致——
 * 插件不自己起线程池、不自己实现并发上限（见 {@link WorkflowEngine}）。
 * <p>
 * <b>为什么是插件而不是内核的一部分</b>：编排的「表达形式」（spec 长什么样、怎么聚合）会随着用法演化，
 * 把它放进内核意味着每次调整都要动内核的发布节奏；而内核只需要提供稳定的原语。
 * 插件缺席时 {@code task} 薄工具照旧可用，这正是「卸载即回退」。
 * <p>
 * 本插件没有配置项：spec 的形状是模型与插件之间的契约，不是部署参数。
 *
 * @author zcd
 */
public final class WorkflowPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowPlugin.class);

    @Override
    public void start(PluginContext context) {
        SubAgentPort port = context.delegations();
        // 先装配台账与引擎再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        WorkflowTracker tracker = new WorkflowTracker(invalidator(context));
        WorkflowEngine engine = new WorkflowEngine(port, tracker);
        context.handle(ToolCallRequest.class, WorkflowTool.NAME, WorkflowTool.descriptor(),
                new WorkflowTool(engine));
        context.contribute(PromptContributionRequest.class, new WorkflowGuidance());
        context.contribute(PanelContributionRequest.class, new WorkflowPanel(tracker));
        LOG.info("编排插件已启动: tool={}", WorkflowTool.NAME);
    }

    /**
     * 构造「编排状态变了，请外壳重新拉一次面板」的通知。
     * <p>
     * <b>为什么必须发</b>：外壳只在缓存失效时收集面板，而「某一步跑完了」发生在回合内部的工具调用里，
     * 外壳自己看不到。没有这条通知，面板会在编排结束后才第一次出现——那时它已经空了。
     * <p>
     * <b>为什么失败只记日志</b>：插件被停止时上下文已失效，{@code emit} 会当场抛错。
     * 那时面板停在最后一帧是正确的，而<b>编排本身不该因为一次界面刷新失败而失败</b>——
     * 这是展示与业务的分界，不是同一条成败线。
     *
     * @param context 插件上下文
     * @return 回调，保证非 {@code null}
     */
    private static Runnable invalidator(final PluginContext context) {
        return () -> {
            try {
                context.emit(new UiInvalidatedEvent());
            } catch (RuntimeException e) {
                LOG.debug("编排状态失效通知未发出（插件已停止？）：{}", e.getMessage());
            }
        };
    }
}
