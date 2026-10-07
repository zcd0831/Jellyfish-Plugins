package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoJson} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办 JSON 读写")
class TodoJsonTest {

    @Test
    @DisplayName("往返后每项内容与状态都要对得上，三态一个不少")
    void read_should_restoreEveryField() {
        List<TodoItem> items = Arrays.asList(new TodoItem("写文档", TodoStatus.PENDING),
                new TodoItem("跑测试", TodoStatus.IN_PROGRESS),
                new TodoItem("提交", TodoStatus.COMPLETED));

        List<TodoItem> restored = TodoJson.read(TodoJson.write(items), "test");

        assertEquals(3, restored.size());
        assertEquals("写文档", restored.get(0).content());
        assertEquals(TodoStatus.PENDING, restored.get(0).status());
        assertEquals(TodoStatus.IN_PROGRESS, restored.get(1).status());
        assertEquals(TodoStatus.COMPLETED, restored.get(2).status());
    }

    @Test
    @DisplayName("落盘字段是新格式：写 status 的线名，且不再写旧的 done")
    void write_should_useStatusWireName() {
        String json = TodoJson.write(Collections.singletonList(new TodoItem("写文档", TodoStatus.IN_PROGRESS)));

        assertTrue(json.contains("\"status\" : \"in_progress\""), json);
        assertFalse(json.contains("done"), json);
    }

    @Test
    @DisplayName("旧文件里的 done 仍要读得出来：升级不该把用户的待办清空")
    void read_should_acceptLegacyDoneField() {
        List<TodoItem> restored = TodoJson.read(
                "[{\"content\":\"写文档\",\"done\":true},{\"content\":\"跑测试\",\"done\":false}]", "test");

        assertEquals(TodoStatus.COMPLETED, restored.get(0).status());
        assertEquals(TodoStatus.PENDING, restored.get(1).status());
    }

    @Test
    @DisplayName("空列表也要能往返")
    void read_should_roundTrip_when_empty() {
        assertTrue(TodoJson.read(TodoJson.write(Collections.<TodoItem>emptyList()), "test").isEmpty());
    }

    @Test
    @DisplayName("多出来的未知字段必须被忽略：旧版本读完新版本写的文件不该整段丢掉")
    void read_should_ignoreUnknownFields() {
        List<TodoItem> restored = TodoJson.read(
                "[{\"content\":\"写文档\",\"status\":\"completed\",\"owner\":\"x\"}]", "test");

        assertEquals(1, restored.size());
        assertEquals(TodoStatus.COMPLETED, restored.get(0).status());
    }

    @Test
    @DisplayName("非法 JSON 应抛错而不是静默当作空列表")
    void read_should_fail_when_jsonInvalid() {
        assertThrows(JellyfishException.class, () -> TodoJson.read("{不是数组", "test"));
    }

    @Test
    @DisplayName("缺少 content 的项应被拒绝")
    void read_should_rejectMissingContent() {
        assertThrows(JellyfishException.class, () -> TodoJson.read("[{\"status\":\"pending\"}]", "test"));
    }

    @Test
    @DisplayName("状态取值无法识别的文件应报错：宁可让人看到，也不猜成某一态")
    void read_should_rejectUnknownStatus() {
        assertThrows(JellyfishException.class,
                () -> TodoJson.read("[{\"content\":\"写文档\",\"status\":\"doing\"}]", "test"));
    }
}
