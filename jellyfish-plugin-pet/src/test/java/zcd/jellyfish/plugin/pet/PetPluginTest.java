package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PetPlugin} 的单元测试：面板与订阅的注册、事件让宠物变样、被打断也能被看见、
 * 会话关闭丢弃、停止后不再产出。
 * <p>
 * 用真实的 {@link PluginContextImpl}、真实注册表与真实事件通道（而不是 mock 上下文）：
 * 本类的职责正是「把订阅与面板交给上下文，并在事件到达时让宠物经历一件事」，
 * 用真实件才能顺带验证它们确实落到同一份注册表上、owner 归本插件，以及失效通知真的发得出去。
 * <p>
 * <b>断言轮询而不是假设某一刻的状态</b>：事件通道是异步的，把断言压在调度延迟上会得到随机红的测试。
 *
 * @author zcd
 */
@DisplayName("宠物插件装配")
class PetPluginTest {

    /** 轮询上限：异步通道正常在毫秒级派发完，3 秒只是防挂死的兜底。 */
    private static final long WAIT_MILLIS = 3000L;

    /** 轮询间隔。 */
    private static final long POLL_MILLIS = 20L;

    /** 共享注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 被测插件。 */
    private PetPlugin plugin;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        plugin = new PetPlugin();
        plugin.start(new PluginContextImpl(PluginDeclaration.of("jellyfish-pet",
                new LinkedHashMap<String, Object>()), extensions, events, Mockito.mock(SessionManager.class)));
    }

    @AfterEach
    void tearDown() {
        plugin.stop();
        events.close();
    }

    @Test
    @DisplayName("只为面板注册一处贡献：一张脸、两行身体、一行形态都在同一块面板里")
    void start_should_registerOnePanel() {
        assertEquals(1, extensions.bindings(PanelContributionRequest.class, null).size());
        assertEquals("jellyfish-pet",
                extensions.bindings(PanelContributionRequest.class, null).get(0).getOwner());
    }

    @Test
    @DisplayName("工具有了结果宠物才出现；在此之前不占区域")
    void panel_should_appearAfterFirstEvent() {
        assertTrue(panelContribution("s-1").isEmpty());

        events.publish(new ToolCallCompletedEvent("c1", "shell", true, 12L, null, "s-1"));

        assertTrue(waitForContent("s-1"), "面板应出现宠物");
        assertEquals(7, panelContribution("s-1").getLines().size());
    }

    @Test
    @DisplayName("被打断会让宠物变得警惕：这是「频繁打断回合」唯一的信号来源")
    void cancelled_should_makePetWary() {
        events.publish(new TurnCancelledEvent("s-1", "t-1"));
        assertTrue(waitForContent("s-1"), "第一次打断也应当先让宠物出现");

        events.publish(new TurnCancelledEvent("s-1", "t-2"));

        assertTrue(waitForPosture("s-1", Posture.WARY.label()), "两次打断后应转警惕");
    }

    @Test
    @DisplayName("连续失败会让宠物留下伤痕")
    void consecutiveFailures_should_wound() {
        for (int i = 0; i < SessionPet.WOUND_STREAK; i++) {
            events.publish(new ToolCallCompletedEvent("cf" + i, "shell", false, 12L, "exit 1", "s-1"));
        }

        assertTrue(waitForPosture("s-1", Posture.WOUNDED.label()), "连续失败后应留下伤痕");
    }

    @Test
    @DisplayName("模型调用让宠物变胖：累计 token 从用量事件来")
    void llmCalls_should_makePetObese() {
        events.publish(new LlmCallCompletedEvent("s-1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1000, 500, 0)));

        assertTrue(waitForContent("s-1"), "面板应出现宠物");
        assertTrue(panelContribution("s-1").getLines().get(5).text().endsWith("1.0k"));
    }

    @Test
    @DisplayName("宠物的变化会广播 UI 失效：外壳不每帧问插件")
    void petChange_should_publishUiInvalidatedEvent() throws Exception {
        CountDownLatch invalidated = new CountDownLatch(1);
        events.subscribe("pet-probe", UiInvalidatedEvent.class, event -> invalidated.countDown());

        events.publish(new ToolCallCompletedEvent("c1", "shell", true, 12L, null, "s-1"));

        assertTrue(invalidated.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "未收到 UI 失效事件");
    }

    @Test
    @DisplayName("会话关闭后它养出来的那只宠物消失")
    void sessionClosed_should_dropPet() {
        events.publish(new ToolCallCompletedEvent("c1", "shell", true, 12L, null, "s-1"));
        assertTrue(waitForContent("s-1"), "面板应出现宠物");

        events.publish(new SessionClosedEvent("s-1", "coder", 3));

        assertTrue(waitForEmpty("s-1"), "会话关闭后不该再留着那只宠物");
    }

    @Test
    @DisplayName("停止后清空宠物，且重复停止不抛错")
    void stop_should_clearAndBeIdempotent() {
        events.publish(new ToolCallCompletedEvent("c1", "shell", true, 12L, null, "s-1"));
        assertTrue(waitForContent("s-1"), "面板应出现宠物");

        plugin.stop();
        plugin.stop();

        assertTrue(panelContribution("s-1").isEmpty());
    }

    /**
     * 取某个会话当前的面板内容。
     *
     * @param sessionId 会话标识
     * @return 面板贡献，保证非 {@code null}
     */
    private PanelContribution panelContribution(String sessionId) {
        return extensions.invoke(panelHandler(), new PanelContributionRequest(sessionId));
    }

    /**
     * 轮询等待面板出现内容。
     *
     * @param sessionId 会话标识
     * @return 出现内容返回 {@code true}
     */
    private boolean waitForContent(String sessionId) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!panelContribution(sessionId).isEmpty()) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /**
     * 轮询等待面板变空。
     *
     * @param sessionId 会话标识
     * @return 变空返回 {@code true}
     */
    private boolean waitForEmpty(String sessionId) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (panelContribution(sessionId).isEmpty()) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /**
     * 轮询等待形态行出现某个形态名。
     *
     * @param sessionId 会话标识
     * @param label     形态名
     * @return 出现返回 {@code true}
     */
    private boolean waitForPosture(String sessionId, String label) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            PanelContribution contribution = panelContribution(sessionId);
            if (!contribution.isEmpty() && contribution.getLines().get(6).text().contains(label)) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /**
     * 短暂让出 CPU，供轮询使用。
     */
    private static void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 取注册进内核的面板处理器。
     *
     * @return 面板处理器，保证非 {@code null}
     */
    private ExtensionHandler<PanelContributionRequest, PanelContribution> panelHandler() {
        return extensions.bindings(PanelContributionRequest.class, null).get(0).getHandler();
    }
}
