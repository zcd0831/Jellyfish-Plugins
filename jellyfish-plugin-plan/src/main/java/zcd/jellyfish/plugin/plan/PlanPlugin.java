package zcd.jellyfish.plugin.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方 plan 模式插件：用权限拦截实现「只看不改」，用 {@code /plan [on|off]} 在会话内切换。
 * <p>
 * <b>plan 模式为什么在插件里</b>：它只是一条「哪些工具此刻可用」的策略，不碰循环、会话与压缩的任何结构，
 * 也不需要只有内核才知道的事实。放进插件顺带获得 {@code jellyfish.json} 里
 * {@code plugins.configurations.jellyfish-plan} 的可配置性与热部署，而不装它的用户不必为这条策略付费。
 * <p>
 * <b>四个面各占一个扩展点，且都不需要新扩展点</b>：
 * <ul>
 *     <li>白名单外的工具被拒 → {@link PermissionCheckRequest}（类型级贡献，拦的是「一次工具调用」这个整体判断）；</li>
 *     <li>{@code /plan [on|off]} 开关 → {@link CommandRequest}，取值候选另走 {@link CommandOptionRequest}；</li>
 *     <li>状态栏片段 → {@link StatusLineContributionRequest}，一眼看出当前开着；</li>
 *     <li>「当前处于 plan 模式」随本轮用户消息送达 → {@link TurnContextRequest}，
 *     <b>而不是往 system prompt 里注</b>——模式会在会话中途切换，放进缓存前缀的第 0 个 token
 *     意味着每次切换都要作废整个请求连同全部历史。</li>
 * </ul>
 * <p>
 * <b>状态存在会话扩展条目里</b>（见 {@link PlanState}）：归属天然是「某一个会话」，
 * 随会话一起落盘、一起恢复，插件卸载也不删——不需要本插件自建文件格式与清理逻辑。
 * <p>
 * <b>子代理不继承</b>：开关按会话存，子代理是另一个会话，因此默认不受 plan 限制。
 * 需要连子代理也只看不写时，应当单独在那个会话上开启（本版本不做自动继承）。
 * <p>
 * 配置见 {@link PlanConfig}：{@code readOnlyTools} 是「plan 下哪些工具可用」的唯一来源，
 * <b>不写就等于一个都不许</b>。
 *
 * @author zcd
 */
public final class PlanPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlanPlugin.class);

    @Override
    public void start(PluginContext context) {
        PlanState state = new PlanState(context);
        // 先解析配置再注册：处理器一旦注册就可能被调用，白名单必须已经就绪
        PlanConfig config = PlanConfig.from(context.configuration(), context.pluginId(), context::emit);
        context.contribute(PermissionCheckRequest.class, new PlanPermission(state, config));
        context.handle(CommandRequest.class, PlanCommand.COMMAND_NAME,
                // 末尾显式声明 sessionRequired=true：开关是按会话存的，没有会话就没有可切换的对象
                new CommandDescriptor("查看或切换 plan 模式", "[on|off]", null, true), new PlanCommand(state));
        context.handle(CommandOptionRequest.class, PlanCommand.COMMAND_NAME, new PlanOptions(state));
        context.contribute(StatusLineContributionRequest.class, new PlanStatusLine(state));
        context.contribute(TurnContextRequest.class, new PlanTurnContext(state, config));
        LOG.info("plan 插件已启动: readOnlyTools={}", config.whitelistText());
    }
}
