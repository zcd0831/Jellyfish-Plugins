package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PetStore} 的单元测试：按会话分桶、读路径不养新的、会话关闭丢弃、null 事件容错。
 *
 * @author zcd
 */
@DisplayName("宠物台账")
class PetStoreTest {

    /** 被测台账。 */
    private PetStore store;

    @BeforeEach
    void setUp() {
        store = new PetStore(System::currentTimeMillis, ZoneId.systemDefault(),
                2L * 3600L * 1000L, 1000000L, 23);
    }

    @Test
    @DisplayName("经历记在对应会话的宠物身上，别的会话不会因此多出一只")
    void events_should_affectOnlyThatSession() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));
        store.onLlmCallCompleted(new LlmCallCompletedEvent("s1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1000, 500, 0)));

        SessionPet mine = store.existing("s1");
        assertNotNull(mine);
        assertEquals(1000L, mine.totalTokens());
        assertNull(store.existing("s2"));
    }

    @Test
    @DisplayName("模型调用失败算经历，但既不记 token 也不留伤痕")
    void failedLlmCall_should_countAsActivityOnly() {
        store.onLlmCallFailed(new LlmCallFailedEvent("s1", "deepseek", "chat", 400, "rejected"));

        SessionPet pet = store.existing("s1");
        assertNotNull(pet);
        assertEquals(0L, pet.totalTokens());
        assertTrue(!pet.isWounded());
    }

    @Test
    @DisplayName("被打断会累积到警惕")
    void cancelled_should_accumulateToWary() {
        store.onTurnCancelled(new TurnCancelledEvent("s1", "t-1"));
        store.onTurnCancelled(new TurnCancelledEvent("s1", "t-2"));

        SessionPet pet = store.existing("s1");
        assertNotNull(pet);
        assertTrue(pet.isWary());
    }

    @Test
    @DisplayName("读路径不养新的宠物：渲染线程不许写共享状态")
    void existing_should_notCreatePet() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));

        assertNull(store.existing("never-seen"));
        assertNull(store.existing(null));
        assertNull(store.existing("never-seen"));
    }

    @Test
    @DisplayName("没有会话标识的事件一律忽略")
    void events_should_ignoreMissingSession() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, null));
        store.onLlmCallCompleted(new LlmCallCompletedEvent(null, null, null, null));
        store.onLlmCallFailed(new LlmCallFailedEvent(null, null, null, 0, null));
        store.onTurnCancelled(new TurnCancelledEvent(null, "t-1"));
        store.onSessionClosed(new SessionClosedEvent(null, null, 0));

        assertNull(store.existing(null));
    }

    @Test
    @DisplayName("会话关闭时丢掉它那一只：长进程里不能每开一个会话就多留一只")
    void sessionClosed_should_dropPet() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));
        assertNotNull(store.existing("s1"));

        store.onSessionClosed(new SessionClosedEvent("s1", "coder", 3));

        assertNull(store.existing("s1"));
    }

    @Test
    @DisplayName("停止时清空全部宠物")
    void clear_should_dropEverything() {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s1"));

        store.clear();

        assertNull(store.existing("s1"));
    }

    @Test
    @DisplayName("事件为 null 时无事发生，不抛错")
    void events_should_tolerateNull() {
        store.onLlmCallCompleted(null);
        store.onLlmCallFailed(null);
        store.onToolCallCompleted(null);
        store.onTurnCancelled(null);
        store.onSessionClosed(null);

        assertNull(store.existing("s1"));
    }
}
