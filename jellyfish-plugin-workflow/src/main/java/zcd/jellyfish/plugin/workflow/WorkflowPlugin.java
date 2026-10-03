package zcd.jellyfish.plugin.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        // 先装配引擎再注册工具：处理器一旦注册就可能被调用，依赖必须已经就绪
        WorkflowEngine engine = new WorkflowEngine(port);
        context.handle(ToolCallRequest.class, WorkflowTool.NAME, WorkflowTool.descriptor(),
                new WorkflowTool(engine));
        context.contribute(PromptContributionRequest.class, new WorkflowGuidance());
        LOG.info("编排插件已启动: tool={}", WorkflowTool.NAME);
    }
}
