package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoStore} 的单元测试：验证懒加载、整表覆盖、空表删文件与安全护栏。
 *
 * @author zcd
 */
@DisplayName("待办仓库")
class TodoStoreTest {

    /** 每个用例一个独立目录，避免相互污染。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
    }

    @Test
    @DisplayName("从没写过待办时返回空列表，不创建任何文件")
    void itemsOf_should_returnEmpty_when_fileAbsent() {
        assertTrue(store.itemsOf("s-1").isEmpty());
        assertFalse(Files.exists(store.fileOf("s-1")));
    }

    @Test
    @DisplayName("覆盖后应能读回，并把文件写到磁盘")
    void replace_should_writeFileAndBeReadable() {
        List<TodoItem> stored = store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.COMPLETED)));

        assertEquals(1, stored.size());
        assertTrue(Files.exists(store.fileOf("s-1")));
        assertEquals(1, store.itemsOf("s-1").size());
        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("另一个仓库实例应能从文件懒加载：这是重启后待办还在的依据")
    void itemsOf_should_loadFromFile_when_newStoreInstance() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.PENDING)));

        TodoStore reopened = new TodoStore(directory);

        assertEquals(1, reopened.itemsOf("s-1").size());
        assertEquals("写文档", reopened.itemsOf("s-1").get(0).content());
    }

    @Test
    @DisplayName("旧版本写的文件（只有 done 字段）也要能懒加载：升级不该把待办清空")
    void itemsOf_should_loadLegacyDoneField_when_newStoreInstance() throws IOException {
        Files.write(store.fileOf("s-1"),
                "[{\"content\":\"写文档\",\"done\":true}]".getBytes(StandardCharsets.UTF_8));

        TodoStore reopened = new TodoStore(directory);

        assertEquals(TodoStatus.COMPLETED, reopened.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("覆盖为空表应删除文件，不留空壳")
    void replace_should_deleteFile_when_empty() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", TodoStatus.PENDING)));

        List<TodoItem> stored = store.replace("s-1", Collections.<TodoItem>emptyList());

        assertTrue(stored.isEmpty());
        assertFalse(Files.exists(store.fileOf("s-1")));
        assertTrue(store.itemsOf("s-1").isEmpty());
    }

    @Test
    @DisplayName("会话之间互不影响")
    void itemsOf_should_isolatePerSession() {
        store.replace("s-1", Arrays.asList(new TodoItem("a", TodoStatus.PENDING)));
        store.replace("s-2", Arrays.asList(new TodoItem("b", TodoStatus.PENDING),
                new TodoItem("c", TodoStatus.COMPLETED)));

        assertEquals(1, store.itemsOf("s-1").size());
        assertEquals(2, store.itemsOf("s-2").size());
    }

    @Test
    @DisplayName("会话标识会拼进文件路径，含分隔符或上跳片段必须被拒绝")
    void itemsOf_should_rejectUnsafeSessionId() {
        assertThrows(JellyfishException.class, () -> store.itemsOf("../escape"));
        assertThrows(JellyfishException.class, () -> store.itemsOf("a/b"));
        assertThrows(JellyfishException.class, () -> store.itemsOf("   "));
        assertThrows(JellyfishException.class, () -> store.itemsOf(null));
    }

    @Test
    @DisplayName("删除会话待办应清掉缓存与文件；文件不存在时返回 false")
    void delete_should_clearCacheAndFile() {
        store.replace("s-1", Arrays.asList(new TodoItem("a", TodoStatus.PENDING)));

        assertTrue(store.delete("s-1"));
        assertFalse(Files.exists(store.fileOf("s-1")));
        assertTrue(store.itemsOf("s-1").isEmpty());
        assertFalse(store.delete("s-1"));
    }

    @Test
    @DisplayName("坏文件必须抛错而不是当作空：否则下一次写入就把原数据覆盖掉了")
    void itemsOf_should_fail_when_fileCorrupt() throws IOException {
        Files.write(store.fileOf("s-1"), "{不是数组".getBytes(StandardCharsets.UTF_8));

        assertThrows(JellyfishException.class, () -> store.itemsOf("s-1"));
    }
    @Test
    @DisplayName("认领：标成进行中并记下认领者")
    void claim_shouldMarkInProgressAndRecordOwner() {
        store.replace("s-1", items("甲", "乙"));

        TodoActionResult result = store.claim("s-1", "run-1");

        assertTrue(result.isOk());
        assertEquals("甲", result.getItem().content());
        assertEquals(TodoStatus.IN_PROGRESS, result.getItem().status());
        assertEquals("run-1", result.getItem().owner());
        // 落盘也要带上，否则下一个进程/父回合读回来就不知道谁在做
        assertEquals("run-1", store.itemsOf("s-1").get(0).owner());
    }

    @Test
    @DisplayName("一个 run 只认领一条：重复调用返回自己那一条，而不是再领一条")
    void claim_should_returnOwnItem_when_calledAgain() {
        store.replace("s-1", items("甲", "乙"));

        TodoItem first = store.claim("s-1", "run-1").getItem();
        TodoItem second = store.claim("s-1", "run-1").getItem();

        assertEquals(first.content(), second.content());
        assertEquals("乙", store.itemsOf("s-1").get(1).content());
        assertNull(store.itemsOf("s-1").get(1).owner(), "第二条不该被同一个 run 领走");
    }

    @Test
    @DisplayName("认领跳过别人已认领的与已完成的")
    void claim_should_skipTakenAndCompleted() {
        store.replace("s-1", Arrays.asList(
                new TodoItem("甲", TodoStatus.IN_PROGRESS, "run-9"),
                new TodoItem("乙", TodoStatus.COMPLETED),
                new TodoItem("丙", TodoStatus.PENDING)));

        TodoItem claimed = store.claim("s-1", "run-1").getItem();

        assertEquals("丙", claimed.content());
    }

    @Test
    @DisplayName("没有可认领的：明确回报，不等待也不重试")
    void claim_should_reportNonePending() {
        store.replace("s-1", items("甲"));

        assertTrue(store.claim("s-1", "run-1").isOk());
        TodoActionResult second = store.claim("s-1", "run-2");

        assertFalse(second.isOk());
        assertEquals(TodoActionResult.Code.NONE_PENDING, second.getCode());
        assertTrue(store.itemsOf("s-1").isEmpty() == false);
    }

    @Test
    @DisplayName("并发认领不丢更新：N 个 run 抢 M 条，恰好 M 次成功且各有其人")
    void claim_should_notLoseUpdates_when_concurrent() throws Exception {
        store.replace("s-1", items("甲", "乙", "丙", "丁"));
        List<String> claimed = Collections.synchronizedList(new ArrayList<String>());
        int workers = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<Thread>(workers);
        for (int i = 0; i < workers; i++) {
            final String runId = "run-" + i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                TodoActionResult result = store.claim("s-1", runId);
                if (result.isOk()) {
                    claimed.add(result.getItem().content());
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        // 四条待办恰好被领走四条，且没有两条落到同一个 run 头上
        assertEquals(4, claimed.size(), "认领次数应当恰好等于待办条数: " + claimed);
        assertEquals(4, new HashSet<String>(claimed).size(), "同一条被领了两次: " + claimed);
        Set<String> owners = new HashSet<String>();
        for (TodoItem item : store.itemsOf("s-1")) {
            assertNotNull(item.owner(), "每条都应当有认领者");
            owners.add(item.owner());
        }
        assertEquals(4, owners.size(), "出现了重复认领者: " + owners);
    }

    @Test
    @DisplayName("完成：认领者能完成自己那条，认领者保留在记录里")
    void complete_should_workForOwner() {
        store.replace("s-1", items("甲"));
        store.claim("s-1", "run-1");

        TodoActionResult result = store.complete("s-1", "甲", "run-1");

        assertTrue(result.isOk());
        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
        assertEquals("run-1", store.itemsOf("s-1").get(0).owner(), "谁做的要留下来");
    }

    @Test
    @DisplayName("完成：别人认领着的条目拒绝改动，并说清是哪一种失败")
    void complete_should_rejectItemOwnedByOther() {
        store.replace("s-1", items("甲"));
        store.claim("s-1", "run-1");

        TodoActionResult result = store.complete("s-1", "甲", "run-2");

        assertEquals(TodoActionResult.Code.TAKEN, result.getCode());
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status(), "不该被改");
    }

    @Test
    @DisplayName("完成：无主的条目谁都能标完成（父回合回收计划就是这种情形）")
    void complete_should_allowUnownedItem() {
        store.replace("s-1", items("甲"));

        assertTrue(store.complete("s-1", "甲", null).isOk());
        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("完成：内容不在清单里时明确回报，不改动任何东西")
    void complete_should_reportNotFound() {
        store.replace("s-1", items("甲"));

        TodoActionResult result = store.complete("s-1", "乙", "run-1");

        assertEquals(TodoActionResult.Code.NOT_FOUND, result.getCode());
        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("父回合重写清单不抹掉认领：内容没变就继承 owner，也不把进行中打回未开始")
    void replace_should_inheritClaims() {
        store.replace("s-1", items("甲", "乙"));
        store.claim("s-1", "run-1");

        List<TodoItem> rewritten = store.replace("s-1", items("甲", "乙", "丙"));

        assertEquals("run-1", rewritten.get(0).owner());
        assertEquals(TodoStatus.IN_PROGRESS, rewritten.get(0).status(), "正在做的事不该被放回池子");
        assertNull(rewritten.get(1).owner());
        assertEquals(TodoStatus.PENDING, rewritten.get(2).status());
        // 落盘也要一致：另一个进程读回来同样不该看见「无人认领」
        assertEquals("run-1", store.itemsOf("s-1").get(0).owner());
    }

    /**
     * 构造一份待办列表。
     *
     * @param contents 内容
     * @return 未开始的待办列表
     */
    private static List<TodoItem> items(String... contents) {
        List<TodoItem> items = new ArrayList<TodoItem>(contents.length);
        for (String content : contents) {
            items.add(new TodoItem(content, TodoStatus.PENDING));
        }
        return items;
    }
    @Test
    @DisplayName("做完再领：同一个 run 完成一条后可以接着领下一条")
    void claim_should_allowNextClaim_afterDone() {
        store.replace("s-1", items("甲", "乙"));

        TodoItem first = store.claim("s-1", "run-1").getItem();
        store.complete("s-1", first.content(), "run-1");
        TodoItem second = store.claim("s-1", "run-1").getItem();

        // 这条是「worker 连着干活」的全部机制：没有任何额外开关，只是一条不变量
        assertNotNull(second);
        assertEquals("乙", second.content());
        assertEquals("run-1", second.owner());
    }

    @Test
    @DisplayName("卡住：记下原因、保留认领者，且不再被任何 run 认领")
    void block_should_recordReasonAndStopClaims() {
        store.replace("s-1", items("甲", "乙"));
        store.claim("s-1", "run-1");

        TodoActionResult result = store.block("s-1", "甲", "run-1", "需要写权限，当前会话是只读");

        assertTrue(result.isOk());
        TodoItem blocked = store.itemsOf("s-1").get(0);
        assertEquals(TodoStatus.BLOCKED, blocked.status());
        assertEquals("需要写权限，当前会话是只读", blocked.reason());
        assertEquals("run-1", blocked.owner(), "谁卡的也是信息");
        // 下一个 run 领到的是另一条：卡住的活不会被反复领走反复失败
        assertEquals("乙", store.claim("s-1", "run-2").getItem().content());
    }

    @Test
    @DisplayName("卡住：原因是必填的，空白当场拒绝")
    void block_should_requireReason() {
        store.replace("s-1", items("甲"));

        assertThrows(JellyfishException.class, () -> store.block("s-1", "甲", null, "   "));
        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("放回：清掉认领者、回到未开始，随后别人可以认领")
    void release_shouldReturnToPool() {
        store.replace("s-1", items("甲"));
        store.claim("s-1", "run-1");

        TodoActionResult result = store.release("s-1", "甲", "run-1");

        assertTrue(result.isOk());
        TodoItem released = store.itemsOf("s-1").get(0);
        assertEquals(TodoStatus.PENDING, released.status());
        assertNull(released.owner());
        assertEquals("run-2", store.claim("s-1", "run-2").getItem().owner());
    }

    @Test
    @DisplayName("放回：已完成的不接受退回——那是悄悄撤销一件已经做完的事")
    void release_should_rejectCompleted() {
        store.replace("s-1", Arrays.asList(new TodoItem("甲", TodoStatus.COMPLETED)));

        TodoActionResult result = store.release("s-1", "甲", null);

        assertEquals(TodoActionResult.Code.WRONG_STATE, result.getCode());
        assertEquals(TodoStatus.COMPLETED, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("放回与卡住：别人认领着的条目一律拒绝")
    void releaseAndBlock_should_rejectItemOwnedByOther() {
        store.replace("s-1", items("甲"));
        store.claim("s-1", "run-1");

        assertEquals(TodoActionResult.Code.TAKEN, store.release("s-1", "甲", "run-2").getCode());
        assertEquals(TodoActionResult.Code.TAKEN, store.block("s-1", "甲", "run-2", "做不了").getCode());
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("完成：父回合不能替别人签字——「做完了」由做过的人说")
    void complete_should_rejectParentTurnOnClaimedItem() {
        store.replace("s-1", items("甲"));
        store.claim("s-1", "run-1");

        TodoActionResult result = store.complete("s-1", "甲", null);

        assertEquals(TodoActionResult.Code.TAKEN, result.getCode());
        assertEquals(TodoStatus.IN_PROGRESS, store.itemsOf("s-1").get(0).status());
    }

    @Test
    @DisplayName("放回与卡住：父回合（没有 run）可以收拾任何一条")
    void releaseAndBlock_should_allowParentTurn() {
        store.replace("s-1", items("甲", "乙"));
        store.claim("s-1", "run-1");

        assertTrue(store.block("s-1", "甲", null, "需求本身矛盾，等人确认").isOk());
        assertTrue(store.release("s-1", "乙", null).isOk());
        assertEquals(TodoStatus.BLOCKED, store.itemsOf("s-1").get(0).status());
        assertEquals(TodoStatus.PENDING, store.itemsOf("s-1").get(1).status());
    }

    @Test
    @DisplayName("父回合重写清单不丢掉卡住的原因：模型看不见的那部分状态按内容继承")
    void replace_should_inheritBlockReason() {
        store.replace("s-1", items("甲"));
        store.block("s-1", "甲", null, "缺权限");

        // 父回合重写时只抄了内容与状态（原因它没抄），原因应当被继承下来
        List<TodoItem> rewritten = store.replace("s-1", Arrays.asList(
                new TodoItem("甲", TodoStatus.BLOCKED)));

        assertEquals(TodoStatus.BLOCKED, rewritten.get(0).status());
        assertEquals("缺权限", rewritten.get(0).reason());
    }
}
