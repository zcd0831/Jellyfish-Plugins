package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Collections;
import java.util.Map;

/**
 * {@code todo_claim} 工具：子代理从共享待办里认领一条活干。
 * <p>
 * <b>为什么需要它</b>：父回合写下计划、派出几个子代理，但「谁做哪一条」在此之前没有落点——
 * 每个子代理各自埋头做自己的 prompt，重复干活与漏活都只能靠父回合事后发现。
 * 认领把这件事变成一个原子动作：读的时候还没主的条目，写回来时已经记上了我的 run 标识。
 * <p>
 * <b>为什么没有参数</b>：认领的是「下一条还没人做的」，而<b>派几个、派谁</b>由父回合决定
 * （它写 spec、拼 prompt）。给这个工具加「指定哪一条」会让父与子各自维护一份「谁该做哪条」，
 * 两份必然漂移；而按内容指定还要求父回合把内容原样抄进 prompt，模型抄错一个字就认领失败。
 * <p>
 * <b>为什么只允许子代理调用</b>：认领的意义是「把这条记在我名下，别人别重复做」，
 * 而顶层回合不在任何 run 上，记不下这个名字。与其记一个空 owner 让归属判断失效，
 * 不如明确拒绝并把话说清楚。
 * <p>
 * <b>认领不到不是错误</b>：它返回一条说明，而不是抛异常——「计划已经全被领走了」是完全正常的结果，
 * 子代理据此收工即可。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoClaimTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "todo_claim";

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造工具。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoClaimTool(TodoStore store) {
        this.store = store;
    }

    /**
     * 构造工具名片。
     *
     * @return 工具描述符
     */
    static ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME,
                "从本会话的共享待办里认领一条还没人做的任务。认领后那一条就归你，做完用 todo_done 标记完成。"
                        + "每次只认领一条，重复调用不会再多领；没有可认领的会明确告诉你，那不是错误。"
                        + "只有子代理能认领——父回合负责写计划，不参与认领。",
                Collections.<String, Object>emptyMap(), Collections.<String>emptyList());
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String key = TodoScope.collaborationKeyOf(request);
        if (key == null) {
            throw new JellyfishException("todo_claim 需要会话上下文，当前没有会话");
        }
        String runId = request.getRunId();
        if (runId == null || runId.trim().isEmpty()) {
            throw new JellyfishException("todo_claim 只能由子代理调用：当前调用不在任何 run 上，"
                    + "没有名字可以记在待办上");
        }
        TodoActionResult result = store.claim(key, runId);
        if (!result.isOk()) {
            return new ToolCallResult(NAME, "当前没有可认领的待办。",
                    summary(store, key, "没有可认领的待办"));
        }
        TodoItem item = result.getItem();
        return new ToolCallResult(NAME,
                "已认领：" + item.content() + "\n做完后用 todo_done 标记它（content 传这条的原文）。",
                summary(store, key, "已认领：" + item.content()));
    }

    /**
     * 组装轨迹行上的一行摘要。
     *
     * @param store     待办仓库
     * @param key       协作键
     * @param fallback  取不到清单时的兜底文本
     * @return 摘要文本，保证非 {@code null}
     */
    private static Map<String, Object> summary(TodoStore store, String key, String fallback) {
        String status = TodoText.statusLine(store.itemsOf(key));
        return Collections.<String, Object>singletonMap(ToolMetadata.KEY_SUMMARY,
                status == null ? fallback : status);
    }
}
