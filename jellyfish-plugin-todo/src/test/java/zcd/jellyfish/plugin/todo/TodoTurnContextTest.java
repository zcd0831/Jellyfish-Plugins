package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoTurnContext} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办回合上下文")
class TodoTurnContextTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测处理器。 */
    private TodoTurnContext turnContext;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        turnContext = new TodoTurnContext(store);
    }

    @Test
    @DisplayName("有待办时渲染出与内核原先一致的待办块")
    void handle_should_renderBlock() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.PENDING),
                new TodoItem("跑测试", TodoStatus.COMPLETED)));

        TurnContext result = turnContext.handle(new TurnContextRequest("s-1", "继续", false));

        assertTrue(!result.isEmpty());
        assertEquals("[待办]\n- [ ] 写文档\n- [x] 跑测试\n- 提示：还有 1 条没人做，可以派子代理用 todo_claim 认领它们。", result.getText());
    }

    @Test
    @DisplayName("没有待办时返回空结果：本处理器每轮都会被问到")
    void handle_should_returnEmpty_when_noTodos() {
        TurnContext result = turnContext.handle(new TurnContextRequest("s-1", "继续", false));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("待办全部完成时返回空结果：此后每一轮都重申一段没有信息量的话，且会落进历史")
    void handle_should_returnEmpty_when_allCompleted() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.COMPLETED),
                new TodoItem("跑测试", TodoStatus.COMPLETED)));

        TurnContext result = turnContext.handle(new TurnContextRequest("s-1", "继续", false));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("还剩一件未完成就照常送达：省 token 不能省到「模型看不见自己的计划」")
    void handle_should_renderBlock_when_anyOpen() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.COMPLETED),
                new TodoItem("跑测试", TodoStatus.PENDING)));

        TurnContext result = turnContext.handle(new TurnContextRequest("s-1", "继续", false));

        assertEquals("[待办]\n- [x] 写文档\n- [ ] 跑测试\n- 提示：还有 1 条没人做，可以派子代理用 todo_claim 认领它们。", result.getText());
    }

    @Test
    @DisplayName("没有会话上下文时返回空结果，不抛异常打扰对话")
    void handle_should_returnEmpty_when_noSession() {
        assertTrue(turnContext.handle(new TurnContextRequest(null, "继续", false)).isEmpty());
    }

    @Test
    @DisplayName("嵌套回合同样送达：子代理也要看得见自己的计划")
    void handle_should_renderBlock_forNestedTurn() {
        store.replace("s-1", Arrays.asList(new TodoItem("查资料", TodoStatus.IN_PROGRESS)));

        TurnContext result = turnContext.handle(new TurnContextRequest("s-1", "去查一下", true));

        assertTrue(result.getText().contains("查资料"));
    }
}
