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
}
