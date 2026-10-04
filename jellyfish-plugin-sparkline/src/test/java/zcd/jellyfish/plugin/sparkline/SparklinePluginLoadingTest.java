package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pf4j.PluginState;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 火花线插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 面板可路由、事件能采到样」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这个插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。这里刻意<b>复用 {@code src/main/resources} 里那份描述符</b>，
 * 而不是在测试里另写一份，否则测的就不是要发出去的那个插件了。
 *
 * @author zcd
 */
@DisplayName("火花线插件加载链路")
class SparklinePluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-sparkline";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，面板贡献的注册落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel eventChannel;

    /** 被测插件管理器。 */
    private PF4JPluginManager manager;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        eventChannel.close();
    }

    @Test
    @DisplayName("真实描述符应让插件启动成功")
    void bootstrap_should_startPlugin_when_descriptorIsValid() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后事件订阅有效、面板可路由")
    void bootstrap_should_registerPanelAndSubscriptions() throws IOException {
        installPlugin();
        manager = newManager();
        manager.bootstrap();

        assertEquals(1, extensions.handlers(PanelContributionRequest.class, null).size());

        // 真正推一条内核事件，验证「订阅确实接通了」——只断言注册数证明不了这一点
        eventChannel.publish(new LlmCallCompletedEvent("s-1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1010, 500, 0)));
        assertTrue(waitForContent(), "事件到达后应能采到样");
    }

    @Test
    @DisplayName("插件卸载后面板注册应被按 owner 回收")
    void close_should_unregisterPanel() throws IOException {
        installPlugin();
        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(PanelContributionRequest.class, null).isEmpty());
    }

    /**
     * 轮询等面板出现内容。
     *
     * @return 出现内容返回 {@code true}
     */
    private boolean waitForContent() {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (!extensions.invoke(extensions.bindings(PanelContributionRequest.class, null).get(0).getHandler(),
                    new PanelContributionRequest("s-1")).isEmpty()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 写入失败时抛出
     */
    private void installPlugin() throws IOException {
        Path pluginDir = pluginsRoot.resolve(PLUGIN_ID);
        Files.createDirectories(pluginDir);
        try (InputStream descriptor = getClass().getResourceAsStream("/plugin.properties")) {
            if (descriptor == null) {
                throw new IllegalStateException("测试类路径上找不到 plugin.properties");
            }
            Files.copy(descriptor, pluginDir.resolve("plugin.properties"));
        }
    }

    /**
     * 构造扫描临时目录的插件管理器。
     *
     * @return 插件管理器，保证非 {@code null}
     */
    private PF4JPluginManager newManager() {
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class),
                new ShellIngress(new MetricsRegistry()));
        return new PF4JPluginManager(contexts, PluginRuntimeConfig.ofRoots(pluginsRoot), eventChannel);
    }
}
