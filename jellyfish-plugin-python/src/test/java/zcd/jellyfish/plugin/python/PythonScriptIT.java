package zcd.jellyfish.plugin.python;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.extension.CompactionDirective;
import zcd.jellyfish.api.extension.CompactionPreRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.api.extension.ModelCatalogRequest;
import zcd.jellyfish.api.extension.ModelCatalogResult;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.api.extension.SessionBeforeForkRequest;
import zcd.jellyfish.api.extension.ToolArgumentDecision;
import zcd.jellyfish.api.extension.ToolArgumentPreRequest;
import zcd.jellyfish.api.extension.ToolResultAdjustment;
import zcd.jellyfish.api.extension.ToolResultPostRequest;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.extension.AgingStrategyRequest;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.api.extension.RequestTuningRequest;
import zcd.jellyfish.api.extension.ToolActivationRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.TurnBeforeRequest;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.script.ScriptBridgeConfig;
import zcd.jellyfish.script.ScriptScheduler;
import zcd.jellyfish.script.protocol.ScriptCancelledException;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Python 的端到端测试：注册 → 调用 → 超时隔离 → 关闭。
 * <p>
 * <b>为什么不进 {@code mvn test}</b>：它需要一个真实的解释器，而 AGENTS.md 规定单元测试不访问
 * 外部资源。因此它命名为 {@code *IT}（Surefire 默认的包含规则只认 {@code *Test}），
 * 由 {@code -Pscript-it} profile 显式启用。这一步提前到 P4 落地，是因为网关、worker 与 SDK
 * 都是「没有测试守着就会悄悄坏掉」的代码——它们唯一能被验证的方式就是真的跑一次。
 * <p>
 * 机器上没装解释器时整类跳过（{@code assumeTrue}），而不是失败：环境缺失与代码有 bug 是两件事。
 *
 * @author zcd
 */
@DisplayName("Python 脚本端到端")
class PythonScriptIT {

    /** 解释器探测超时。 */
    private static final long PROBE_TIMEOUT_SECONDS = 5L;

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
    void requirePython() {
        assumeTrue(interpreterAvailable(), "本机没有可用的 python3，跳过端到端测试");
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
        // 事件通道要显式关闭：它是自己的线程池，留着会让下一个用例看到上一轮的通知
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
    @DisplayName("scripts.<id> 应按脚本 id 送达：ctx.configuration 与模块级 configuration() 都读得到")
    void scriptConfiguration_should_reachScript_when_configured() throws IOException {
        writeScript("web", CONFIG_SCRIPT, CONFIG_MANIFEST);
        Map<String, Object> web = new LinkedHashMap<String, Object>();
        web.put("provider", "brave");
        web.put("apiKey", "k-123");
        Map<String, Map<String, Object>> scripts = new LinkedHashMap<String, Map<String, Object>>();
        scripts.put("web", web);
        startRuntime(5, scripts);

        ToolCallResult result = invokeTool("web_probe", Collections.<String, Object>emptyMap());

        assertEquals("provider=brave; key=k-123; module=brave", result.getOutput());
    }

    @Test
    @DisplayName("没配 scripts 的脚本读到空配置，而不是报错")
    void scriptConfiguration_should_beEmpty_when_notConfigured() throws IOException {
        writeScript("web", CONFIG_SCRIPT, CONFIG_MANIFEST);
        startRuntime();

        ToolCallResult result = invokeTool("web_probe", Collections.<String, Object>emptyMap());

        assertEquals("provider=None; key=None; module=None", result.getOutput());
    }

    @Test
    @DisplayName("脚本抛出的异常应变成可读的调用失败，而不是静默的空结果")
    void tool_should_failWithMessage_when_scriptRaises() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("缺少参数 key"), failure.getMessage());
    }

    @Test
    @DisplayName("取消令牌触发时，在途脚本调用应立即失败，而不是等满超时")
    void cancellation_should_abortInflightCall() throws Exception {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        // 超时给足：失败必须来自取消，而不是超时
        startRuntime(60);
        // 先把网关拉起来（懒启动），否则取消回调会在冷启动窗口里的另一条路径上
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "PROJ-1"));

        AtomicReference<Runnable> canceller = new AtomicReference<Runnable>();
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable callback) {
                canceller.set(callback);
            }
        };
        ToolCallRequest request = new ToolCallRequest("jira_hang", Collections.<String, Object>emptyMap(),
                "s-1", token, ToolOutputSink.NOOP, null, null, null);

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        long started = System.currentTimeMillis();
        Thread call = new Thread(() -> {
            try {
                extensions.invoke(extensions.handler(ToolCallRequest.class, "jira_hang"), request);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "cancel-it-call");
        call.start();

        long deadline = System.currentTimeMillis() + 10_000L;
        while (canceller.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertNotNull(canceller.get(), "取消回调未被注册，说明令牌没被传到网关");
        canceller.get().run();
        call.join(15_000L);
        long elapsed = System.currentTimeMillis() - started;

        assertNotNull(failure.get(), "取消后调用仍未返回");
        assertTrue(failure.get() instanceof ScriptCancelledException
                        || String.valueOf(failure.get().getMessage()).contains("已取消"),
                String.valueOf(failure.get()));
        assertTrue(elapsed < 20_000L, "取消后耗时过长: " + elapsed + "ms");
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
    @DisplayName("没返回 ToolResult 的工具元数据为空，行为与引入之前一致")
    void toolResult_should_beEmpty_when_scriptReturnsPlainValue() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "PROJ-1"));

        assertTrue(result.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("二期扩展点应能被脚本声明并真实调用（含 routed handler）")
    void secondWaveExtensions_should_beInvoked_endToEnd() throws IOException {
        writeScript("ext", SECOND_WAVE_SCRIPT, SECOND_WAVE_MANIFEST);
        startRuntime();

        assertEquals("now: 2026-10-04", extensions.invoke(
                extensions.handler(TurnContextRequest.class, null),
                new TurnContextRequest("s-1", "hi", false)).getText());

        ToolArgumentDecision decision = extensions.invoke(
                extensions.handler(ToolArgumentPreRequest.class, null),
                new ToolArgumentPreRequest("coder", "read_file", Collections.<String, Object>emptyMap(),
                        ToolArgumentPreRequest.Source.MODEL, "s-1"));
        assertTrue(decision.isReplace());
        assertEquals("/tmp/read_file", decision.getArguments().get("path"));

        ToolResultAdjustment adjustment = extensions.invoke(
                extensions.handler(ToolResultPostRequest.class, null),
                new ToolResultPostRequest("coder", "read_file", Collections.<String, Object>emptyMap(),
                        "body", Collections.<String, Object>emptyMap(), false, "s-1"));
        assertTrue(adjustment.hasMetadata());
        assertEquals("已脱敏", adjustment.getMetadata().get("summary"));

        assertTrue(extensions.invoke(extensions.handler(SessionBeforeCloseRequest.class, null),
                new SessionBeforeCloseRequest("s-1", "coder",
                        SessionBeforeCloseRequest.Reason.USER_REQUEST)).isCancelled());

        assertTrue(extensions.invoke(extensions.handler(SessionBeforeForkRequest.class, null),
                new SessionBeforeForkRequest("s-1", "coder", "m-1", 0, 1)).isCancelled());

        CompactionDirective directive = extensions.invoke(
                extensions.handler(CompactionPreRequest.class, null),
                new CompactionPreRequest("s-1", CompactionTrigger.MANUAL, 10, 1000, 5, null));
        assertEquals(3, directive.getKeepRecent().intValue());

        ModelCatalogResult catalog = extensions.invoke(
                extensions.handler(ModelCatalogRequest.class, "local"),
                new ModelCatalogRequest("local", "openai"));
        assertEquals(1, catalog.getModels().size());
        assertEquals("local-model", catalog.getModels().get(0).getId());
        assertEquals(8192, catalog.getModels().get(0).getContextLength());
    }

    @Test
    @DisplayName("三期扩展点应能被脚本声明并真实调用（含 input_directive 的标记路由）")
    void thirdWaveExtensions_should_beInvoked_endToEnd() throws IOException {
        writeScript("ext", THIRD_WAVE_SCRIPT, THIRD_WAVE_MANIFEST);
        startRuntime();

        // 先暖网关（热路径点不冷启动）
        assertTrue(extensions.invoke(extensions.handler(TurnBeforeRequest.class, null),
                new TurnBeforeRequest("s-1", "coder", "干活", false, 0)).hasInput());

        assertTrue(extensions.invoke(extensions.handler(ToolActivationRequest.class, null),
                new ToolActivationRequest("s-1", "coder", new ToolDescriptor("bad", "坏工具")))
                .isHidden());
        assertFalse(extensions.invoke(extensions.handler(ToolActivationRequest.class, null),
                new ToolActivationRequest("s-1", "coder", new ToolDescriptor("good", "好工具")))
                .isDecided());

        assertEquals(1, extensions.invoke(extensions.handler(RequestTuningRequest.class, null),
                new RequestTuningRequest("s-1", "openai", "m", "k", 1, 1)).getCacheBreakpoints()
                .intValue());
        assertEquals(30, extensions.invoke(extensions.handler(AgingStrategyRequest.class, null),
                new AgingStrategyRequest("s-1", 1, 1, 1, 1, 1, 1)).getAgingPercent().intValue());

        InputTransformResult transformed = extensions.invoke(
                extensions.handler(InputTransformRequest.class, null),
                new InputTransformRequest("s-1", "!ls", InputTransformRequest.Source.CLI, true));
        assertTrue(transformed.isHandled());
        assertEquals("已接过去", transformed.getNotice());

        InputDirectiveResult directive = extensions.invoke(
                extensions.handler(InputDirectiveRequest.class, "!"),
                new InputDirectiveRequest("!", "ls -la", "s-1"));
        assertTrue(directive.isToolCall());
        assertEquals("shell", directive.getToolName());
        assertEquals("ls -la", directive.getArguments().get("command"));
    }

    @Test
    @DisplayName("热路径点在 worker 未热时按「不表态」处理，不冷启动")
    void hotPath_should_notColdStart() throws IOException {
        writeScript("ext", THIRD_WAVE_SCRIPT, THIRD_WAVE_MANIFEST);
        startRuntime();

        // 未做任何调用 → 网关没起来 → 热路径点应直接返回「不表态」
        assertNull(extensions.invoke(extensions.handler(RequestTuningRequest.class, null),
                new RequestTuningRequest("s-1", "openai", "m", "k", 1, 1)).getCacheBreakpoints());

        // 调一个非热路径的点把 worker 拉起来，再问一次——这次应该拿到脚本的答案
        assertTrue(extensions.invoke(extensions.handler(TurnBeforeRequest.class, null),
                new TurnBeforeRequest("s-1", "coder", "干活", false, 0)).hasInput());
        assertEquals(1, extensions.invoke(extensions.handler(RequestTuningRequest.class, null),
                new RequestTuningRequest("s-1", "openai", "m", "k", 1, 1)).getCacheBreakpoints()
                .intValue());
    }

    @Test
    @DisplayName("类型级贡献应能按类型调用")
    void contribution_should_beInvoked_byType() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        zcd.jellyfish.api.extension.PromptContribution contribution =
                extensions.invoke(extensions.handler(PromptContributionRequest.class, null),
                        new PromptContributionRequest("s-1"));

        assertEquals("Jira：会话 s-1 有 3 个未读", contribution.getText());
    }

    @Test
    @DisplayName("命令应被真实执行，结果带命令名与输出")
    void command_should_beInvoked_endToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "jira"),
                new CommandRequest("jira", null, "s-1"));

        assertEquals("已切换 PROJ-1", result.getOutput());
    }

    @Test
    @DisplayName("卡死的脚本应在超时后被隔离，且下一次调用能重新拉起")
    void tool_should_beIsolatedAfterTimeout_thenRecover() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        // 卡死的调用必须在超时后失败——而不是永远挂着（那会把 ReAct 回合也一起拖住）。
        // 两侧的超时值相同，因此**谁先发现就由谁报**：宿主可能报本地超时，
        // 也可能先收到网关的「已隔离」。这个不确定性是设计使然（而不是测试写得松），
        // 它会在 P5 落地「宿主超时 → 主动 kill_worker」之后收敛成单一来源
        long started = System.currentTimeMillis();
        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));
        assertTrue(System.currentTimeMillis() - started >= 2000, "不应早于配置的超时返回");
        boolean timeoutReported = failure instanceof zcd.jellyfish.script.protocol.ScriptTimeoutException
                || failure.getMessage().contains("隔离");
        assertTrue(timeoutReported, failure.getMessage());

        // 隔离只针对该脚本的那一代 worker：重新拉起之后调用应当恢复。
        // 这里给几秒重试窗口而不是立刻断言，因为「隔离」是两段式的：请求先被拒绝，
        // 卡死的 worker 随后被终止（它可能正卡在不响应信号的系统调用里），
        // 新 worker 就绪之前的那一小段时间里调用仍会失败——这是**已实现行为**，不是抖动
        ToolCallResult recovered = invokeUntilSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "X"), 15_000L);
        assertEquals("issue X 处于 OPEN（会话 s-1）", recovered.getOutput());
    }

    @Test
    @DisplayName("清单与实现不一致时脚本应拒绝服务，且错误可归因为清单问题")
    void script_should_refuseService_when_manifestLies() throws IOException {
        // 清单里多声明了一个代码没有的工具：这正是「模型按不存在的定义去调用」的成因
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST.replace(
                "\"tools\":[", "\"tools\":[{\"name\":\"jira_nonexistent\"},"));
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_nonexistent", Collections.<String, Object>emptyMap()));

        // 错误必须点名「哪个脚本、差在哪一项」：只说「初始化失败」等于把定位工作推回给用户，
        // 而这两条信息此刻正好都在网关手里
        assertTrue(failure.getMessage().contains("jira_nonexistent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tools"), failure.getMessage());
    }

    @Test
    @DisplayName("has_options 的签名看不出两条路时应在启动期拒绝，并给出最小可用写法")
    void script_should_refuseService_when_optionsSignatureIsAmbiguous() throws IOException {
        writeScript("badopt", AMBIGUOUS_OPTIONS_SCRIPT, AMBIGUOUS_OPTIONS_MANIFEST);
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(JellyfishException.class,
                () -> invokeTool("badopt_ping", Collections.<String, Object>emptyMap()));

        // 关键在「什么时候报」：这条路径原先只在用户按下补全键的那一刻才炸，报出来的是一个
        // 关于参数个数的 TypeError，与「候选查询」这件事看不出关系。现在它在脚本启动时就拒绝，
        // 而消息里带着可照抄的写法——这类错误的现场离写法太远了
        assertTrue(failure.getMessage().contains("has_options"), failure.getMessage());
        assertTrue(failure.getMessage().contains("@command_options"), failure.getMessage());
    }

    @Test
    @DisplayName("状态命令应列出脚本与已登记能力")
    void statusCommand_should_listScripts() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null));

        assertTrue(result.getOutput().contains("脚本 1 个"), result.getOutput());
        assertTrue(result.getOutput().contains("运行时"), result.getOutput());
    }


    @Test
    @DisplayName("周期任务应按间隔反复触发，并在每次成功后由桥接层代发界面失效")
    void periodic_should_fireRepeatedly_and_invalidateUi() throws IOException {
        writeScript("beat", PERIODIC_SCRIPT, PERIODIC_MANIFEST);
        java.util.List<zcd.jellyfish.api.event.notification.UiInvalidatedEvent> invalidated =
                Collections.synchronizedList(new java.util.ArrayList<>());
        events.subscribe("it-periodic", zcd.jellyfish.api.event.notification.UiInvalidatedEvent.class,
                invalidated::add);

        startRuntime();

        // 两次以上才算「反复」：只跳一次无法区分「周期任务」与「启动时打了一发」
        assertTrue(awaitEventLog("beat", "tick:1") && awaitEventLog("beat", "tick:2"),
                "周期任务没有按间隔反复触发，实际记录: " + readEventLog("beat"));

        long deadline = System.currentTimeMillis() + 10_000L;
        while (invalidated.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(100L);
        }
        // 脚本发不了这个事件（可发布事件白名单里没有它），因此它必须来自桥接层的代发。
        // 这是「定时刷到的新数据能画上屏」的全部通路，缺了它面板永远不会自己重画
        assertFalse(invalidated.isEmpty(), "周期任务成功后应代发一次界面失效事件");
    }

    @Test
    @DisplayName("周期任务的间隔可被 scripts.<id>.schedules.<name>.intervalSeconds 覆写")
    void periodic_should_honourIntervalOverride_fromScriptConfiguration() throws IOException {
        // 清单里声明 60 秒（用例窗口内绝不可能触发），配置段覆写成 1 秒：
        // 只有覆写真的生效，这个用例才会观察到跳动
        writeScript("beat", PERIODIC_SCRIPT, PERIODIC_MANIFEST_SLOW);
        Map<String, Object> schedules = new LinkedHashMap<String, Object>();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put(ScriptScheduler.KEY_INTERVAL, Integer.valueOf(1));
        schedules.put("beat", entry);
        Map<String, Object> beatConfig = new LinkedHashMap<String, Object>();
        beatConfig.put(ScriptScheduler.KEY_SCHEDULES, schedules);
        Map<String, Map<String, Object>> scriptConfigs =
                new LinkedHashMap<String, Map<String, Object>>();
        scriptConfigs.put("beat", beatConfig);

        startRuntime(5, scriptConfigs);

        assertTrue(awaitEventLog("beat", "tick:1"),
                "覆写的间隔没有生效（清单里是 60 秒，实际记录: " + readEventLog("beat") + "）");
    }

    @Test
    @DisplayName("空闲的 worker 应自行退场，进程数回到只剩网关")
    void worker_should_selfDestruct_when_idle() throws IOException, InterruptedException {
        // 「空闲时进程数回零」是本方案明确承诺的性质（懒启动 + 自毁），而它依赖 worker 与网关
        // 两侧各自的周期检查。这里用操作系统的进程表直接验证，而不是看日志——
        // 后者只能证明「它说要退场」，前者才能证明「它真的退了」
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntimeWithIdle(1);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        assertTrue(awaitProcessCount(gatewayDirectory, 2), "调用之后应有一个 worker 在跑");
        assertTrue(awaitProcessCount(gatewayDirectory, 1),
                "空闲超过配置时长后 worker 应自行退场，实际仍有 "
                        + countProcesses(gatewayDirectory) + " 个进程");
    }


    @Test
    @DisplayName("熔断只影响出问题的脚本，且不摘注册：同语言其它脚本照常工作")
    void circuit_should_isolateOnlyTheBrokenScript() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        writeScript("git", GIT_SCRIPT, GIT_MANIFEST);
        // 一次超时就打开、冷却 1 秒：把「熔断 — 拒绝 — 冷却 — 恢复」压缩到用例能跑的时长里
        startRuntimeWithCircuit(2, 1, 1, 5);

        assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));

        // 同一个网关、同一个语言下的另一个脚本完全不受影响——这正是「每脚本一 worker」的直接收益
        ToolCallResult other = invokeTool("git_status", Collections.<String, Object>emptyMap());
        assertEquals("main 干净", other.getOutput());

        // 熔断不摘注册：工具还在清单里，但调用被**立即**拒绝，且文案带剩余时间。
        // 「立即」是这条断言的全部价值——同一个工具在没有熔断时会正常返回，
        // 因此耗时能证明它是被拒绝的，而不是被执行的
        long started = System.currentTimeMillis();
        JellyfishException refusal = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "X")));
        assertTrue(System.currentTimeMillis() - started < 1000,
                "拒绝应发生在派发之前，而不是等一个超时");
        assertTrue(refusal.getMessage().contains("熔断"), refusal.getMessage());

        String status = statusCommand();
        // 台账按脚本名排序，而这两个名字的先后是 `git` 在前——因此断言的是片段而不是整行
        assertTrue(status.contains("jira 熔断中"), status);
        assertTrue(status.contains("git 正常"), status);
    }

    @Test
    @DisplayName("冷却到期后应自动半开并恢复，全程不需要 /reload")
    void circuit_should_recoverAutomatically_afterCooldown() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntimeWithCircuit(2, 1, 1, 5);

        assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));

        // 「自动恢复」是这个机制存在的理由：摘注册的代价就是恢复必须走 /reload，
        // 而这里什么都不做，只是等过冷却
        ToolCallResult recovered = invokeUntilSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "X"), 20_000L);

        assertEquals("issue X 处于 OPEN（会话 s-1）", recovered.getOutput());
        assertTrue(statusCommand().contains("jira 正常"), statusCommand());
    }


    @Test
    @DisplayName("超时配 0 表示不超时：调用应正常完成，而不是被当成立即超时秒杀")
    void invokeTimeout_should_meanNoTimeout_whenConfiguredAsZero() throws IOException {
        // 这个用例钉的是一处**字面意思与实现相反**的缺陷：网关最初把 0 当成截止时间，
        // 于是 `now > deadline` 恒真，任何调用都在派发的下一拍被隔离——
        // 现象是「脚本永远跑不出结果」，而用户从配置里读到的却是「不超时」
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(0);

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "K"));

        assertEquals("issue K 处于 OPEN（会话 s-1）", result.getOutput());
    }

    @Test
    @DisplayName("网关被强杀后，卡在用户代码里的 worker 也应自行退出（不留孤儿）")
    void worker_should_notSurvive_when_gatewayIsKilledFiercely()
            throws IOException, InterruptedException {
        // 这是最难清理的一种孤儿：worker 卡在用户代码里，既读不到协议套接字的 EOF，
        // 也轮不到事件循环里的「父进程没了」检查。它只能靠 worker 侧的定时器看门狗发现，
        // 因此这里用操作系统的进程表直接断言——先让它真的卡住，再强杀网关
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntime(30);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));
        assertTrue(awaitProcessCount(gatewayDirectory, 2), "应先有一个 worker 在跑");

        // 让脚本卡死：它会在 time.sleep 里待十分钟，期间不会回到事件循环
        Thread hanging = new Thread(() -> {
            try {
                invokeTool("jira_hang", Collections.<String, Object>emptyMap());
            } catch (JellyfishException expected) {
                // 网关会被杀，这次调用必然失败；这里只关心进程是否残留
            }
        });
        hanging.setDaemon(true);
        hanging.start();
        Thread.sleep(1000L);

        Process kill = new ProcessBuilder("pkill", "-9", "-f", gatewayDirectory.toString()).start();
        assertEquals(0, kill.waitFor(), "强杀网关的命令应成功");

        assertTrue(awaitProcessCount(gatewayDirectory, 0),
                "网关被强杀后不该留下任何进程，实际仍有 "
                        + countProcesses(gatewayDirectory) + " 个");
        hanging.join(5000L);
    }

    @Test
    @DisplayName("被隔离的 worker 还在两段式关闭里时换了新一代：不能把它就地丢掉（那会成为永久孤儿）")
    void killedWorker_should_stillBeForceKilled_whenNewWorkerTakesOver()
            throws IOException, InterruptedException {
        // 缺陷现场：网关在「再派一次调用」时会把上一代 worker 的句柄就地丢掉，
        // 而两段式关闭的第二段（SIGKILL）正是靠那个句柄发的。这里用一个**自己接管了 SIGTERM**
        // 的脚本把第一段彻底堵死，于是「第二段会不会送到」成了唯一能救它的东西。
        // 两件事要在同一次运行里发生：先卡死一次（进入隔离），再立刻调一次（网关换一代 worker）
        writeScript("stubborn", STUBBORN_SCRIPT, STUBBORN_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntime(2);
        Map<String, Object> noArguments = Collections.<String, Object>emptyMap();

        assertThrows(JellyfishException.class, () -> invokeTool("stubborn_hang", noArguments));
        // 紧接着再调一次：上一代此刻正处在「SIGTERM 已发、SIGKILL 未发」的宽限期里
        assertThrows(JellyfishException.class, () -> invokeTool("stubborn_hang", noArguments));

        // 期望 1 个进程（网关自己）：那两个 sleep(600) 都该被 SIGKILL 收掉，
        // 而网关是设计成可空闲十分钟的长命进程，它留下是对的
        assertTrue(awaitProcessCount(gatewayDirectory, 1),
                "被隔离的 worker 成了永久孤儿（进程数 " + countProcesses(gatewayDirectory) + "）");
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

        assertEquals(200000, String.valueOf(result.getOutput()).length(),
                "大结果没有完整送达（拿到的长度是 " + String.valueOf(result.getOutput()).length() + "）");
    }

    @Test
    @DisplayName("宿主按行的缓冲上限必须严格大于脚本侧的出帧上限（两个数字，互相不能悄悄改）")
    void hostLineLimit_should_beGreaterThanScriptFrameLimit() throws IOException {
        // 这条守卫存在的理由：这是**跨语言的两个数字**，而它们的关系（宿主 > 脚本）是硬要求。
        // 任何一侧单独改了数，另一侧不会编译失败、不会测试变红，只会在大结果上表现成「超时」。
        // 因此这里把关系本身钉死：从脚本侧的源码里读那个数，与宿主侧那个数比大小
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        String source = new String(Files.readAllBytes(
                gatewayDirectory.resolve("script").resolve("script_wire.py")),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("MAX_FRAME_BYTES\\s*=\\s*(\\d+)").matcher(source);
        assertTrue(matcher.find(), "没在 script_wire.py 里找到 MAX_FRAME_BYTES 的字面量");
        long scriptLimit = Long.parseLong(matcher.group(1));
        assertTrue(zcd.jellyfish.script.protocol.ScriptProtocol.MAX_LINE_BYTES > scriptLimit,
                "宿主按行缓冲的上限(" + zcd.jellyfish.script.protocol.ScriptProtocol.MAX_LINE_BYTES
                        + ") 必须严格大于脚本侧出帧上限(" + scriptLimit
                        + ")：否则大结果会被宿主切成两条非法行，现场只看到「超时」");
    }


    @Test
    @DisplayName("PID 文件应记下网关自己的 PID，并在正常退出时删掉")
    void pidFile_should_recordGatewayPid_andRemoveIt_whenGatewayExits() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        int pid = readPid(pidFile());
        assertTrue(pid > 0, "PID 文件里应是一个正整数，实际为 " + pid);
        assertTrue(statusCommand().contains("网关 PID " + pid), statusCommand());

        // 正常退出（宿主关闭网关）时必须删掉：留着它会让下一次启动把一个还活着的 PID 报成遗留
        manager.close();
        manager = null;
        assertFalse(Files.exists(pidFile()), "正常退出后 PID 文件不应残留");
    }

    @Test
    @DisplayName("启动时发现的遗留 PID 只报告不处置：文件里那个进程必须活着")
    void stalePidFile_should_beReportedWithoutKilling_theRecordedProcess()
            throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        // 造一个「上一任网关」：用 sh 写下自己的 PID 再 exec 成 sleep，
        // 这样一个真实存活的进程就占了那个 PID，且它不属于本 JVM（不会被任何回收路径带走）
        Files.createDirectories(pidRoot);
        Path marker = pidRoot.resolve("sleeper.pid");
        Process sleeper = new ProcessBuilder("sh", "-c", "echo $$ > " + marker + "; exec sleep 120")
                .start();
        try {
            int stalePid = awaitPid(marker, 5000L);
            assertTrue(sleeper.isAlive(), "造出来的「上一任网关」应先真的活着");
            Files.write(pidFile(), (stalePid + "\n").getBytes(StandardCharsets.UTF_8));

            startRuntime();
            invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

            // 「只报告」：报告必须能被人看见（台账输出），而不是只进日志
            String status = statusCommand();
            assertTrue(status.contains("PID " + stalePid + "（仍存活）"), status);
            // 「不处置」：那个进程必须原封不动地活着，且文件已被本代网关接管
            assertTrue(sleeper.isAlive(), "遗留 PID 文件里的进程被误杀了");
            assertTrue(readPid(pidFile()) != stalePid, "PID 文件应被本代网关接管");
        } finally {
            sleeper.destroyForcibly();
            if (!sleeper.waitFor(10, TimeUnit.SECONDS) && Files.exists(marker)) {
                // 兜底：正常情况下 destroyForcibly 就够；这里用记录下来的 PID 再杀一次，
                // 免得一个卡住的 sleep 留到下一次全量测试里变成“背景噪声”
                new ProcessBuilder("kill", "-9", String.valueOf(readPid(marker)))
                        .start().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("网关被强杀后 PID 文件应留下来，充当事后排查的线索")
    void pidFile_should_survive_whenGatewayIsKilledFiercely() throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntime(30);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));
        int killedPid = readPid(pidFile());

        Process kill = new ProcessBuilder("pkill", "-9", "-f", gatewayDirectory.toString()).start();
        assertEquals(0, kill.waitFor(), "强杀网关的命令应成功");

        // 强杀后没有人会去删它，而且这正是它的全部价值：下一次启动能从里面读到那个死掉的 PID
        assertEquals(killedPid, readPid(pidFile()), "强杀后 PID 文件应保留原内容");
        invokeUntilSucceeds("jira_issue", Collections.<String, Object>singletonMap("key", "K"), 15_000L);
        String status = statusCommand();
        assertTrue(status.contains("PID " + killedPid + "（已不存在）"), status);
    }

    @Test
    @DisplayName("台账应报出 worker 的 PID 与就绪状态")
    void statusCommand_should_showWorkerPid_afterInvoke() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        String status = statusCommand();
        assertTrue(status.contains("worker "), status);
        assertTrue(status.contains("ready"), status);
        // PID 是排查时唯一需要手动输入的东西，而它只有进程侧知道
        assertTrue(status.contains("pid="), status);
    }

    @Test
    @DisplayName("脚本卡住时台账应报出「在途」——这是「为什么这个工具很慢」的直接答案")
    void statusCommand_should_showInflight_whileScriptIsBusy()
            throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        Thread hanging = new Thread(() -> {
            try {
                invokeTool("jira_hang", Collections.<String, Object>emptyMap());
            } catch (JellyfishException expected) {
                // 这个调用注定超时，这里只关心超时之前台账能看到什么
            }
        });
        hanging.setDaemon(true);
        hanging.start();
        Thread.sleep(700L);

        String status = statusCommand();
        assertTrue(status.contains("在途=1"), status);

        // 超时之后 worker 被隔离，台账必须跟着变成「已退出」而不是继续报就绪：
        // 这两个数字都是网关推过来的快照，推不出去就是永久陈旧
        hanging.join(15_000L);
        assertTrue(awaitStatus("exited", 10_000L), statusCommand());
    }

    /**
     * 等到台账里出现某个字样。
     *
     * @param marker    待查找的字样
     * @param timeoutMs 等待上限
     * @return 等到了返回 {@code true}
     * @throws InterruptedException 等待被中断时抛出
     */
    private boolean awaitStatus(String marker, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (statusCommand().contains(marker)) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    @Test
    @DisplayName("仓库里的示例脚本应能被注册、调用、命令与贡献都跑通")
    void examples_should_beUsable_endToEnd() throws IOException {
        // 直接用仓库里的示例（先拷进脚本根目录：示例自己会往它自己的目录写文件，
        // 不能拿仓库当运行目录，否则一次测试就在工作区里留下痕迹）。
        // 这样写的好处是「示例能不能用」被 CI 一直守着——放在文档里的示例代码会腐烂，
        // 而这份不会：它一坏，这个用例就红
        installExample("hello");
        installExample("jira");
        startRuntime();

        // 工具：只读那个按名字打招呼，并把会话标识一起带回来
        assertEquals("你好，jellyfish！（会话 s-1）",
                invokeTool("hello_greet", Collections.<String, Object>singletonMap("name", "jellyfish"))
                        .getOutput());
        // 工具：参数里的大小写不该影响查找（示例自己做的归一化）
        assertEquals("PROJ-1 [OPEN] 脚本插件跑不起来",
                invokeTool("jira_read", Collections.<String, Object>singletonMap("key", "proj-1"))
                        .getOutput());

        // 命令：原名与别名都要能走通。别名不是「另一个处理器」，而是内核的命令名解析。
        // 因此这里过一遍真实的 CommandManager，而不是直接按别名查处理器——
        // 后者会得到 NO_HANDLER，而「/hi 能不能用」这个问题的答案在解析那一步
        zcd.jellyfish.infra.command.CommandManager commands =
                new zcd.jellyfish.infra.command.CommandManager(extensions, events);
        assertEquals("你好，world！", commands.execute("/hello world", "s-1").getOutput());
        assertEquals("你好，jellyfish！", commands.execute("/hi jellyfish", "s-1").getOutput());
        // 命令候选查询（二级选择页）也是一条独立的只读路径。两个示例刻意各走一个入口：
        // jira 把候选查询写在单独的函数里，hello 用 has_options 让同一个函数回答两条路
        zcd.jellyfish.api.extension.CommandOptions options = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.CommandOptionRequest.class, "jira"),
                new zcd.jellyfish.api.extension.CommandOptionRequest("jira", "s-1"));
        assertEquals(2, options.getChoices().size(), options.toString());
        zcd.jellyfish.api.extension.CommandOptions helloOptions = extensions.invoke(
                extensions.handler(zcd.jellyfish.api.extension.CommandOptionRequest.class, "hello"),
                new zcd.jellyfish.api.extension.CommandOptionRequest("hello", "s-1"));
        assertEquals(2, helloOptions.getChoices().size(), helloOptions.toString());

        // 贡献：prompt 与 panel 都是类型级扩展点。类型级扩展点的取法是 bindings（列表），
        // 而不是 handler（单个）——多个插件往往同时贡献同一个类型（本用例里两个示例都贡献了
        // prompt，拿单个会得到 AMBIGUOUS_HANDLER）。内核的 PromptAssembler / UiContributions
        // 也是按这个方式遍历的
        assertTrue(promptText().contains("工单系统可用"), promptText());
        assertFalse(panelLines().isEmpty(), "读过一个工单之后面板应当有内容");

        // 可写工具：写一次便签，顺便验证「脚本能写自己的目录」与参数报错
        assertTrue(String.valueOf(invokeTool("hello_remember",
                Collections.<String, Object>singletonMap("note", "试一下")).getOutput()).contains("试一下"));
        assertTrue(Files.exists(scriptsRoot.resolve("hello").resolve("notes.txt")),
                "示例应当把便签写到自己的目录里");
        assertTrue(assertThrows(JellyfishException.class, () -> invokeTool("jira_read",
                Collections.<String, Object>singletonMap("key", "NOPE-1")))
                .getMessage().contains("没有工单"), "业务失败要能被看见");
    }

    @Test
    @DisplayName("脚本订阅的事件应真的能跑到：内核发一条，状态栏贡献就跟着变")
    void exampleEventSubscription_should_changeContribution() throws IOException {
        installExample("hello");
        startRuntime();

        assertTrue(statusText().contains("0 次"), statusText());

        // 走真实的 EventChannel：订阅这一路（通道 → 桥接 → 网关 → worker → SDK）
        // 任何一段写错都只会表现为「数字没动」
        events.publish(new zcd.jellyfish.api.event.notification.ToolCallCompletedEvent(
                "call-1", "hello_greet", true, 12L, null, "s-1"));

        assertTrue(awaitStatusLine("1 次"), "事件未送达脚本，实际状态栏: " + statusText());
    }

    @Test
    @DisplayName("web 示例：搜索端点在内网不需要任何放行开关，但 web_fetch 不沾这个光")
    void webExample_should_trustConfiguredEndpoint_without_unlockingFetch() throws Exception {
        HttpServer server = startMockServer();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            installExample("web");
            // 只配了 endpoint，没有任何「允许内网」的开关：auto 认出端点是自建的，用它
            startRuntime(10, scripts(webConfig("endpoint", base)));

            // 搜索打的是用户自己配的端点——它来自配置文件、不是模型能拨动的，因此直接放行
            ToolCallResult search = invokeTool("web_search",
                    Collections.<String, Object>singletonMap("query", "jellyfish"));
            String listed = String.valueOf(search.getOutput());
            assertTrue(listed.contains("示例标题一"), listed);
            assertTrue(listed.contains("http://example.com/a"), listed);
            // 摘要进 metadata（给人看），并且把服务它的后端也写出来
            assertEquals("搜到 2 条（searxng）", ToolMetadata.summaryOf(search.getMetadata()));

            // 同样是 127.0.0.1，web_fetch 的目标却是模型给的 —— 因此照旧拒绝。
            // 「我有内网搜索服务」不该顺带放开抓取，这是这次修复的核心
            JellyfishException failure = assertThrows(JellyfishException.class,
                    () -> invokeTool("web_fetch",
                            Collections.<String, Object>singletonMap("url", base + "/page")));
            assertTrue(failure.getMessage().contains("内网地址"), failure.getMessage());
            assertTrue(failure.getMessage().contains("allowRanges"),
                    "报错要直接给出下一步: " + failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("web 示例：allowRanges 放行网段后，抓取正文与重定向都走得通")
    void webExample_should_fetchWithinAllowedRanges() throws Exception {
        HttpServer server = startMockServer();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            installExample("web");
            // allowRanges 是「整段网段豁免」，真正的用途是 TUN + 假 IP 代理（公网域名被解析到保留网段）。
            // 这里拿它给回环网段放行，因为走的是同一条判定路径
            Map<String, Object> web = webConfig("endpoint", base);
            web.put("allowRanges", Collections.singletonList("127.0.0.0/8"));
            startRuntime(10, scripts(web));

            ToolCallResult fetch = invokeTool("web_fetch",
                    Collections.<String, Object>singletonMap("url", base + "/page"));
            String text = String.valueOf(fetch.getOutput());
            assertTrue(text.contains("第一段正文"), text);
            assertTrue(text.contains("第二段 & 实体"), text);
            // 脚本 / 样式 / 导航 / 页脚都不是正文：它们出现在结果里就说明提取没生效
            assertFalse(text.contains("导航应被丢弃"), text);
            assertFalse(text.contains("页脚应被丢弃"), text);
            assertFalse(text.contains("var x=1"), text);

            // 重定向是逐跳校验的，因此豁免也必须跟着带过去；否则第二跳会被拦下来
            ToolCallResult redirected = invokeTool("web_fetch",
                    Collections.<String, Object>singletonMap("url", base + "/redirect"));
            assertTrue(String.valueOf(redirected.getOutput()).contains("第一段正文"),
                    String.valueOf(redirected.getOutput()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("web 示例：什么都不配时默认走 Exa 的公开 MCP 端点（零配置就能搜）")
    void webExample_should_defaultToExaMcp_withoutAnyKey() throws Exception {
        HttpServer server = startMockServer();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            installExample("web");
            // 没有 provider、没有 endpoint、没有 apiKey，只把公开端点指向 mock：
            // 这条路径要证明的就是「不填任何东西也有得用」
            startRuntime(10, scripts(webConfig("exaMcpUrl", base + "/mcp")));

            ToolCallResult search = invokeTool("web_search",
                    Collections.<String, Object>singletonMap("query", "jellyfish"));
            String listed = String.valueOf(search.getOutput());
            // 端点回的是 SSE，解析器必须能从 data: 行里把结果抠出来
            assertTrue(listed.contains("MCP-标题一"), listed);
            assertTrue(listed.contains("http://example.com/mcp-1"), listed);
            assertTrue(String.valueOf(ToolMetadata.summaryOf(search.getMetadata())).contains("exa-mcp"),
                    String.valueOf(ToolMetadata.summaryOf(search.getMetadata())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("web 示例：已移除的 allowPrivateAddresses 要当场报错，而不是被静默忽略")
    void webExample_should_rejectRemovedFlag() throws Exception {
        installExample("web");
        Map<String, Object> web = webConfig("endpoint", "https://searx.example.org");
        web.put("allowPrivateAddresses", Boolean.TRUE);
        startRuntime(10, scripts(web));

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> invokeTool("web_search",
                        Collections.<String, Object>singletonMap("query", "x")));

        // 静默忽略会让人以为「已经配好了」，然后在另一个地方莫名其妙地失败
        assertTrue(failure.getMessage().contains("allowPrivateAddresses"), failure.getMessage());
        assertTrue(failure.getMessage().contains("allowRanges"), failure.getMessage());
    }

    /**
     * 造一份只放一个键的 {@code scripts.web} 配置。
     *
     * @param key   配置键
     * @param value 配置值
     * @return scripts.web 段
     */
    private static Map<String, Object> webConfig(String key, Object value) {
        Map<String, Object> web = new LinkedHashMap<String, Object>();
        web.put(key, value);
        return web;
    }

    /**
     * 把一份 {@code scripts.web} 段包成逐脚本配置。
     *
     * @param web scripts.web 段
     * @return 逐脚本配置
     */
    private static Map<String, Map<String, Object>> scripts(Map<String, Object> web) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<String, Map<String, Object>>();
        all.put("web", web);
        return all;
    }

    /**
     * 起一个只在回环上监听的 mock 服务：搜索接口、一个 MCP 端点、一个 HTML 页面、一条重定向。
     *
     * @return 已启动的服务
     * @throws IOException 启动失败时抛出
     */
    private static HttpServer startMockServer() throws IOException {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, "application/json",
                "{\"results\":["
                        + "{\"title\":\"示例标题一\",\"url\":\"http://example.com/a\","
                        + "\"content\":\"摘要一\",\"engine\":\"mock\"},"
                        + "{\"title\":\"示例标题二\",\"url\":\"http://example.com/b\","
                        + "\"content\":\"摘要二\",\"engine\":\"mock\"}]}"));
        // Exa 的公开端点回的是 SSE（text/event-stream）而不是裸 JSON。
        // mock 也照真实形状回，否则「两种形态都试一次」的分支永远没被跨过。
        // 并且照真实服务那样**校验请求头**：少了 Content-Type: application/json 就回 415。
        // 这不是凑数——urllib 带 body 时会自己塞 application/x-www-form-urlencoded，
        // 不校的话测试会绿而真实调用 415（真撞过一次）
        server.createContext("/mcp", exchange -> {
            String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
            String accept = String.valueOf(exchange.getRequestHeaders().getFirst("Accept"));
            if (!contentType.startsWith("application/json") || !accept.contains("text/event-stream")) {
                respond(exchange, 415, "text/plain; charset=utf-8",
                        "需要 Content-Type: application/json 且 Accept 含 text/event-stream");
                return;
            }
            respond(exchange, 200, "text/event-stream",
                    "event: message\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":"
                            + "\"Title: MCP-标题一\\nURL: http://example.com/mcp-1\\n"
                            + "Published: N/A\\nHighlights:\\n来自 MCP 的正文一。\\n---\\n"
                            + "Title: MCP-标题二\\nURL: http://example.com/mcp-2\\n"
                            + "Highlights:\\n来自 MCP 的正文二。\"}]}}\n\n");
        });
        server.createContext("/page", exchange -> respond(exchange, "text/html; charset=utf-8",
                "<!doctype html><html><head><title>示例页面</title>"
                        + "<script>var x=1;</script><style>.a{color:red}</style></head>"
                        + "<body><nav>导航应被丢弃</nav><article><h1>标题一</h1>"
                        + "<p>第一段正文。</p><p>第二段 &amp; 实体。</p></article>"
                        + "<footer>页脚应被丢弃</footer></body></html>"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            exchange.sendResponseHeaders(302, -1L);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * 回一段固定内容。
     *
     * @param exchange   交换对象
     * @param status     状态码
     * @param contentType 内容类型
     * @param body       正文
     * @throws IOException 写入失败时抛出
     */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 回一段 200 固定内容。
     *
     * @param exchange    交换对象
     * @param contentType 内容类型
     * @param body        正文
     * @throws IOException 写入失败时抛出
     */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String contentType,
                                String body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    @Test
    @DisplayName("清单生成器：生成结果必须能被内核接受，且与仓库里的清单一致")
    void dumpManifest_should_agreeWithExamples() throws IOException {
        // 两件事一起验，因为它们是同一个承诺的两面：
        // ① 生成器吐出来的是内核认得的清单（能直接落盘），② 示例的清单没有落后于实现。
        // 后者是「清单与实现必须一致」这条约束唯一能自动守住的地方
        for (String id : new String[] {"hello", "jira", "web"}) {
            Path script = examplesDirectory().resolve(id);
            String generated = runPython(resources("dump_manifest.py"), script.toString());
            zcd.jellyfish.script.ScriptManifest fromCode = zcd.jellyfish.script.ScriptManifest.parse(
                    generated, id, zcd.jellyfish.script.codec.ExtensionCodecs.DEFAULTS);
            // 直接用仓库里那份清单过同一个严格解析器：清单里写了不存在的键、
            // 或者 tool 的 readOnly 写成了字符串，都会在这里当场曝露
            String onDisk = new String(Files.readAllBytes(script.resolve("manifest.json")),
                    StandardCharsets.UTF_8);
            zcd.jellyfish.script.ScriptManifest checkedIn = zcd.jellyfish.script.ScriptManifest.parse(
                    onDisk, id, zcd.jellyfish.script.codec.ExtensionCodecs.DEFAULTS);
            assertEquals(toolNames(checkedIn), toolNames(fromCode),
                    id + " 的清单与实现不一致，跑 dump_manifest.py --check 看差异");
            assertEquals(commandNames(checkedIn), commandNames(fromCode), id + " 的命令列表不一致");

            // --check 是给作者用的入口，它必须在这两个示例上返回成功
            String check = runPython(resources("dump_manifest.py"), script.toString(), "--check");
            assertTrue(check.contains("清单与实现一致"), check);
        }
    }

    /**
     * 取工具名清单。
     *
     * @param manifest 清单
     * @return 工具名列表
     */
    private static java.util.List<String> toolNames(zcd.jellyfish.script.ScriptManifest manifest) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (zcd.jellyfish.script.ScriptManifest.Tool tool : manifest.tools()) {
            names.add(tool.name());
        }
        return names;
    }

    /**
     * 取命令名清单。
     *
     * @param manifest 清单
     * @return 命令名列表
     */
    private static java.util.List<String> commandNames(
            zcd.jellyfish.script.ScriptManifest manifest) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (zcd.jellyfish.script.ScriptManifest.Command command : manifest.commands()) {
            names.add(command.name());
        }
        return names;
    }

    /**
     * 调用一条命令。
     *
     * @param name 命令名
     * @param args 参数原文
     * @return 命令结果
     */
    private CommandResult invokeCommand(String name, String args) {
        // tokens 与 raw 都给：脚本常按 tokens 处理、按 raw 记录原文，
        // 只给一个就无法覆盖「两个字段都能拿到」这个事实
        java.util.List<String> tokens = args == null || args.trim().isEmpty()
                ? java.util.Collections.<String>emptyList()
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
     * 汇总全部面板贡献的行文本。
     *
     * @return 行文本列表
     */
    private java.util.List<String> panelLines() {
        zcd.jellyfish.api.extension.PanelContributionRequest request =
                new zcd.jellyfish.api.extension.PanelContributionRequest("s-1");
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (zcd.jellyfish.infra.extension.HandlerBinding<
                zcd.jellyfish.api.extension.PanelContributionRequest,
                zcd.jellyfish.api.extension.PanelContribution> binding
                : extensions.bindings(zcd.jellyfish.api.extension.PanelContributionRequest.class, null)) {
            zcd.jellyfish.api.extension.PanelContribution contribution =
                    extensions.invoke(binding.getHandler(), request);
            if (contribution == null) {
                continue;
            }
            for (zcd.jellyfish.api.ui.UiLine line : contribution.getLines()) {
                if (!line.isEmpty()) {
                    lines.add(line.text());
                }
            }
        }
        return lines;
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
        // 轮询再久也看不到。这正是本用例最初三次里失败两次的全部原因：
        // 观察者挡住了被观察的事。先让出时间让推送落地，再去读它
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

    /**
     * 把仓库里的示例拷进脚本根目录。
     *
     * @param id 示例标识
     * @throws IOException 拷贝失败时抛出
     */
    private void installExample(String id) throws IOException {
        Path source = examplesDirectory().resolve(id);
        assertTrue(Files.isDirectory(source), "示例目录不存在: " + source);
        Path target = scriptsRoot.resolve(id);
        Files.createDirectories(target);
        // 递归拷贝：示例以后可能拆成多个文件，测试不该因此失效
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.filter(Files::isRegularFile).toArray(Path[]::new)) {
                Path relative = source.relativize(path);
                // 跳过 Python 的字节码缓存：它是「就地跑示例」的用例（如 dump_manifest 那一组）留下的产物，
                // 拷进沙箱毫无用处，反而会因为拷贝期间被重新生成而随机失败
                if (isBytecodeCache(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                // 中间目录必须显式建出来：Files.copy 不会替我们创建父目录，
                // 示例将来多一层子目录（例如 resources/）时就会以 NoSuchFileException 炸掉
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        }
    }

    /**
     * 判断相对路径是否落在 Python 的字节码缓存里。
     *
     * @param relative 相对示例根目录的路径
     * @return 路径中出现 {@code __pycache__} 或以 {@code .pyc} 结尾时返回 {@code true}
     */
    private static boolean isBytecodeCache(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if ("__pycache__".equals(name) || name.endsWith(".pyc")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取示例脚本目录。
     *
     * @return 目录
     */
    private static Path examplesDirectory() {
        String property = System.getProperty("jellyfish.test.examples");
        assumeTrue(property != null && !property.trim().isEmpty(),
                "未设置 jellyfish.test.examples（用 -Pscript-it 跑本用例）");
        Path directory = Paths.get(property).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "示例目录不存在: " + directory);
        return directory;
    }

    /**
     * 取抽取出来的网关资源里的某个文件。
     *
     * @param name 资源文件名
     * @return 文件路径
     */
    private Path resources(String name) {
        Path directory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        // 抽取后的布局与类路径同形（资源名带 script/ 前缀），入口也是按这个相对路径启动的
        return directory.resolve("script").resolve(name);
    }

    @Test
    @DisplayName("清单生成器：清单落后于实现时必须报出来（否则「守着漂移」这句话是空的）")
    void dumpManifest_should_reportDrift() throws IOException {
        // 一个永远回答「一致」的检查器比没有检查器更糟：它会把「清单已同步」变成一种错觉。
        // 因此这里反向验一次——往清单里塞一个代码里没有的工具，它必须失败并点名
        installExample("hello");
        Path manifest = scriptsRoot.resolve("hello").resolve("manifest.json");
        String broken = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                .replace("\"tools\": [", "\"tools\": [{\"name\": \"hello_nonexistent\"}, ");
        Files.write(manifest, broken.getBytes(StandardCharsets.UTF_8));

        String output = runPythonExpectingFailure(
                resources("dump_manifest.py"), scriptsRoot.resolve("hello").toString(), "--check");

        assertTrue(output.contains("hello_nonexistent"), output);
        assertTrue(output.contains("清单里多出了这一项"), output);
    }

    /**
     * 跑一个 Python 脚本并取标准输出。
     *
     * @param script 脚本路径
     * @param args   参数
     * @return 标准输出与标准错误合并后的文本
     * @throws IOException 启动失败时抛出
     */
    private static String runPython(Path script, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(interpreter());
        command.add(script.toString());
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        try {
            boolean finished = process.waitFor(30L, TimeUnit.SECONDS);
            assertTrue(finished, "清单生成器没有在 30 秒内结束");
            assertEquals(0, process.exitValue(), "清单生成器退出了非零码，输出: " + output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return output;
    }

    /**
     * 跑一个 Python 脚本，要求它**失败**，并取输出。
     *
     * @param script 脚本路径
     * @param args   参数
     * @return 标准输出与标准错误合并后的文本
     * @throws IOException 启动失败时抛出
     */
    private static String runPythonExpectingFailure(Path script, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(interpreter());
        command.add(script.toString());
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        try {
            boolean finished = process.waitFor(30L, TimeUnit.SECONDS);
            assertTrue(finished, "清单生成器没有在 30 秒内结束");
            assertFalse(process.exitValue() == 0, "清单与实现不一致时应当退出非零码，输出: " + output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return output;
    }

    @Test
    @DisplayName("内核事件应送达订阅它的脚本")
    void event_should_reachScript_when_kernelPublishes() throws IOException {
        // 这条链路跨了四个进程内/进程外的边界：EventChannel 通知线程 → 桥接队列 →
        // 桥接推送线程 → 网关 → worker → SDK 分发。任何一段写错都表现为「处理器不执行」，
        // 因此这里断言的是**最终落在磁盘上的那条记录**
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        events.publish(new zcd.jellyfish.api.event.notification.ConfigWarningEvent(
                "unit-test", "配置有问题"));

        assertTrue(awaitEventLog("jira", "warning:unit-test:配置有问题"),
                "事件未送达脚本，实际记录: " + readEventLog("jira"));
    }

    @Test
    @DisplayName("脚本发布的事件应进入内核事件通道，且不回声给发布者自己")
    void emit_should_publishEvent_and_notEchoBackToPublisher() throws IOException {
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        java.util.List<zcd.jellyfish.api.event.notification.PluginNotificationEvent> received =
                Collections.synchronizedList(
                        new java.util.ArrayList<zcd.jellyfish.api.event.notification.PluginNotificationEvent>());
        events.subscribe("it", zcd.jellyfish.api.event.notification.PluginNotificationEvent.class,
                received::add);

        invokeTool("publish", Collections.<String, Object>emptyMap());

        long deadline = System.currentTimeMillis() + 10_000L;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(100L);
        }
        assertEquals(1, received.size(), "脚本发布的事件应恰好到达内核一次");
        assertEquals("script:jira", received.get(0).getSource());

        // 回声：脚本自己也订阅了这个事件，但它不该收到自己刚发的那个。
        // 等一小会儿再断言，因为「没收到」只能通过时间来观察
        sleepQuietly(500L);
        assertFalse(readEventLog("jira").contains("echo:"),
                "脚本收到了自己发布的事件（回声），实际记录: " + readEventLog("jira"));
    }

    @Test
    @DisplayName("发布不可发布的事件应被拒绝，且不影响脚本继续服务")
    void emit_should_beRejected_when_eventIsNotEmittable() throws IOException {
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        ToolCallResult result = invokeTool("publish_forbidden", Collections.<String, Object>emptyMap());

        // 拒绝是**正常结论**而不是故障：脚本这次调用照样成功返回，
        // 这正是「脚本不能伪造内核语义事件」在没有抛出异常的情况下生效的样子
        assertEquals("attempted", result.getOutput());
        sleepQuietly(500L);
        assertFalse(readEventLog("jira").contains("warning:"),
                "不可发布的事件不该进入内核，实际记录: " + readEventLog("jira"));
    }

    /**
     * 执行一次状态命令。
     *
     * @return 命令输出
     */
    private String statusCommand() {
        return extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null)).getOutput();
    }

    /**
     * 本用例组使用的 PID 文件路径。
     *
     * @return 路径
     */
    private Path pidFile() {
        return pidRoot.resolve("script-python.pid");
    }

    /**
     * 读一个 PID 文件。
     *
     * @param file 文件路径
     * @return PID
     * @throws IOException 读取失败时抛出
     */
    private static int readPid(Path file) throws IOException {
        return Integer.parseInt(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    }

    /**
     * 等到 PID 标记文件出现并读出内容。
     *
     * @param marker   标记文件
     * @param timeoutMs 等待上限
     * @return PID
     * @throws IOException          读取失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int awaitPid(Path marker, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker) && Files.size(marker) > 0) {
                return readPid(marker);
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("没有等到 PID 标记文件: " + marker);
    }


    /**
     * 事件记录文件路径。
     *
     * @param scriptId 脚本目录名
     * @return 记录文件路径
     */
    private Path eventLog(String scriptId) {
        return scriptsRoot.resolve(scriptId).resolve("events.log");
    }

    /**
     * 覆盖写入事件记录文件。
     * <p>
     * 每个用例开头清空一次：事件是异步的，上一个用例的残留会让断言「看起来通过了」。
     *
     * @param scriptId 脚本目录名
     * @param content  初始内容
     * @throws IOException 写入失败时抛出
     */
    private void writeEventLog(String scriptId, String content) throws IOException {
        Files.write(eventLog(scriptId), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取事件记录文件。
     *
     * @param scriptId 脚本目录名
     * @return 文件内容；文件不存在时返回空串
     */
    private String readEventLog(String scriptId) {
        try {
            Path path = eventLog(scriptId);
            return Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "";
        } catch (IOException error) {
            return "";
        }
    }

    /**
     * 等到事件记录里出现某段文本。
     *
     * @param scriptId 脚本目录名
     * @param expected 期望片段
     * @return 出现返回 {@code true}
     */
    private boolean awaitEventLog(String scriptId, String expected) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (readEventLog(scriptId).contains(expected)) {
                return true;
            }
            sleepQuietly(100L);
        }
        return false;
    }

    /**
     * 安静地睡一会儿（中断只恢复标志位）。
     *
     * @param millis 毫秒
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动插件并指定熔断参数。
     * <p>
     * <b>为什么探测轮数要放宽</b>：冷却只有 1 秒，而被隔离的 worker 要几秒才真正退场
     * （它可能卡在不响应信号的系统调用里，只能等强杀）。若用默认的 3 轮，用例可能把
     * 「新 worker 还没就绪」连着记成 3 轮探测失败、直接转永久——那是配置过紧，不是被测行为错了。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param failuresToOpen       连续失败多少次打开熔断
     * @param cooldownSeconds      冷却秒数
     * @param roundsToPermanent    探测失败几轮转永久
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithCircuit(int invokeTimeoutSeconds, int failuresToOpen,
                                         int cooldownSeconds, int roundsToPermanent) throws IOException {
        installPlugin();
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(ScriptBridgeConfig.KEY_FAILURES_TO_OPEN, Integer.valueOf(failuresToOpen));
        breaker.put(ScriptBridgeConfig.KEY_COOLDOWN_SECONDS, Integer.valueOf(cooldownSeconds));
        breaker.put(ScriptBridgeConfig.KEY_ROUNDS_TO_PERMANENT, Integer.valueOf(roundsToPermanent));
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        python.put(PythonBridgePlugin.KEY_INTERPRETER, interpreter());
        python.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        python.put(ScriptBridgeConfig.KEY_CIRCUIT_BREAKER, breaker);
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(
                extensions, events, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class), new ShellIngress(new MetricsRegistry())),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations),
                events);
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("jellyfish-plugin-python"));
    }

    /**
     * 用默认超时启动插件，并指定 worker 空闲自毁秒数。
     *
     * @param idleSeconds worker 空闲自毁秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithIdle(int idleSeconds) throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonBridgePlugin.KEY_INTERPRETER, interpreter());
        python.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        python.put(ScriptBridgeConfig.KEY_WORKER_IDLE, Integer.valueOf(idleSeconds));
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(
                extensions, events, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class), new ShellIngress(new MetricsRegistry())),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations),
                events);
        manager.bootstrap();
    }

    /**
     * 数一数某个网关目录下还有几个进程（网关自己 + 它的 worker，二者的命令行相同）。
     *
     * @param gatewayDirectory 网关资源目录
     * @return 进程数
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int countProcesses(java.nio.file.Path gatewayDirectory)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ps", "-eo", "command").redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        process.waitFor(5, TimeUnit.SECONDS);
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.contains(gatewayDirectory.toString())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 等到进程数落到期望值。
     *
     * @param gatewayDirectory 网关资源目录
     * @param expected         期望进程数
     * @return 在超时前到达期望值返回 {@code true}
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean awaitProcessCount(java.nio.file.Path gatewayDirectory, int expected)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (countProcesses(gatewayDirectory) == expected) {
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
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** 供 {@code statusCommand} 断言用的示例脚本。 */
    private static final String TOOL_SCRIPT = ""
            + "from jellyfish_sdk import tool, command, contributes, ScriptError\n"
            + "\n"
            + "@tool(name=\"jira_issue\", description=\"读 issue\")\n"
            + "def jira_issue(args, ctx):\n"
            + "    if not args.get(\"key\"):\n"
            + "        raise ScriptError(\"缺少参数 key\")\n"
            + "    return \"issue %s 处于 OPEN（会话 %s）\" % (args[\"key\"], ctx.session_id)\n"
            + "\n"
            + "@tool(name=\"jira_hang\", description=\"卡死\")\n"
            + "def jira_hang(args, ctx):\n"
            + "    import time\n"
            + "    time.sleep(600)\n"
            + "    return \"never\"\n"
            + "\n"
            + "@command(\"jira\", summary=\"操作 Jira\")\n"
            + "def jira(tokens, raw, ctx):\n"
            + "    return \"已切换 PROJ-1\"\n"
            + "\n"
            + "@contributes(\"prompt\")\n"
            + "def prompt(ctx):\n"
            + "    return \"Jira：会话 %s 有 3 个未读\" % ctx.session_id\n";

    /**
     * 事件脚本：一个工具负责发布事件，一个处理器负责记录收到的事件。
     * <p>
     * 记录到文件而不是返回给宿主，是因为事件的送达是**异步且旁路**的：
     * 它不会、也不该出现在任何一次调用的返回值里。文件是唯一能从外部观察它的地方。
     */
    private static final String EVENT_SCRIPT = ""
            + "import os\n"
            + "from jellyfish_sdk import tool, subscribe\n"
            + "\n"
            + "LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), \"events.log\")\n"
            + "\n"
            + "\n"
            + "def record(line):\n"
            + "    with open(LOG, \"a\") as handle:\n"
            + "        handle.write(line + \"\\n\")\n"
            + "\n"
            + "\n"
            + "@tool(name=\"publish\", description=\"发布一条通知\")\n"
            + "def publish(args, ctx):\n"
            + "    ctx.emit_event(\"PluginNotificationEvent\", {\"payload\": {\"hello\": \"world\"}})\n"
            + "    return \"published\"\n"
            + "\n"
            + "\n"
            + "@tool(name=\"publish_forbidden\", description=\"发布一条不可发布的事件\")\n"
            + "def publish_forbidden(args, ctx):\n"
            + "    ctx.emit_event(\"SessionCreatedEvent\", {\"agentId\": \"fake\"})\n"
            + "    return \"attempted\"\n"
            + "\n"
            + "\n"
            + "@subscribe(\"ConfigWarningEvent\")\n"
            + "def on_warning(event, ctx):\n"
            + "    record(\"warning:%s:%s\" % (event.get(\"source\"), event.get(\"message\")))\n"
            + "\n"
            + "\n"
            + "@subscribe(\"PluginNotificationEvent\")\n"
            + "def on_notification(event, ctx):\n"
            + "    record(\"echo:\" + str(event.get(\"payload\")))\n";

    /**
     * 与 {@link #EVENT_SCRIPT} 逐字对应的清单。
     */
    private static final String EVENT_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"publish\"},{\"name\":\"publish_forbidden\"}],"
            + "\"events\":[\"ConfigWarningEvent\",\"PluginNotificationEvent\"]}";

    /**
     * 周期任务脚本：每次触发往文件里追加一行，另有一个工具读出已触发次数。
     * <p>
     * <b>为什么记到文件而不是返回给宿主</b>：周期任务**没有任何调用方**——它的返回值被丢弃
     * （宿主只为它代发一次界面失效）。因此从外部唯一能观察它的地方就是它自己留下的痕迹。
     * <p>
     * 顺带断言了一件容易漏的事：周期任务调用发生在**没有会话**的上下文里，
     * 所以脚本读到 ``ctx.session_id`` 必须是 ``None``。把它一起写进记录，
     * 「宿主误把某个会话传下去」就会当场暴露。
     */
    private static final String PERIODIC_SCRIPT = ""
            + "import os\n"
            + "from jellyfish_sdk import periodic, tool\n"
            + "\n"
            + "LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), \"events.log\")\n"
            + "COUNT = os.path.join(os.path.dirname(os.path.abspath(__file__)), \"count.txt\")\n"
            + "\n"
            + "\n"
            + "def _bump():\n"
            + "    current = 0\n"
            + "    if os.path.exists(COUNT):\n"
            + "        with open(COUNT) as handle:\n"
            + "            current = int(handle.read().strip() or \"0\")\n"
            + "    current += 1\n"
            + "    with open(COUNT, \"w\") as handle:\n"
            + "        handle.write(str(current))\n"
            + "    return current\n"
            + "\n"
            + "\n"
            + "@periodic(name=\"beat\", interval_seconds=1)\n"
            + "def beat(ctx):\n"
            + "    with open(LOG, \"a\") as handle:\n"
            + "        handle.write(\"tick:%d:session=%s\\n\" % (_bump(), ctx.session_id))\n"
            + "\n"
            + "\n"
            + "@tool(name=\"beat_count\", description=\"读出周期任务已触发的次数\")\n"
            + "def beat_count(args, ctx):\n"
            + "    if not os.path.exists(COUNT):\n"
            + "        return \"0\"\n"
            + "    with open(COUNT) as handle:\n"
            + "        return handle.read().strip()\n";

    /**
     * 与 {@link #PERIODIC_SCRIPT} 逐字对应的清单（间隔 1 秒，用例窗口内够跳两次）。
     */
    private static final String PERIODIC_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"beat_count\"}],"
            + "\"schedules\":[{\"name\":\"beat\",\"intervalSeconds\":1}]}";

    /**
     * 同上一份，但间隔声明成 60 秒——供「配置段覆写间隔」的用例使用。
     * <p>
     * <b>为什么要这一份</b>：覆写是否生效，只能靠「不该触发的却触发了」来证明。
     * 声明 60 秒而配置写成 1 秒，用例窗口内出现跳动就只可能来自覆写。
     */
    private static final String PERIODIC_MANIFEST_SLOW = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"beat_count\"}],"
            + "\"schedules\":[{\"name\":\"beat\",\"intervalSeconds\":60}]}";

    /**
     * 夹具脚本：声明了 ``has_options=True``，但函数只收 ``ctx``——
     * 于是它既回答不了候选查询，也看不出这一次到底是哪条路。
     */
    private static final String AMBIGUOUS_OPTIONS_SCRIPT = ""
            + "from jellyfish_sdk import command, tool\n"
            + "\n"
            + "@tool(name='badopt_ping', description='只为了让这个脚本有个可调用的入口')\n"
            + "def ping(args, ctx):\n"
            + "    return 'pong'\n"
            + "\n"
            + "@command(name='badopt', has_options=True)\n"
            + "def badopt(ctx):\n"
            + "    return {'choices': []}\n";

    /**
     * 与 {@link #AMBIGUOUS_OPTIONS_SCRIPT} 逐字对应的清单。
     * <p>
     * 刻意让它与声明一致：这样脚本拒绝服务的原因只可能是签名，而不是「清单对不上」。
     */
    private static final String AMBIGUOUS_OPTIONS_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"badopt_ping\"}],"
            + "\"commands\":[{\"name\":\"badopt\",\"hasOptions\":true}],"
            + "\"commandOptions\":[]}";

    /**
     * 与 {@link #TOOL_SCRIPT} 逐字对应的清单。
     * <p>
     * 两者必须一起改：这正是「清单与实现必须一致」这条约束在测试里的样子——
     * 用例一旦用一份过时的清单，脚本会直接拒绝服务，而错误信息会立刻指出差在哪一项。
     */
    private static final String FULL_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"jira_issue\"},{\"name\":\"jira_hang\"}],"
            + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"}}],"
            + "\"contributions\":[\"prompt\"]}";


    /**
     * 第二个脚本：用于验证「一个脚本熔断不牵连同语言的其它脚本」。
     * <p>
     * 它与 {@link #TOOL_SCRIPT} 分属不同目录、各自一个 worker，但在内核眼里提供的是同一批扩展点。
     */
    private static final String GIT_SCRIPT = ""
            + "from jellyfish_sdk import tool\n"
            + "\n"
            + "@tool(name=\"git_status\", description=\"工作区状态\")\n"
            + "def git_status(args, ctx):\n"
            + "    return \"main 干净\"\n";

    /** 与 {@link #GIT_SCRIPT} 逐字对应的清单。 */
    private static final String GIT_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"git_status\"}]}";

    /**
     * 自己接管 SIGTERM 的脚本：用来验证两段式关闭的**第二段**真的会送到。
     * <p>
     * 「脚本自己装 SIGTERM 处理器」不是编出来的场景——做收尾清理是很自然的动机，而一旦
     * 脚本接管了这个信号，网关的第一段（SIGTERM）就失效了，收场全靠第二段（SIGKILL）。
     */
    private static final String STUBBORN_SCRIPT = ""
            + "import signal\n"
            + "import time\n"
            + "\n"
            + "from jellyfish_sdk import tool\n"
            + "\n"
            + "\n"
            + "@tool(name=\"stubborn_hang\", description=\"接管 SIGTERM 之后卡死\")\n"
            + "def stubborn_hang(args, ctx):\n"
            + "    # 先接管 SIGTERM（脚本做收尾清理很自然），再卡住不回来\n"
            + "    signal.signal(signal.SIGTERM, lambda *_: None)\n"
            + "    time.sleep(600)\n"
            + "    return \"never\"\n";

    /** 与 {@link #STUBBORN_SCRIPT} 逐字对应的清单。 */
    private static final String STUBBORN_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"stubborn_hang\"}]}";

    /** 返回一个大结果的脚本：用来钉住「帧比宿主按行缓冲的上限大」时会发生什么。 */
    private static final String LARGE_SCRIPT = ""
            + "from jellyfish_sdk import tool\n"
            + "\n"
            + "\n"
            + "@tool(name=\"large_probe\", description=\"返回一个很大的结果\")\n"
            + "def large_probe(args, ctx):\n"
            + "    return \"x\" * 200000\n";

    /** 与 {@link #LARGE_SCRIPT} 逐字对应的清单。 */
    private static final String LARGE_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"large_probe\"}]}";

    /**
     * 读配置的脚本：同时从 ``ctx.configuration`` 与模块级 ``configuration()`` 取值。
     * <p>
     * 两个入口都要试：模块级那个要求 worker **在 import 脚本之前**注入配置，
     * 而这是一个很容易在重构中丢掉、且现场看起来像「配置没生效」的细节。
     */
    private static final String CONFIG_SCRIPT = ""
            + "from jellyfish_sdk import tool, configuration\n"
            + "\n"
            + "@tool(name=\"web_probe\", description=\"读配置\")\n"
            + "def web_probe(args, ctx):\n"
            + "    cfg = ctx.configuration\n"
            + "    return \"provider=%s; key=%s; module=%s\" % (cfg.get(\"provider\"),"
            + " cfg.get(\"apiKey\"), configuration().get(\"provider\"))\n";

    /** 与 {@link #CONFIG_SCRIPT} 逐字对应的清单。 */
    private static final String CONFIG_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"web_probe\"}]}";

    /** 返回 {@code ToolResult} 的脚本：摘要里带上调用者身份，一次验证两件事。 */
    private static final String METADATA_SCRIPT = ""
            + "from jellyfish_sdk import tool, ToolResult\n"
            + "\n"
            + "@tool(name=\"web_meta\", description=\"带元数据的工具\")\n"
            + "def web_meta(args, ctx):\n"
            + "    summary = \"parent=%s run=%s root=%s\" % (\n"
            + "        ctx.parent_session_id, ctx.run_id, ctx.root_run_id)\n"
            + "    return ToolResult(\"正文\", summary=summary)\n";

    /** 与 {@link #METADATA_SCRIPT} 逐字对应的清单。 */
    private static final String METADATA_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"web_meta\"}]}";

    /**
     * 二期扩展点夹具：六个类型级贡献 + 一个带路由键的处理器（{@code model_catalog}）。
     * <p>
     * 处理器签名刻意不一致：有的只声明 {@code ctx}（不关心的参数就不必列），有的声明了用得上的槽位。
     * 两种写法都要能跑——这是 SDK “声明什么就给什么”的约定。
     */
    private static final String SECOND_WAVE_SCRIPT = ""
            + "from jellyfish_sdk import contributes, handler\n"
            + "\n"
            + "@contributes(\"turn_context\")\n"
            + "def turn_context(ctx):\n"
            + "    return \"now: 2026-10-04\"\n"
            + "\n"
            + "@contributes(\"tool_argument_pre\")\n"
            + "def tool_argument_pre(tool_name, ctx):\n"
            + "    return {\"arguments\": {\"path\": \"/tmp/\" + str(tool_name)}}\n"
            + "\n"
            + "@contributes(\"tool_result_post\")\n"
            + "def tool_result_post(ctx):\n"
            + "    return {\"metadata\": {\"summary\": \"已脱敏\"}}\n"
            + "\n"
            + "@contributes(\"session_before_close\")\n"
            + "def session_before_close(veto_supported, ctx):\n"
            + "    return \"dirty\" if veto_supported else None\n"
            + "\n"
            + "@contributes(\"session_before_fork\")\n"
            + "def session_before_fork(ctx):\n"
            + "    return True\n"
            + "\n"
            + "@contributes(\"compaction_pre\")\n"
            + "def compaction_pre(ctx):\n"
            + "    return {\"keepRecent\": 3}\n"
            + "\n"
            + "@handler(\"model_catalog\", route=\"local\")\n"
            + "def local_catalog(provider_name, provider_type, ctx):\n"
            + "    return [{\"id\": \"local-model\", \"contextLength\": 8192}]\n";

    /** 与 {@link #SECOND_WAVE_SCRIPT} 逐字对应的清单。 */
    private static final String SECOND_WAVE_MANIFEST = "{\"entry\":\"main.py\","
            + "\"contributions\":[\"turn_context\",\"tool_argument_pre\",\"tool_result_post\","
            + "\"session_before_close\",\"session_before_fork\",\"compaction_pre\"],"
            + "\"handlers\":[{\"type\":\"model_catalog\",\"route\":\"local\"}]}";

    /** 三期夹具：五个类型级贡献 + 一个带标记路由的 {@code input_directive}。 */
    private static final String THIRD_WAVE_SCRIPT = ""
            + "from jellyfish_sdk import contributes, handler\n"
            + "\n"
            + "@contributes(\"tool_activation\")\n"
            + "def tool_activation(tool_name, ctx):\n"
            + "    return \"service down\" if tool_name == \"bad\" else None\n"
            + "\n"
            + "@contributes(\"request_tuning\")\n"
            + "def request_tuning(ctx):\n"
            + "    return {\"cacheBreakpoints\": 1}\n"
            + "\n"
            + "@contributes(\"aging_strategy\")\n"
            + "def aging_strategy(ctx):\n"
            + "    return {\"agingPercent\": 30}\n"
            + "\n"
            + "@contributes(\"input_transform\")\n"
            + "def input_transform(ctx):\n"
            + "    return {\"handled\": True, \"notice\": \"已接过去\"}\n"
            + "\n"
            + "@contributes(\"turn_before\")\n"
            + "def turn_before(ctx):\n"
            + "    return {\"input\": \"改写后的输入\"}\n"
            + "\n"
            + "@handler(\"input_directive\", route=\"!\")\n"
            + "def bang(marker, input, ctx):\n"
            + "    return {\"toolName\": \"shell\", \"arguments\": {\"command\": input}}\n";

    /** 与 {@link #THIRD_WAVE_SCRIPT} 逐字对应的清单。 */
    private static final String THIRD_WAVE_MANIFEST = "{\"entry\":\"main.py\","
            + "\"contributions\":[\"tool_activation\",\"request_tuning\",\"aging_strategy\","
            + "\"input_transform\",\"turn_before\"],"
            + "\"handlers\":[{\"type\":\"input_directive\",\"route\":\"!\"}]}";

    /**
     * 启动插件并完成注册。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds) throws IOException {
        startRuntime(invokeTimeoutSeconds, Collections.<String, Map<String, Object>>emptyMap());
    }

    /**
     * 启动插件并完成注册，同时下发逐脚本配置。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param scriptConfigs        逐脚本配置（脚本 id → 配置）
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds, Map<String, Map<String, Object>> scriptConfigs)
            throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(ScriptBridgeConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        python.put(PythonBridgePlugin.KEY_INTERPRETER, interpreter());
        python.put(ScriptBridgeConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        if (!scriptConfigs.isEmpty()) {
            python.put(ScriptBridgeConfig.KEY_SCRIPTS, scriptConfigs);
        }
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(
                extensions, events, registry,
                new RuntimeInfoHolder(), new ActionQueue(), Mockito.mock(SessionManager.class), new ShellIngress(new MetricsRegistry())),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations),
                events);
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("jellyfish-plugin-python"));
    }

    /**
     * 用默认超时启动插件。
     *
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime() throws IOException {
        startRuntime(5);
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
     * @return 最后一次成功的结果
     */
    private ToolCallResult invokeUntilSucceeds(String toolName, Map<String, Object> arguments,
                                               long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JellyfishException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return invokeTool(toolName, arguments);
            } catch (JellyfishException e) {
                last = e;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new AssertionError("重试窗口内始终未恢复", last);
    }

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
        Files.write(directory.resolve("main.py"), source.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 复制失败时抛出
     */
    private void installPlugin() throws IOException {
        // 与 PythonPluginLoadingTest 同一套装载方式：PF4J 认「插件目录 + plugin.properties」，
        // 目录名必须是 plugin.id——这也是 PF4J 判定插件身份的依据
        Path directory = pluginsRoot.resolve("jellyfish-plugin-python");
        Files.createDirectories(directory);
        try (InputStream stream = PythonScriptIT.class.getResourceAsStream("/plugin.properties")) {
            assertTrue(stream != null, "测试类路径上找不到 plugin.properties");
            Files.copy(stream, directory.resolve("plugin.properties"));
        }
    }

    /**
     * 判断本机是否有可用的解释器。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean interpreterAvailable() {
        try {
            Process process = new ProcessBuilder(interpreter(), "--version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            process.destroyForcibly();
            return finished && code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 取解释器路径，允许用系统属性覆盖（例如指向虚拟环境里的解释器）。
     *
     * @return 解释器路径或命令名
     */
    private static String interpreter() {
        return System.getProperty("jellyfish.test.python", PythonBridgePlugin.DEFAULT_INTERPRETER);
    }

}
