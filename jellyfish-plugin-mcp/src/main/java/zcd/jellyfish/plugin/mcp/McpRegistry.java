package zcd.jellyfish.plugin.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 运行期的共享状态：每个 server 的连接状态、已注册的工具、以及只读标记。
 * <p>
 * <b>为什么需要一个共享点</b>：这些事实有三个互不相识的读方——权限拦截要在「每次工具调用」的同步路径上
 * 回答「这个工具是不是只读」，{@code /mcp} 台账要回答「现在到底连上了没有」，
 * 而工具注册要在运行期不断改写它们。让连接对象自己持有，前两个读方就得去够一个随时可能被替换的引用。
 * <p>
 * <b>只读标记为什么要在这里留一份</b>：权限拦截处理器跑在同步路径上，它拿不到注册表
 * （插件看不到内核），因此只读这件事必须在注册那一刻就落在这里，而不是每次现算。
 * <p>
 * <b>状态是既成事实，读状态不推进状态</b>：连接成功、失败、收到通知分别由连接对象自己写，
 * 台账只读。这与熔断器「只有 {@code admit} / {@code recordSuccess} 能转移状态」是同一条口径。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class McpRegistry {

    /** 连接状态。 */
    enum State {

        /** 尚未开始连接（启动等待窗口内）。 */
        PENDING("等待连接"),

        /** 正在连接与握手。 */
        CONNECTING("连接中"),

        /** 已连接。 */
        CONNECTED("已连接"),

        /** 连接失败或进程已退出。 */
        FAILED("失败"),

        /** 插件已停止。 */
        STOPPED("已停止"),

        /** 配置里禁用。 */
        DISABLED("已禁用");

        /** 展示名。 */
        private final String displayName;

        /**
         * 构造状态。
         *
         * @param displayName 展示名
         */
        State(String displayName) {
            this.displayName = displayName;
        }

        /**
         * 获取展示名。
         *
         * @return 展示名
         */
        String displayName() {
            return displayName;
        }
    }

    /**
     * server 标识 → 该 server 当前工具展开名到只读标记的映射（只驱动本插件的审批策略）。
     * <p>
     * <b>按 server 存整份视图，而不是存一张「工具名 → 只读」的大表</b>：一次 {@code tools/list}
     * 就是一次整体替换，而整份替换必须是原子的——若按名字逐条删旧加新，中间那一瞬「新名字还没进表」，
     * 权限处理器会把它当成别人的工具（{@code abstain}），于是「写类工具要审批」这道闸门在刷新窗口里
     * 短暂消失。一条 {@code put} 换掉整份视图，窗口就不存在了。
     * <p>
     * 顺带解决同名互相踩：两个 server 都提供 {@code read_file} 时，替换 B 的清单不会动到 A 的标记。
     */
    private final Map<String, Map<String, Boolean>> toolsByServer =
            new ConcurrentHashMap<String, Map<String, Boolean>>();

    /** server 标识 → 运行状态；按标识排序以保证台账输出稳定。 */
    private final Map<String, ServerStatus> statuses = new ConcurrentSkipListMap<String, ServerStatus>();

    /**
     * 登记一个 server，初始状态为待连接。
     *
     * @param serverId 服务标识
     * @param state    初始状态
     * @param detail   初始说明
     */
    void register(String serverId, State state, String detail) {
        ServerStatus status = statuses.get(serverId);
        if (status == null) {
            ServerStatus created = new ServerStatus(serverId);
            ServerStatus previous = statuses.putIfAbsent(serverId, created);
            status = previous == null ? created : previous;
        }
        status.update(state, detail);
    }

    /**
     * 用一份新的工具清单替换某个 server 的工具集。
     * <p>
     * <b>整体替换而不是增量追加</b>：{@code tools/list} 返回的是「当前全部」，
     * 增量追加会让一个已被 server 删掉的工具永远留在只读标记里——而它的处理器已经不在了。
     * <p>
     * <b>替换是原子的</b>：新视图建好之后一条 {@code put} 顶掉旧的，因此不存在
     * 「旧名字已删、新名字未进」的空窗（见 {@link #toolsByServer}）。
     *
     * @param serverId 服务标识
     * @param tools    新的工具清单
     */
    void replaceTools(String serverId, List<McpToolDefinition> tools) {
        Map<String, Boolean> flags = new LinkedHashMap<String, Boolean>();
        for (McpToolDefinition tool : tools) {
            flags.put(tool.qualifiedName(), Boolean.valueOf(tool.readOnly()));
        }
        install(serverId, flags);
    }

    /**
     * 把某个 server 的工具集收窄到给定名字，其余摘掉。
     * <p>
     * 调用点是「注册失败之后」：名字与内置工具撞车、或与别的插件撞车时，内核注册不上去，
     * 这个名字就不该再留在标记表里——留着它会让权限处理器把<b>内置工具</b>认成
     * 「我们的写类工具」，于是内置工具被判成「未声明只读」要审批，而 {@code -cli}/{@code -server}
     * 下 ASK 等于拒绝，内置工具直接不可用。
     *
     * @param serverId 服务标识
     * @param keptNames 真正注册成功的工具展开名
     */
    void retainTools(String serverId, Set<String> keptNames) {
        Map<String, Boolean> current = toolsByServer.get(serverId);
        if (current == null) {
            return;
        }
        Map<String, Boolean> kept = new LinkedHashMap<String, Boolean>();
        for (Map.Entry<String, Boolean> entry : current.entrySet()) {
            if (keptNames.contains(entry.getKey())) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        install(serverId, kept);
    }

    /**
     * 安装某个 server 的整份工具视图，并同步台账上的工具数。
     *
     * @param serverId 服务标识
     * @param flags    工具展开名到只读标记的映射，构造方保证之后不再改动
     */
    private void install(String serverId, Map<String, Boolean> flags) {
        Map<String, Boolean> view = Collections.unmodifiableMap(flags);
        toolsByServer.put(serverId, view);
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.toolCount = view.size();
        }
    }

    /**
     * 移除某个 server 的全部事实（连接断开时调用）。
     * <p>
     * <b>只由注册器调用</b>：标记表的生命周期与「谁注册了处理器」绑定，连接对象不碰它——
     * 否则会出现「处理器还在、标记已经没了」的窗口，那一刻写类工具是不需要审批的。
     *
     * @param serverId 服务标识
     */
    void removeServer(String serverId) {
        toolsByServer.remove(serverId);
    }

    /**
     * 判断一个工具展开名是否由本插件提供。
     * <p>
     * 遍历各 server 的视图而不是查一张反查表：server 数是个位数，而「按名字反查」需要一张
     * 跨 server 的聚合表，每次替换都得原子重建它——为省几次 map 查找引入一个更容易写错的表不划算。
     *
     * @param qualifiedName 工具展开名，可为 {@code null}
     * @return 由本插件提供返回 {@code true}
     */
    boolean isMcpTool(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        for (Map<String, Boolean> flags : toolsByServer.values()) {
            if (flags.containsKey(qualifiedName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一个工具是否被用户声明为只读。
     * <p>
     * 多个 server 都提供同名工具时取<b>最严</b>：只要有一个把它算成写类，就按写类要审批。
     *
     * @param qualifiedName 工具展开名，可为 {@code null}
     * @return 用户声明为只读时返回 {@code true}；未知工具返回 {@code false}
     */
    boolean isReadOnly(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        boolean claimed = false;
        for (Map<String, Boolean> flags : toolsByServer.values()) {
            Boolean readOnly = flags.get(qualifiedName);
            if (readOnly == null) {
                continue;
            }
            if (!readOnly.booleanValue()) {
                return false;
            }
            claimed = true;
        }
        return claimed;
    }

    /**
     * 取某个 server 的工具展开名。
     *
     * @param serverId 服务标识
     * @return 不可变集合，保证非 {@code null}
     */
    Set<String> toolsOf(String serverId) {
        Map<String, Boolean> flags = toolsByServer.get(serverId);
        return flags == null ? Collections.<String>emptySet() : flags.keySet();
    }

    /**
     * 更新某个 server 的状态。
     *
     * @param serverId 服务标识
     * @param state    新状态
     * @param detail   说明
     */
    void noteState(String serverId, State state, String detail) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.update(state, detail);
        }
    }

    /**
     * 记录一次失败。
     *
     * @param serverId 服务标识
     * @param message  失败原因
     */
    void noteFailure(String serverId, String message) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lastError = message;
        }
        noteState(serverId, State.FAILED, message);
    }

    /**
     * 记一次「迟到响应被丢弃」。
     *
     * @param serverId 服务标识
     */
    void noteLateResponse(String serverId) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lateResponses.incrementAndGet();
        }
    }

    /**
     * 记一条告警（例如清单被上限截断）。
     *
     * @param serverId 服务标识
     * @param message  告警文本
     */
    void noteWarning(String serverId, String message) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lastWarning = message;
        }
    }

    /**
     * 取全部 server 的状态快照，按标识排序。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<ServerStatus> snapshot() {
        return Collections.unmodifiableList(new ArrayList<ServerStatus>(statuses.values()));
    }

    /**
     * 取某个 server 的状态。
     *
     * @param serverId 服务标识
     * @return 状态；未登记时返回 {@code null}
     */
    ServerStatus statusOf(String serverId) {
        return statuses.get(serverId);
    }

    /**
     * 一个 server 的运行状态；字段用 {@code volatile}，因为写方是连接线程、读方是渲染线程。
     * <p>
     * 刻意做成可变对象而不是每次替换一个新快照：连接过程中的状态更新很频繁（每次通知、每次失败），
     * 而读方只想要「此刻大概是什么样」——为了这一点去引入一整套不可变快照的分配不划算。
     */
    static final class ServerStatus {

        /** 服务标识：台账需要它，而状态表本身只有值。 */
        private final String serverId;

        /** 当前状态。 */
        private volatile State state = State.PENDING;

        /** 状态说明。 */
        private volatile String detail = "";

        /** 已注册工具数。 */
        private volatile int toolCount;

        /** 最近一次失败原因。 */
        private volatile String lastError;

        /** 最近一条告警。 */
        private volatile String lastWarning;

        /** 迟到响应计数。 */
        private final AtomicLong lateResponses = new AtomicLong();

        /**
         * 构造状态。
         *
         * @param serverId 服务标识
         */
        private ServerStatus(String serverId) {
            this.serverId = serverId;
        }

        /**
         * 获取服务标识。
         *
         * @return 标识
         */
        String serverId() {
            return serverId;
        }

        /**
         * 更新状态与说明。
         *
         * @param state  新状态
         * @param detail 说明
         */
        private void update(State state, String detail) {
            this.state = state;
            this.detail = detail == null ? "" : detail;
        }

        /**
         * 获取当前状态。
         *
         * @return 状态
         */
        State state() {
            return state;
        }

        /**
         * 获取状态说明。
         *
         * @return 说明，保证非 {@code null}
         */
        String detail() {
            return detail;
        }

        /**
         * 获取已注册工具数。
         *
         * @return 工具数
         */
        int toolCount() {
            return toolCount;
        }

        /**
         * 获取最近一次失败原因。
         *
         * @return 失败原因，可能为 {@code null}
         */
        String lastError() {
            return lastError;
        }

        /**
         * 获取最近一条告警。
         *
         * @return 告警文本，可能为 {@code null}
         */
        String lastWarning() {
            return lastWarning;
        }

        /**
         * 获取迟到响应计数。
         *
         * @return 条数
         */
        long lateResponses() {
            return lateResponses.get();
        }
    }
}
