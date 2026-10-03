package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code todo_block} 工具：把一条待办记成卡住，并写下为什么。
 * <p>
 * <b>为什么必须有它</b>：没有「做不了」这个状态，一条做不了的活只能一直停在未开始，
 * 于是一批一批的子代理反复把它领走、反复失败——抢单式协作最容易烧钱的地方就是这个。
 * 卡住之后它不再被认领，直到有人把它放回（换个做法）或由父回合重写。
 * <p>
 * <b>原因必填且会一直留在清单里</b>：不写为什么，「卡住」与「没人做」在人看来是一样的，
 * 而这两件事该由谁去处理完全不同。
 * <p>
 * <b>按内容定位</b>：与认领同一套把手（内容），因为落盘里刻意没有编号。
 * <b>归属判定</b>：别人正认领着的条目会被拒绝；已完成的条目也拒绝卡住（它已经做完了）。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoBlockTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "todo_block";

    /** 报错消息里回显内容时的长度上限：消息首行会显示在轨迹行上。 */
    private static final int MAX_DESCRIBE_CHARS = 40;

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造工具。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoBlockTool(TodoStore store) {
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
        content.put("description", "要标记为卡住的那条待办的内容，必须是原文（与共享待办里显示的一致）");
        properties.put("content", content);
        Map<String, Object> reason = new LinkedHashMap<String, Object>();
        reason.put("type", "string");
        reason.put("description", "它为什么做不了：缺权限、环境不通、信息不足、需求本身矛盾……"
                + "写具体一点，人和父回合要靠它决定下一步（换成别的做法 / 换个 agent / 问用户）");
        properties.put("reason", reason);
        return new ToolDescriptor(NAME,
                "把本会话共享待办里的一条标记为「做不了」，并写下原因。卡住之后它不会再被任何子代理认领，"
                        + "直到有人把它放回或父回合重写清单。用于「它做不了」（缺权限、环境不通、需求矛盾）；"
                        + "「我没做它」请用 todo_release。content 传那条待办的原文，reason 必填。",
                properties, Arrays.asList("content", "reason"));
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String key = TodoScope.collaborationKeyOf(request);
        if (key == null) {
            throw new JellyfishException("todo_done 需要会话上下文，当前没有会话");
        }
        String content = text(request.getArguments().get("content"));
        String reason = text(request.getArguments().get("reason"));
        TodoActionResult result = store.block(key, content, request.getRunId(), reason);
        if (!result.isOk()) {
            return new ToolCallResult(NAME, failureText(result, content), summary(store, key));
        }
        return new ToolCallResult(NAME,
                "已记下卡住：" + result.getItem().content() + "\n原因：" + result.getItem().reason()
                        + "\n它不会再被认领；要重新做，先由认领者放回或由父回合重写清单。",
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
                return "这条待办正被另一个子代理认领，没有改动任何东西。"
                        + "只有认领者能把它记成卡住。";
            case WRONG_STATE:
                return "这条待办已经完成，不能记成卡住。";
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
            throw new JellyfishException("参数必须是非空字符串，实际为 " + describe(raw));
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
