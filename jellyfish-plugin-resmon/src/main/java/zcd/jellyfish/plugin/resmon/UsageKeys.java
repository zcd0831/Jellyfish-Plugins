package zcd.jellyfish.plugin.resmon;

/**
 * 各类资源占用项的稳定标识：面板、命令与统计三处共用的键名。
 * <p>
 * <b>为什么要有这一份</b>：键名同时出现在「统计时按什么顺序取」与「显示时叫什么名字」两处，
 * 各写一次字面量就会出现「面板上少了一行而没人发现」这种静默漂移。集中一处之后，
 * {@link DirSizer} 产出的名字与 {@link ResmonFormat} 的标签映射必然对齐。
 * <p>
 * <b>为什么名字是驼峰而不是中文</b>：它是键不是标签。显示用的名字在 {@link ResmonFormat} 里，
 * 面板要短名、命令要全名，两者都从这份键映射过去。
 *
 * @author zcd
 */
final class UsageKeys {

    /** 会话文件目录（由 session-file 插件落盘）。 */
    static final String SESSIONS = "sessions";

    /** 工具输出目录（内核在输出溢出时落盘，含子代理 run 归档）。 */
    static final String TOOL_OUTPUTS = "toolOutputs";

    /** 插件 jar 目录。 */
    static final String PLUGINS = "plugins";

    /** 待办文件目录（由 todo 插件落盘）。 */
    static final String TODOS = "todos";

    /** 脚本网关抽取目录（python / node 桥接插件落盘）。 */
    static final String GATEWAY = "gateway";

    /** TUI 日志文件（内核在 TUI 模式下落盘；非 TUI 模式不落盘）。 */
    static final String LOG = "log";

    /** 私有构造器：常量类不可实例化。 */
    private UsageKeys() {
    }
}
