package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpPlugin} 的装配测试。
 * <p>
 * <b>默认不配置任何启用的 server</b>：那会让插件真的去 fork 子进程，
 * 而单元测试不访问外部资源（真实 server 的端到端在 {@code mcp-it} profile 里）。
 * 需要驱动「连上、断开、重连」的用例则注入内存传输（{@link FakeTransport}），
 * 并把重连退避压到毫秒级——否则一条「重试用尽」的用例要真等一分钟。
 * <p>
 * <b>观察点主要是内核的扩展注册表</b>：工具在不在，就是它在注册表里有没有处理器。
 * 这条口径比读内部状态更接近使用者的感受——模型能看到的正是注册表。
 *
 * @author zcd
 */
@DisplayName("mcp 插件装配")
class McpPluginTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-mcp";

    /** 测试用的工具展开名（前缀 + server id + 原名）。 */
    private static final String TOOL = "mcp__fs__ping";

    /** 共用注册表。 */
    private TypeRegistry typeRegistry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 被测插件。 */
    private McpPlugin plugin;

    @BeforeEach
    void setUp() {
        typeRegistry = new TypeRegistry();
        extensions = new ExtensionRegistry(typeRegistry);
        events = new EventChannel(EventChannelOptions.defaults(), typeRegistry);
        events.start();
        plugin = new McpPlugin();
    }

    @AfterEach
    void tearDown() {
        plugin.stop();
        events.close();
    }

    @Test
    @DisplayName("即便没有任何 server，/mcp 台账也必须注册：那是「连不上」的唯一答案")
    void start_should_registerLedgerCommand_withoutServers() {
        // When
        plugin.start(context(new HashMap<String, Object>()));

        // Then
        assertEquals(1, extensions.handlers(CommandRequest.class, "mcp").size());
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__x__y").isEmpty());
    }

    @Test
    @DisplayName("默认开启写类工具审批，因此权限拦截应被注册")
    void start_should_registerPermissionContribution_byDefault() {
        // When
        plugin.start(context(new HashMap<String, Object>()));

        // Then
        assertEquals(1, extensions.bindings(PermissionCheckRequest.class, null).size());
    }

    @Test
    @DisplayName("关掉审批开关后不应注册权限拦截")
    void start_should_skipPermissionContribution_when_askingDisabled() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_ASK_WRITE_TOOLS, Boolean.FALSE);

        // When
        plugin.start(context(values));

        // Then
        assertTrue(extensions.bindings(PermissionCheckRequest.class, null).isEmpty());
        assertEquals(1, extensions.handlers(CommandRequest.class, "mcp").size());
    }

    @Test
    @DisplayName("/mcp 命令应可执行并返回台账文本")
    void command_should_renderLedger() throws Exception {
        // Given
        plugin.start(context(new HashMap<String, Object>()));

        // When
        String output = ledger();

        // Then
        assertTrue(output.contains("mcp 插件"));
        assertTrue(output.contains("没有配置任何 MCP server"));
    }

    @Test
    @DisplayName("配置非法时应启动期就抛：不让它变成「工具怎么一个都没有」这种隐式失效")
    void start_should_throw_when_configInvalid() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "fs");
        // 缺少必填的 command
        values.put(McpConfig.KEY_SERVERS, Collections.singletonList(server));

        // When / Then
        assertThrows(JellyfishException.class, () -> plugin.start(context(values)));
    }

    @Test
    @DisplayName("停止赶在连接中途：那条连接必须被收掉，且连接线程必须在 stop 返回前结束")
    void stop_should_closeConnectionAndJoinConnector_whenStoppingMidConnect() throws Exception {
        // Given：一个「正在起进程」的 server——传输的出生被挡住，好让 stop() 精确落在那一刻
        final CountDownLatch born = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final FakeTransport transport = new FakeTransport();
        plugin = new McpPlugin(config -> {
            born.countDown();
            try {
                release.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return transport;
        });
        Map<String, Object> values = new HashMap<String, Object>();
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "fs");
        server.put("command", "echo");
        values.put(McpConfig.KEY_SERVERS, Collections.singletonList(server));
        // 不等连接线程：本用例要的是「它正卡在起进程时被停掉」，等待窗口只会白等 5 秒
        values.put(McpConfig.KEY_STARTUP_WAIT_SECONDS, Integer.valueOf(0));
        plugin.start(context(values));
        assertTrue(born.await(2L, TimeUnit.SECONDS), "连接线程应已开始起进程");

        // When
        release.countDown();
        plugin.stop();

        // Then：进程不能活在停止之后，连接线程也不能还在跑
        assertFalse(transport.isAlive(), "停止之后出生的传输必须被收掉");
        assertEquals(0, countThreads("mcp-connector"), "连接线程必须在 stop 返回前结束");
    }

    @Test
    @DisplayName("停止之后不再连剩下的 server：不会在停止之后还生出新的子进程")
    void connectAll_should_notConnectRemainingServers_whenStopped() throws Exception {
        // Given：两个 server，第一个在「出生」的一瞬间触发停止
        final List<FakeTransport> births = Collections.synchronizedList(new ArrayList<FakeTransport>());
        plugin = new McpPlugin(config -> {
            FakeTransport created = new FakeTransport();
            births.add(created);
            plugin.stop();
            return created;
        });
        List<Map<String, Object>> servers = new ArrayList<Map<String, Object>>();
        servers.add(serverConfig("first"));
        servers.add(serverConfig("second"));
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_SERVERS, servers);
        values.put(McpConfig.KEY_STARTUP_WAIT_SECONDS, Integer.valueOf(0));

        // When
        plugin.start(context(values));
        assertTrue(await(() -> countThreads("mcp-connector") == 0, 2_000L), "连接线程应已结束");

        // Then：第二个 server 连进程都不该起
        assertEquals(1, births.size(), "停止之后不该再连下一个 server");
        assertFalse(births.get(0).isAlive(), "那一刻正在出生的那条也已经被收掉");
    }

    @Test
    @DisplayName("server 进程退出后，它的工具必须从注册表摘掉：不能留一批必然失败的工具")
    void disconnect_should_unregisterTools() throws Exception {
        // Given：一个连得上、带一个工具的 server
        final FakeTransport transport = new FakeTransport();
        transport.responder(McpPluginTest::respondAsServer);
        plugin = new McpPlugin(config -> transport, 1L, 2L, 3);
        plugin.start(context(values(Collections.singletonList(serverConfig("fs")))));
        assertTrue(awaitTool(true, 3_000L), "工具应先注册上");

        // When：对面没了（进程退出，在读线程看来就是读到 null）
        transport.close();

        // Then：工具必须摘掉。留着它，模型会继续调用一个每次都「未连接」的工具，
        // 并据此规划下一步——那份清单等于在撒谎
        assertTrue(awaitTool(false, 3_000L), "进程退出后工具必须被摘掉");
    }

    @Test
    @DisplayName("断开之后按退避自动重连，工具会自己回来")
    void disconnect_should_reconnect() throws Exception {
        // Given：每次连接都发一条新的传输
        final List<FakeTransport> created =
                Collections.synchronizedList(new ArrayList<FakeTransport>());
        plugin = new McpPlugin(config -> {
            FakeTransport transport = new FakeTransport();
            transport.responder(McpPluginTest::respondAsServer);
            created.add(transport);
            return transport;
        }, 1L, 2L, 3);
        plugin.start(context(values(Collections.singletonList(serverConfig("fs")))));
        assertTrue(awaitTool(true, 3_000L), "工具应先注册上");

        // When
        created.get(0).close();

        // Then：新连接建起来、工具重新注册——这条断言在「没有重连」时必然失败（永远只有 1 条）
        assertTrue(await(() -> created.size() >= 2 && hasTool(), 5_000L),
                "重连之后工具应重新出现，实际连接数=" + created.size());
    }

    @Test
    @DisplayName("重试用尽后停在失败终态，并说清怎么恢复、真实原因不被覆盖")
    void reconnect_should_giveUp_afterMaxAttempts() throws Exception {
        // Given：一个永远不回话的 server（initialize 1 秒超时 → 每次尝试都失败）
        plugin = new McpPlugin(config -> new FakeTransport(), 1L, 2L, 3);
        Map<String, Object> server = serverConfig("fs");
        server.put("connectTimeoutSeconds", Integer.valueOf(1));
        plugin.start(context(values(Collections.singletonList(server))));

        // When：等它把 3 次重试用尽
        assertTrue(await(() -> ledger().contains("已放弃"), 15_000L),
                "用尽之后应停在失败终态，台账：" + ledger());

        // Then：放弃的话要给出恢复路径，而「上次到底是为什么起不来」不能被它覆盖掉
        String output = ledger();
        assertTrue(output.contains("/reload 可重试"), output);
        assertTrue(output.contains("最近错误"), output);
        assertTrue(output.contains("超时"), output);
    }

    @Test
    @DisplayName("停止之后不再重连：停止是终态")
    void stop_should_notReconnect() throws Exception {
        // Given
        final List<FakeTransport> created =
                Collections.synchronizedList(new ArrayList<FakeTransport>());
        plugin = new McpPlugin(config -> {
            FakeTransport transport = new FakeTransport();
            transport.responder(McpPluginTest::respondAsServer);
            created.add(transport);
            return transport;
        }, 1L, 2L, 3);
        plugin.start(context(values(Collections.singletonList(serverConfig("fs")))));
        assertTrue(awaitTool(true, 3_000L), "工具应先注册上");
        created.get(0).close();

        // When
        plugin.stop();
        int connectedBefore = created.size();
        Thread.sleep(200L);

        // Then
        assertEquals(connectedBefore, created.size(), "停止之后不该再起新的连接");
        assertEquals(0, countThreads("mcp-connector"), "连接线程必须在 stop 返回前结束");
    }

    /**
     * 一个「标准 MCP server」的应答：握手成功、提供一个 {@code ping} 工具。
     *
     * @param message 请求消息
     * @return 应答文本；{@code null} 表示这条不回
     */
    private static String respondAsServer(ObjectNode message) {
        String method = McpJson.text(message, "method", "");
        long id = message.get("id").asLong();
        if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
            return FakeTransport.result(id, "{\"protocolVersion\":\""
                    + McpProtocol.PROTOCOL_VERSION + "\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}");
        }
        if (McpProtocol.METHOD_TOOLS_LIST.equals(method)) {
            return FakeTransport.result(id, "{\"tools\":[{\"name\":\"ping\",\"description\":\"打一下\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}");
        }
        return null;
    }

    /**
     * 判断那个工具此刻是否在内核注册表里。
     *
     * @return 在注册表里返回 {@code true}
     */
    private boolean hasTool() {
        return !extensions.handlers(ToolCallRequest.class, TOOL).isEmpty();
    }

    /**
     * 等工具在注册表里出现（或消失）。
     *
     * @param expected 期望的状态
     * @param millis   最多等待的毫秒数
     * @return 等到返回 {@code true}
     * @throws InterruptedException 等待被中断时抛出
     */
    private boolean awaitTool(boolean expected, long millis) throws InterruptedException {
        return await(() -> hasTool() == expected, millis);
    }

    /**
     * 轮询等待一个条件成立。
     * <p>
     * 这里等的是别的线程（连接线程、读线程）推着状态变化，因此只能轮询；20 毫秒的间隔
     * 比任何一次真实动作都快，不会让用例变得依赖运气。
     *
     * @param condition 条件
     * @param millis    最多等待的毫秒数
     * @return 到期时条件是否成立
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    /**
     * 取一次 {@code /mcp} 台账文本。
     *
     * @return 台账文本
     */
    private String ledger() {
        try {
            return extensions.<CommandRequest, CommandResult>handler(CommandRequest.class, "mcp")
                    .handle(new CommandRequest("mcp", CommandArguments.EMPTY, "s1"))
                    .getOutput();
        } catch (Exception e) {
            // 台账命令是个不该失败的纯渲染：这里包成非受检异常，免得每个调用点都要声明 throws
            throw new IllegalStateException("读取 /mcp 台账失败", e);
        }
    }

    /**
     * 构造带 server 清单的配置段（启动期不等，用例自己轮询）。
     *
     * @param servers server 清单
     * @return 配置映射
     */
    private static Map<String, Object> values(List<Map<String, Object>> servers) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_SERVERS, servers);
        values.put(McpConfig.KEY_STARTUP_WAIT_SECONDS, Integer.valueOf(0));
        return values;
    }

    /**
     * 构造一个 server 配置。
     *
     * @param id 服务标识
     * @return 配置映射
     */
    private static Map<String, Object> serverConfig(String id) {
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", id);
        server.put("command", "echo");
        return server;
    }

    /**
     * 数一数名字匹配指定前缀的存活线程。
     *
     * @param prefix 线程名前缀
     * @return 数量
     */
    private static int countThreads(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 构造插件上下文。
     *
     * @param configuration 配置段
     * @return 上下文
     */
    private PluginContext context(Map<String, Object> configuration) {
        return new PluginContextImpl(PluginDeclaration.of(PLUGIN_ID, configuration), extensions,
                events, Mockito.mock(SessionManager.class));
    }
}
