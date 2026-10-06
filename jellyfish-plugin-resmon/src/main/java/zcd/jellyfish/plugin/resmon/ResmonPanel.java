package zcd.jellyfish.plugin.resmon;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板贡献处理器：把最近一次采样拼成一块常驻面板。
 * <p>
 * <b>没有告警行</b>：越阈值的项直接把它自己那一行转成警示档位就够用了，再额外加一行
 * {@code ! 磁盘 92% 超阈值 90%} 只是把同一个事实说两遍，而侧栏的每一行都来自别处。
 * 阈值仍然有用——它决定哪一行变黄（以及 {@code /resmon} 的告警段里写什么）。
 * <p>
 * <b>行数不设自设上限</b>：侧栏（{@code LEFT} / {@code RIGHT}）的高度就是消息区全高
 * （{@code ChatShell} 传的 {@code maxRows} 是 {@code layout.getMessageRows()}），
 * 而 8 行上限只作用于 {@code DOCK} / {@code TOP} 那两个纵向区域（{@code ChatLayout.panelRows}）。
 * 因此本面板把完整读数都交出去，由外壳按当前终端高度决定显出多少：终端够高就全显，
 * 不够高才截尾并留一行「… 还有 N 行」。反过来，在这里自己砍到 8 行会让高终端上的大片空白
 * 白白浪费——而这块面板的用处正是「一屏之内看全」。
 * <p>
 * <b>顺序即优先级</b>：外壳是从前往后截的，因此越靠前的行越不能丢。顺序是
 * 堆 → 内存池 → GC → 线程 → FD → CPU → 分区余量 → 磁盘占用（按体积从大到小）→ 合计。
 * 磁盘那一段在最后：它是「可以慢慢看」的信息，而 JVM 那几行是「此刻正在发生」的信息。
 * <p>
 * <b>宽度压在 {@value #MAX_WIDTH} 个显示列以内</b>：侧栏宽度由内容宽度推出
 * （「内容宽 + 边框」夹进 {@code [20, 终端宽/4]}），内容越宽，消息区被拿走的列就越多——
 * 面板是锦上添花，消息区是主体。子项名由用户决定长度，因此这里按显示宽度截断它；
 * 完整名字在 {@code /resmon disk} 里。
 * <p>
 * <b>它自己一行 I/O 都不做</b>：内容全部取自 {@link ResmonSampler} 已经算好的内存快照。
 * 这不是风格问题——本处理器在 TUI 的渲染线程里被内联调用，一次目录遍历会把整个界面冻住，
 * 而契约也明确要求这类处理器纯只读。
 * <p>
 * <b>没有数据时返回空贡献</b>：采样还没跑完第一轮时给一块空面板，会被用户读成「界面坏了」，
 * 而事实上两秒后它就有内容了。空贡献的语义正是「这次没有要显示的」。
 * <p>
 * 无状态，可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题前缀。 */
    private static final String TITLE_PREFIX = "资源 ";

    /** 一行的显示宽度上限：含边框后仍落在侧栏的宽度预算里，不挤压消息区。 */
    static final int MAX_WIDTH = 22;

    /**
     * GC 占比的强调阈值（百分比）。
     * <p>
     * 刻意不做成配置项：GC 占掉一成时间意味着停顿已经能被用户感觉到，
     * 而「感觉卡」与「感觉不卡」之间没有需要用户自己调的空间——这跟磁盘占用（不同磁盘的余量容忍度
     * 差别很大）不是一回事。
     */
    private static final double GC_WARN_PERCENT = 10.0;

    /** 插件配置（取阈值，让面板的配色与 {@code /resmon} 的告警段同口径）。 */
    private final PluginConfig config;

    /** 采样器。 */
    private final ResmonSampler sampler;

    /**
     * 构造处理器。
     *
     * @param config  插件配置，不可为 {@code null}
     * @param sampler 采样器，不可为 {@code null}
     */
    ResmonPanel(PluginConfig config, ResmonSampler sampler) {
        this.config = config;
        this.sampler = sampler;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        JvmStats jvm = sampler.jvm();
        if (jvm == null) {
            return PanelContribution.empty();
        }
        List<UiLine> lines = new ArrayList<UiLine>(24);
        addHeapLines(lines, jvm);
        addGcLines(lines, jvm);
        addThreadLines(lines, jvm);
        addCpuLines(lines, jvm);
        addDiskLines(lines, sampler.disk());
        return PanelContribution.of(title(jvm), lines, UiRegion.RIGHT);
    }

    /**
     * 拼标题：时刻 + 自动刷新状态。
     * <p>
     * 时刻必须写出来。面板显示的是「上次成功采样的快照」而不是实时值，不写时刻的话，
     * 关掉自动刷新之后用户无法区分「现在没在刷」与「刷新一直在失败」——
     * 而这两件事需要完全不同的动作。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 标题
     */
    private String title(JvmStats jvm) {
        String suffix = sampler.autoRefresh() ? "" : " 暂停";
        return TITLE_PREFIX + ResmonFormat.clock(jvm.capturedAtMillis()) + suffix;
    }

    /**
     * 追加堆与非堆、内存池。
     * <p>
     * 堆上限未设（{@code -Xmx} 缺省）时只报已用与占比：把 {@code -1} 打成 {@code -1B} 会让人以为
     * 是负数而不是「没有上限」。
     * <p>
     * 元空间单独占一行（而不是并进非堆）：它涨说明的是<b>类</b>太多（插件反复加载、脚本网关不断抽取
     * 新资源），修法与「对象太多」完全不同，值得一眼能看见。
     *
     * @param lines 行收集列表，不可为 {@code null}
     * @param jvm   快照，不可为 {@code null}
     */
    private void addHeapLines(List<UiLine> lines, JvmStats jvm) {
        String used = ResmonFormat.bytes(jvm.heapUsedBytes());
        String percent = ResmonFormat.percent(jvm.heapPercent());
        String heap = jvm.heapMaxBytes() > 0L
                ? "堆 " + used + "/" + ResmonFormat.bytes(jvm.heapMaxBytes()) + " " + percent
                : "堆 " + used + " " + percent;
        lines.add(line(heap, emphase(jvm.heapPercent(), config.alertHeapPercent())));
        lines.add(line("非堆 " + ResmonFormat.bytes(jvm.nonHeapUsedBytes())
                + " 元空间 " + ResmonFormat.bytes(jvm.metaspaceUsedBytes()), UiEmphasis.NORMAL));
        lines.add(line("类空间 " + ResmonFormat.bytes(jvm.compressedClassSpaceUsedBytes())
                + " 缓存 " + ResmonFormat.bytes(jvm.codeCacheUsedBytes()), UiEmphasis.NORMAL));
        lines.add(line("类 " + jvm.loadedClassCount() + " 运行 " + ResmonFormat.duration(jvm.uptimeMillis()),
                UiEmphasis.DIM));
    }

    /**
     * 追加 GC 两行：总量与占比一行，老年代单列一行。
     * <p>
     * 老年代必须是独立一行：年轻代 GC 频繁只说明「分配快」，老年代 GC（Full GC）意味着进程被停下来，
     * 是用户能感觉到的卡顿。把两者合并成一个数就再也分不出是哪种。
     *
     * @param lines 行收集列表，不可为 {@code null}
     * @param jvm   快照，不可为 {@code null}
     */
    private void addGcLines(List<UiLine> lines, JvmStats jvm) {
        if (jvm.gcElapsedMillis() <= 0L) {
            // 首次采样没有可比的上一次，占比无意义；次数仍然是真读数
            lines.add(line("GC " + ResmonFormat.UNKNOWN + " " + jvm.gcCount() + "次", UiEmphasis.NORMAL));
            lines.add(line("老年代 " + jvm.oldGcCount() + "次", UiEmphasis.DIM));
            return;
        }
        lines.add(line("GC " + ResmonFormat.percent(jvm.gcPercent()) + " " + jvm.gcCount() + "次 "
                + ResmonFormat.duration(jvm.gcTimeMillis()), emphase(jvm.gcPercent(), GC_WARN_PERCENT)));
        lines.add(line("老年代 " + jvm.oldGcCount() + "次 " + ResmonFormat.duration(jvm.oldGcTimeMillis()),
                jvm.oldGcCount() > 0L ? UiEmphasis.ACCENT : UiEmphasis.DIM));
    }

    /**
     * 追加线程两行：总量一行、死锁单独一行。
     * <p>
     * 死锁单独占一行而不是跟在总数后面：它是唯一「不会自己恢复」的状态，
     * 与「有几个线程在跑」完全不是一类信息，混在同行里会被扫过去。
     *
     * @param lines 行收集列表，不可为 {@code null}
     * @param jvm   快照，不可为 {@code null}
     */
    private static void addThreadLines(List<UiLine> lines, JvmStats jvm) {
        lines.add(line("线程 " + jvm.threadCount() + " 峰 " + jvm.peakThreadCount()
                + " 守 " + jvm.daemonThreadCount(), UiEmphasis.NORMAL));
        boolean deadlocked = jvm.deadlockedThreadCount() > 0L;
        lines.add(line("死锁 " + jvm.deadlockedThreadCount(),
                deadlocked ? UiEmphasis.ERROR : UiEmphasis.DIM));
    }

    /**
     * 追加文件描述符与 CPU。
     * <p>
     * 两项各占一行（而不是并在一行）：并排后这一行的显示宽度会到 23 列，越过了面板的宽度预算，
     * 而侧栏的行数不受 8 行规则约束、并不紧张——用「多一行」换「不挤压消息区」是划算的。
     * 分区标签写「进程 / 系统」而不是省略：只给两个百分比，读的人分不出哪个是自己这个进程。
     *
     * @param lines 行收集列表，不可为 {@code null}
     * @param jvm   快照，不可为 {@code null}
     */
    private void addCpuLines(List<UiLine> lines, JvmStats jvm) {
        String fd = jvm.maxFileDescriptorCount() > 0L
                ? "FD " + jvm.openFileDescriptorCount() + "/" + jvm.maxFileDescriptorCount()
                + " " + ResmonFormat.percent(jvm.fdPercent())
                : "FD " + ResmonFormat.UNKNOWN;
        lines.add(line(fd, emphase(jvm.fdPercent(), config.alertFdPercent())));
        lines.add(line("CPU 进程 " + ResmonFormat.percent(jvm.processCpuPercent())
                + " 系统 " + ResmonFormat.percent(jvm.systemCpuPercent()), UiEmphasis.NORMAL));
    }

    /**
     * 追加分区余量与各一级子项的占用、合计。
     * <p>
     * 子项按体积从大到小列（采样器已排好），最多 {@link PluginConfig#diskEntries()} 条；
     * 超出时补一行「… 还有 N 项」，而不是静默省略——省略会让人以为机器上就这么多东西。
     * 文件数在体积之后：体积回答「占了多少」，「37文件」回答「是零碎还是一坨」。
     *
     * @param lines 行收集列表，不可为 {@code null}
     * @param disk  磁盘报告，不可为 {@code null}
     */
    private void addDiskLines(List<UiLine> lines, DiskReport disk) {
        if (!disk.scanned()) {
            lines.add(line("磁盘 " + ResmonFormat.UNKNOWN + "（首次扫描中）", UiEmphasis.DIM));
            return;
        }
        double percent = disk.partitionUsedPercent();
        String partition = percent < 0.0
                ? "磁盘 " + ResmonFormat.UNKNOWN
                : "磁盘 " + ResmonFormat.percent(percent) + " 剩 " + ResmonFormat.bytes(disk.partitionUsableBytes());
        lines.add(line(partition, emphase(percent, config.alertDiskPercent())));
        List<PathUsage> usages = disk.usages();
        int shown = Math.min(usages.size(), config.diskEntries());
        for (int i = 0; i < shown; i++) {
            lines.add(line(usageText(usages.get(i)), UiEmphasis.NORMAL));
        }
        if (usages.size() > shown) {
            lines.add(line("\u2026 还有 " + (usages.size() - shown) + " 项", UiEmphasis.DIM));
        }
        lines.add(line("合计 " + ResmonFormat.bytes(disk.totalBytes()) + growthText(disk), UiEmphasis.DIM));
    }

    /**
     * 拼一个子项：名字 + 体积 + 文件数。
     * <p>
     * <b>名字的宽度预算按这一行的实际后缀算，而不是一个固定值</b>：后缀（体积 + 文件数）短的时候
     * 名字就能多留几个字符，于是 {@code jellyfish-tui.log} 这种刚好在临界的名字能完整显示，
     * 而不是被截成和其他 {@code jellyfish-…} 开头的项看不出区别的 {@code jellyfish…}。
     * 名字本身按显示宽度截断，因为全中文目录名按字符数截会让这一行实际宽度翻倍。
     *
     * @param usage 占用结果，不可为 {@code null}
     * @return 文本，保证显示宽度不超过 {@link #MAX_WIDTH}
     */
    private static String usageText(PathUsage usage) {
        String suffix;
        if (!usage.present()) {
            suffix = " " + ResmonFormat.UNKNOWN;
        } else {
            suffix = " " + ResmonFormat.bytes(usage.bytes())
                    + (usage.files() > 1L ? " " + usage.files() + "文件" : "");
        }
        int nameBudget = Math.max(1, MAX_WIDTH - ResmonFormat.displayWidth(suffix));
        return ResmonFormat.clip(usage.name(), nameBudget) + suffix;
    }

    /**
     * 拼增长速度。
     *
     * @param disk 磁盘报告，不可为 {@code null}
     * @return 文本；没有上一次扫描时返回空串
     */
    private static String growthText(DiskReport disk) {
        long growth = disk.growthBytesPerHour();
        if (growth < 0L) {
            return "";
        }
        if (growth == 0L) {
            return " 持平";
        }
        return " " + (growth > 0L ? "+" : "-") + ResmonFormat.bytes(Math.abs(growth)) + "/时";
    }

    /**
     * 按阈值取强调档位。
     *
     * @param percent   实测百分比，负数表示不可用
     * @param threshold 阈值
     * @return 越界且开启标出时为 {@link UiEmphasis#WARN}，否则 {@link UiEmphasis#NORMAL}
     */
    private UiEmphasis emphase(double percent, double threshold) {
        return config.alerts() && percent >= 0.0 && percent >= threshold
                ? UiEmphasis.WARN : UiEmphasis.NORMAL;
    }

    /**
     * 构造一行单段文本。
     *
     * @param text     文本
     * @param emphasis 强调档位
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine line(String text, UiEmphasis emphasis) {
        return UiLine.of(UiSegment.of(text, emphasis));
    }
}
