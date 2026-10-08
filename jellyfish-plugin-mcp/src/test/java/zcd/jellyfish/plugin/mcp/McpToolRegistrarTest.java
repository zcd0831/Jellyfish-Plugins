package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolRegistrar} 的单元测试：整体替换、按 server 归因、以及冲突时的隔离。
 *
 * @author zcd
 */
@DisplayName("MCP 工具注册器")
class McpToolRegistrarTest {

    /** 共用注册表。 */
    private TypeRegistry typeRegistry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 插件上下文。 */
    private PluginContext context;

    /** 共享状态。 */
    private McpRegistry registry;

    /** 被测注册器。 */
    private McpToolRegistrar registrar;

    /** 替身发起方。 */
    private final McpInvoker invoker =
            (name, arguments, timeout, token) -> new McpInvoker.McpCallOutcome("ok", false, 1L);

    @BeforeEach
    void setUp() {
        typeRegistry = new TypeRegistry();
        extensions = new ExtensionRegistry(typeRegistry);
        events = new EventChannel(EventChannelOptions.defaults(), typeRegistry);
        events.start();
        context = new PluginContextImpl(PluginDeclaration.of("jellyfish-plugin-mcp"), extensions, events,
                Mockito.mock(SessionManager.class));
        registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        registrar = new McpToolRegistrar(context, registry);
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("应把工具注册到 <插件>::<server> 的子命名空间下")
    void apply_should_registerUnderServerNamespace() {
        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("read_file", true), tool("write", false)));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__read_file").size());
        assertEquals("jellyfish-plugin-mcp::fs", typeRegistry.resolve(ToolCallRequest.class, "mcp__fs__write")
                .get(0).getOwner());
        assertTrue(registry.isReadOnly("mcp__fs__read_file"));
    }

    @Test
    @DisplayName("再次 apply 应整体替换：名字变了的那一个必须消失")
    void apply_should_replacePreviousTools() {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("old", true)));

        // When
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("new", true)));

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__fs__old").isEmpty());
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__new").size());
        assertTrue(!registry.isMcpTool("mcp__fs__old"));
    }

    @Test
    @DisplayName("注册冲突应只跳过那一个工具，其余照常可用")
    void apply_should_skipConflictingToolOnly() {
        // Given：先占住一个名字，模拟关掉前缀时撞上内置工具
        extensions.handle("core", ToolCallRequest.class, "mcp__fs__taken", null,
                request -> new ToolCallResult("mcp__fs__taken", "builtin"),
                zcd.jellyfish.api.event.RegisterOptions.DEFAULT);

        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("taken", true), tool("free", true)));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__free").size());
        assertTrue(registry.statusOf("fs").lastWarning().contains("mcp__fs__taken"));
    }

    @Test
    @DisplayName("注册失败的名字必须从标记表里摘掉：留着它会把占了那个名字的内置工具判成我们的写类工具")
    void apply_should_notMarkTool_when_registrationFails() {
        // Given：mcp__fs__taken 已被占住，而它在本插件的清单里是「未声明只读」
        extensions.handle("core", ToolCallRequest.class, "mcp__fs__taken", null,
                request -> new ToolCallResult("mcp__fs__taken", "builtin"),
                zcd.jellyfish.api.event.RegisterOptions.DEFAULT);

        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("taken", false), tool("free", false)));

        // Then：注册不成的名字不再是「我们的工具」，权限处理器不会替它下结论
        assertFalse(registry.isMcpTool("mcp__fs__taken"));
        assertTrue(registry.isMcpTool("mcp__fs__free"));
    }

    @Test
    @DisplayName("换清单时应先装标记再注册：注册那一刻名字已经在表里，否则写类工具在刷新窗口里不需要审批")
    void apply_should_installMarkersBefore_when_registering() {
        // Given：把上下文换成观察点——注册照常发生，只是在每次注册前先看一眼标记表
        List<Boolean> flaggedAtRegistration = new ArrayList<Boolean>();
        PluginContext root = observingRoot(context, flaggedAtRegistration);
        registrar = new McpToolRegistrar(root, registry);

        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("read", true), tool("write", false)));

        // Then：两个名字都必须在注册前就带着标记（把 replaceTools 挪到注册之后，这里会变成 false）
        assertEquals(Arrays.asList(Boolean.TRUE, Boolean.TRUE), flaggedAtRegistration);
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__read").size());
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__write").size());
    }

    /**
     * 包一层上下文：每次注册前记录「这个名字此刻是否已在标记表里」，随后照常完成注册。
     *
     * @param real 真实上下文
     * @param seen 记录目标
     * @return 包装后的上下文
     */
    private PluginContext observingRoot(PluginContext real, List<Boolean> seen) {
        PluginContext sub = real.subContext("fs");
        PluginContext observedSub = Mockito.mock(PluginContext.class);
        Mockito.doAnswer(invocation -> {
            seen.add(Boolean.valueOf(registry.isMcpTool(invocation.getArgument(1))));
            return registerOn(sub, invocation);
        }).when(observedSub).handle(Mockito.<Class<ToolCallRequest>>any(), Mockito.anyString(),
                Mockito.any(), Mockito.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        PluginContext root = Mockito.mock(PluginContext.class);
        Mockito.when(root.subContext("fs")).thenReturn(observedSub);
        return root;
    }

    /**
     * 在被观察的上下文上真的完成一次注册。
     *
     * @param sub        真实的子上下文
     * @param invocation 被拦截的调用
     * @return 注册句柄
     */
    @SuppressWarnings("unchecked")
    private static Subscription registerOn(PluginContext sub, InvocationOnMock invocation) {
        return sub.handle((Class<ToolCallRequest>) invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2),
                (ExtensionHandler<ToolCallRequest, ToolCallResult>) invocation.getArgument(3));
    }

    @Test
    @DisplayName("close 应注销该 server 的全部工具")
    void close_should_unregisterServerTools() {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("read_file", true)));

        // When
        registrar.close("fs");

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__fs__read_file").isEmpty());
        assertTrue(!registry.isMcpTool("mcp__fs__read_file"));
    }

    @Test
    @DisplayName("closeAll 应把全部 server 的工具都收干净")
    void closeAll_should_unregisterEverything() {
        // Given
        registrar.apply(invoker, server("a"), Collections.singletonList(tool("t", true)));
        registrar.apply(invoker, server("b"), Collections.singletonList(tool("t", true)));

        // When
        registrar.closeAll();

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__a__t").isEmpty());
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__b__t").isEmpty());
        assertTrue(typeRegistry.snapshot().isEmpty());
    }

    @Test
    @DisplayName("注册出来的处理器应真的能把调用转给发起方")
    void apply_should_wireCallerToInvoker() throws Exception {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("read_file", true)));

        // When
        ToolCallResult result = extensions.<ToolCallRequest, ToolCallResult>handler(
                        ToolCallRequest.class, "mcp__fs__read_file")
                .handle(new ToolCallRequest("mcp__fs__read_file", Collections.<String, Object>emptyMap(), "s1"));

        // Then
        assertEquals("ok", result.getOutput());
    }

    @Test
    @DisplayName("两条线程同时换清单：最后留在注册表里的必须恰好是其中一份，不能是混合")
    void apply_should_neverLeaveMixedRegistration_whenConcurrent() throws Exception {
        // Given：两份完全不相交的清单（名字不同，因此「泄漏」在注册表里一眼可见）
        List<McpToolDefinition> first = tools("a", 50);
        List<McpToolDefinition> second = tools("b", 50);
        Set<String> expectedFirst = namesOf(first);
        Set<String> expectedSecond = namesOf(second);

        // When / Then：反复并发换清单。这条用例是**压力型**的：它守的不变量很清楚
        // （最后只该留一份），但能不能撞上交错取决于线程调度，因此多跑几轮提高命中率。
        // 之所以要这条：apply 是「关旧 → 装标记 → 注册新 → 摘没注册成的」四步，
        // 整段不是原子的，交错会让先注册的那批 Subscription 再没人关，
        // 于是一批已经不在清单上的工具留在注册表里——而模型看得见它们。
        for (int round = 0; round < 20; round++) {
            Thread one = new Thread(() -> registrar.apply(invoker, server("fs"), first), "apply-a");
            Thread two = new Thread(() -> registrar.apply(invoker, server("fs"), second), "apply-b");
            one.start();
            two.start();
            one.join();
            two.join();

            Set<String> live = liveTools(expectedFirst, expectedSecond);
            assertTrue(live.equals(expectedFirst) || live.equals(expectedSecond),
                    "第 " + (round + 1) + " 轮出现了混合注册：活着的工具 " + live.size()
                            + " 个（应为 50）");
        }
    }

    /**
     * 取此刻真正在注册表里的工具名。
     *
     * @param candidates 候选名字（两份清单的并集）
     * @return 活着的名字集合
     */
    private Set<String> liveTools(Set<String>... candidates) {
        Set<String> live = new LinkedHashSet<String>();
        for (Set<String> group : candidates) {
            for (String name : group) {
                if (!extensions.handlers(ToolCallRequest.class, name).isEmpty()) {
                    live.add(name);
                }
            }
        }
        return live;
    }

    /**
     * 构造一批工具名（展开名形式）。
     *
     * @param prefix 名字前缀
     * @param count  个数
     * @return 名字集合
     */
    private static Set<String> namesOf(List<McpToolDefinition> tools) {
        Set<String> names = new LinkedHashSet<String>();
        for (McpToolDefinition definition : tools) {
            names.add(definition.qualifiedName());
        }
        return names;
    }

    /**
     * 构造一批工具定义。
     *
     * @param prefix 原始名前缀
     * @param count  个数
     * @return 定义列表
     */
    private static List<McpToolDefinition> tools(String prefix, int count) {
        List<McpToolDefinition> definitions = new ArrayList<McpToolDefinition>(count);
        for (int index = 0; index < count; index++) {
            String name = prefix + index;
            definitions.add(new McpToolDefinition("fs", name, "mcp__fs__" + name, "d",
                    Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), true));
        }
        return definitions;
    }

    /**
     * 构造服务配置。
     *
     * @param id 服务标识
     * @return 服务配置
     */
    private static McpServerConfig server(String id) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("id", id);
        values.put("command", "echo");
        values.put("callTimeoutSeconds", 5);
        return McpServerConfig.from(values);
    }

    /**
     * 构造工具定义。
     *
     * @param originalName 原始名
     * @param readOnly     是否只读
     * @return 定义
     */
    private static McpToolDefinition tool(String originalName, boolean readOnly) {
        List<String> required = Collections.emptyList();
        return new McpToolDefinition("fs", originalName, "mcp__fs__" + originalName, "d",
                Collections.<String, Object>emptyMap(), required, readOnly);
    }
}
