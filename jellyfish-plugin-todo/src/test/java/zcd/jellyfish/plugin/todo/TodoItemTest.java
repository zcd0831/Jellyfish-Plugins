package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TodoItem} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办项")
class TodoItemTest {

    @Test
    @DisplayName("构造应保留内容与状态")
    void constructor_should_keepContentAndStatus() {
        TodoItem item = new TodoItem("写文档", TodoStatus.IN_PROGRESS);

        assertEquals("写文档", item.content());
        assertEquals(TodoStatus.IN_PROGRESS, item.status());
    }

    @Test
    @DisplayName("状态缺省按未开始：少写状态是更容易改对的一侧")
    void constructor_should_defaultToPending_when_statusAbsent() {
        assertEquals(TodoStatus.PENDING, new TodoItem("写文档", null).status());
    }

    @Test
    @DisplayName("内容为空白应被拒绝：它是渲染与注入的唯一依据")
    void constructor_should_rejectBlankContent() {
        assertThrows(JellyfishException.class, () -> new TodoItem("   ", TodoStatus.PENDING));
        assertThrows(JellyfishException.class, () -> new TodoItem(null, TodoStatus.PENDING));
    }

    @Test
    @DisplayName("反序列化构造器认旧字段 done：升级前的待办文件不能读空")
    void jsonCreator_should_readLegacyDoneField() {
        assertEquals(TodoStatus.COMPLETED, new TodoItem("写文档", null, true, null, null).status());
        assertEquals(TodoStatus.PENDING, new TodoItem("写文档", null, false, null, null).status());
    }

    @Test
    @DisplayName("两个字段都在时以 status 为准：它是当前格式")
    void jsonCreator_should_preferStatusOverLegacyDone() {
        assertEquals(TodoStatus.IN_PROGRESS, new TodoItem("写文档", "in_progress", false, null, null).status());
    }

    @Test
    @DisplayName("两个字段都缺时按未开始，而不是报错")
    void jsonCreator_should_defaultToPending_when_bothAbsent() {
        assertEquals(TodoStatus.PENDING, new TodoItem("写文档", null, null, null, null).status());
    }

    @Test
    @DisplayName("认领者随文件读写：它是「谁在做」的可持久事实")
    void owner_shouldSurviveJsonRoundTrip() {
        TodoItem claimed = new TodoItem("核对缓存策略", TodoStatus.IN_PROGRESS, "run-1");

        assertEquals("run-1", claimed.owner());
        assertTrue(claimed.ownedBy("run-1"));
        assertFalse(claimed.ownedBy("run-2"));
        assertFalse(claimed.ownedBy(null));
    }

    @Test
    @DisplayName("旧文件没有 owner 字段时读成未认领，而不是报错")
    void jsonCreator_should_defaultOwnerToNull() {
        TodoItem legacy = new TodoItem("写文档", "pending", null, null, null);

        assertNull(legacy.owner());
        assertFalse(legacy.ownedBy("run-1"));
    }

    @Test
    @DisplayName("状态取值写错要报错并说出实际值，不能静默退回旧字段")
    void jsonCreator_should_rejectUnknownStatus() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> new TodoItem("写文档", "doing", true, null, null));

        assertEquals("待办文件的 status 只能是 pending、in_progress、completed 或 blocked，实际为 \"doing\"",
                error.getMessage());
    }
}
