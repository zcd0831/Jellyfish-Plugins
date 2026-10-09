package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoBlockTool} 的单元测试：完成语义、三种失败的区分，以及子代理改的是**父会话**那一份。
 *
 * @author zcd
 */
@DisplayName("todo_block 工具")
class TodoBlockToolTest {

    /** 每个用例一个独立目录，避免相互污染。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测工具。 */
    private TodoBlockTool tool;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        tool = new TodoBlockTool(store, TodoTestScope.self());
    }

    @Test
    @DisplayName("卡住：原因落到文件里、条目不再可认领、回灌文本把原因说回去")
    void handle_should_blockWithReason() {
        store.replace("s-1", Arrays.asList(new TodoItem("甲", TodoStatus.PENDING),
                new TodoItem("乙", TodoStatus.PENDING)));
        store.claim("s-1", "run-1");

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "甲", "需要写权限"));

        String text = String.valueOf(result.getOutput());
        assertTrue(text.contains("已记下卡住：甲"), text);
        assertTrue(text.contains("原因：需要写权限"), text);
        assertEquals(TodoStatus.BLOCKED, store.itemsOf("s-1").get(0).status());
        assertEquals("需要写权限", store.itemsOf("s-1").get(0).reason());
        // 卡住的活不会被反复领走：下一个 run 领到的是另一条
        assertEquals("乙", store.claim("s-1", "run-2").getItem().content());
    }

    @Test
    @DisplayName("原因必填：缺失或空白都当场拒绝并回显实际值")
    void handle_should_requireReason() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));

        JellyfishException missing = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(TodoBlockTool.NAME, arguments("甲", null), "s-1")));
        assertTrue(missing.getMessage().contains("参数必须是非空字符串"), missing.getMessage());

        JellyfishException blank = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", null, "run-1", "甲", "  ")));
        assertTrue(blank.getMessage().contains("参数必须是非空字符串"), blank.getMessage());
    }

    @Test
    @DisplayName("内容对不上：回显实际值并说明没有改动，模型据此能改对")
    void handle_should_reportNotFound() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "假", "原因"));

        assertTrue(String.valueOf(result.getOutput()).contains("没有内容为 \"假\" 的那一条"),
                String.valueOf(result.getOutput()));
        assertTrue(String.valueOf(result.getOutput()).contains("没有改动"),
                String.valueOf(result.getOutput()));
        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("别人认领着的条目：拒绝并说明")
    void handle_should_reportTaken() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));
        store.claim("s-1", "run-9");

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "甲", "做不了"));

        assertTrue(String.valueOf(result.getOutput()).contains("正被另一个子代理认领"),
                String.valueOf(result.getOutput()));
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("子代理卡住的是它归属的那一份：父回合立刻看得见")
    void handle_should_blockInParentList() {
        store.replace("parent-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));
        store.claim("parent-1", "run-1");
        tool = new TodoBlockTool(store, TodoTestScope.nested("child-1", "parent-1"));

        tool.handle(request("child-1", "parent-1", "run-1", "甲", "环境不通"));

        assertEquals(TodoStatus.BLOCKED, store.itemsOf("parent-1").get(0).status());
        assertEquals("环境不通", store.itemsOf("parent-1").get(0).reason());
        assertTrue(store.itemsOf("child-1").isEmpty());
    }

    @Test
    @DisplayName("没有会话时要说清是哪个工具需要会话上下文")
    void handle_should_nameItsOwnTool_when_noSession() {
        // 这里曾经写成「todo_done 需要会话上下文」：文案指向另一个工具，排查时会被带偏
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> tool.handle(request(null, null, null, "甲", "环境不通")));

        assertTrue(failure.getMessage().contains("todo_block"), failure.getMessage());
    }

    @Test
    @DisplayName("content 缺失或空白：当场拒绝并回显实际值")
    void handle_should_rejectBadContent() {
        JellyfishException missing = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(TodoBlockTool.NAME,
                        Collections.<String, Object>emptyMap(), "s-1")));
        assertTrue(missing.getMessage().contains("参数必须是非空字符串"), missing.getMessage());

        JellyfishException blank = assertThrows(JellyfishException.class,
                () -> tool.handle(request("child-1", "s-1", "run-1", "   ", "原因")));
        assertTrue(blank.getMessage().contains("参数必须是非空字符串"), blank.getMessage());
    }

    /**
     * 构造一次工具调用。
     *
     * @param sessionId       调用方的会话
     * @param parentSessionId 父会话，可为 {@code null}
     * @param runId           调用方所在 run，可为 {@code null}
     * @param content         {@code content} 参数
     * @return 工具调用请求
     */
    private static ToolCallRequest request(String sessionId, String parentSessionId, String runId,
                                           String content, String reason) {
        Map<String, Object> arguments = arguments(content, reason);
        return new ToolCallRequest(TodoBlockTool.NAME, arguments, sessionId, null, null,
                parentSessionId, runId, null);
    }

    /**
     * 组装参数。
     *
     * @param content 待办内容
     * @param reason  卡住原因，可为 {@code null}（表示不传）
     * @return 参数映射，保证非 {@code null}
     */
    private static Map<String, Object> arguments(String content, String reason) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", content);
        if (reason != null) {
            arguments.put("reason", reason);
        }
        return arguments;
    }
}
