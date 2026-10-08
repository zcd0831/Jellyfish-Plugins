package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册器：把「某个 server 当前提供哪些工具」落到内核的扩展点上。
 * <p>
 * <b>每个 server 一个子上下文</b>：注册走 {@code PluginContext.subContext(serverId)}，落在
 * {@code jellyfish-plugin-mcp::<serverId>} 下。诊断输出因此能指出「这个工具是哪个 server 提供的」，
 * 而框架卸载时按命名空间一次性把整份注册收干净。
 * <p>
 * <b>整体替换而不是增量增删</b>：{@code tools/list} 返回的是「当前全部」，而工具的名片
 * （描述、参数 Schema、只读标记）都可能变。增量增删要判断「这个工具是新的还是改过的」，
 * 而那条判断一旦漏了「改过」这一档，模型就会拿着旧 Schema 去调新工具。
 * 整体替换的代价是注册表在极短窗口内少了几个名字——而那个窗口里最坏的结果是模型看不到这个工具。
 * <p>
 * <b>单个工具注册失败不拖垮其余</b>：名字与内置工具撞了（关掉前缀时的 {@code read_file}）
 * 只记一条告警并跳过它。这与脚本桥接插件「单个脚本问题只记台账」是同一条取舍。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class McpToolRegistrar {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpToolRegistrar.class);

    /** 插件上下文：注册与子上下文的来源。 */
    private final PluginContext context;

    /** 共享状态。 */
    private final McpRegistry registry;

    /** server 标识 → 已注册的句柄。 */
    private final Map<String, List<Subscription>> registered =
            new ConcurrentHashMap<String, List<Subscription>>();

    /**
     * 构造注册器。
     *
     * @param context  插件上下文，不可为 {@code null}
     * @param registry 共享状态，不可为 {@code null}
     */
    McpToolRegistrar(PluginContext context, McpRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    /**
     * 用新的工具清单替换某个 server 的注册。
     * <p>
     * <b>四步的顺序本身就是防线</b>：关旧处理器 → 装新标记 → 注册新处理器 → 摘掉没注册成的名字。
     * 每一步之后，「此刻能被调用」的名字都必然已经带着标记：
     * <ul>
     *   <li>第 1 步只关处理器，标记留到第 2 步一起换——若反过来先摘标记，那几步里旧处理器还在，
     *       写类工具就是<b>不需要审批</b>的；</li>
     *   <li>第 2 步装的是整份清单（{@link McpRegistry#replaceTools} 是原子替换），因此第 3 步里
     *       每注册成一个名字，它已经在表里了。代价是清单里那些<b>注定注册不上</b>的名字
     *       （与内置工具或别的插件撞车）在第 4 步之前也带着标记，最长也就是一次刷新窗口——
     *       最坏结果是给那个名字多弹一次审批，方向偏严；</li>
     *   <li>第 4 步摘掉的是「注册不成的名字」，摘完只会让判定回到「不是我们的工具」。</li>
     * </ul>
     *
     * @param invoker       工具调用发起方，不可为 {@code null}
     * @param server        服务配置，不可为 {@code null}
     * @param tools         新的工具清单，不可为 {@code null}
     */
    void apply(McpInvoker invoker, McpServerConfig server, List<McpToolDefinition> tools) {
        closeHandlers(server.id());
        registry.replaceTools(server.id(), tools);
        List<Subscription> created = new ArrayList<Subscription>(tools.size());
        Set<String> registeredNames = new LinkedHashSet<String>();
        PluginContext serverContext = context.subContext(server.id());
        long timeoutMillis = server.callTimeoutSeconds() * 1000L;
        for (McpToolDefinition tool : tools) {
            try {
                created.add(serverContext.handle(ToolCallRequest.class, tool.qualifiedName(),
                        tool.descriptor(), new McpToolCaller(tool, invoker, timeoutMillis)));
                registeredNames.add(tool.qualifiedName());
            } catch (RuntimeException e) {
                // 名字被占了（关掉前缀时最容易撞上内置工具）：跳过它，让其余工具照常可用
                LOG.warn("MCP 工具注册失败，已跳过: server={} tool={} reason={}", server.id(),
                        tool.qualifiedName(), e.getMessage());
                registry.noteWarning(server.id(),
                        "工具 " + tool.qualifiedName() + " 注册失败：" + e.getMessage());
            }
        }
        // 注册不成的名字得从标记表里摘掉：留着它，权限处理器就会把占了那个名字的「内置工具」
        // 认成我们的写类工具，于是内置工具平白多一道审批（-cli/-server 下 ASK 即拒绝）
        registry.retainTools(server.id(), registeredNames);
        registered.put(server.id(), created);
    }

    /**
     * 注销某个 server 的全部工具。
     *
     * @param serverId 服务标识
     */
    void close(String serverId) {
        closeHandlers(serverId);
        registry.removeServer(serverId);
    }

    /**
     * 注销全部工具。
     */
    void closeAll() {
        for (String serverId : new ArrayList<String>(registered.keySet())) {
            close(serverId);
        }
    }

    /**
     * 只关处理器、不动标记。
     *
     * @param serverId 服务标识
     */
    private void closeHandlers(String serverId) {
        List<Subscription> previous = registered.remove(serverId);
        if (previous == null) {
            return;
        }
        for (Subscription subscription : previous) {
            subscription.close();
        }
    }
}
