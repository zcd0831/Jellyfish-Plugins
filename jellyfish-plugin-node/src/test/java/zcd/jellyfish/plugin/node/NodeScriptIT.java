package zcd.jellyfish.plugin.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.script.GatewayResources;
import zcd.jellyfish.script.ScriptBridgeConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Node 的端到端测试：注册 → 调用 → 事件 → 超时隔离 → 熔断 → 关闭。
 * <p>
 * <b>为什么不进 {@code mvn test}</b>：它需要一个真实的 node，而 AGENTS.md 规定单元测试不访问
 * 外部资源。因此它命名为 {@code *IT}（Surefire 默认的包含规则只认 {@code *Test}），
 * 由 {@code -Pscript-it} profile 显式启用。
 * <p>
 * <b>它与 Python 版 IT 的分工</b>：进程模型、超时链、熔断状态机、PID 文件这些机制都在
 * {@code jellyfish-script} 里，由 {@code ScriptGatewayTest}、{@code ScriptCircuitBreakerTest}
 * 与 Python 的端到端用例覆盖；Node 这边要证明的是**这门语言的适配真的接得上**——
 * 网关资源抽取、启动命令、环境白名单、SDK 的 require 路径、worker 的握手与死亡处置。
 * 换句话说：这里失败，一定是 Node 适配的问题，而不是机制层的问题。
 * <p>
 * 机器上没装 node 时整类跳过（{@code assumeTrue}），而不是失败：环境缺失与代码有 bug 是两件事。
 *
 * @author zcd
 */
@DisplayName("Node 脚本端到端")
class NodeScriptIT {

    /** 解释器探测超时。 */
    private static final long PROBE_TIMEOUT_SECONDS = 5L;

    /** 插件标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-node";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 脚本根目录。 */
    @TempDir
    Path scriptsRoot;

    /** 网关资源抽取根目录。 */
    @TempDir
    Path gatewayRoot;

    /** PID 文件目录；固定到临时目录，避免测试往用户主目录写东西。 */
    @TempDir
    Path pidRoot;

    /** 插件管理器。 */
    private PF4JPluginManager manager;

    /** 共用注册表：注册与调用必须落在同一份上，否则测试会「注册成功但调用找不到」。 */
    private final zcd.jellyfish.infra.registry.TypeRegistry registry =
            new zcd.jellyfish.infra.registry.TypeRegistry();

    /** 同步扩展点策略。 */
    private final zcd.jellyfish.infra.extension.ExtensionRegistry extensions =
            new zcd.jellyfish.infra.extension.ExtensionRegistry(registry);

    /** 事件通道。 */
    private final zcd.jellyfish.infra.event.EventChannel events =
            new zcd.jellyfish.infra.event.EventChannel(
                    zcd.jellyfish.infra.event.EventChannelOptions.defaults(), registry);

    /**
     * 跳过没有解释器的环境。
     */
    @BeforeEach
    void requireNode() {
        assumeTrue(interpreterAvailable(), "本机没有可用的 node，跳过端到端测试");
        // 事件桥接的用例要求通道真的在派发：未 start 的通道只把通知塞进启动期缓冲，
        // 于是「脚本没收到事件」会表现为一个与代码无关的谜题
        events.start();
    }

    /**
     * 关闭运行时，确保子进程不残留。
     */
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        events.close();
    }

    @Test
    @DisplayName("工具应被真实执行，结果与脚本返回一致")
    void tool_should_returnScriptOutput_when_invokedEndToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "PROJ-1"));

        assertEquals("issue PROJ-1 处于 OPEN（会话 s-1）", result.getOutput());
    }

    @Test
    @DisplayName("脚本抛出的异常应变成可读的调用失败，而不是静默的空结果")
    void tool_should_failWithMessage_when_scriptThrows() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("缺少参数 key"), failure.getMessage());
    }

    @Test
    @DisplayName("脚本的未捕获异常也应变成调用失败，且带类型名")
    void tool_should_failWithTypeName_when_scriptThrowsUnexpectedError() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_boom", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("TypeError"), failure.getMessage());
    }

    @Test
    @DisplayName("命令应拿到 tokens 与 raw，并按脚本返回的三态结果回灌")
    void command_should_receiveTokensAndRaw_when_invokedEndToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = invokeCommand("jira", "PROJ-1 DONE");

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("命令 PROJ-1|DONE（raw=PROJ-1 DONE）", result.getOutput());
    }

    @Test
    @DisplayName("类型级贡献应能按类型调用")
    void contribution_should_beInvoked_byType() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        assertTrue(promptText().contains("Node 示例脚本可用"), promptText());
    }

    @Test
    @DisplayName("内核事件应送达脚本，并体现在它的贡献里")
    void event_should_reachScript_when_publishedByKernel() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        assertTrue(statusText().contains("0 次"), statusText());

        events.publish(new zcd.jellyfish.api.event.notification.ToolCallCompletedEvent(
                "call-1", "jira_issue", true, 12L, null, "s-1"));

        assertTrue(awaitStatusLine("1 次"), "事件未送达脚本，实际状态栏: " + statusText());
    }

    @Test
    @DisplayName("scripts.<id> 应按脚本 id 送达，且 async handler 的返回值正确")
    void scriptConfiguration_should_reachAsyncScript_when_configured() throws IOException {
        writeScript("web", ASYNC_CONFIG_SCRIPT, ASYNC_CONFIG_MANIFEST);
        Map<String, Object> web = new LinkedHashMap<String, Object>();
        web.put("provider", "brave");
        web.put("apiKey", "k-123");
        Map<String, Map<String, Object>> scripts = new LinkedHashMap<String, Map<String, Object>>();
        scripts.put("web", web);
        startRuntime(5, scripts);

        assertEquals("provider=brave; key=k-123; module=brave",
                invokeTool("web_probe", Collections.<String, Object>emptyMap()).getOutput());
    }

    @Test
    @DisplayName("async 事件处理器应真的跑完，且不拖住后续调用")
    void asyncEventHandler_should_complete_withoutBlockingRequests() throws IOException, InterruptedException {
        writeScript("web", ASYNC_CONFIG_SCRIPT, ASYNC_CONFIG_MANIFEST);
        startRuntime();

        // 先把网关拉起来：脚本运行时是**懒启动**的，网关还没起来时事件会直接丢掉。
        // 这不是测试技巧，而是「事件可以丢」这条契约的真实形状（冷启动窗口内的事件本来就没有接收方）
        invokeTool("web_events", Collections.<String, Object>emptyMap());

        events.publish(new zcd.jellyfish.api.event.notification.ToolCallCompletedEvent(
                "call-1", "web_probe", true, 5L, null, "s-1"));

        String handled = null;
        long deadline = System.currentTimeMillis() + 10_000L;
        // 先让事件先被送达：网关对「忙」的 worker 直接丢事件，而紧接着的 invoke 就会把它标记为忙。
        // 这个先后关系同样不是测试的妥协，而是事件通道「可以丢」这条契约的真实形状
        Thread.sleep(300L);
        while (System.currentTimeMillis() < deadline) {
            // 事件处理器是 async 的，因此这里必须容忍它晚一点才跑完；
            // 关键点是这一串 invoke 不能被它拖住（否则永远读不到 handled=1）
            String output = String.valueOf(invokeTool("web_events",
                    Collections.<String, Object>emptyMap()).getOutput());
            if (output.contains("handled=1")) {
                handled = output;
                break;
            }
            Thread.sleep(100L);
        }
        assertNotNull(handled, "async 事件处理器未在预期时间内跑完");
    }

    @Test
    @DisplayName("脚本往 stdout 打一行合法 JSON 时，不能顶替在途调用的应答")
    void callTool_should_notBeHijacked_whenScriptLogsJsonOnStdout() throws IOException {
        // Given：一个在 handler 里 `console.log(JSON.stringify(...))` 的脚本——这是脚本作者最自然的
        // 调试动作，而 node 侧脚本的 stdout 与协议共用 fd 1，于是一行合法 JSON 会进到协议流里
        writeScript("web_noisy", NOISY_STDOUT_SCRIPT, NOISY_STDOUT_MANIFEST);
        startRuntime();

        // When：连调两次：第一次那帧假应答若被当成回答，第二次的真实结果就会算到第一次头上
        String first = String.valueOf(invokeTool("web_noisy_a",
                Collections.<String, Object>emptyMap()).getOutput());
        String second = String.valueOf(invokeTool("web_noisy_b",
                Collections.<String, Object>emptyMap()).getOutput());

        // Then：两次都必须拿到**自己**的真实结果
        assertEquals("first-real", first, "第一次调用拿到了假应答（或别人的结果）");
        assertEquals("second-real", second, "第二次调用拿到了假应答（或别人的结果）");
    }

    @Test
    @DisplayName("大于宿主按行缓冲上限的结果也必须完整送达（而不是被切成两条谁也解析不了的行）")
    void largeResult_should_reachCaller_whenItExceedsHostLineLimit() throws IOException {
        // 宿主是**按行**读协议流的，而它给「一行」留的缓冲有上限；脚本侧又各自声明了一个
        // 出帧上限。两者一旦错位（宿主那张比脚本侧那张小），落在中间的结果就会被宿主**就地切成
        // 两条非法行**丢掉——现场表现是「调用一直等到超时」，而脚本侧那条「结果超过传输上限」
        // 的错误码永远不会触发（它压根没觉得自己超限）
        writeScript("large", LARGE_SCRIPT, LARGE_MANIFEST);
        startRuntime(10);

        ToolCallResult result = invokeTool("large_probe", Collections.<String, Object>emptyMap());

        assertEquals(200_000, String.valueOf(result.getOutput()).length(),
                "大结果没有完整送达（拿到的长度是 " + String.valueOf(result.getOutput()).length() + "）");
    }

    @Test
    @DisplayName("宿主按行的缓冲上限必须严格大于脚本侧的出帧上限（两个数字，互相不能悄悄改）")
    void hostLineLimit_should_beGreaterThanScriptFrameLimit() throws IOException {
        // 这条守卫存在的理由：这是**跨语言的两个数字**，而它们的关系（宿主 > 脚本）是硬要求。
        // 任何一侧单独改了数，另一侧不会编译失败、不会测试变红，只会在大结果上表现成「超时」。
        // 因此这里把关系本身钉死：从脚本侧的源码里读那个数，与宿主侧那个数比大小
        String source = new String(Files.readAllBytes(
                gatewayDirectory().resolve("script").resolve("script_wire.js")),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("MAX_FRAME_BYTES\\s*=\\s*(\\d+)").matcher(source);
        assertTrue(matcher.find(), "没在 script_wire.js 里找到 MAX_FRAME_BYTES 的字面量");
        long scriptLimit = Long.parseLong(matcher.group(1));
        assertTrue(zcd.jellyfish.script.protocol.ScriptProtocol.MAX_LINE_BYTES > scriptLimit,
                "宿主按行缓冲的上限(" + zcd.jellyfish.script.protocol.ScriptProtocol.MAX_LINE_BYTES
                        + ") 必须严格大于脚本侧出帧上限(" + scriptLimit
                        + ")：否则大结果会被宿主切成两条非法行，现场只看到「超时」");
    }

    @Test
    @DisplayName("工具返回 ToolResult 时：正文进 output，摘要与调用者身份进 metadata")
    void toolResult_should_carryMetadataAndIdentity_when_scriptReturnsIt() throws IOException {
        writeScript("web", METADATA_SCRIPT, METADATA_MANIFEST);
        startRuntime();

        ToolCallResult result = extensions.invoke(
                extensions.handler(ToolCallRequest.class, "web_meta"),
                new ToolCallRequest("web_meta", Collections.<String, Object>emptyMap(), "s-1",
                        CancellationToken.NONE, ToolOutputSink.NOOP, "parent-1", "r-1", "root-1"));

        assertEquals("正文", result.getOutput());
        assertEquals("parent=parent-1 run=r-1 root=root-1",
                ToolMetadata.summaryOf(result.getMetadata()));
    }

    @Test
    @DisplayName("带路由键的处理器（model_catalog）应能声明并真实调用")
    void routedHandler_should_beInvoked_endToEnd() throws IOException {
        writeScript("ext", HANDLER_SCRIPT, HANDLER_MANIFEST);
        startRuntime();

        zcd.jellyfish.api.extension.ModelCatalogResult catalog = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.ModelCatalogRequest.class, "local"),
                new zcd.jellyfish.api.extension.ModelCatalogRequest("local", "openai"));
        assertEquals(1, catalog.getModels().size());
        assertEquals("local-model", catalog.getModels().get(0).getId());
        assertEquals(8192, catalog.getModels().get(0).getContextLength());

        zcd.jellyfish.api.extension.TurnContext context = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.TurnContextRequest.class, null),
                new zcd.jellyfish.api.extension.TurnContextRequest("s-1", "hi", false));
        assertEquals("now: 2026-10-04", context.getText());
    }

    @Test
    @DisplayName("标记路由（input_directive）与三期扩展点应能真实调用")
    void thirdWaveExtensions_should_beInvoked_endToEnd() throws IOException {
        writeScript("ext", THIRD_WAVE_SCRIPT, THIRD_WAVE_MANIFEST);
        startRuntime();

        // 先暖网关（热路径点 request_tuning 不冷启动）
        assertTrue(extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.TurnBeforeRequest.class, null),
                new zcd.jellyfish.api.extension.TurnBeforeRequest("s-1", "coder", "干活", false, 0))
                .hasInput());

        zcd.jellyfish.api.extension.InputDirectiveResult directive = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.InputDirectiveRequest.class, "!"),
                new zcd.jellyfish.api.extension.InputDirectiveRequest("!", "ls -la", "s-1"));
        assertTrue(directive.isToolCall());
        assertEquals("shell", directive.getToolName());
        assertEquals("ls -la", directive.getArguments().get("command"));

        assertTrue(extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.ToolActivationRequest.class, null),
                new zcd.jellyfish.api.extension.ToolActivationRequest("s-1", "coder",
                        new zcd.jellyfish.api.extension.ToolDescriptor("bad", "坏工具"))).isHidden());
        assertEquals(1, extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.RequestTuningRequest.class, null),
                new zcd.jellyfish.api.extension.RequestTuningRequest("s-1", "openai", "m", "k", 1, 1))
                .getCacheBreakpoints().intValue());
    }

    @Test
    @DisplayName("调用超时应隔离 worker，并把原因作为失败回灌")
    void timeout_should_isolateWorker_andReportReason() throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        long started = System.currentTimeMillis();
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
        // 隔离链的另一半：卡在同步忙等里的 worker 收不到 SIGTERM，因此只能靠网关强杀。
        // 期望的进程数是 **1** 而不是 0：网关是设计成可空闲十分钟的长命进程，它在隔离之后
        // 仍然活着（下一次调用不必重新拉起它）；该消失的是那个卡死的 worker。
        // 只断言「数字降到了期望值」而不是「进程为 0」，才能真的把这一件事测出来
        assertTrue(awaitProcessCount(gatewayDirectory(), 1, 20_000L),
                "超时隔离后卡死的 worker 仍在（进程数 " + countProcesses(gatewayDirectory())
                        + "，耗时 " + (System.currentTimeMillis() - started) + "ms）");
        // 隔离之后必须能重新拉起：否则一次卡死就等于这个脚本永久不可用
        assertTrue(awaitInvokeSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "PROJ-1"), 30_000L));
    }

    @Test
    @DisplayName("被隔离的 worker 还在两段式关闭里时换了新一代：不能把它就地丢掉（那会成为永久孤儿）")
    void killedWorker_should_stillBeForceKilled_whenNewWorkerTakesOver()
            throws IOException, InterruptedException {
        // 缺陷现场：网关在「再派一次调用」时会把上一代 worker 的句柄就地丢掉
        // （`invoke()` 见 `killAt !== null` 就 `closeWorker()`），而两段式关闭的第二段
        // （SIGKILL）正是靠那个句柄发的。句柄一丢，卡在同步忙等里、连 SIGTERM 处理器
        // 都没机会跑的 worker 就再也没人收得动它——它会一直占满一个核，直到 60 秒的忙等
        // 自己跑完；而宿主连这个进程的存在都不知道。
        // 这里让两件事在同一次运行里发生：先卡死一次（进入隔离），再立刻调一次（网关换一代）
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);
        Map<String, Object> noArguments = Collections.<String, Object>emptyMap();

        assertThrows(JellyfishException.class, () -> invokeTool("jira_hang", noArguments));
        // 紧接着再调一次：上一代此刻正处在「SIGTERM 已发、SIGKILL 未发」的宽限期里
        assertThrows(JellyfishException.class, () -> invokeTool("jira_hang", noArguments));

        // 期望 1 个进程（网关自己）：那两个 60 秒忙等都该被 SIGKILL 收掉，
        // 而网关是设计成可空闲十分钟的长命进程，它留下是对的
        assertTrue(awaitProcessCount(gatewayDirectory(), 1, 30_000L),
                "被隔离的 worker 成了永久孤儿（进程数 " + countProcesses(gatewayDirectory()) + "）");
    }

    @Test
    @DisplayName("连续失败应触发熔断，且熔断期间不派发、工具仍在清单里")
    void circuitBreaker_should_rejectWithoutDispatch_whenFailuresExceedThreshold() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntimeWithCircuit(5, 2, 30, 3);
        Map<String, Object> noArguments = Collections.<String, Object>emptyMap();

        assertThrows(JellyfishException.class, () -> invokeTool("jira_issue", noArguments));
        assertThrows(JellyfishException.class, () -> invokeTool("jira_issue", noArguments));
        JellyfishException rejected = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_issue", noArguments));

        assertTrue(rejected.getMessage().contains("熔断"), rejected.getMessage());
        // 不摘注册：熔断只是一种「暂时拒绝」，工具定义仍然在清单里，
        // 模型看到的是一条带原因的失败，而不是「工具凭空消失」
        assertNotNull(extensions.handler(ToolCallRequest.class, "jira_issue"));
        // **熔断期间不派发**：换一个「一调就卡 60 秒」的工具去调它。真派发出去的话，
        // 拿到的会是超时隔离（要等满一个超时），而不是立刻回来的熔断拒绝——
        // 这条断言把「拒绝发生在派发之前」变成可观察的事实，而不是读代码才敢相信的结论
        long started = System.currentTimeMillis();
        JellyfishException alsoRejected = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", noArguments));
        assertTrue(alsoRejected.getMessage().contains("熔断"), alsoRejected.getMessage());
        assertTrue(System.currentTimeMillis() - started < 1000L,
                "熔断期间的调用被派发出去了（耗时 " + (System.currentTimeMillis() - started) + "ms）");
        // 熔断态要能被用户看见：台账里那一行是它唯一的窗口
        assertTrue(invokeCommand("node", null).getOutput().contains("熔断：jira 熔断中"),
                invokeCommand("node", null).getOutput());
    }

    @Test
    @DisplayName("桥接插件自己的 /node 状态命令应报告脚本与熔断态")
    void statusCommand_should_renderLedger() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        String output = invokeCommand("node", null).getOutput();

        assertTrue(output.contains("Node：脚本 1 个"), output);
        assertTrue(output.contains("熔断：尚未发生调用"), output);
    }

    @Test
    @DisplayName("仓库里的示例脚本应真的可用（CI 由此守住示例不腐烂）")
    void examples_should_beUsable_endToEnd() throws IOException {
        installExample("hello");
        startRuntime();

        ToolCallResult result = invokeTool("hello_greet",
                Collections.<String, Object>singletonMap("name", "世界"));

        assertEquals("你好，世界！（会话 s-1）", result.getOutput());
        assertTrue(invokeCommand("hello", null).getOutput().contains("用法：/hello"));
        // 候选查询（二级选择页）是一条独立的只读路径。hello 用 hasOptions 让同一个函数回答
        // 两条路，两条路的区别只在 params.tokens 是不是 null：执行给真实值，候选查询给 null
        zcd.jellyfish.api.extension.CommandOptions helloOptions = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.CommandOptionRequest.class, "hello"),
                new zcd.jellyfish.api.extension.CommandOptionRequest("hello", "s-1"));
        assertEquals(2, helloOptions.getChoices().size(), helloOptions.toString());
    }

    @Test
    @DisplayName("示例的清单必须与实现一致（生成器守卫）")
    void dumpManifest_should_agreeWithExamples() throws IOException, InterruptedException {
        Path examples = examplesDirectory();
        Path dumper = gatewayDirectory().resolve("script").resolve("dump_manifest.js");
        assertTrue(Files.isRegularFile(dumper), "网关资源里没有清单生成器: " + dumper);

        for (String name : new String[] {"hello", "jira"}) {
            java.util.List<String> command = new java.util.ArrayList<String>();
            command.add(interpreter());
            command.add(dumper.toString());
            command.add(examples.resolve(name).toString());
            command.add("--check");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(30L, TimeUnit.SECONDS), "清单生成器没有在 30 秒内结束");
            assertEquals(0, process.exitValue(), "示例 " + name + " 的清单落后于实现: " + output);
        }
    }

    @Test
    @DisplayName("周期任务应按间隔反复触发（Node 侧与 Python 同构）")
    void periodic_should_fireRepeatedly() throws Exception {
        writeScript("beat", PERIODIC_SCRIPT, PERIODIC_MANIFEST);
        startRuntime();

        // 脚本侧的证据：共享模块里计数，工具把它读出来。等它涨到 2 以上才算「反复触发」
        // （只涨一次无法区分「周期任务」与「启动时打了一发」）
        long deadline = System.currentTimeMillis() + 15_000L;
        int seen = 0;
        while (System.currentTimeMillis() < deadline && seen < 2) {
            try {
                seen = Integer.parseInt(String.valueOf(invokeTool("beat_count",
                        Collections.<String, Object>emptyMap()).getOutput()).trim());
            } catch (JellyfishException | NumberFormatException ignored) {
                // worker 可能正在冷启动：重试即可，计数本身还在
            }
            if (seen < 2) {
                Thread.sleep(500L);
            }
        }
        assertTrue(seen >= 2, "Node 侧周期任务没有按间隔反复触发，实际触发 " + seen + " 次");
    }

    @Test
    @DisplayName("关闭运行时后不应残留网关与 worker 进程")
    void close_should_leaveNoProcesses() throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "PROJ-1"));
        Path directory = gatewayDirectory();
        assertTrue(countProcesses(directory) > 0, "调用之后应当有网关与 worker 在跑");

        manager.close();
        manager = null;

        assertTrue(awaitProcessCount(directory, 0, 20_000L), "关闭后仍残留进程");
    }

    // ------------------------------------------------------------ 运行时装配

    /**
     * 用默认超时启动插件。
     *
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime() throws IOException {
        startRuntime(5);
    }

    /**
     * 启动插件，并指定单次调用超时。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds) throws IOException {
        startRuntime(invokeTimeoutSeconds, Collections.<String, Map<String, Object>>emptyMap());
    }

    /**
     * 启动插件，并指定单次调用超时与逐脚本配置。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param scriptConfigs        逐脚本配置（脚本 id → 配置）
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds, Map<String, Map<String, Object>> scriptConfigs)
            throws IOException {
        installPlugin();
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        node.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        node.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        node.put(NodeBridgePlugin.KEY_INTERPRETER, interpreter());
        node.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        if (!scriptConfigs.isEmpty()) {
            node.put(ScriptBridgeConfig.KEY_SCRIPTS, scriptConfigs);
        }
        bootstrap(node);
    }

    /**
     * 启动插件，并指定熔断参数。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param failuresToOpen       连续失败几次后打开
     * @param cooldownSeconds      冷却秒数
     * @param roundsToPermanent    再失败几轮后转永久
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithCircuit(int invokeTimeoutSeconds, int failuresToOpen,
                                         int cooldownSeconds, int roundsToPermanent) throws IOException {
        installPlugin();
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN, Integer.valueOf(failuresToOpen));
        breaker.put(ScriptBridgeConfig.KEY_COOLDOWN_SECONDS, Integer.valueOf(cooldownSeconds));
        breaker.put(ScriptBridgeConfig.KEY_ROUNDS_TO_PERMANENT, Integer.valueOf(roundsToPermanent));
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        node.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        node.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        node.put(NodeBridgePlugin.KEY_INTERPRETER, interpreter());
        node.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        node.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);
        bootstrap(node);
    }

    /**
     * 装插件并 bootstrap。
     *
     * @param node 配置段
     */
    private void bootstrap(Map<String, Object> node) {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put(PLUGIN_ID, node);
        manager = new PF4JPluginManager(new PluginContextFactory(
                extensions, events, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class), new ShellIngress(new MetricsRegistry())),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations),
                events);
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    /**
     * 调用一个工具。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @return 调用结果
     */
    private ToolCallResult invokeTool(String toolName, Map<String, Object> arguments) {
        return extensions.invoke(extensions.handler(ToolCallRequest.class, toolName),
                new ToolCallRequest(toolName, arguments, "s-1"));
    }

    /**
     * 反复调用直到成功，用于容忍「worker 正在被替换」的短暂窗口。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @param timeoutMs 总等待上限
     * @return 在窗口内成功返回 {@code true}
     */
    private boolean awaitInvokeSucceeds(String toolName, Map<String, Object> arguments, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                invokeTool(toolName, arguments);
                return true;
            } catch (JellyfishException expected) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 调用一条命令。
     *
     * @param name 命令名
     * @param args 参数原文
     * @return 命令结果
     */
    private CommandResult invokeCommand(String name, String args) {
        java.util.List<String> tokens = args == null || args.trim().isEmpty()
                ? Collections.<String>emptyList()
                : java.util.Arrays.asList(args.trim().split("\\s+"));
        return extensions.invoke(extensions.handler(CommandRequest.class, name),
                new CommandRequest(name,
                        new zcd.jellyfish.api.extension.CommandArguments(tokens, args), "s-1"));
    }

    /**
     * 汇总全部 prompt 贡献的文本（按注册顺序拼接，与内核组装 system prompt 同法）。
     *
     * @return 文本
     */
    private String promptText() {
        PromptContributionRequest request = new PromptContributionRequest("s-1");
        StringBuilder builder = new StringBuilder();
        for (zcd.jellyfish.infra.extension.HandlerBinding<PromptContributionRequest,
                zcd.jellyfish.api.extension.PromptContribution> binding
                : extensions.bindings(PromptContributionRequest.class, null)) {
            zcd.jellyfish.api.extension.PromptContribution contribution =
                    extensions.invoke(binding.getHandler(), request);
            if (contribution != null && contribution.getText() != null) {
                builder.append(contribution.getText()).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 汇总全部状态栏贡献的文本。
     *
     * @return 文本
     */
    private String statusText() {
        zcd.jellyfish.api.extension.StatusLineContributionRequest request =
                new zcd.jellyfish.api.extension.StatusLineContributionRequest("s-1");
        StringBuilder builder = new StringBuilder();
        for (zcd.jellyfish.infra.extension.HandlerBinding<
                zcd.jellyfish.api.extension.StatusLineContributionRequest,
                zcd.jellyfish.api.extension.StatusLineContribution> binding
                : extensions.bindings(zcd.jellyfish.api.extension.StatusLineContributionRequest.class,
                        null)) {
            zcd.jellyfish.api.extension.StatusLineContribution contribution =
                    extensions.invoke(binding.getHandler(), request);
            if (contribution != null && contribution.getText() != null) {
                builder.append(contribution.getText()).append(' ');
            }
        }
        return builder.toString();
    }

    /**
     * 等状态栏出现某段文字。
     *
     * @param marker 待查找的文字
     * @return 等到了返回 {@code true}
     */
    private boolean awaitStatusLine(String marker) {
        // 开头这段停顿是用例正确性的一部分，而不是「等得久一点」：
        // 事件异步到达，且**只推给空闲 worker**（忙的按设计直接丢、不排队、不重试），
        // 而「读状态栏」这个动作本身就把 worker 占住了。发布之后立刻轮询，
        // 那一支推送很可能正撞在进行中的调用上而被丢掉——事件丢掉就是永久丢掉，
        // 轮询再久也看不到。这条教训在 Python 侧的同一个用例上实测过（三次里失败两次）
        try {
            Thread.sleep(500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (statusText().contains(marker)) {
                return true;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 夹具

    /**
     * 在脚本根目录下写一个脚本。
     *
     * @param id       脚本标识
     * @param source   入口源码
     * @param manifest 清单正文
     * @throws IOException 写入失败时抛出
     */
    private void writeScript(String id, String source, String manifest) throws IOException {
        Path directory = scriptsRoot.resolve(id);
        Files.createDirectories(directory);
        Files.write(directory.resolve("main.js"), source.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把仓库里的示例拷进临时脚本根目录。
     * <p>
     * 刻意不在示例目录里就地运行：`hello` 会往自己的目录写便签，而那是仓库。
     *
     * @param name 示例名
     * @throws IOException 复制失败时抛出
     */
    private void installExample(String name) throws IOException {
        Path source = examplesDirectory().resolve(name);
        assertTrue(Files.isDirectory(source), "示例目录不存在: " + source);
        Path target = scriptsRoot.resolve(name);
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> files = Files.list(source)) {
            for (Path file : files.toArray(Path[]::new)) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, target.resolve(file.getFileName().toString()));
                }
            }
        }
    }

    /**
     * 取示例脚本目录；没有配置时让用例跳过。
     * <p>
     * 刻意不做「向上找仓库根」的猜测：路径写错时用例应当立刻报错，而不是悄悄跳过。
     *
     * @return 示例目录
     */
    private static Path examplesDirectory() {
        String configured = System.getProperty("jellyfish.test.examples");
        assumeTrue(configured != null && !configured.trim().isEmpty(),
                "未配置 jellyfish.test.examples，跳过示例相关用例");
        return Paths.get(configured);
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 复制失败时抛出
     */
    private void installPlugin() throws IOException {
        Path directory = pluginsRoot.resolve(PLUGIN_ID);
        Files.createDirectories(directory);
        try (InputStream stream = NodeScriptIT.class.getResourceAsStream("/plugin.properties")) {
            assertNotNull(stream, "测试类路径上找不到 plugin.properties");
            Files.copy(stream, directory.resolve("plugin.properties"));
        }
    }

    /**
     * 取网关资源抽取目录（用它区分「本轮测试的进程」）。
     *
     * @return 目录
     */
    private Path gatewayDirectory() {
        return new GatewayResources(gatewayRoot)
                .materialize(new NodeLanguage(interpreter()), NodeLanguage.GATEWAY_RESOURCES);
    }

    /**
     * 数一数某个网关目录下还有几个进程（网关自己 + 它的 worker，二者的命令行相同）。
     *
     * @param directory 网关资源目录
     * @return 进程数
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int countProcesses(Path directory) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ps", "-eo", "command").redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        process.waitFor(5L, TimeUnit.SECONDS);
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.contains(directory.toString())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 等到进程数落到期望值。
     *
     * @param directory 网关资源目录
     * @param expected  期望进程数
     * @param timeoutMs 等待上限
     * @return 在超时前到达期望值返回 {@code true}
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean awaitProcessCount(Path directory, int expected, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countProcesses(directory) == expected) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    /**
     * 读完一个输入流。
     *
     * @param stream 输入流
     * @return 字节内容
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 判断本机是否有可用的解释器。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean interpreterAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder(interpreter(), "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            return finished && code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 取解释器路径，允许用系统属性覆盖（例如指向 nvm 里的某个版本）。
     *
     * @return 解释器路径或命令名
     */
    private static String interpreter() {
        return System.getProperty("jellyfish.test.node", NodeBridgePlugin.DEFAULT_INTERPRETER);
    }

    /** 夹具脚本：一个可读工具、一个必失败工具、一个必崩工具、一个卡死工具、一条命令与两个贡献。 */
    private static final String TOOL_SCRIPT = ""
            + "'use strict';\n"
            + "const { ScriptError, tool, command, contributes, subscribe } = require('jellyfish_sdk');\n"
            + "let completed = 0;\n"
            + "tool({ name: 'jira_issue', description: '读 issue',\n"
            + "       parameters: { key: { type: 'string' } }, required: ['key'] },\n"
            + "     (params, ctx) => {\n"
            + "         const key = params.args.key;\n"
            + "         if (!key) { throw new ScriptError('缺少参数 key'); }\n"
            + "         return `issue ${key} 处于 OPEN（会话 ${ctx.sessionId}）`;\n"
            + "     });\n"
            + "tool({ name: 'jira_boom', description: '内部异常' }, () => { throw new TypeError('不是函数'); });\n"
            + "tool({ name: 'jira_hang', description: '卡死 60 秒（同步忙等，收不到 SIGTERM）' },\n"
            + "     () => { const until = Date.now() + 60000; while (Date.now() < until) { /* 忙等 */ } });\n"
            + "command({ name: 'jira', summary: '操作 Jira' },\n"
            + "        (params) => `命令 ${params.tokens.join('|')}（raw=${params.raw}）`);\n"
            + "contributes('prompt', () => 'Node 示例脚本可用。');\n"
            + "contributes('status_line', () => `收到 ${completed} 次工具结束`);\n"
            + "subscribe('ToolCallCompletedEvent')(() => { completed += 1; });\n";

    /**
     * 周期任务夹具：``@periodic`` 每次触发把模块级计数加一，工具把计数读出来。
     * <p>
     * 两者共享同一个 worker 进程里的模块状态，因此工具读到的数就是「真的被触发了几次」——
     * 这是从外部观察周期任务唯一可靠的方式（它的返回值被宿主丢弃，也没有调用方）。
     */
    private static final String PERIODIC_SCRIPT = ""
            + "'use strict';\n"
            + "const { periodic, tool } = require('jellyfish_sdk');\n"
            + "let ticks = 0;\n"
            + "periodic({ name: 'beat', intervalSeconds: 1 }, () => { ticks += 1; });\n"
            + "tool({ name: 'beat_count', description: '读出周期任务已触发的次数' },\n"
            + "     () => String(ticks));\n";

    /** 与 {@link #PERIODIC_SCRIPT} 逐字对应的清单。 */
    private static final String PERIODIC_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"beat_count\"}],"
            + "\"schedules\":[{\"name\":\"beat\",\"intervalSeconds\":1}]}";

    /** 夹具脚本的清单：名字集合必须与上面的声明一致，否则脚本会拒绝服务。 */
    private static final String FULL_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"jira_issue\",\"description\":\"读 issue\"},"
            + "{\"name\":\"jira_boom\"},{\"name\":\"jira_hang\"}],"
            + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"}}],"
            + "\"contributions\":[\"prompt\",\"status_line\"],"
            + "\"events\":[\"ToolCallCompletedEvent\"]}";

    /**
     * 配置与异步夹具：一个 async 工具读配置，一个 async 事件处理器累加计数。
     * <p>
     * 两件事必须一起测：它们在现场是同一类需求（调用外部 API），而「配置能不能读到」与
     * 「handler 能不能 await」是两个独立的失败点。
     */
    private static final String ASYNC_CONFIG_SCRIPT = ""
            + "'use strict';\n"
            + "const { tool, configuration, subscribe } = require('jellyfish_sdk');\n"
            + "let handled = 0;\n"
            + "tool({ name: 'web_probe', description: '读配置' },\n"
            + "     async (params, ctx) => {\n"
            + "         await new Promise((resolve) => setTimeout(resolve, 20));\n"
            + "         return `provider=${ctx.configuration.provider}; key=${ctx.configuration.apiKey};"
            + " module=${configuration().provider}`;\n"
            + "     });\n"
            + "tool({ name: 'web_events', description: '事件计数' }, () => `handled=${handled}`);\n"
            + "subscribe('ToolCallCompletedEvent')(async () => {\n"
            + "    await new Promise((resolve) => setTimeout(resolve, 10));\n"
            + "    handled += 1;\n"
            + "});\n";

    /** 与 {@link #ASYNC_CONFIG_SCRIPT} 逐字对应的清单。 */
    private static final String ASYNC_CONFIG_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"web_probe\"},{\"name\":\"web_events\"}],"
            + "\"events\":[\"ToolCallCompletedEvent\"]}";

    /** 返回 {@code ToolResult} 的脚本：摘要里带上调用者身份，一次验证两件事。 */
    private static final String METADATA_SCRIPT = ""
            + "'use strict';\n"
            + "const { tool, ToolResult } = require('jellyfish_sdk');\n"
            + "tool({ name: 'web_meta', description: '带元数据的工具' }, (params, ctx) =>\n"
            + "    new ToolResult('正文', {\n"
            + "        summary: `parent=${ctx.parentSessionId} run=${ctx.runId} root=${ctx.rootRunId}`,\n"
            + "    }));\n";

    /** 与 {@link #METADATA_SCRIPT} 逐字对应的清单。 */
    private static final String METADATA_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"web_meta\"}]}";

    /**
     * 边打日志边返回结果的脚本：两行 `console.log` 都是**合法 JSON**（最容易被误当成协议帧的形状）。
     * <p>
     * 它对应的缺陷是：网关此前不看应答帧的 id，于是这两行里的第一行会被当成在途请求的应答。
     */
    private static final String NOISY_STDOUT_SCRIPT = ""
            + "'use strict';\n"
            + "const { tool } = require('jellyfish_sdk');\n"
            + "tool({ name: 'web_noisy_a', description: '会打日志的工具' }, () => {\n"
            + "    console.log(JSON.stringify({ level: 'info' }));\n"
            + "    return 'first-real';\n"
            + "});\n"
            + "tool({ name: 'web_noisy_b', description: '也会打日志的工具' }, () => {\n"
            + "    console.log(JSON.stringify([1, 2]));\n"
            + "    return 'second-real';\n"
            + "});\n";

    /** 与 {@link #NOISY_STDOUT_SCRIPT} 逐字对应的清单。 */
    private static final String NOISY_STDOUT_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"web_noisy_a\"},{\"name\":\"web_noisy_b\"}]}";

    /** 返回一个大结果的脚本：用来钉住「帧比宿主按行缓冲的上限大」时会发生什么。 */
    private static final String LARGE_SCRIPT = ""
            + "'use strict';\n"
            + "const { tool } = require('jellyfish_sdk');\n"
            + "tool({ name: 'large_probe', description: '返回一个很大的结果' },\n"
            + "     () => 'x'.repeat(200000));\n";

    /** 与 {@link #LARGE_SCRIPT} 逐字对应的清单。 */
    private static final String LARGE_MANIFEST = "{\"entry\":\"main.js\","
            + "\"tools\":[{\"name\":\"large_probe\"}]}";

    /** 路由处理器夹具：一个类型级贡献 + 一个 {@code handler}（路由键来自用户配置）。 */
    private static final String HANDLER_SCRIPT = ""
            + "'use strict';\n"
            + "const { contributes, handler } = require('jellyfish_sdk');\n"
            + "contributes('turn_context', () => 'now: 2026-10-04');\n"
            + "handler({ type: 'model_catalog', route: 'local' },\n"
            + "    () => [{ id: 'local-model', contextLength: 8192 }]);\n";

    /** 与 {@link #HANDLER_SCRIPT} 逐字对应的清单。 */
    private static final String HANDLER_MANIFEST = "{\"entry\":\"main.js\","
            + "\"contributions\":[\"turn_context\"],"
            + "\"handlers\":[{\"type\":\"model_catalog\",\"route\":\"local\"}]}";

    /** 三期夹具：四个类型级贡献 + 一个带标记路由的 {@code input_directive}。 */
    private static final String THIRD_WAVE_SCRIPT = ""
            + "'use strict';\n"
            + "const { contributes, handler } = require('jellyfish_sdk');\n"
            + "contributes('turn_before', () => ({ input: '改写后的输入' }));\n"
            + "contributes('input_transform', () => ({ handled: true, notice: '已接过去' }));\n"
            + "contributes('request_tuning', () => ({ cacheBreakpoints: 1 }));\n"
            + "contributes('tool_activation',\n"
            + "    (params) => (params.toolName === 'bad' ? 'service down' : null));\n"
            + "handler({ type: 'input_directive', route: '!' },\n"
            + "    (params) => ({ toolName: 'shell', arguments: { command: params.input } }));\n";

    /** 与 {@link #THIRD_WAVE_SCRIPT} 逐字对应的清单。 */
    private static final String THIRD_WAVE_MANIFEST = "{\"entry\":\"main.js\","
            + "\"contributions\":[\"turn_before\",\"input_transform\",\"request_tuning\",\"tool_activation\"],"
            + "\"handlers\":[{\"type\":\"input_directive\",\"route\":\"!\"}]}";
}
