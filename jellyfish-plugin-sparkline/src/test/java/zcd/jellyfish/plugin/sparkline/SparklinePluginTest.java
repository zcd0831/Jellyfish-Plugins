package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
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
 * {@link SparklinePlugin} 的单元测试：订阅与面板的注册、采样后广播 UI 失效、会话关闭清桶、
 * 停止后不再产出。
 * <p>
 * 用真实的 {@link PluginContextImpl}、真实注册表与真实事件通道（而不是 mock 上下文）：
 * 本类的职责正是「把订阅与面板交给上下文，并在事件到达时采样」，用真实件才能顺带验证
 * 它们确实落到同一份注册表上、owner 归本插件，以及失效通知真的发得出去。
 * <p>
 * <b>断言等的是「采样发生了」，不是「事件通道多快」</b>：通道是异步的，因此每条用例轮询面板
 * 直到出现/消失，而不是假设某一刻的状态——把断言压在调度延迟上会得到随机红的测试。
 *
 * @author zcd
 */
@DisplayName("火花线插件装配")
class SparklinePluginTest {

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
    private SparklinePlugin plugin;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        plugin = new SparklinePlugin();
        plugin.start(new PluginContextImpl(PluginDeclaration.of("jellyfish-sparkline",
                new LinkedHashMap<String, Object>()), extensions, events, Mockito.mock(SessionManager.class)));
    }

    @AfterEach
    void tearDown() {
        plugin.stop();
        events.close();
    }

    @Test
    @DisplayName("只为面板注册一处贡献：三条线共用一块面板")
    void start_should_registerOnePanel() {
        assertEquals(1, extensions.bindings(PanelContributionRequest.class, null).size());
        assertEquals("jellyfish-sparkline",
                extensions.bindings(PanelContributionRequest.class, null).get(0).getOwner());
    }

    @Test
    @DisplayName("采样后广播 UI 失效：外壳不每帧问插件，必须有人告诉它内容脏了")
    void sampling_should_publishUiInvalidatedEvent() throws Exception {
        CountDownLatch invalidated = new CountDownLatch(1);
        events.subscribe("spark-probe", UiInvalidatedEvent.class, event -> invalidated.countDown());

        events.publish(new ToolCallCompletedEvent("c1", "shell", true, 12L, null, "s-1"));

        assertTrue(invalidated.await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "未收到 UI 失效事件");
    }

    @Test
    @DisplayName("模型调用事件到达后面板就有趋势可画")
    void panel_should_showTrendAfterCall() {
        assertTrue(panelContribution("s-1").isEmpty());

        events.publish(new LlmCallCompletedEvent("s-1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1010, 500, 0)));

        assertTrue(waitForContent("s-1"), "面板应出现趋势");
        assertEquals(3, panelContribution("s-1").getLines().size());
    }

    @Test
    @DisplayName("失败的模型调用也会长出一条趋势（只有失败率线有数据）")
    void panel_should_appearOnFailedCallToo() {
        events.publish(new LlmCallFailedEvent("s-1", "deepseek", "chat", 400, "rejected"));

        assertTrue(waitForContent("s-1"), "一次被拒的调用也应当让人看见");
    }

    @Test
    @DisplayName("会话关闭后该会话的趋势消失，再问就是空贡献")
    void sessionClosed_should_dropTrend() {
        events.publish(new LlmCallCompletedEvent("s-1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1010, 500, 0)));
        assertTrue(waitForContent("s-1"), "面板应出现趋势");

        events.publish(new SessionClosedEvent("s-1", "coder", 3));

        assertTrue(waitForEmpty("s-1"), "会话关闭后不应再留着那一桶采样点");
    }

    @Test
    @DisplayName("停止后清空采样点，且重复停止不抛错")
    void stop_should_clearAndBeIdempotent() {
        events.publish(new LlmCallCompletedEvent("s-1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1010, 500, 0)));
        assertTrue(waitForContent("s-1"), "面板应出现趋势");

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
