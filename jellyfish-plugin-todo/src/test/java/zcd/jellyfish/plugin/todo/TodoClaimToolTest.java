package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoClaimTool} 的单元测试：认领语义、拒绝的两种前置条件，以及**子代理写的是父会话那一份**。
 *
 * @author zcd
 */
@DisplayName("todo_claim 工具")
class TodoClaimToolTest {

    /** 每个用例一个独立目录，避免相互污染。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测工具。 */
    private TodoClaimTool tool;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        tool = new TodoClaimTool(store);
    }

    @Test
    @DisplayName("认领成功：回显内容、记下认领者、摘要里能看出还剩几条")
    void handle_should_claimAndReport() {
        store.replace("s-1", items("甲", "乙"));

        ToolCallResult result = tool.handle(child("child-1", "s-1", "run-1"));

        assertTrue(String.valueOf(result.getOutput()).contains("已认领：甲"), String.valueOf(result.getOutput()));
        assertTrue(String.valueOf(result.getOutput()).contains(TodoDoneTool.NAME),
                "要告诉子代理下一步用什么标记完成");
        assertEquals("run-1", store.itemsOf("s-1").get(0).owner());
        assertEquals("待办 0/2 · 进行中 1", String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)));
    }

    @Test
    @DisplayName("没有可认领的：返回一句话而不是抛异常——计划被领完是完全正常的结果")
    void handle_should_reportNothingPending() {
        store.replace("s-1", Collections.<TodoItem>emptyList());

        ToolCallResult result = tool.handle(child("child-1", "s-1", "run-1"));

        assertEquals("当前没有可认领的待办。", String.valueOf(result.getOutput()));
    }

    @Test
    @DisplayName("顶层回合不能认领：没有 run 名字可记，拒绝并说清原因")
    void handle_should_rejectWithoutRun() {
        store.replace("s-1", items("甲"));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(TodoClaimTool.NAME, Collections.<String, Object>emptyMap(),
                        "s-1")));

        assertTrue(error.getMessage().contains("只能由子代理调用"), error.getMessage());
        assertNull(store.itemsOf("s-1").get(0).owner(), "拒绝时不该留下认领");
    }

    @Test
    @DisplayName("没有会话上下文：拒绝")
    void handle_should_rejectWithoutSession() {
        assertThrows(JellyfishException.class, () -> tool.handle(
                new ToolCallRequest(TodoClaimTool.NAME, Collections.<String, Object>emptyMap())));
    }

    @Test
    @DisplayName("子代理认领的是**父会话**那一份：自己的会话里不留东西，也不产生文件")
    void handle_should_claimInParentList() {
        store.replace("parent-1", items("甲"));

        tool.handle(child("child-1", "parent-1", "run-1"));

        assertEquals("run-1", store.itemsOf("parent-1").get(0).owner(), "父回合要看得见谁在做");
        assertTrue(store.itemsOf("child-1").isEmpty(), "子代理不该另起一份");
        assertFalse(Files.exists(store.fileOf("child-1")), "更不该在盘上留一个孤儿文件");
    }

    @Test
    @DisplayName("工具名片不需要参数")
    void descriptor_should_requireNoArgument() {
        assertTrue(TodoClaimTool.descriptor().getDescription().contains("子代理"));
    }

    /**
     * 构造一份待办列表。
     *
     * @param contents 内容
     * @return 未开始的待办列表
     */
    private static List<TodoItem> items(String... contents) {
        java.util.List<TodoItem> items = new java.util.ArrayList<TodoItem>(contents.length);
        for (String content : contents) {
            items.add(new TodoItem(content, TodoStatus.PENDING));
        }
        return items;
    }

    /**
     * 构造一次「子代理调用」：自己的会话、父会话、以及它所属的 run。
     *
     * @param sessionId       子代理自己的会话
     * @param parentSessionId 父会话
     * @param runId           该 run 的标识
     * @return 工具调用请求
     */
    private static ToolCallRequest child(String sessionId, String parentSessionId, String runId) {
        return new ToolCallRequest(TodoClaimTool.NAME, Collections.<String, Object>emptyMap(), sessionId,
                null, null, parentSessionId, runId, "root-1");
    }
}
