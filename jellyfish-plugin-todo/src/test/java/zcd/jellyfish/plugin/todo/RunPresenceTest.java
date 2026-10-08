package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;
import zcd.jellyfish.api.subagent.DelegationStatus;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunPresence} 的单元测试：三种在场形态，以及「通知丢了必须自愈」。
 *
 * @author zcd
 */
@DisplayName("认领者在场记录")
class RunPresenceTest {

    /** 可推进的时钟。 */
    private final AtomicLong now = new AtomicLong(1_000L);

    @Test
    @DisplayName("开始通知：知道是谁、还在做")
    void started_shouldMarkRunning() {
        RunPresence presence = new RunPresence(now::get);

        presence.on(AgentRunProgressEvent.started("run-1", null, "root-1", "s-1", "researcher"));

        RunPresence.Presence state = presence.stateOf("run-1");
        assertTrue(state.isKnown());
        assertEquals("researcher", state.getAgentId());
        assertFalse(state.isFinished());
    }

    @Test
    @DisplayName("结束通知：知道是谁、但已经结束")
    void finished_shouldMarkFinished() {
        RunPresence presence = new RunPresence(now::get);
        presence.on(AgentRunProgressEvent.started("run-1", null, "root-1", "s-1", "researcher"));

        presence.on(AgentRunProgressEvent.finished("run-1", null, "root-1", "s-1", "researcher",
                DelegationStatus.COMPLETED, 3, 120L));

        assertTrue(presence.stateOf("run-1").isFinished());
    }

    @Test
    @DisplayName("从没听说过的 run：不知道，不虚构")
    void unknownRun_shouldBeUnknown() {
        RunPresence presence = new RunPresence(now::get);

        assertFalse(presence.stateOf("run-9").isKnown());
        assertFalse(presence.stateOf(null).isKnown());
    }

    @Test
    @DisplayName("只发了开始就没下文：过了有效期回落成「不知道」，而不是永远显示正在跑")
    void staleStarted_shouldFallBackToUnknown() {
        RunPresence presence = new RunPresence(now::get);
        presence.on(AgentRunProgressEvent.started("run-1", null, "root-1", "s-1", "researcher"));
        assertTrue(presence.stateOf("run-1").isKnown());

        now.addAndGet(RunPresence.STALE_MILLIS + 1L);

        // 丢失的结束事件不该变成永久显示的「正在跑」：宁可知不知道
        assertFalse(presence.stateOf("run-1").isKnown());
        // 顺手回收：再读一次仍然是不知道（条目已被清掉），不会来回摆动
        assertFalse(presence.stateOf("run-1").isKnown());
    }

    @Test
    @DisplayName("已经结束的条目不受有效期影响：它说的是历史事实")
    void finished_shouldNotExpire() {
        RunPresence presence = new RunPresence(now::get);
        presence.on(AgentRunProgressEvent.finished("run-1", null, "root-1", "s-1", "researcher",
                DelegationStatus.COMPLETED, 1, 10L));

        now.addAndGet(RunPresence.STALE_MILLIS * 10);

        assertTrue(presence.stateOf("run-1").isKnown());
        assertTrue(presence.stateOf("run-1").isFinished());
    }

    @Test
    @DisplayName("两条通知之间没有任何等待：同一条 run 开始后又结束，以最后一条为准")
    void startedThenFinished_shouldUseLatest() {
        RunPresence presence = new RunPresence(now::get);

        presence.on(AgentRunProgressEvent.started("run-1", null, "root-1", "s-1", "researcher"));
        presence.on(AgentRunProgressEvent.finished("run-1", null, "root-1", "s-1", "researcher",
                DelegationStatus.FAILED, 2, 30L));

        assertTrue(presence.stateOf("run-1").isFinished());
    }

    @Test
    @DisplayName("容量之内一条都不清：正常使用下语义一个字都不变")
    void on_should_keepEverything_whenUnderCapacity() {
        RunPresence presence = new RunPresence(now::get);

        for (int i = 0; i < RunPresence.MAX_ENTRIES; i++) {
            presence.on(finished("run-" + i));
        }

        // 一条都没少：容量上限不该在正常量级上生效
        assertEquals(RunPresence.MAX_ENTRIES, presence.size());
        assertTrue(presence.stateOf("run-0").isKnown());
    }

    @Test
    @DisplayName("超过容量时优先清「还在跑但已过期」的，新条目一条不动")
    void on_should_evictStaleRunningFirst_whenOverCapacity() {
        // Given：塞满一批「只发了开始就没下文」的条目
        RunPresence presence = new RunPresence(now::get);
        int stale = RunPresence.MAX_ENTRIES - 1;
        for (int i = 0; i < stale; i++) {
            presence.on(started("stale-" + i));
        }
        now.addAndGet(RunPresence.STALE_MILLIS + 1L);

        // When：再来两条新的，把总量顶到上限之上
        presence.on(started("fresh-1"));
        presence.on(started("fresh-2"));

        // Then：过期的那些被清掉（它们本来就是「不知道」），新的两条不受影响
        assertEquals(2, presence.size());
        assertTrue(presence.stateOf("fresh-1").isKnown());
        assertTrue(presence.stateOf("fresh-2").isKnown());
        assertFalse(presence.stateOf("stale-0").isKnown());
    }

    @Test
    @DisplayName("全是有效条目时按时间最旧淘汰，内存有确定上界")
    void on_should_evictOldest_whenEverythingIsFresh() {
        // Given：持续写入，每条都比上一条新一分钟
        RunPresence presence = new RunPresence(now::get);
        int writes = RunPresence.MAX_ENTRIES * 2;
        for (int i = 0; i < writes; i++) {
            presence.on(finished("run-" + i));
            now.addAndGet(60_000L);
        }

        // Then：不会无界增长（此前这张表只增不减），且最新那条一定还在
        assertTrue(presence.size() <= RunPresence.MAX_ENTRIES, "实际 " + presence.size());
        assertTrue(presence.stateOf("run-" + (writes - 1)).isKnown());
        assertFalse(presence.stateOf("run-0").isKnown(), "最旧的应当已经被淘汰");
    }

    @Test
    @DisplayName("淘汰是按时间来的，不是按插入顺序：中间写入的也可能留下")
    void on_should_evictByAge_notByInsertOrder() {
        // Given：先写一批「很旧」的，再写一批「很新」的
        RunPresence presence = new RunPresence(now::get);
        for (int i = 0; i < RunPresence.MAX_ENTRIES; i++) {
            presence.on(finished("old-" + i));
        }
        now.addAndGet(60 * 60 * 1000L);
        for (int i = 0; i < RunPresence.MAX_ENTRIES + 100; i++) {
            presence.on(finished("new-" + i));
            now.addAndGet(1000L);
        }

        // Then：留下的应当是新的那批
        assertTrue(presence.size() <= RunPresence.MAX_ENTRIES, "实际 " + presence.size());
        assertFalse(presence.stateOf("old-0").isKnown());
        assertTrue(presence.stateOf("new-" + (RunPresence.MAX_ENTRIES + 99)).isKnown());
    }

    /**
     * 造一条「已结束」通知。
     *
     * @param runId run 标识
     * @return 通知，保证非 {@code null}
     */
    private static AgentRunProgressEvent finished(String runId) {
        return AgentRunProgressEvent.finished(runId, null, "root-1", "s-1", "researcher",
                DelegationStatus.COMPLETED, 1, 10L);
    }

    /**
     * 造一条「已开始」通知。
     *
     * @param runId run 标识
     * @return 通知，保证非 {@code null}
     */
    private static AgentRunProgressEvent started(String runId) {
        return AgentRunProgressEvent.started(runId, null, "root-1", "s-1", "researcher");
    }
}
