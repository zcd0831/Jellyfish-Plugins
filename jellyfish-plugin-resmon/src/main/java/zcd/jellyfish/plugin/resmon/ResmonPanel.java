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
 * 面板贡献处理器：把最近一次采样拼成一块常驻的 8 行面板。
 * <p>
 * <b>它自己一行 I/O 都不做</b>：内容全部取自 {@link ResmonSampler} 已经算好的内存快照。
 * 这不是风格问题——本处理器在 TUI 的渲染线程里被内联调用，一次目录遍历会把整个界面冻住，
 * 而契约也明确要求这类处理器纯只读、不做 I/O。
 * <p>
 * <b>为什么建议落在右栏（{@code RIGHT}）</b>：这套内容是一列纵向的读数，不是清单，
 * 侧栏的「宽度按内容取、高度是消息区全高」正好合适；落在横向区域（{@code DOCK} / {@code TOP}）
 * 会顶掉消息区最多 12 行，而消息区才是主体。落位只是建议——用户可以用 {@code /ui} 换地方，
 * 也可以把这一块整个关掉（那时面板只在外壳自身的失效点更新，见 {@code /resmon auto}）。
 * <p>
 * <b>行宽为什么要压到 20 列以内</b>：侧栏宽度由内容宽度推出（外壳的规则是「内容宽 + 边框」夹进
 * {@code [20, 终端宽/4]}），因此更宽的内容会抬高「不折行」所需的终端宽度；而面板一旦折行，
 * 8 行的上限会被折行后的行占满，末行会被外壳换成「… 还有 N 行」——
 * 那正好是磁盘那几行所在的位置。压窄内容换来的正是「窄终端上也信息完整」。
 * <p>
 * <b>没有数据时返回空贡献</b>：采样还没跑完第一轮时给一块空面板，会被用户读成「界面坏了」，
 * 而事实上两秒后它就有内容了。空贡献的语义正是「这次没有要显示的」。
 * <p>
 * 无状态，可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板内容行上限，与外壳的 {@code ChatLayout.PANEL_MAX_ROWS} 一致。 */
    private static final int MAX_ROWS = 8;

    /** 面板标题前缀。 */
    private static final String TITLE_PREFIX = "资源 ";

    /**
     * GC 占比的强调阈值（百分比）。
     * <p>
     * 刻意不做成配置项：GC 占掉一成时间意味着停顿已经能被用户感觉到，
     * 而「感觉卡」与「感觉不卡」之间没有需要用户自己调的空间——这跟磁盘占用（不同磁盘的余量容忍度
     * 差别很大）不是一回事。
     */
    private static final double GC_WARN_PERCENT = 10.0;

    /** 插件配置（取告警阈值，让面板的配色与告警口径一致）。 */
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
        DiskReport disk = sampler.disk();
        List<UiLine> lines = new ArrayList<UiLine>(MAX_ROWS);
        lines.add(line(heapText(jvm), heapEmphasis(jvm)));
        lines.add(line(gcText(jvm), gcEmphasis(jvm)));
        lines.add(line(threadText(jvm), jvm.deadlockedThreadCount() > 0L ? UiEmphasis.ERROR : UiEmphasis.NORMAL));
        lines.add(line(fdText(jvm), emphase(jvm.fdPercent(), config.alertFdPercent())));
        lines.add(line(diskText(disk), emphase(disk.partitionUsedPercent(), config.alertDiskPercent())));
        addUsageLines(disk, lines);
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
     * 追加磁盘各占用项的行，每行两项。
     * <p>
     * 六项分三行是行预算逼出来的：8 行里前 5 行给了 JVM 与分区余量，剩下的正好三行。
     * 因此这里的取舍是「宁可每行两项，也不把某一项挤掉」——被挤掉的那一项恰好可能有几百兆。
     *
     * @param disk  磁盘报告，不可为 {@code null}
     * @param lines 行收集列表，不可为 {@code null}
     */
    private static void addUsageLines(DiskReport disk, List<UiLine> lines) {
        List<PathUsage> usages = disk.usages();
        for (int i = 0; i < usages.size() && lines.size() < MAX_ROWS; i += 2) {
            StringBuilder text = new StringBuilder(usageText(usages.get(i)));
            if (i + 1 < usages.size()) {
                text.append(' ').append(usageText(usages.get(i + 1)));
            }
            lines.add(line(text.toString(), UiEmphasis.NORMAL));
        }
    }

    /**
     * 拼一个占用项的「短名 + 体积」。
     *
     * @param usage 占用结果，不可为 {@code null}
     * @return 文本
     */
    private static String usageText(PathUsage usage) {
        String value = usage.present() ? ResmonFormat.bytes(usage.bytes()) : ResmonFormat.UNKNOWN;
        return ResmonFormat.shortLabel(usage.key()) + " " + value;
    }

    /**
     * 拼堆占用那行。
     * <p>
     * 堆上限未设（{@code -Xmx} 缺省）时只报已用与占比：把 {@code -1} 打成 {@code -1B} 会让人以为
     * 是负数而不是「没有上限」。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 文本
     */
    private static String heapText(JvmStats jvm) {
        String used = ResmonFormat.bytes(jvm.heapUsedBytes());
        String percent = ResmonFormat.percent(jvm.heapPercent());
        if (jvm.heapMaxBytes() > 0L) {
            return "堆 " + used + "/" + ResmonFormat.bytes(jvm.heapMaxBytes()) + " " + percent;
        }
        return "堆 " + used + " " + percent;
    }

    /**
     * 拼 GC 那行。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 文本
     */
    private static String gcText(JvmStats jvm) {
        if (jvm.gcElapsedMillis() <= 0L) {
            // 首次采样没有可比的上一次，占比无意义；次数仍然是真读数
            return "GC " + ResmonFormat.UNKNOWN + " " + jvm.gcCount() + "次";
        }
        return "GC " + ResmonFormat.percent(jvm.gcPercent()) + " " + jvm.gcCount() + "次 "
                + ResmonFormat.duration(jvm.gcTimeMillis());
    }

    /**
     * 拼线程那行。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 文本
     */
    private static String threadText(JvmStats jvm) {
        return "线程 " + jvm.threadCount() + " 死锁 " + jvm.deadlockedThreadCount();
    }

    /**
     * 拼文件描述符那行。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 文本
     */
    private static String fdText(JvmStats jvm) {
        if (jvm.maxFileDescriptorCount() <= 0L) {
            return "FD " + ResmonFormat.UNKNOWN;
        }
        return "FD " + jvm.openFileDescriptorCount() + "/" + jvm.maxFileDescriptorCount();
    }

    /**
     * 拼分区余量那行。
     *
     * @param disk 磁盘报告，不可为 {@code null}
     * @return 文本
     */
    private static String diskText(DiskReport disk) {
        double percent = disk.partitionUsedPercent();
        if (!disk.scanned() || percent < 0.0) {
            return "磁盘 " + ResmonFormat.UNKNOWN;
        }
        return "磁盘 " + ResmonFormat.percent(percent) + " 剩 "
                + ResmonFormat.bytes(disk.partitionUsableBytes());
    }

    /**
     * 按阈值取强调档位。
     *
     * @param percent   实测百分比，负数表示不可用
     * @param threshold 阈值
     * @return 越界为 {@link UiEmphasis#WARN}，否则 {@link UiEmphasis#NORMAL}
     */
    private static UiEmphasis emphase(double percent, double threshold) {
        return percent >= 0.0 && percent >= threshold ? UiEmphasis.WARN : UiEmphasis.NORMAL;
    }

    /**
     * 取堆占用的强调档位。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 强调档位
     */
    private UiEmphasis heapEmphasis(JvmStats jvm) {
        return emphase(jvm.heapPercent(), config.alertHeapPercent());
    }

    /**
     * 取 GC 的强调档位。
     *
     * @param jvm 快照，不可为 {@code null}
     * @return 强调档位
     */
    private static UiEmphasis gcEmphasis(JvmStats jvm) {
        return emphase(jvm.gcPercent(), GC_WARN_PERCENT);
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
