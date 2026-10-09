package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoWriteTool} 的单元测试：重点是「模型传坏参数时必须当场报错，而不是静默落盘」，
 * 以及三态取值（尤其是模型习惯用的 {@code in_progress}）必须被如实接住。
 *
 * @author zcd
 */
@DisplayName("todo_write 工具")
class TodoWriteToolTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测工具。 */
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        tool = new TodoWriteTool(store, TodoTestScope.self());
    }

    @Test
    @DisplayName("名片要能被模型读懂：名称、必填项与参数 Schema 缺一不可")
    void descriptor_should_describeToolAndRequiredArguments() {
        ToolDescriptor descriptor = TodoWriteTool.descriptor();

        assertEquals(TodoWriteTool.NAME, descriptor.getName());
        assertNotNull(descriptor.getDescription());
        assertEquals(Collections.singletonList("todos"), descriptor.getRequired());
        assertTrue(descriptor.getParameters().containsKey("todos"));
    }

    @Test
    @DisplayName("名片里的状态 enum 必须带上三态：模型写 in_progress 时它得先知道自己可以用")
    void descriptor_should_declareEveryStatus() {
        ToolDescriptor descriptor = TodoWriteTool.descriptor();

        assertEquals(Arrays.asList("pending", "in_progress", "completed", "blocked"), statusEnum(descriptor));
    }

    @Test
    @DisplayName("整表覆盖：新列表落盘并回显清单")
    void handle_should_replaceWholeList() {
        store.replace("s-1", Arrays.asList(new TodoItem("旧任务", TodoStatus.PENDING)));

        ToolCallResult result = tool.handle(request("s-1", item("写文档", "pending"), item("跑测试", "completed")));

        assertEquals(TodoWriteTool.NAME, result.getToolName());
        assertTrue(result.getOutput().toString().contains("[ ] 1. 写文档"));
        assertTrue(result.getOutput().toString().contains("[x] 2. 跑测试"));
        assertEquals(2, store.itemsOf("s-1").size());
        assertEquals("写文档", store.itemsOf("s-1").get(0).content());
    }

    @Test
    @DisplayName("in_progress 是合法状态：它曾让整批写入失败，被迫改写成 pending 或谎报 completed")
    void handle_should_acceptInProgress() {
        ToolCallResult result = tool.handle(request("s-1",
                item("写文档", "completed"), item("跑测试", "in_progress")));

        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(1).status());
        assertTrue(result.getOutput().toString().contains("[~] 2. 跑测试"));
    }

    @Test
    @DisplayName("空数组表示清空")
    void handle_should_clear_when_emptyArray() {
        store.replace("s-1", Arrays.asList(new TodoItem("旧任务", TodoStatus.PENDING)));

        ToolCallResult result = tool.handle(request("s-1"));

        assertTrue(result.getOutput().toString().contains("清空"));
        assertTrue(store.itemsOf("s-1").isEmpty());
    }

    @Test
    @DisplayName("摘要在轨迹行上回答「现在有几项、完成几项」，与状态栏同一口径")
    void handle_should_summarizeProgress() {
        ToolCallResult result = tool.handle(request("s-1", item("写文档", "pending"), item("跑测试", "completed")));

        assertEquals("待办 1/2", ToolMetadata.summaryOf(result.getMetadata()));
    }

    @Test
    @DisplayName("有进行中项时摘要也要说出来，轨迹行上看得到「正在做哪一件」")
    void handle_should_summarizeInProgress() {
        ToolCallResult result = tool.handle(request("s-1", item("写文档", "in_progress"), item("跑测试", "pending")));

        assertEquals("待办 0/2 · 进行中 1", ToolMetadata.summaryOf(result.getMetadata()));
    }

    @Test
    @DisplayName("清空后摘要说明已清空，而不是残留上一次的进度")
    void handle_should_summarizeClear() {
        tool.handle(request("s-1", item("写文档", "pending")));

        ToolCallResult result = tool.handle(request("s-1"));

        assertEquals("待办已清空", ToolMetadata.summaryOf(result.getMetadata()));
    }

    @Test
    @DisplayName("缺少 status 按未开始处理：更容易改对的一侧")
    void handle_should_treatMissingStatusAsPending() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("content", "写文档");

        tool.handle(request("s-1", item));

        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("状态打成 In-Progress 也认：一次格式差异不该让整批待办白写")
    void handle_should_normalizeStatusText() {
        tool.handle(request("s-1", item("写文档", " In-Progress ")));

        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("todos 不是数组应报错")
    void handle_should_rejectNonArray() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("todos", "写文档");

        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                arguments, "s-1")));
    }

    @Test
    @DisplayName("todos 缺失应报错")
    void handle_should_rejectMissingTodos() {
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                new LinkedHashMap<String, Object>(), "s-1")));
    }

    @Test
    @DisplayName("列表项不是对象应报错")
    void handle_should_rejectNonObjectItem() {
        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                arguments(Arrays.<Object>asList("写文档")), "s-1")));
    }

    @Test
    @DisplayName("content 为空白或缺失应报错")
    void handle_should_rejectBlankContent() {
        Map<String, Object> blank = new LinkedHashMap<String, Object>();
        blank.put("content", "   ");
        blank.put("status", "pending");

        assertThrows(JellyfishException.class, () -> tool.handle(request("s-1", blank)));
        assertThrows(JellyfishException.class, () -> tool.handle(request("s-1", item(null, "pending"))));
    }

    @Test
    @DisplayName("status 取值非法应报错并提示允许值")
    void handle_should_rejectUnknownStatus() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", item("写文档", "doing"))));

        assertTrue(error.getMessage().contains("pending"));
        assertTrue(error.getMessage().contains("in_progress"));
        assertTrue(error.getMessage().contains("completed"));
    }

    @Test
    @DisplayName("报错必须带上模型实际传的值：只说「实际为 String」它改不动，只会原样重试")
    void handle_should_reportRejectedStatusValue() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", item("写文档", "doing"))));

        assertEquals("todos 的 status 只能是 pending、in_progress、completed 或 blocked，实际为 \"doing\"",
                error.getMessage());
    }

    @Test
    @DisplayName("status 不是字符串时打出类型，且消息保持单行")
    void handle_should_reportRejectedStatusType() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", item("写文档", 5))));

        assertTrue(error.getMessage().endsWith("实际为 Integer"), error.getMessage());
    }

    @Test
    @DisplayName("没有会话上下文时不写任何文件")
    void handle_should_rejectMissingSessionId() {
        Map<String, Object> arguments = arguments(Arrays.<Object>asList(item("写文档", "pending")));

        assertThrows(JellyfishException.class, () -> tool.handle(new ToolCallRequest(TodoWriteTool.NAME, arguments)));
    }

    @Test
    @DisplayName("子代理写的是它归属的那份清单：父回合的文件被改，而不是多出一个只属于那个 run 的文件")
    void handle_should_writeOwnerList_when_subAgent() {
        // Given：子代理的临时会话归用户会话（归属由内核算，这里显式给出）
        tool = new TodoWriteTool(store, TodoTestScope.of(Collections.singletonMap("child", "root")));

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest(TodoWriteTool.NAME,
                arguments(Arrays.<Object>asList(item("写文档", "pending"))), "child", null, null,
                "root", "run-1", "root-run-1"));

        // Then：写进父回合那份，子代理自己的会话里什么都不该留下
        assertEquals(1, store.itemsOf("root").size());
        assertTrue(store.itemsOf("child").isEmpty(), "不该给子代理会话留下孤儿清单");
        assertTrue(result.getOutput().toString().contains("写文档"), result.getOutput().toString());
    }

    /**
     * 构造一次工具调用请求。
     *
     * @param sessionId 会话标识
     * @param items     待办项参数
     * @return 工具调用请求
     */
    @SafeVarargs
    private static ToolCallRequest request(String sessionId, Map<String, Object>... items) {
        return new ToolCallRequest(TodoWriteTool.NAME, arguments(Arrays.<Object>asList(items)), sessionId);
    }

    @Test
    @DisplayName("状态不是 blocked 时写下的 reason 应被丢掉：它会变成永久黏性标记")
    void handle_should_dropReason_when_statusIsNotBlocked() {
        Map<String, Object> item = item("甲", "pending");
        item.put("reason", "顺手写的");

        tool.handle(request("s-1", item));

        TodoItem stored = store.itemsOf("s-1").get(0);
        assertEquals(TodoStatus.PENDING, stored.status());
        assertEquals(null, stored.reason());
        // 而「有认领者」那条黏性判据不受影响：pending 本来就不许带认领者
        assertEquals(null, stored.owner());
    }

    @Test
    @DisplayName("blocked 上的 reason 照常保留：那是它唯一的载体")
    void handle_should_keepReason_when_statusIsBlocked() {
        Map<String, Object> item = item("甲", "blocked");
        item.put("reason", "缺权限");

        tool.handle(request("s-1", item));

        assertEquals("缺权限", store.itemsOf("s-1").get(0).reason());
    }

    /**
     * 构造工具参数映射。
     *
     * @param todos 待办项列表
     * @return 参数映射
     */
    private static Map<String, Object> arguments(List<Object> todos) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("todos", new ArrayList<Object>(todos));
        return arguments;
    }

    /**
     * 构造一条待办参数。
     *
     * @param content 内容，可为 {@code null}
     * @param status  状态，可为 {@code null}
     * @return 待办参数
     */
    private static Map<String, Object> item(String content, Object status) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("content", content);
        item.put("status", status);
        return item;
    }

    /**
     * 从工具名片里取出 {@code todos[].status} 的 enum 取值列表。
     *
     * @param descriptor 工具名片
     * @return enum 取值列表
     */
    @SuppressWarnings("unchecked")
    private static List<String> statusEnum(ToolDescriptor descriptor) {
        Map<String, Object> todos = (Map<String, Object>) descriptor.getParameters().get("todos");
        Map<String, Object> itemSchema = (Map<String, Object>) todos.get("items");
        Map<String, Object> properties = (Map<String, Object>) itemSchema.get("properties");
        Map<String, Object> status = (Map<String, Object>) properties.get("status");
        return (List<String>) status.get("enum");
    }
}
