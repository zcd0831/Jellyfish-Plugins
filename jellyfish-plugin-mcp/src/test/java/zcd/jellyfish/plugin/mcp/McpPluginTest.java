package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpPlugin} 的装配测试。
 * <p>
 * <b>刻意不配置任何启用的 server</b>：那会让插件真的去 fork 子进程，
 * 而单元测试不访问外部资源（真实 server 的端到端在 {@code mcp-it} profile 里）。
 * 这里要钉的是「注册了哪些扩展点」与「配置非法时在启动期就抛」。
 *
 * @author zcd
 */
@DisplayName("mcp 插件装配")
class McpPluginTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-mcp";

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
        String output = extensions.<CommandRequest, zcd.jellyfish.api.extension.CommandResult>handler(
                CommandRequest.class, "mcp")
                .handle(new CommandRequest("mcp",
                        zcd.jellyfish.api.extension.CommandArguments.EMPTY, "s1"))
                .getOutput();

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
        assertEquals(0, countThreads("mcp-connect"), "连接线程必须在 stop 返回前结束");
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
        waitForConnectorToFinish();

        // Then：第二个 server 连进程都不该起
        assertEquals(1, births.size(), "停止之后不该再连下一个 server");
        assertFalse(births.get(0).isAlive(), "那一刻正在出生的那条也已经被收掉");
    }

    /**
     * 等连接线程结束。
     *
     * @throws InterruptedException 等待被中断时抛出
     */
    private static void waitForConnectorToFinish() throws InterruptedException {
        for (int round = 0; round < 100; round++) {
            if (countThreads("mcp-connect") == 0) {
                return;
            }
            Thread.sleep(20L);
        }
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
