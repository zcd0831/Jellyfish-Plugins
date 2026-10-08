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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoDoneTool} 的单元测试：完成语义、三种失败的区分，以及子代理改的是**父会话**那一份。
 *
 * @author zcd
 */
@DisplayName("todo_done 工具")
class TodoDoneToolTest {

    /** 每个用例一个独立目录，避免相互污染。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测工具。 */
    private TodoDoneTool tool;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        tool = new TodoDoneTool(store, TodoTestScope.self());
    }

    @Test
    @DisplayName("认领者能完成自己那条：状态落到文件里，摘要跟着更新")
    void handle_should_completeOwnItem() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));
        store.claim("s-1", "run-1");

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "甲"));

        assertEquals("已完成：甲", String.valueOf(result.getOutput()));
        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
        assertEquals("待办 1/1", String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)));
    }

    @Test
    @DisplayName("内容对不上：回显实际值并说明没有改动，模型据此能改对")
    void handle_should_reportNotFound() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "假"));

        assertTrue(String.valueOf(result.getOutput()).contains("没有内容为 \"假\" 的那一条"),
                String.valueOf(result.getOutput()));
        assertTrue(String.valueOf(result.getOutput()).contains("没有改动"),
                String.valueOf(result.getOutput()));
        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("别人认领着的条目：拒绝并说明，避免把「没改成」当成「已经完成」")
    void handle_should_reportTaken() {
        store.replace("s-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));
        store.claim("s-1", "run-9");

        ToolCallResult result = tool.handle(request("s-1", null, "run-1", "甲"));

        assertTrue(String.valueOf(result.getOutput()).contains("正被另一个子代理认领"),
                String.valueOf(result.getOutput()));
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("子代理完成的是它归属的那一份：父回合立刻看得见")
    void handle_should_completeInParentList() {
        store.replace("parent-1", Collections.singletonList(new TodoItem("甲", TodoStatus.PENDING)));
        store.claim("parent-1", "run-1");
        tool = new TodoDoneTool(store, TodoTestScope.nested("child-1", "parent-1"));

        tool.handle(request("child-1", "parent-1", "run-1", "甲"));

        assertEquals(TodoStatus.COMPLETED, store.itemsOf("parent-1").get(0).status());
        assertTrue(store.itemsOf("child-1").isEmpty());
    }

    @Test
    @DisplayName("content 不是非空字符串：当场拒绝并回显实际值")
    void handle_should_rejectBadContent() {
        JellyfishException missing = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(TodoDoneTool.NAME, Collections.<String, Object>emptyMap(),
                        "s-1")));
        assertTrue(missing.getMessage().contains("content 必须是非空字符串"), missing.getMessage());

        JellyfishException blank = assertThrows(JellyfishException.class,
                () -> tool.handle(request("s-1", null, "run-1", "   ")));
        assertTrue(blank.getMessage().contains("content 必须是非空字符串"), blank.getMessage());
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
                                           String content) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("content", content);
        return new ToolCallRequest(TodoDoneTool.NAME, arguments, sessionId, null, null,
                parentSessionId, runId, null);
    }
}
