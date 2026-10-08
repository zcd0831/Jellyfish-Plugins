package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code todo_write} 工具：模型写待办的唯一入口。
 * <p>
 * <b>写的是「协作键」那一份清单</b>：子代理有独立会话，直接按 {@code sessionId} 写就会写到它自己那一份里
 * （父回合看不见，盘上还多一个文件）。父回合调用时协作键与自己的会话标识相同，行为与从前一致。
 * <p>
 * <b>整表覆盖而不是增量</b>：模型手上没有稳定的编号，让它 {@code add 1} / {@code done 2} 就得先把编号读回来，
 * 多一轮往返且容易记错；传一整份列表则「增、删、改、重排」共用同一种表达，也不会出现两份互相打架的状态。
 * 编号只在渲染给人看时按位置生成。
 * <p>
 * <b>参数是不可信输入</b>：模型可能传错类型、漏字段、写错枚举值。这里一律当场抛
 * {@link JellyfishException}——ReAct 会把工具异常转成 tool 结果回灌，模型因此能看着错误信息自己改，
 * 而不是让一份坏数据静默落盘。报错消息因此必须<b>带上实际收到的值</b>：只说「实际为 String」时
 * 模型改不动（它以为自己传的就是对的），会原样重试。
 * <p>
 * <b>状态取值与模型的语言对齐</b>：见 {@link TodoStatus}。曾经只认 {@code pending} / {@code completed}，
 * 而模型习惯写 {@code in_progress}，于是一次写入被整批拒掉——整表覆盖是原子操作，同一批里已经标成
 * {@code completed} 的项跟着一起丢。三态是为了让模型能如实表达「正在做」，不是为了放宽校验。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoWriteTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "todo_write";

    /** 报错消息里回显参数值时的长度上限：消息首行会显示在轨迹行上，不能让它变成一整段内容。 */
    private static final int MAX_DESCRIBE_CHARS = 40;

    /** 待办仓库。 */
    private final TodoStore store;

    /** 协作键解析。 */
    private final TodoScope scope;

    /**
     * 构造工具。
     *
     * @param store 待办仓库，不可为 {@code null}
     * @param scope 协作键解析，不可为 {@code null}
     */
    TodoWriteTool(TodoStore store, TodoScope scope) {
        this.store = store;
        this.scope = scope;
    }

    /**
     * 构造工具名片：名称、用途与参数 Schema。
     *
     * @return 工具描述符
     */
    static ToolDescriptor descriptor() {
        Map<String, Object> itemProperties = new LinkedHashMap<String, Object>();
        itemProperties.put("content", property("string", "待办内容，一句话说清要做什么"));
        Map<String, Object> status = property("string",
                "pending 表示还没轮到它，in_progress 表示此刻正在做的就是这一项，completed 表示已完成，"
                        + "blocked 表示认领过它的人试过、做不了（这时应在 reason 里写明为什么）");
        status.put("enum", wiredNames());
        itemProperties.put("status", status);
        Map<String, Object> reason = property("string",
                "只有 status 为 blocked 时才有意义：它为什么做不了。写具体一点，"
                        + "人和父回合要靠它决定下一步");
        itemProperties.put("reason", reason);

        Map<String, Object> itemSchema = new LinkedHashMap<String, Object>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", Arrays.asList("content", "status"));

        Map<String, Object> todosSchema = property("array",
                "完整的新待办列表。整表覆盖：上一次列过而这次没列出的项视为删除，"
                        + "不要只传变化的部分；用空数组清空整个列表。");
        todosSchema.put("items", itemSchema);

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("todos", todosSchema);

        return new ToolDescriptor(NAME,
                "创建或更新本会话的待办清单。开始一项需要多步的工作时，先把计划写成待办，"
                        + "并在推进过程中把当前在做的那一项标成 in_progress、做完的标成 completed，"
                        + "让用户能看到进度。内容要简短、可执行；同一时间只应有一项是 in_progress。",
                properties, Collections.singletonList("todos"));
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        // 协作键而不是 getSessionId()：子代理写的是它归属的那一份清单（见 TodoScope）。
        // 父回合自己调用时两者相同，因此这条规则对它没有任何行为变化。
        String sessionId = scope.collaborationKeyOf(request);
        if (sessionId == null) {
            throw new JellyfishException("todo_write 需要会话上下文，当前没有会话");
        }
        List<TodoItem> stored = store.replace(sessionId, parse(request.getArguments().get("todos")));
        // 摘要在轨迹行上回答「现在有几项待办、完成了几项」，与状态栏同一口径
        String status = TodoText.statusLine(stored);
        return new ToolCallResult(NAME, TodoText.confirmation(stored),
                Collections.<String, Object>singletonMap(ToolMetadata.KEY_SUMMARY,
                        status == null ? "待办已清空" : status));
    }

    /**
     * 解析并校验模型传来的待办列表。
     *
     * @param raw {@code todos} 参数原值，可为 {@code null}
     * @return 待办列表，保证非 {@code null}
     * @throws JellyfishException 参数缺失或类型非法时抛出
     */
    private static List<TodoItem> parse(Object raw) {
        if (!(raw instanceof Collection)) {
            throw new JellyfishException("todos 必须是数组，实际为 " + describe(raw));
        }
        List<TodoItem> items = new ArrayList<TodoItem>(((Collection<?>) raw).size());
        for (Object element : (Collection<?>) raw) {
            if (!(element instanceof Map)) {
                throw new JellyfishException("todos 的每一项都必须是对象，实际为 " + describe(element));
            }
            Map<?, ?> item = (Map<?, ?>) element;
            Object content = item.get("content");
            if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
                throw new JellyfishException("todos 每一项的 content 必须是非空字符串，实际为 " + describe(content));
            }
            items.add(new TodoItem((String) content, status(item.get("status")),
                    null, reason(item.get("reason"))));
        }
        return items;
    }

    /**
     * 解析状态取值。
     *
     * @param raw {@code status} 参数原值，可为 {@code null}（按未开始处理）
     * @return 待办状态，保证非 {@code null}
     * @throws JellyfishException 取值不在允许集合内时抛出
     */
    private static TodoStatus status(Object raw) {
        if (raw == null) {
            // 少写状态时按「未开始」处理：这是更容易改对的一侧，且不会把计划项当成已完成
            return TodoStatus.PENDING;
        }
        TodoStatus status = raw instanceof String ? TodoStatus.ofWireName((String) raw) : null;
        if (status == null) {
            throw new JellyfishException("todos 的 status 只能是 " + TodoStatus.allowedNames()
                    + "，实际为 " + describe(raw));
        }
        return status;
    }

    /**
     * 解析卡住原因。
     * <p>
     * 与状态取值不同，它<b>不做枚举校验</b>：只有卡住时才有意义，写在别的状态上只是冗余，
     * 为它报错会把一次本来正确的整表写入整批拒掉——那正是 state 那条路踩过的坑。
     * 空白按「没写」处理。
     *
     * @param raw {@code reason} 参数原值，可为 {@code null}
     * @return 原因文本；没写时返回 {@code null}
     */
    private static String reason(Object raw) {
        if (!(raw instanceof String)) {
            return null;
        }
        String text = ((String) raw).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 拼出允许的状态取值列表，供工具名片使用。
     *
     * @return 取值列表
     */
    private static List<String> wiredNames() {
        List<String> names = new ArrayList<String>(TodoStatus.values().length);
        for (TodoStatus status : TodoStatus.values()) {
            names.add(status.wireName());
        }
        return names;
    }

    /**
     * 构造一个 JSON Schema 属性定义。
     *
     * @param type        类型名
     * @param description 说明
     * @return 可继续补充字段的属性映射
     */
    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    /**
     * 生成便于排错的参数描述。
     * <p>
     * 字符串要打出<b>原文</b>而不是类名：模型最常犯的错是把 {@code status} 写成别的词，
     * 而「实际为 String」对它是零信息——那句话与「实际为 pending」长得一模一样，于是它只会原样重试
     * （实测如此：同一份 {@code in_progress} 参数被连续重试了两次）。长度与换行都做了限制，
     * 因为这条消息的首行会显示在轨迹行上。
     *
     * @param value 参数值，可为 {@code null}
     * @return 描述文本
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
