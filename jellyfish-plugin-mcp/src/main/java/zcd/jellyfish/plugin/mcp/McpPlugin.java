package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 官方 MCP 客户端插件：连上 stdio MCP server，把它的工具接入内核。
 * <p>
 * <b>连接在启动之后才发生，且不在启动线程上做完</b>：MCP 的工具清单只有连上才知道，
 * 而连上要起进程、要握手、要等对面的回应。把这些塞进 {@code start()} 的同步路径，
 * 等于让「某个 server 的安装有问题」变成「内核起不来」——这与脚本桥接插件
 * 「{@code start()} 期零进程、解释器缺失不影响内核启动」是同一条纪律。
 * <p>
 * <b>但也不完全不等</b>：{@code startupWaitSeconds}（缺省 5 秒）内会等一等。
 * 一个只等零秒的实现会让「第一次问它问题时工具还没到」成为每个人的第一印象，
 * 而绝大多数本地 server（{@code npx} 之类）一两秒内就绪。超过这个窗口就转异步，
 * 连接成功时工具会自己出现——{@code ToolCatalog} 每轮现取注册表，不需要任何人通知它。
 * <p>
 * <b>工具是运行期注册的</b>：这是本插件依赖「注册窗口是插件存活期」那条契约的地方，
 * 也是它与所有既有插件的根本差别——那些插件的能力在 {@code start()} 里就是已知的。
 * <p>
 * <b>不支持 sampling / elicitation</b>：{@code initialize} 里不声明，真被请求时回一条明确的
 * {@code -32601}。这两个能力要求「插件反过来向内核要东西」，而本项目的能力边界是
 * 「插件只能注册回调、订阅事件、发布事件」。声明了却办不到比不声明更糟：
 * server 会按「客户端支持」去规划它的行为。
 * <p>
 * <b>断开会自动重连，退避封顶，用尽就停</b>：server 进程被杀、被 OOM 带走、或第一次就没起来，
 * 都会进同一套退避重试（{@link #RECONNECT_INITIAL_MILLIS} 起、每次翻倍、封顶
 * {@link #RECONNECT_MAX_MILLIS}，共 {@link #RECONNECT_MAX_ATTEMPTS} 次，合计约一分钟）。
 * 这条与「启动期失败不让内核起不来」是同一个立场：MCP server 起不来是常态而不是异常。
 * 但**不无限重试**——每次尝试都可能真的起一遍进程（{@code npx} 还会拉网），一个装坏了的
 * server 不该让用户一直为此付费；用尽之后停在 FAILED 并写明「{@code /reload} 可重试」。
 * 重连期间该 server 的工具会被注销：留着它们，模型会反复调用一批注定失败的工具。
 *
 * @author zcd
 */
public final class McpPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpPlugin.class);

    /** {@code /mcp} 命令名。 */
    private static final String COMMAND_MCP = "mcp";

    /** 等待连接线程收尾的上限（毫秒）：够它走完一次「失败就返回」的路径，又不至于拖住停止。 */
    private static final long CONNECTOR_JOIN_MILLIS = 2000L;

    /** 重连退避的初始间隔（毫秒）。 */
    static final long RECONNECT_INITIAL_MILLIS = 1000L;

    /** 重连退避的上限（毫秒）：再长就等于放弃了，而用户通常已经发现并去改了配置。 */
    static final long RECONNECT_MAX_MILLIS = 60000L;

    /** 一次断开最多再试几次（不含首次连接那次）：退避 1/2/4/8/16/32 秒，合计约一分钟。 */
    static final int RECONNECT_MAX_ATTEMPTS = 6;

    /** 当前配置。 */
    private volatile McpConfig config;

    /** 共享状态。 */
    private volatile McpRegistry registry;

    /** 二进制内容落盘器。 */
    private volatile McpMediaSpill spill;

    /** 工具注册器。 */
    private volatile McpToolRegistrar registrar;

    /** 传输工厂：生产实现起子进程，测试注入内存通道。 */
    private final McpTransport.Factory transportFactory;

    /** 重连退避的初始间隔（毫秒）。 */
    private final long reconnectInitialMillis;

    /** 重连退避的上限（毫秒）。 */
    private final long reconnectMaxMillis;

    /** 一次断开最多再试几次。 */
    private final int reconnectMaxAttempts;

    /**
     * 已建立的连接，按 server 标识索引。
     * <p>
     * 用映射而不是清单：重连会把同一个 server 的连接换成一个新对象，而「当前是哪一条」必须
     * 只有一个答案——清单里同时留着新旧两条，断开通知与 {@code stop()} 就会各关一条。
     * <p>
     * <b>访问必须全部在 {@link #connectionLock} 内</b>：「检查是否正在停止」与「把连接记进来」
     * 必须是同一个原子动作，否则停止时取的快照就漏得掉刚建好的那条（见 {@link #adopt}）。
     */
    private final Map<String, McpServerConnection> connections =
            new LinkedHashMap<String, McpServerConnection>();

    /** 「是否正在停止」与「连接清单」的互斥锁。 */
    private final Object connectionLock = new Object();

    /** 待重连的 server 队列：由连接线程消费，因此只有一个消费者。 */
    private final BlockingQueue<McpServerConfig> reconnectQueue =
            new LinkedBlockingQueue<McpServerConfig>();

    /** 已经在队列里等着重连的 server 标识，用于去重。 */
    private final Set<String> queuedForReconnect = ConcurrentHashMap.newKeySet();

    /** 连接线程。 */
    private volatile Thread connector;

    /** 是否正在停止：连接线程与工具接收方都靠它提前退出。 */
    private volatile boolean stopping;

    /**
     * 构造插件（PF4J 用）。
     */
    public McpPlugin() {
        this(McpStdioTransport::start);
    }

    /**
     * 构造插件，使用指定的传输工厂。
     *
     * @param transportFactory 传输工厂，不可为 {@code null}
     */
    McpPlugin(McpTransport.Factory transportFactory) {
        this(transportFactory, RECONNECT_INITIAL_MILLIS, RECONNECT_MAX_MILLIS, RECONNECT_MAX_ATTEMPTS);
    }

    /**
     * 构造插件，并指定重连退避。
     * <p>
     * 退避可注入只为一件事：<b>让「重试用尽」这条路径能被确定性地测到</b>——按缺省值，
     * 那条用例要真等一分钟。除此之外没有别的调用方会传它（生产只有那两个构造器）。
     *
     * @param transportFactory      传输工厂，不可为 {@code null}
     * @param reconnectInitialMillis 退避初始间隔（毫秒）
     * @param reconnectMaxMillis     退避上限（毫秒）
     * @param reconnectMaxAttempts   一次断开最多再试几次
     */
    McpPlugin(McpTransport.Factory transportFactory, long reconnectInitialMillis,
              long reconnectMaxMillis, int reconnectMaxAttempts) {
        this.transportFactory = transportFactory;
        this.reconnectInitialMillis = reconnectInitialMillis;
        this.reconnectMaxMillis = reconnectMaxMillis;
        this.reconnectMaxAttempts = reconnectMaxAttempts;
    }

    @Override
    public synchronized void start(PluginContext context) {
        // 先解析配置：值非法属配置错误，启动期就该让人看见，而不是压到第一次调用
        McpConfig parsed = McpConfig.from(context.configuration());
        McpRegistry parsedRegistry = new McpRegistry();
        for (McpServerConfig server : parsed.servers()) {
            parsedRegistry.register(server.id(),
                    server.enabled() ? McpRegistry.State.PENDING : McpRegistry.State.DISABLED,
                    server.enabled() ? "" : "配置里 enabled=false");
        }
        config = parsed;
        registry = parsedRegistry;
        spill = new McpMediaSpill(McpMediaSpill.defaultBaseDirectory());
        registrar = new McpToolRegistrar(context, parsedRegistry);
        stopping = false;
        // 台账命令无论有没有 server 都注册：连不上时的唯一答案就在这里
        context.handle(CommandRequest.class, COMMAND_MCP,
                new CommandDescriptor("查看 MCP server 的连接状态与已注册工具", null, null, false),
                request -> CommandResult.ok(McpLedger.render(parsed, parsedRegistry)));
        if (parsed.askWriteTools()) {
            context.contribute(PermissionCheckRequest.class,
                    new McpPermissionContribution(parsedRegistry, parsed));
        }
        if (parsed.enabledServers().isEmpty()) {
            LOG.warn("mcp 插件已启动但没有任何启用的 server：在 "
                    + "plugins.configurations.jellyfish-plugin-mcp.servers 里配置");
            return;
        }
        startConnector(parsed, parsedRegistry);
    }

    /**
     * 停止插件。
     * <p>
     * <b>顺序是「立旗 → 叫停连接线程 → 关连接 → 等它收尾」</b>：立旗与取快照在同一把锁内，
     * 因此不会再有连接溜进清单；关连接要排在等之前，因为连接线程此刻可能正卡在握手等应答上，
     * 而叫醒在途请求的正是 {@code close()}——先等就会白等到超时。
     * <p>
     * 等的是 {@code mcp-connector} 线程本身：AGENTS.md 要求「产生注册的后台线程必须在
     * {@code stop()} 返回前停下来」。它有界（{@link #CONNECTOR_JOIN_MILLIS}），
     * 因为一个卡在进程启动或读管道上的线程无法被强杀——那种情况只能如实告警。
     */
    @Override
    public synchronized void stop() {
        List<McpServerConnection> snapshot;
        synchronized (connectionLock) {
            stopping = true;
            snapshot = new ArrayList<McpServerConnection>(connections.values());
            connections.clear();
        }
        Thread thread = connector;
        connector = null;
        if (thread != null) {
            thread.interrupt();
        }
        for (McpServerConnection connection : snapshot) {
            closeQuietly(connection);
        }
        awaitConnector(thread);
        McpToolRegistrar currentRegistrar = registrar;
        if (currentRegistrar != null) {
            currentRegistrar.closeAll();
        }
        McpMediaSpill currentSpill = spill;
        if (currentSpill != null) {
            currentSpill.cleanup();
        }
        config = null;
        registry = null;
        registrar = null;
        spill = null;
        reconnectQueue.clear();
        queuedForReconnect.clear();
    }

    /**
     * 有界地等连接线程收尾。
     *
     * @param thread 连接线程，可为 {@code null}
     */
    private static void awaitConnector(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(CONNECTOR_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            // 它已经起不了新连接（立旗在前），但还卡在某次启动或读上：如实说一声，
            // 因为这正是「停止之后还有东西在跑」的那类现场
            LOG.warn("mcp 连接线程未能在 {}ms 内结束，可能仍在收尾", Long.valueOf(CONNECTOR_JOIN_MILLIS));
        }
    }

    /**
     * 把一个 server 交给重连队列。
     * <p>
     * 去重是必要的：一次「连不上」可能被两处看到（连接对象自己上报断开、以及刚失败的
     * {@code connectOne}），而重连本身要去起进程——同一个 server 排两次就是白起一遍。
     *
     * @param server 服务配置，不可为 {@code null}
     */
    private void scheduleReconnect(McpServerConfig server) {
        if (stopping || !queuedForReconnect.add(server.id())) {
            return;
        }
        reconnectQueue.add(server);
    }

    /**
     * 把一条建好的连接记进清单。
    /**
     * 起连接线程并可选地等待一小段时间。
     * <p>
     * <b>等的是「首轮连接尝试做完」而不是「每个 server 都连上」</b>：连接线程按顺序处理每个 server，
     * 一个 server 卡到超时不应该让后面的一个都没机会。因此等待窗口一到就返回，
     * 连接线程继续做后面的事（首轮剩下的 server，以及其后的重连）。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     */
    private void startConnector(McpConfig parsed, McpRegistry parsedRegistry) {
        CountDownLatch finished = new CountDownLatch(1);
        Thread thread = new Thread(() -> connectorLoop(parsed, parsedRegistry, finished), "mcp-connector");
        thread.setDaemon(true);
        connector = thread;
        thread.start();
        int waitSeconds = parsed.startupWaitSeconds();
        if (waitSeconds > 0) {
            try {
                finished.await(waitSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("mcp 插件已启动: servers={} 已连接={} 工具={}", parsed.enabledServers().size(),
                countConnected(parsedRegistry), countTools(parsedRegistry));
    }

    /**
     * 连接线程的主体：先做一轮首轮连接，然后一直消费重连队列。
     * <p>
     * <b>两件事一条线程</b>：重连必须串行（同一个 server 的两次尝试不能同时起两个进程），
     * 而首轮连接本来就是串行的——各起一条线程只会多出「两条线程都要在 {@code stop()} 前停」
     * 这个额外的收口点。等待窗口（{@code startupWaitSeconds}）等的是首轮结束，
     * 因此 {@code finished} 在第一轮之后就放开，之后本线程可能还要活很久（重连队列没空或阻塞在取任务上）。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     * @param finished       首轮连接结束信号
     */
    private void connectorLoop(McpConfig parsed, McpRegistry parsedRegistry, CountDownLatch finished) {
        try {
            connectAll(parsed, parsedRegistry);
        } finally {
            finished.countDown();
        }
        while (!stopping) {
            McpServerConfig server;
            try {
                server = reconnectQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            queuedForReconnect.remove(server.id());
            if (stopping) {
                return;
            }
            reconnect(parsed, parsedRegistry, server);
        }
    }

    /**
     * 首轮连接：每个启用的 server 各试一次。
     * <p>
     * <b>逐个而不是并发</b>：一个 server 的连接失败要能被独立记下（哪个失败、为什么），
     * 而并发连接会把日志与状态混在一起。server 数量是个人配置级别的量级，串行足够。
     * <p>
     * <b>首轮失败就排重连，不当场重试</b>：当场重试会把「后面那些 server 先连上」推迟几十秒，
     * 而它们完全无辜。失败者进队列，等首轮跑完慢慢退避。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     */
    private void connectAll(McpConfig parsed, McpRegistry parsedRegistry) {
        for (McpServerConfig server : parsed.enabledServers()) {
            if (stopping) {
                return;
            }
            if (!connectOne(parsed, parsedRegistry, server) && !stopping) {
                scheduleReconnect(server);
            }
        }
    }

    /**
     * 按退避重试一个 server，直到连上或用尽次数。
     * <p>
     * <b>这里做的是「重新连」而不是「重启进程」</b>：起进程、握手、拉清单本来就都在
     * {@link #connectOne} 里，重连只是再走一遍——旧连接已经在上报断开时被收掉了。
     * <p>
     * <b>失败的现场要留在台账上</b>：每轮失败的原因由连接对象自己写进「最近错误」，
     * 因此最后放弃时只改状态的说明文字，不去覆盖 {@code lastError}——用户最需要看到的
     * 恰恰是「上次到底是为什么起不来」（命令写错、缺解释器、握手被拒），而不是「重试了 6 次」。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     * @param server         服务配置
     */
    private void reconnect(McpConfig parsed, McpRegistry parsedRegistry, McpServerConfig server) {
        long backoff = reconnectInitialMillis;
        for (int attempt = 1; attempt <= reconnectMaxAttempts; attempt++) {
            if (stopping) {
                return;
            }
            parsedRegistry.noteState(server.id(), McpRegistry.State.RECONNECTING,
                    "连接已断开，第 " + attempt + " 次重连尝试");
            if (connectOne(parsed, parsedRegistry, server)) {
                LOG.info("mcp server 已重连: id={} 第 {} 次尝试", server.id(), Integer.valueOf(attempt));
                return;
            }
            if (stopping || attempt == reconnectMaxAttempts) {
                break;
            }
            if (!sleepQuietly(backoff)) {
                return;
            }
            backoff = Math.min(backoff * 2L, reconnectMaxMillis);
        }
        if (!stopping) {
            parsedRegistry.noteState(server.id(), McpRegistry.State.FAILED,
                    "重连 " + reconnectMaxAttempts + " 次仍失败，已放弃（/reload 可重试）");
        }
    }

    /**
     * 静默地等待一段时间。
     *
     * @param millis 毫秒数
     * @return 等满返回 {@code true}；被中断返回 {@code false}（调用方应当就此收手）
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 连接单个 server。
     * <p>
     * <b>先入表再连接，且入表要拿着锁问一句「停了没」</b>：连接线程顶部的停止检查挡不住这段窗口
     * ——它检查完到这里之间隔着构造与整个 {@code connect()}，而 {@code connect()} 是要起子进程的。
     * 因此「停止」与「入表」必须是同一个原子动作：没停才入表（停止时的快照就一定收得到它），
     * 停了就根本不去连（此时连进程都还没起，没有东西要收）。
     *
     * @param parsed         配置
     * @param parsedRegistry 共享状态
     * @param server         服务配置
     * @return 连上返回 {@code true}；「已停止」或连接失败返回 {@code false}
     */
    private boolean connectOne(McpConfig parsed, McpRegistry parsedRegistry, McpServerConfig server) {
        // 工具接收方需要「它所属的那个连接」，而连接又要先有接收方才能构造：
        // 用一个一元素数组把这两件事的顺序解开。数组是 final 的，每个 server 一份
        final McpServerConnection[] holder = new McpServerConnection[1];
        Consumer<List<McpToolDefinition>> sink = tools -> {
            McpServerConnection connection = holder[0];
            if (connection == null || stopping || !isCurrent(server.id(), connection)) {
                return;
            }
            try {
                registrar.apply(connection, server, tools);
            } catch (RuntimeException e) {
                LOG.warn("注册 MCP 工具失败: id={}", server.id(), e);
                parsedRegistry.noteWarning(server.id(), "工具注册失败：" + e.getMessage());
            }
        };
        McpServerConnection connection =
                new McpServerConnection(server, parsed, parsedRegistry, sink, spill, transportFactory);
        holder[0] = connection;
        connection.onDisconnected(() -> handleDisconnected(server, connection));
        if (!adopt(server.id(), connection)) {
            return false;
        }
        try {
            connection.connect();
            return true;
        } catch (RuntimeException e) {
            // 单个 server 连不上不该让插件 FAILED：其余 server 与「没有 MCP」都还能用。
            // 失败状态由连接对象自己记（它才知道握手停在哪一步），这里只负责收尾资源
            discard(server.id(), connection);
            closeQuietly(connection);
            return false;
        }
    }

    /**
     * 处理「连接被对面断掉」。
     * <p>
     * 跑在读线程上（读循环的收尾），因此这里只做快动作：收掉旧连接的资源、把工具摘掉、
     * 把 server 排进重连队列。真正的重连（要起进程、要退避）在连接线程上做。
     * <p>
     * <b>工具必须摘掉</b>：进程已经没了，而注册表里那批工具还挂着。留着它们，模型会继续调用
     * 一批每次都以「未连接」收场的工具——那份清单等于在撒谎，而模型据此规划出来的下一步
     * 全都白费。与 {@code G-23} 那批「没扫完不要说是空的」是同一条：<b>宁可少给能力，
     * 也不要给一层看起来能用、实际必然失败的能力</b>。
     *
     * @param server     服务配置，不可为 {@code null}
     * @param connection 断开的那条连接，不可为 {@code null}
     */
    private void handleDisconnected(McpServerConfig server, McpServerConnection connection) {
        if (!discard(server.id(), connection)) {
            // 已经不是「当前那条」了（正常关闭或已被替换）：别去动它的继任者
            return;
        }
        // 旧连接自己的资源要收干净：子进程没了，但通知线程池与管道句柄还挂着
        closeQuietly(connection);
        McpToolRegistrar currentRegistrar = registrar;
        if (currentRegistrar != null) {
            // 先关处理器再摘标记（registrar 内部就是这个顺序），因此不存在「处理器还在、
            // 标记已经没了」的免审批窗口
            currentRegistrar.close(server.id());
        }
        McpRegistry currentRegistry = registry;
        if (currentRegistry != null) {
            currentRegistry.noteState(server.id(), McpRegistry.State.RECONNECTING, "连接已断开，准备重连");
        }
        if (stopping) {
            return;
        }
        scheduleReconnect(server);
    }

    /**
     * 判断一条连接是不是某个 server 当前的那条。
     * <p>
     * <b>取清单的一方必须自己确认「我这条还作数吗」</b>：一次清单拉取是「发请求、等应答」，
     * 而在这段时间里连接完全可能已经断开、被重连换掉、或者插件已经停了。不确认的后果是
     * 把一批工具重新注册回一条已经死掉的连接上——那正是 {@code G-14} 要消掉的那种「撒谎的清单」，
     * 只不过这次是拆掉之后又自己装回去。
     *
     * @param serverId   服务标识，不可为 {@code null}
     * @param connection 连接，不可为 {@code null}
     * @return 仍是当前那条返回 {@code true}
     */
    private boolean isCurrent(String serverId, McpServerConnection connection) {
        synchronized (connectionLock) {
            return connections.get(serverId) == connection;
        }
    }

    /**
     * 把一条连接记进清单。
     *
     * @param serverId   服务标识，不可为 {@code null}
     * @param connection 连接，不可为 {@code null}
     * @return 停止时返回 {@code false}（调用方不得再去连它），否则返回 {@code true}
     */
    private boolean adopt(String serverId, McpServerConnection connection) {
        McpServerConnection previous;
        synchronized (connectionLock) {
            if (stopping) {
                return false;
            }
            previous = connections.put(serverId, connection);
        }
        if (previous != null && previous != connection) {
            // 正常不该发生（断开时已摘掉），但真发生就留着它在外面收——两条连接指向同一个
            // server 会让「当前是哪一条」失去答案，而重连逻辑依赖这个答案。
            // 关连接放在锁外：它要 join 读线程，占着锁会让正在收尾的人排队等它
            LOG.warn("同一个 server 出现了两条连接，旧的将被关闭: id={}", serverId);
            closeQuietly(previous);
        }
        return true;
    }

    /**
     * 把一条连接从清单里摘掉。
     *
     * @param serverId   服务标识，不可为 {@code null}
     * @param connection 连接，不可为 {@code null}
     * @return 摘掉的是这条连接返回 {@code true}
     */
    private boolean discard(String serverId, McpServerConnection connection) {
        synchronized (connectionLock) {
            return connections.remove(serverId, connection);
        }
    }

    /**
     * 安静地关闭一条连接。
     *
     * @param connection 连接，可为 {@code null}
     */
    private static void closeQuietly(McpServerConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException e) {
            LOG.warn("关闭 MCP 连接失败: id={}", connection.serverId(), e);
        }
    }

    /**
     * 统计已连接的 server 数。
     *
     * @param registryOfRun 共享状态
     * @return 数量
     */
    private static int countConnected(McpRegistry registryOfRun) {
        int count = 0;
        for (McpRegistry.ServerStatus status : registryOfRun.snapshot()) {
            if (status.state() == McpRegistry.State.CONNECTED) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计已注册工具数。
     *
     * @param registryOfRun 共享状态
     * @return 数量
     */
    private static int countTools(McpRegistry registryOfRun) {
        int count = 0;
        for (McpRegistry.ServerStatus status : registryOfRun.snapshot()) {
            count += status.toolCount();
        }
        return count;
    }
}
