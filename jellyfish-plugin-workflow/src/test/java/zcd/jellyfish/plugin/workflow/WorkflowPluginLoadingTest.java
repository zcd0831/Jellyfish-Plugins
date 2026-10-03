package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.subagent.SubAgentPort;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 两个面（工具 / 提示词贡献）都可路由 → 卸载可回收」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「注册调用发生了」，证明不了这个插件真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单元测试全绿。这里刻意<b>复用 {@code src/main/resources} 里那份描述符</b>，
 * 而不是在测试里另写一份，否则测的就不是要发出去的那个插件了。
 * <p>
 * <b>本插件多一个必须验证的点</b>：启动时要拿到 {@code PluginContext.delegations()}。
 * 它在能力缺失时给的是「永远拒绝」的端口而非 {@code null}，因此这里用
 * {@link SubAgentPort#unavailable()} 装配也不会让启动失败——这正是要断言的行为。
 *
 * @author zcd
 */
@DisplayName("编排插件加载链路")
class WorkflowPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-workflow";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略，两个面的注册落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道，插件上下文装配需要。 */
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
    void bootstrap_shouldStartPlugin_when_descriptorIsValid() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("启动后 workflow 工具与提示词贡献都应可路由")
    void bootstrap_should_registerAllCapabilities() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        List<String> tools = new ArrayList<String>();
        for (ToolDescriptor descriptor : extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class)) {
            tools.add(descriptor.getName());
        }
        assertTrue(tools.contains(WorkflowTool.NAME), tools.toString());
        assertEquals(1, extensions.handlers(PromptContributionRequest.class, null).size());
    }

    @Test
    @DisplayName("插件卸载后两个面的注册都应被按 owner 全部回收")
    void close_should_unregisterAllCapabilities() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.handlers(ToolCallRequest.class, WorkflowTool.NAME).isEmpty());
        assertTrue(extensions.handlers(PromptContributionRequest.class, null).isEmpty());
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
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class),
                new ShellIngress(new MetricsRegistry()));
        return new PF4JPluginManager(contexts, PluginRuntimeConfig.ofRoots(pluginsRoot), eventChannel);
    }
}
