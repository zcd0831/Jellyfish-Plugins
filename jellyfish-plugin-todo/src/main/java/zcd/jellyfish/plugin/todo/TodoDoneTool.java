package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code todo_done} 工具：把一条待办标成完成。
 * <p>
 * <b>与 {@code todo_write} 的分工</b>：整表覆盖仍然是父回合模型表达「计划」的方式；
 * 本工具是「一件事做完了」这个动作，它只碰一条，且必须由做过它的人（或没有任何人认领时）调用。
 * 子代理若用 {@code todo_write} 去勾自己那一条，就得把整份清单原样抄回来——抄漏一条就是删掉一条，
 * 那是比「多做一次」严重得多的错。
 * <p>
 * <b>按内容定位</b>：与认领同一套把手（内容），因为落盘里刻意没有编号。
 * <b>归属判定</b>：别人正认领着的条目会被拒绝，消息里说清是哪一种失败——模型据此能改对，
 * 而不是把「没改成」当成「已经完成」。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoDoneTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "todo_done";

    /** 报错消息里回显内容时的长度上限：消息首行会显示在轨迹行上。 */
    private static final int MAX_DESCRIBE_CHARS = 40;

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造工具。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoDoneTool(TodoStore store) {
        this.store = store;
    }

    /**
     * 构造工具名片。
     *
     * @return 工具描述符
     */
    static ToolDescriptor descriptor() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("type", "string");
        content.put("description", "要标记完成的那条待办的内容，必须是原文（与共享待办里显示的一致）");
        properties.put("content", content);
        return new ToolDescriptor(NAME,
                "把本会话共享待办里的一条标记为完成。content 传那条待办的原文；"
                        + "已经有人认领的条目只有认领者能完成它，父回合可以完成没人认领的条目。",
                properties, Collections.singletonList("content"));
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String key = TodoScope.collaborationKeyOf(request);
        if (key == null) {
            throw new JellyfishException("todo_done 需要会话上下文，当前没有会话");
        }
        String content = text(request.getArguments().get("content"));
        TodoActionResult result = store.complete(key, content, request.getRunId());
        if (!result.isOk()) {
            return new ToolCallResult(NAME, failureText(result, content), summary(store, key));
        }
        return new ToolCallResult(NAME, "已完成：" + result.getItem().content(),
                summary(store, key));
    }

    /**
     * 渲染失败说明。
     * <p>
     * 三种失败要说得出区别：模型只有看到「不是你的」才不会把「没改成」当成「已经完成」，
     * 只有看到原文回显才能发现自己抄错了字。
     *
     * @param result  失败结果
     * @param content 调用方给的内容
     * @return 说明文本，保证非 {@code null}
     */
    private static String failureText(TodoActionResult result, String content) {
        switch (result.getCode()) {
            case NOT_FOUND:
                return "共享待办里没有内容为 \"" + content + "\" 的那一条，没有改动任何东西。"
                        + "请用原文重试，或先看看当前清单。";
            case TAKEN:
                return "这条待办正被另一个子代理认领，没有改动任何东西。若你确实在做它，"
                        + "说明你们重复领取了同一件事，请把结论回报给父回合。";
            default:
                return "没有改动任何东西。";
        }
    }

    /**
     * 组装轨迹行上的一行摘要。
     *
     * @param store 待办仓库
     * @param key   协作键
     * @return 摘要文本，保证非 {@code null}
     */
    private static Map<String, Object> summary(TodoStore store, String key) {
        String status = TodoText.statusLine(store.itemsOf(key));
        return Collections.<String, Object>singletonMap(ToolMetadata.KEY_SUMMARY,
                status == null ? "待办已清空" : status);
    }

    /**
     * 取字符串参数。
     *
     * @param raw 参数原值，可为 {@code null}
     * @return 去空白后的文本，保证非 {@code null}
     * @throws JellyfishException 参数缺失或不是非空字符串时抛出
     */
    private static String text(Object raw) {
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new JellyfishException("content 必须是非空字符串，实际为 " + describe(raw));
        }
        return ((String) raw).trim();
    }

    /**
     * 生成便于排错的参数描述。
     *
     * @param value 参数值，可为 {@code null}
     * @return 描述文本，保证非 {@code null}
     */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            String text = ((String) value).replace('\n', ' ').trim();
            if (text.length() <= MAX_DESCRIBE_CHARS) {
                return '"' + text + '"';
            }
            return '"' + text.substring(0, MAX_DESCRIBE_CHARS) + "…\"";
        }
        return value.getClass().getSimpleName();
    }
}
