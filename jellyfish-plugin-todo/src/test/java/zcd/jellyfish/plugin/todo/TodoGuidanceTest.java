package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.PromptPlacement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoGuidance} 的单元测试：选型规则讲清三种编排形态，且落位是 STATIC。
 *
 * @author zcd
 */
@DisplayName("待办选型提示词")
class TodoGuidanceTest {

    @Test
    @DisplayName("落位 STATIC：这段是编译期固定的选型规则，不是每轮都变的状态")
    void handle_should_useStaticPlacement() {
        PromptContribution contribution = new TodoGuidance().handle(new PromptContributionRequest("s-1"));

        assertEquals(PromptPlacement.STATIC, contribution.getPlacement());
        assertSame(TodoGuidance.TEXT, contribution.getText());
    }

    @Test
    @DisplayName("三种编排形态各自点名：task / workflow 的 spec / 写进待办让子代理认领")
    void text_should_nameEveryOrchestrationShape() {
        assertTrue(TodoGuidance.TEXT.contains("task"), TodoGuidance.TEXT);
        assertTrue(TodoGuidance.TEXT.contains("workflow 的 spec"), TodoGuidance.TEXT);
        assertTrue(TodoGuidance.TEXT.contains("todo_write"), TodoGuidance.TEXT);
        // 认领与完成的两个工具名要给出来：选型规则说不清「怎么用」，那是工具名片的活
        assertTrue(TodoGuidance.TEXT.contains("todo_claim"), TodoGuidance.TEXT);
        assertTrue(TodoGuidance.TEXT.contains("todo_done"), TodoGuidance.TEXT);
        assertTrue(TodoGuidance.TEXT.contains("todo_block"), TodoGuidance.TEXT);
    }

    @Test
    @DisplayName("不复述 schema：取值与参数在工具名片里，这里只说什么时候用")
    void text_should_notDuplicateToolSchema() {
        assertTrue(TodoGuidance.TEXT.indexOf("pending") < 0, "状态取值属于工具名片");
        assertTrue(TodoGuidance.TEXT.indexOf("json") < 0, TodoGuidance.TEXT);
    }
}
