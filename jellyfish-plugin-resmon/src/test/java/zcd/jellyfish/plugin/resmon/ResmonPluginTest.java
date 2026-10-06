package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonPlugin} 的装配测试：用真实的注册表与真实的插件上下文。
 * <p>
 * 这里刻意不用 mock 上下文（除了会话域服务这类与本插件毫无关系的协作者）：本类的职责就是
 * 「把命令、候选与面板交给上下文」，用真的上下文才能顺带验证它们落到了同一份注册表上、
 * 并且 owner 归本插件。
 * <p>
 * <b>面板的注册条件也要在这里验</b>：它在没有交互界面的外壳里不注册（那是刻意的降级），
 * 因此测试必须能构造出两种外壳——这正是用真 {@link RuntimeInfoHolder} 而不是 mock 上下文的原因。
 *
 * @author zcd
 */
@DisplayName("资源监控插件装配")
class ResmonPluginTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-resmon";

    /** 每个用例一个独立目录，六个统计路径都指到这里。 */
    @TempDir
    Path directory;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 插件上下文工厂。 */
    private PluginContextFactory contexts;

    /** 被测插件。 */
    private ResmonPlugin plugin;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
    }

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            plugin.stop();
        }
        events.close();
    }

    @Test
    @DisplayName("启动后命令与候选查询都注册到本插件名下")
    void start_should_register_command_and_options() {
        start(RuntimeInfo.tui(true));

        assertEquals(1, extensions.bindings(CommandRequest.class, ResmonCommand.NAME).size());
        assertEquals(1, extensions.bindings(CommandOptionRequest.class, ResmonCommand.NAME).size());
        assertEquals(PLUGIN_ID,
                extensions.bindings(CommandRequest.class, ResmonCommand.NAME).get(0).getOwner());
    }

    @Test
    @DisplayName("有交互界面时注册面板")
    void start_should_register_panel_when_uiAvailable() {
        start(RuntimeInfo.tui(true));

        assertEquals(1, extensions.bindings(PanelContributionRequest.class, null).size());
    }

    @Test
    @DisplayName("没有交互界面时不注册面板：-cli / -server 根本不会问它")
    void start_should_skip_panel_when_noUi() {
        start(RuntimeInfo.cli(true));

        assertTrue(extensions.bindings(PanelContributionRequest.class, null).isEmpty());
        assertEquals(1, extensions.bindings(CommandRequest.class, ResmonCommand.NAME).size());
    }

    @Test
    @DisplayName("配置里关掉面板时不注册，命令照常可用")
    void start_should_skip_panel_when_disabled() {
        start(RuntimeInfo.tui(true), false);

        assertTrue(extensions.bindings(PanelContributionRequest.class, null).isEmpty());
        assertEquals(1, extensions.bindings(CommandRequest.class, ResmonCommand.NAME).size());
    }

    @Test
    @DisplayName("注册进内核的命令处理器真的能被调用")
    void start_should_make_command_invocable() {
        start(RuntimeInfo.tui(true));

        CommandResult result = extensions.invoke(
                extensions.bindings(CommandRequest.class, ResmonCommand.NAME).get(0).getHandler(),
                new CommandRequest(ResmonCommand.NAME, null, "s-1"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("JVM"), result.getOutput());
    }

    @Test
    @DisplayName("注册进内核的候选处理器真的能被调用")
    void start_should_make_options_invocable() {
        start(RuntimeInfo.tui(true));

        CommandOptions result = extensions.invoke(
                extensions.bindings(CommandOptionRequest.class, ResmonCommand.NAME).get(0).getHandler(),
                new CommandOptionRequest(ResmonCommand.NAME, "s-1"));

        assertEquals(5, result.getChoices().size());
    }

    @Test
    @DisplayName("停止幂等且有后台线程时也能干净返回")
    void stop_should_be_idempotent() {
        start(RuntimeInfo.tui(true));

        plugin.stop();
        plugin.stop();

        assertEquals(1, extensions.bindings(CommandRequest.class, ResmonCommand.NAME).size());
    }

    /**
     * 启动插件。
     *
     * @param info 外壳运行时信息，不可为 {@code null}
     */
    private void start(RuntimeInfo info) {
        start(info, true);
    }

    /**
     * 启动插件。
     *
     * @param info          外壳运行时信息，不可为 {@code null}
     * @param panelEnabled  配置里的面板开关
     */
    private void start(RuntimeInfo info, boolean panelEnabled) {
        RuntimeInfoHolder holder = new RuntimeInfoHolder();
        holder.set(info);
        contexts = new PluginContextFactory(extensions, events, registry, holder, new ActionQueue(),
                Mockito.mock(SessionManager.class), new ShellIngress(new MetricsRegistry()));
        plugin = new ResmonPlugin();
        plugin.start(contexts.create(PluginDeclaration.of(PLUGIN_ID, configuration(panelEnabled))));
    }

    /**
     * 构造把六个路径都指向临时目录的配置段。
     *
     * @param panelEnabled 面板开关
     * @return 配置段，保证非 {@code null}
     */
    private Map<String, Object> configuration(boolean panelEnabled) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, directory.resolve("sessions").toString());
        values.put(PluginConfig.KEY_TOOL_OUTPUTS_DIR, directory.resolve("tool-outputs").toString());
        values.put(PluginConfig.KEY_PLUGINS_DIR, directory.resolve("plugins").toString());
        values.put(PluginConfig.KEY_TODOS_DIR, directory.resolve("todos").toString());
        values.put(PluginConfig.KEY_GATEWAY_DIR, directory.resolve("gateway").toString());
        values.put(PluginConfig.KEY_LOG_FILE, directory.resolve("jellyfish-tui.log").toString());
        values.put(PluginConfig.KEY_PANEL, panelEnabled);
        return values;
    }
}
