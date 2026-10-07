package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TodoStatus} 的单元测试：读入归一化与对外取值是「模型写的词」与「界面画的标记」之间唯一的桥。
 *
 * @author zcd
 */
@DisplayName("待办状态")
class TodoStatusTest {

    @Test
    @DisplayName("三个线名都要解析到对应状态")
    void ofWireName_should_parseEachWireName() {
        assertEquals(TodoStatus.PENDING, TodoStatus.ofWireName("pending"));
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.ofWireName("in_progress"));
        assertEquals(TodoStatus.COMPLETED, TodoStatus.ofWireName("completed"));
    }

    @Test
    @DisplayName("大小写、前后空白与连字符都要认：模型打成 In-Progress 不该让整批写入失败")
    void ofWireName_should_normalizeText() {
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.ofWireName(" In-Progress "));
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.ofWireName("IN_PROGRESS"));
        assertEquals(TodoStatus.COMPLETED, TodoStatus.ofWireName("Completed"));
    }

    @Test
    @DisplayName("缺省按未开始：不给状态是最常见的一侧")
    void ofWireName_should_defaultToPending_when_null() {
        assertEquals(TodoStatus.PENDING, TodoStatus.ofWireName(null));
    }

    @Test
    @DisplayName("认不出来的取值返回 null，由调用点决定报错口径")
    void ofWireName_should_returnNull_when_unknown() {
        assertNull(TodoStatus.ofWireName("doing"));
        assertNull(TodoStatus.ofWireName(""));
        assertNull(TodoStatus.ofWireName("已完成"));
    }

    @Test
    @DisplayName("标记与线名一一对应：模型写 in_progress，界面就得画 [~]")
    void mark_should_matchEachState() {
        assertEquals("[ ] ", TodoStatus.PENDING.mark());
        assertEquals("[~] ", TodoStatus.IN_PROGRESS.mark());
        assertEquals("[x] ", TodoStatus.COMPLETED.mark());
        assertEquals("in_progress", TodoStatus.IN_PROGRESS.wireName());
    }

    @Test
    @DisplayName("允许取值集合从枚举自身拼出来，报错消息不会漏改")
    void allowedNames_should_listEveryWireName() {
        assertEquals("pending、in_progress、completed 或 blocked", TodoStatus.allowedNames());
    }
}
