package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pf4j.PluginState;
import zcd.jellyfish.api.extension.CommandRequest;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源监控插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类跑一遍
 * 「加载 → 描述符体检 → 启动 → 命令可路由 → 卸载回收」。
 * <p>
 * <b>为什么单元测试不够</b>：装配测试只能证明「注册调用发生了」，证明不了这条插件真能被内核加载——
 * 描述符少一个键、插件包自带内核契约、入口类没有公开无参构造器，任何一项出错都会让插件在运行时
 * 悄无声息地不生效，而单测全绿。这里刻意复用 {@code src/main/resources} 里那份描述符，
 * 而不是在测试里另写一份，否则测的就不是要发出去的那个插件了。
 * <p>
 * <b>这一条对本插件尤其要紧</b>：它的采样线程在 {@code start()} 里就起来了，如果入口类因为描述符
 * 问题加载不了，用户看到的是「面板一直没有」，而不是任何一条错误——那是这类故障里最难查的一种。
 *
 * @author zcd
 */
@DisplayName("资源监控插件加载链路")
class ResmonPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-resmon";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 数据根目录：把插件的六个统计路径都指到这里，测试不去扫用户真实的家目录。 */
    @TempDir
    Path dataRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
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
    @DisplayName("启动后命令应可路由")
    void bootstrap_should_registerCommand() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(1, extensions.bindings(CommandRequest.class, ResmonCommand.NAME).size());
    }

    @Test
    @DisplayName("插件卸载后命令注册应按 owner 被回收")
    void close_should_unregisterCommand() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        manager.close();
        manager = null;

        assertTrue(extensions.bindings(CommandRequest.class, ResmonCommand.NAME).isEmpty());
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
     * <p>
     * <b>配置段必须一起带上</b>：不带的话插件会用 {@code ~/.jellyfish/...} 这组缺省路径，
     * 于是这条「只想知道能不能加载」的用例会去递归遍历用户真实的会话目录——
     * 既慢，又让一条加载用例的耗时取决于用户的磁盘上堆了多少东西。
     *
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class),
                new ShellIngress(new MetricsRegistry()));
        Map<String, Map<String, Object>> configurations = new HashMap<String, Map<String, Object>>();
        configurations.put(PLUGIN_ID, configuration());
        PluginRuntimeConfig runtime = new PluginRuntimeConfig(Collections.<Path>singletonList(pluginsRoot),
                null, null, configurations);
        return new PF4JPluginManager(contexts, runtime, eventChannel);
    }

    /**
     * 构造以临时目录为根目录的插件配置段。
     *
     * @return 配置段，保证非 {@code null}
     */
    private Map<String, Object> configuration() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, dataRoot.toString());
        return values;
    }
}
