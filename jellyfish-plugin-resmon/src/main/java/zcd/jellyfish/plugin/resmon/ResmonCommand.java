package zcd.jellyfish.plugin.resmon;

import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.util.List;
import java.util.Locale;

/**
 * {@code /resmon} 命令：只读地报告 JVM 与磁盘的当前占用。
 * <p>
 * <b>为什么明细走命令而不是面板</b>：面板只有 8 行，且每块区域同时只显示一个插件的一块面板；
 * 命令没有这些约束——它能在三种外壳里用（{@code -tui} / {@code -cli} / {@code -server}），
 * 输出不被截断，还可以把整段文本直接交给模型去判断「该清理什么」。面板负责「瞟一眼」，
 * 命令负责「查清楚」，两者不是重复而是不同粒度。
 * <p>
 * <b>它刻意什么都不能改</b>：删除文件、清空目录这类动作涉及用户的真实数据，且「哪些文件可以删」
 * 需要结合会话是否还要用来判断——那是用户与模型的决策，不是一个监控插件的职责。
 * 本命令因此只报告，唯一会改变状态的是 {@code auto}（它只影响面板刷新，不碰任何数据）。
 * <p>
 * <b>声明「不需要会话」</b>：JVM 与磁盘都是进程级事实，与当前会话无关。这样 {@code -cli} 单次调用、
 * 以及首页（还没有会话）都能用它，而那是用户发现磁盘快满时最可能的时刻。
 * <p>
 * 无状态（配置与采样器都不可变地持有），可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonCommand implements ExtensionHandler<CommandRequest, CommandResult> {

    /** 命令名，也是路由键。 */
    static final String NAME = "resmon";

    /** 参数：只看 JVM。 */
    static final String ARG_JVM = "jvm";

    /** 参数：只看磁盘。 */
    static final String ARG_DISK = "disk";

    /** 参数：自动刷新开关。 */
    static final String ARG_AUTO = "auto";

    /** 参数：帮助。 */
    static final String ARG_HELP = "help";

    /** 自动刷新候选值：开。 */
    static final String VALUE_AUTO_ON = "auto on";

    /** 自动刷新候选值：关。 */
    static final String VALUE_AUTO_OFF = "auto off";

    /** 单行缩进。 */
    private static final String INDENT = "  ";

    /** 插件配置。 */
    private final PluginConfig config;

    /** 采样器。 */
    private final ResmonSampler sampler;

    /**
     * 构造命令处理器。
     *
     * @param config  插件配置，不可为 {@code null}
     * @param sampler 采样器，不可为 {@code null}
     */
    ResmonCommand(PluginConfig config, ResmonSampler sampler) {
        this.config = config;
        this.sampler = sampler;
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        List<String> tokens = request.getArguments().getTokens();
        if (tokens.isEmpty()) {
            return CommandResult.ok(report(true, true));
        }
        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        if (ARG_JVM.equals(first)) {
            return CommandResult.ok(report(true, false));
        }
        if (ARG_DISK.equals(first)) {
            return CommandResult.ok(report(false, true));
        }
        if (ARG_AUTO.equals(first)) {
            return auto(tokens);
        }
        if (ARG_HELP.equals(first)) {
            return CommandResult.ok(usage());
        }
        return CommandResult.error(usage());
    }

    /**
     * 处理 {@code auto} 子命令。
     *
     * @param tokens 全部参数（第一个是 {@code auto}）
     * @return 命令结果
     */
    private CommandResult auto(List<String> tokens) {
        if (tokens.size() == 1) {
            return CommandResult.ok("面板自动刷新：" + onOff(sampler.autoRefresh())
                    + "（配置缺省 " + onOff(config.autoRefresh()) + "）");
        }
        if (tokens.size() > 2) {
            return CommandResult.error(usage());
        }
        String action = tokens.get(1).toLowerCase(Locale.ROOT);
        if ("on".equals(action)) {
            sampler.autoRefresh(true);
            return CommandResult.ok("面板自动刷新已开启。");
        }
        if ("off".equals(action)) {
            sampler.autoRefresh(false);
            return CommandResult.ok("面板自动刷新已关闭。面板仍会在会话切换、回合收敛与命令执行后更新；"
                    + "要看最新数据请随时敲 /resmon。");
        }
        return CommandResult.error(usage());
    }

    /**
     * 拼一份报告。
     *
     * @param withJvm  是否包含 JVM 部分
     * @param withDisk 是否包含磁盘部分
     * @return 报告文本，保证非 {@code null}
     */
    private String report(boolean withJvm, boolean withDisk) {
        JvmStats jvm = sampler.jvm();
        DiskReport disk = sampler.disk();
        StringBuilder out = new StringBuilder();
        if (withJvm && withDisk) {
            out.append("资源快照  ").append(ResmonFormat.timestamp(latestStamp(jvm, disk))).append('\n');
        }
        // 告警在两种视图里都出现，且放在最前：它是这份输出里唯一「需要动作」的部分，
        // 而把它藏进某一种子视图会让「/resmon jvm」看起来一切正常
        appendAlerts(out);
        if (withJvm) {
            appendJvm(out, jvm);
        }
        if (withDisk) {
            appendDisk(out, disk);
        }
        return out.toString();
    }

    /**
     * 追加告警段。
     * <p>
     * 没有告警时什么都不写，而不是写一行「无告警」：这份输出的常态是没有告警，
     * 每个默认场景都多一行噪音，只会让人不再读这一段。
     *
     * @param out 输出缓冲，不可为 {@code null}
     */
    private void appendAlerts(StringBuilder out) {
        for (Alert alert : sampler.alerts()) {
            out.append("告警 ").append(alert.detailText()).append('\n');
        }
    }

    /**
     * 取两份快照里较晚的采样时刻。
     *
     * @param jvm  快照，可为 {@code null}
     * @param disk 磁盘报告，不可为 {@code null}
     * @return 毫秒时间戳；两者都没有数据时返回当前时刻
     */
    private static long latestStamp(JvmStats jvm, DiskReport disk) {
        long jvmAt = jvm == null ? 0L : jvm.capturedAtMillis();
        long diskAt = disk.capturedAtMillis();
        long latest = Math.max(jvmAt, diskAt);
        return latest > 0L ? latest : System.currentTimeMillis();
    }

    /**
     * 追加 JVM 明细。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，可为 {@code null}
     */
    private static void appendJvm(StringBuilder out, JvmStats jvm) {
        if (jvm == null) {
            out.append("JVM：尚未采集到数据，请稍后再试（插件启动后立即开始采样）。").append('\n');
            return;
        }
        out.append("JVM（采样于 ").append(ResmonFormat.clock(jvm.capturedAtMillis())).append("）").append('\n');
        appendHeap(out, jvm);
        appendMemoryPools(out, jvm);
        appendGc(out, jvm);
        appendThreads(out, jvm);
        appendProcess(out, jvm);
    }

    /**
     * 追加堆与非堆用量。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，不可为 {@code null}
     */
    private static void appendHeap(StringBuilder out, JvmStats jvm) {
        out.append(INDENT).append("堆 ").append(ResmonFormat.bytes(jvm.heapUsedBytes())).append(" / ")
                .append(jvm.heapMaxBytes() > 0L ? ResmonFormat.bytes(jvm.heapMaxBytes()) : "无上限")
                .append("（已提交 ").append(ResmonFormat.bytes(jvm.heapCommittedBytes()))
                .append("，使用 ").append(ResmonFormat.percent(jvm.heapPercent())).append("）").append('\n');
        out.append(INDENT).append("非堆 ").append(ResmonFormat.bytes(jvm.nonHeapUsedBytes())).append('\n');
    }

    /**
     * 追加内存池用量。
     * <p>
     * 元空间单列的意义在于区分两类「内存涨了」：堆满说明对象太多，元空间涨说明<b>类</b>太多
     * （插件反复加载、脚本网关不断抽取新资源都会这样），后者的修法完全不同。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，不可为 {@code null}
     */
    private static void appendMemoryPools(StringBuilder out, JvmStats jvm) {
        out.append(INDENT).append("内存池 元空间 ").append(ResmonFormat.bytes(jvm.metaspaceUsedBytes()))
                .append("，压缩类空间 ").append(ResmonFormat.bytes(jvm.compressedClassSpaceUsedBytes()))
                .append("，代码缓存 ").append(ResmonFormat.bytes(jvm.codeCacheUsedBytes())).append('\n');
    }

    /**
     * 追加 GC 用量。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，不可为 {@code null}
     */
    private static void appendGc(StringBuilder out, JvmStats jvm) {
        out.append(INDENT).append("GC ").append(jvm.gcCount()).append(" 次 / ")
                .append(ResmonFormat.duration(jvm.gcTimeMillis())).append("，占采样窗口 ")
                .append(ResmonFormat.percent(jvm.gcPercent())).append('\n');
        out.append(INDENT).append("　　其中年轻代 ").append(jvm.youngGcCount()).append(" 次 / ")
                .append(ResmonFormat.duration(jvm.youngGcTimeMillis()))
                .append("，老年代 ").append(jvm.oldGcCount()).append(" 次 / ")
                .append(ResmonFormat.duration(jvm.oldGcTimeMillis())).append('\n');
        if (jvm.gcElapsedMillis() <= 0L) {
            out.append(INDENT).append("　　占比不可用：这是首次采样，还没有可比较的窗口。").append('\n');
        }
    }

    /**
     * 追加线程用量。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，不可为 {@code null}
     */
    private static void appendThreads(StringBuilder out, JvmStats jvm) {
        out.append(INDENT).append("线程 ").append(jvm.threadCount())
                .append("（峰值 ").append(jvm.peakThreadCount())
                .append("，守护 ").append(jvm.daemonThreadCount()).append("），死锁 ")
                .append(jvm.deadlockedThreadCount())
                .append("（每 ").append(ManagementJvmProbe.DEADLOCK_CHECK_INTERVAL).append(" 次采样检测一次）")
                .append('\n');
    }

    /**
     * 追加进程级用量。
     *
     * @param out 输出缓冲，不可为 {@code null}
     * @param jvm 快照，不可为 {@code null}
     */
    private static void appendProcess(StringBuilder out, JvmStats jvm) {
        out.append(INDENT).append("文件描述符 ").append(jvm.openFileDescriptorCount())
                .append(" / ").append(jvm.maxFileDescriptorCount())
                .append("（").append(ResmonFormat.percent(jvm.fdPercent())).append("）").append('\n');
        out.append(INDENT).append("CPU 进程 ").append(ResmonFormat.percent(jvm.processCpuPercent()))
                .append("，系统 ").append(ResmonFormat.percent(jvm.systemCpuPercent()))
                .append("（").append(jvm.availableProcessors()).append(" 核）").append('\n');
        out.append(INDENT).append("已加载类 ").append(jvm.loadedClassCount())
                .append("，运行时长 ").append(ResmonFormat.duration(jvm.uptimeMillis())).append('\n');
    }

    /**
     * 追加磁盘明细。
     *
     * @param out  输出缓冲，不可为 {@code null}
     * @param disk 磁盘报告，不可为 {@code null}
     */
    private static void appendDisk(StringBuilder out, DiskReport disk) {
        if (!disk.scanned()) {
            out.append("磁盘：尚未完成首次扫描，请稍后再试（首次扫描在插件启动后立即开始）。").append('\n');
            return;
        }
        out.append("磁盘（扫描于 ").append(ResmonFormat.clock(disk.capturedAtMillis())).append("）").append('\n');
        out.append(INDENT).append("分区 ").append(ResmonFormat.bytes(disk.partitionTotalBytes()))
                .append(" 已用 ").append(ResmonFormat.percent(disk.partitionUsedPercent()))
                .append("，剩 ").append(ResmonFormat.bytes(disk.partitionUsableBytes())).append('\n');
        for (PathUsage usage : disk.usages()) {
            out.append(INDENT).append(ResmonFormat.label(usage.key())).append(' ').append(text(usage))
                    .append("  ").append(usage.path()).append('\n');
        }
        out.append(INDENT).append("合计 ").append(ResmonFormat.bytes(disk.totalBytes()))
                .append(growthText(disk)).append('\n');
        out.append('\n').append("以上目录路径取自本插件的配置段（plugins.configurations.jellyfish-plugin-resmon）。")
                .append("若你改过内核的落盘位置，请在配置里同步，否则这里统计的不是真实目录。").append('\n');
    }

    /**
     * 拼一个占用项的读数。
     *
     * @param usage 占用结果，不可为 {@code null}
     * @return 文本
     */
    private static String text(PathUsage usage) {
        if (!usage.present()) {
            return ResmonFormat.UNKNOWN + "（不存在）";
        }
        if (usage.files() == 1L) {
            return ResmonFormat.bytes(usage.bytes());
        }
        return ResmonFormat.bytes(usage.bytes()) + "，" + usage.files() + " 个文件";
    }

    /**
     * 拼增长速度文本。
     *
     * @param disk 磁盘报告，不可为 {@code null}
     * @return 文本；没有上一次扫描时返回空串
     */
    private static String growthText(DiskReport disk) {
        long growth = disk.growthBytesPerHour();
        if (growth < 0L) {
            return "";
        }
        return "，每小时 " + (growth == 0L ? "±0" : (growth > 0L ? "+" : "-") + ResmonFormat.bytes(Math.abs(growth)));
    }

    /**
     * 把布尔转成开关文本。
     *
     * @param value 布尔值
     * @return {@code 开} 或 {@code 关}
     */
    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    /**
     * 拼用法说明。
     *
     * @return 说明文本，保证非 {@code null}
     */
    private static String usage() {
        return String.join("\n",
                "用法：",
                "  /resmon              完整快照（JVM + 磁盘）",
                "  /resmon jvm          只显示 JVM 明细",
                "  /resmon disk         只显示磁盘明细",
                "  /resmon auto         查看面板自动刷新状态",
                "  /resmon auto on|off  开关面板自动刷新",
                "  /resmon help         本说明",
                "",
                "越阈值的项会在输出开头以「告警」列出，并显示在 TUI 右栏面板的最前面。",
                "本命令是只读的：它不删除任何文件，只报告占用。要清理请把这些输出交给模型评估。");
    }
}
