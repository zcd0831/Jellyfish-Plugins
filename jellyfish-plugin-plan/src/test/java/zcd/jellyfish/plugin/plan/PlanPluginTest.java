package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PlanPlugin} 的单元测试：验证四个扩展点各注册一次、owner 归本插件。
 * <p>
 * 用真实的 {@link PluginContextImpl} 与真实注册表（而不是 mock 上下文）：本类的职责就是
 * 「把处理器交给上下文」，用真实上下文能顺带验证它们确实落到了同一份注册表上。
 *
 * @author zcd
 */
@DisplayName("plan 插件装配")
class PlanPluginTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-plan";

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        new PlanPlugin().start(context(extensions, events));
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("权限拦截、命令、候选、状态栏与回合上下文各注册一次")
    void start_should_registerAllCapabilities() {
        assertEquals(1, extensions.bindings(PermissionCheckRequest.class, null).size());
        assertEquals(1, extensions.bindings(CommandRequest.class, PlanCommand.COMMAND_NAME).size());
        assertEquals(1, extensions.bindings(CommandOptionRequest.class, PlanCommand.COMMAND_NAME).size());
        assertEquals(1, extensions.bindings(StatusLineContributionRequest.class, null).size());
        assertEquals(1, extensions.bindings(TurnContextRequest.class, null).size());
    }

    @Test
    @DisplayName("注册的 owner 是本插件标识：卸载时按它一次收干净")
    void start_should_registerUnderPluginOwner() {
        assertEquals(PLUGIN_ID, extensions.bindings(PermissionCheckRequest.class, null).get(0).getOwner());
    }

    /**
     * 构造带白名单的插件上下文。
     *
     * @param extensions 同步扩展点策略
     * @param events     事件通道
     * @return 插件上下文
     */
    private static PluginContext context(ExtensionRegistry extensions, EventChannel events) {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(PlanConfig.KEY_READ_ONLY_TOOLS, Arrays.<Object>asList("read_file", "list_dir"));
        return new PluginContextImpl(PluginDeclaration.of(PLUGIN_ID, configuration), extensions, events,
                Mockito.mock(SessionManager.class));
    }
}
