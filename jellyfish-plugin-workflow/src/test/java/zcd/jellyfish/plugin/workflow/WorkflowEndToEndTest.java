package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排插件的端到端测试：<b>真插件 + 真 PF4J 加载 + 真扩展点注册表</b>跑一份 spec。
 * <p>
 * <b>与 {@code WorkflowEngineTest} 的分工</b>：那个测引擎的调度逻辑（用手搓的 spec），
 * 这个测「插件被内核加载之后，{@code workflow} 工具真的能被调起来并把整份 spec 跑完」——
 * 也就是说，它走的是内核 {@code ToolExecutor} 用的同一条路：从注册表取处理器、调它、拿结果。
 * 处理器不是 new 出来的，是插件在 {@code start()} 里注册的。
 * <p>
 * <b>端口为什么是假的</b>：子代理的委派端口实现在内核（{@code SubAgentDelegationAdapter}），
 * 而本仓库与内核之间只有 {@code jellyfish-api} 的编译期契约——这正是插件与内核解耦的证明。
 * 「端口接到真内核之后行为如何」由内核仓库的 {@code SubAgentDelegationEndToEndTest} 覆盖，
 * 两半合起来才是完整的一条链：插件侧（本测试）到端口为止，内核侧从端口开始。
 *
 * @author zcd
 */
@DisplayName("编排插件端到端")
class WorkflowEndToEndTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-workflow";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略：内核调用工具的落点。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel eventChannel;

    /** 被测插件管理器。 */
    private PF4JPluginManager manager;

    /** 脚本化的伪委派端口。 */
    private ScriptedPort port;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
        port = new ScriptedPort();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        eventChannel.close();
    }

    @Test
    @DisplayName("三步 spec：两个并行步骤 + 一个依赖它们的步骤 + 汇总，全部走完并如实汇报")
    void workflow_shouldRunWholeSpecThroughTheRegistry() throws IOException {
        // Given：插件被真实加载，端口按步骤给脚本化结果
        installPlugin();
        manager = newManager();
        manager.bootstrap();
        port.answer("probe-a", DelegationResult.completed("run-a", "A 的结论", 2, 30L));
        port.answer("probe-b", DelegationResult.completed("run-b", "B 的结论", 2, 30L));
        port.answer("plan", DelegationResult.completed("run-p", "方案正文", 3, 40L));
        port.answer("planner", DelegationResult.completed("run-s", "汇总结论", 1, 10L));

        // When：像内核的工具执行器那样，从注册表取处理器并调用
        ToolCallResult result = extensions.invoke(
                extensions.handler(ToolCallRequest.class, WorkflowTool.NAME),
                new ToolCallRequest(WorkflowTool.NAME, arguments(), "s-1"));

        // Then：三个步骤都派生了，顺序是「两个并行 → 依赖它们的那个」
        assertEquals(Arrays.asList("probe-a", "probe-b", "plan"), port.stepPrompts());
        // And：汇总那一步用 planner 又派了一次，且材料里带着前几步的结论
        assertEquals("planner", port.synthesisRequest().getAgentId());
        assertTrue(port.synthesisRequest().getPrompt().contains("A 的结论"),
                port.synthesisRequest().getPrompt());
        assertTrue(port.synthesisRequest().getPrompt().contains("方案正文"),
                port.synthesisRequest().getPrompt());

        // And：文本按声明顺序分节，聚合取汇总那一次的结论
        String text = String.valueOf(result.getOutput());
        assertTrue(text.startsWith("[workflow 调研并写方案 完成 · 3 步 · 8 轮 · 110 tok]"), text);
        assertTrue(text.contains("汇总结论"), text);
        assertNull(result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertEquals(3, result.getMetadata().get("workflowSteps"));
        assertEquals(110L, result.getMetadata().get("workflowTokens"));
        assertTrue(String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)).contains("3 步"));
    }

    @Test
    @DisplayName("成环的 spec 在派生任何子代理之前就被拒绝")
    void invalidSpec_shouldBeRejectedBeforeSpawningAnything() throws IOException {
        // Given：一份成环的 spec
        installPlugin();
        manager = newManager();
        manager.bootstrap();
        Map<String, Object> arguments = arguments();
        Map<String, Object> spec = (Map<String, Object>) arguments.get("spec");
        List<Object> steps = (List<Object>) spec.get("steps");
        ((Map<String, Object>) steps.get(0)).put("needs", Arrays.asList("plan"));
        spec.remove("aggregate");

        // When / Then：报错里点明成环，且一个 run 都没派生
        JellyfishException error = assertThrows(JellyfishException.class, () -> extensions.invoke(
                extensions.handler(ToolCallRequest.class, WorkflowTool.NAME),
                new ToolCallRequest(WorkflowTool.NAME, arguments, "s-1")));
        assertTrue(error.getMessage().contains("成环"), error.getMessage());
        assertTrue(port.isEmpty(), "成环的 spec 不该派生任何 run");
    }

    /**
     * 构造一份三步 spec 的参数（两个并行调研 → 一个方案 → 汇总）。
     *
     * @return 工具参数
     */
    private static Map<String, Object> arguments() {
        Map<String, Object> probeA = step("probe-a", "scout", "probe-a");
        Map<String, Object> probeB = step("probe-b", "scout", "probe-b");
        Map<String, Object> plan = step("plan", "planner", "plan");
        plan.put("needs", Arrays.asList("probe-a", "probe-b"));

        Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
        aggregate.put("mode", "summarize");
        aggregate.put("agent", "planner");

        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("name", "调研并写方案");
        spec.put("steps", new ArrayList<Object>(Arrays.asList(probeA, probeB, plan)));
        spec.put("aggregate", aggregate);

        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", spec);
        return arguments;
    }

    /**
     * 构造一个步骤对象。测试里让「步骤 id」与「任务原文」取同一个值（与引擎测试同一约定），
     * 脚本端口据此用 prompt 找回该步的结果。
     *
     * @param id     步骤标识
     * @param agent  子代理类型
     * @param prompt 任务原文
     * @return 步骤对象
     */
    private static Map<String, Object> step(String id, String agent, String prompt) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("id", id);
        step.put("agent", agent);
        step.put("prompt", prompt);
        return step;
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
     * 构造扫描临时目录的插件管理器，并把脚本端口交给插件上下文。
     *
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class),
                new ShellIngress(new MetricsRegistry()), port);
        return new PF4JPluginManager(contexts, PluginRuntimeConfig.ofRoots(pluginsRoot), eventChannel);
    }

    /**
     * 脚本化的伪委派端口：记录派生顺序与请求，按「prompt 优先、agent 兜底」给出预置结果。
     * <p>
     * 结果立即就绪（{@code settled}），因此整个编排是同步的——本测试要验的是「链路通不通」，
     * 并发行为由插件自己的引擎测试与内核的调度测试各自覆盖。
     */
    private static final class ScriptedPort implements SubAgentPort {

        /** 收到的请求，按到达顺序。 */
        private final List<DelegationRequest> requests = new ArrayList<DelegationRequest>();

        /** 按 prompt 或 agent 给出的应答。 */
        private final Map<String, DelegationResult> answers = new LinkedHashMap<String, DelegationResult>();

        /**
         * 指定某个步骤（按 prompt）或某个类型（按 agent）的结果。
         *
         * @param key    prompt 或 agent
         * @param result 结果
         */
        void answer(String key, DelegationResult result) {
            answers.put(key, result);
        }

        /**
         * 取步骤派生的顺序（不含汇总那一次；本测试里 prompt 就是步骤 id）。
         *
         * @return 标识列表
         */
        List<String> stepPrompts() {
            List<String> ids = new ArrayList<String>();
            for (int i = 0; i < requests.size() - 1; i++) {
                ids.add(requests.get(i).getPrompt());
            }
            return ids;
        }

        /**
         * 取汇总那一次的请求。
         *
         * @return 请求
         */
        DelegationRequest synthesisRequest() {
            return requests.get(requests.size() - 1);
        }

        /**
         * 判断有没有派生过。
         *
         * @return 派生过返回 {@code true}
         */
        boolean isEmpty() {
            return requests.isEmpty();
        }

        @Override
        public DelegationHandle spawn(DelegationRequest request) {
            requests.add(request);
            DelegationResult result = answers.containsKey(request.getPrompt())
                    ? answers.get(request.getPrompt()) : answers.get(request.getAgentId());
            return DelegationHandle.settled(result != null ? result
                    : DelegationResult.completed("run", "正文", 1, 1L));
        }
    }
}
