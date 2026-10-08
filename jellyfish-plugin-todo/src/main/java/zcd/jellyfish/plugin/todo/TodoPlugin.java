package zcd.jellyfish.plugin.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;

/**
 * 官方待办插件：把会话待办做成插件能力，内核不再持有该领域。
 * <p>
 * <b>五个面各占一个扩展点，且都不需要新扩展点</b>：
 * <ul>
 *     <li>{@code todo_write} 工具 → {@link ToolCallRequest}，模型写待办的唯一入口；</li>
 *     <li>{@code todo_claim} / {@code todo_done} / {@code todo_release} / {@code todo_block} 工具 →
 *     {@link ToolCallRequest}，子代理认领、完成、放回与记成卡住；它们与父回合读写的是<b>同一份</b>清单
 *     （键是「归属会话」，见 {@link TodoScope}）；</li>
 *     <li>{@code /todo} 命令 → {@link CommandRequest}，给人看的只读清单；</li>
 *     <li>选型规则 → {@link PromptContributionRequest}（{@code STATIC}）：什么时候把活写进待办让子代理认领，
 *     而不是写进 system prompt 的状态部分——那部分每变一次就要重新计费整段历史；</li>
 *     <li>待办随本轮用户消息送达 → {@link TurnContextRequest}，让模型每轮都看得见自己的计划；
 *     <b>而不是往 system prompt 里注</b>——待办是会话中途反复改写的状态，放进缓存前缀的第 0 个 token
 *     意味着每勾掉一件事，整个请求连同全部历史都要重新计费一次；</li>
 *     <li>状态栏进度 → {@link StatusLineContributionRequest}，不敲命令也能看到还剩几件事；</li>
 *     <li>待办面板 → {@link PanelContributionRequest}，在左栏常驻显示完整清单，
 *     被认领的条目还会带上「谁在做」；</li>
 *     <li>run 通知订阅 → {@link AgentRunProgressEvent}，把认领者的 run 标识翻成人看得懂的类型与状态
 *     （见 {@link RunPresence}）；</li>
 * </ul>
 * <p>
 * <b>第六个扩展点是生命周期清理</b>：{@link SessionDeleteRequest}，内核删除会话时把本会话的待办文件
 * 一并删掉。待办按 {@code sessionId} 归属，会话没了它就没有意义；不处理的话每删一个会话就会多一个
 * 孤儿待办文件——而删除会话这件事本身就是为了不再堆积。
 * <p>
 * <b>插件为什么能拥有这份状态</b>：待办只需 {@code sessionId} 作为归属，而命令与工具请求都带它；
 * 存储由插件自己的文件负责，内核既不用新增会话字段，也不必为它保留任何调用点。反过来，待办也因此
 * 不可能「做不成插件」——它不属于需要碰会话内部结构的类型。
 * <p>
 * 配置见 {@link PluginConfig}：{@code todoDir}，默认 {@code ~/.jellyfish/todos}。
 *
 * @author zcd
 */
public final class TodoPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TodoPlugin.class);

    @Override
    public void start(PluginContext context) {
        PluginConfig config = PluginConfig.from(context.configuration());
        Path directory = config.todoDirectory();
        // 先装配仓库再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        TodoStore store = new TodoStore(directory);
        // 协作键解析：工具与回合上下文共用同一份规则（归属会话，见 TodoScope）
        TodoScope scope = new TodoScope(context);
        context.handle(CommandRequest.class, "todo",
                // 末尾显式声明 sessionRequired=true：待办是按会话归属的，没有会话就没有待办可看
                new CommandDescriptor("查看当前会话待办", null, null, true), new TodoCommand(store));
        context.contribute(TurnContextRequest.class, new TodoTurnContext(store, scope));
        // 选型规则走 STATIC（编译期固定、进可缓存前缀）；「还剩几条」那类状态随回合块走
        context.contribute(PromptContributionRequest.class, new TodoGuidance());
        context.contribute(StatusLineContributionRequest.class, new TodoStatusLine(store));
        // 认领者的在场记录：订阅内核的 run 通知，把待办里的 run 标识翻成人看得懂的类型与状态。
        // 它认识的是内核的事件类型，不认识任何编排插件——这正是「跨能力整合只走内核中立面」的落地。
        final RunPresence presence = new RunPresence();
        context.observe(AgentRunProgressEvent.class, presence::on);
        context.contribute(PanelContributionRequest.class, new TodoPanel(store, presence));
        // 会话删除时清掉本会话的待办文件（lambda 只为把 store 带进处理器，逻辑全在 TodoStore）
        context.contribute(SessionDeleteRequest.class, request -> {
            store.delete(request.getSessionId());
            return null;
        });
        registerStateChangingTool(context, TodoWriteTool.NAME, TodoWriteTool.descriptor(),
                new TodoWriteTool(store, scope));
        registerStateChangingTool(context, TodoClaimTool.NAME, TodoClaimTool.descriptor(),
                new TodoClaimTool(store, scope));
        registerStateChangingTool(context, TodoDoneTool.NAME, TodoDoneTool.descriptor(),
                new TodoDoneTool(store, scope));
        registerStateChangingTool(context, TodoReleaseTool.NAME, TodoReleaseTool.descriptor(),
                new TodoReleaseTool(store, scope));
        registerStateChangingTool(context, TodoBlockTool.NAME, TodoBlockTool.descriptor(),
                new TodoBlockTool(store, scope));
        LOG.info("待办插件已启动: dir={}", directory);
    }

    /**
     * 注册一个会改动待办的 工具，并在调用后广播一次 UI 失效。
     * <p>
     * <b>为什么包一层而不是让工具自己发事件</b>：工具只该关心「把待办存好」，
     * 「状态栏要刷新」是插件的展示职责；把两者分开，{@link TodoWriteTool} 就可以在没有任何
     * 插件上下文的情况下单测。
     * <p>
     * <b>为什么写失败也发</b>：不必分辨——失效只是「下一帧重问一次」，写失败时状态没变，
     * 重问一次所得与之前完全相同，而少发一次的代价是「某条分支忘了发」导致内容陈旧。
     * <p>
     * <b>为什么认领与完成也走这条路</b>：它们同样改动了清单，而子代理改完时父回合那边
     * 没有任何别的信号——不发失效，用户看到的就是一份陈旧的清单。
     *
     * @param context    插件上下文
     * @param name       工具名
     * @param descriptor 工具名片
     * @param tool       工具处理器
     */
    private static void registerStateChangingTool(final PluginContext context, String name, Object descriptor,
                                                  final ExtensionHandler<ToolCallRequest, ToolCallResult> tool) {
        context.handle(ToolCallRequest.class, name, descriptor, request -> {
            ToolCallResult result = tool.handle(request);
            context.emit(new UiInvalidatedEvent());
            return result;
        });
    }
}
