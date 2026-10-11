package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 面板贡献：把正在跑的编排画成一张「链 + 汇合线」的图，放在消息区上方。
 * <p>
 * <b>为什么需要它</b>：一次编排可以在「没有输出」的状态下跑上好几分钟（几个子代理各自在调模型），
 * 而它是<b>并行</b>的——工具行上滚动的那几行进度看不出「哪一步在跑、哪些已经完了、谁在等谁、
 * 还剩几步」。面板把这段沉默期变成一张可数的图。
 * <p>
 * <b>建议落消息区上方（{@code TOP}）而不是停靠区</b>：停靠区里已经排了内核那块「子代理」面板
 * （它声明了更小的 {@code order}），而一次编排<b>就是</b>批量派子代理——两块面板恰好在同一时刻都要看
 * （那边讲「谁在跑」，这里讲「编排走到哪儿了」），同区域等于永远只能看见一个。上方是全宽，连线也不会
 * 因为侧栏只有 18 列而被折行。当然这只是<b>软建议</b>，外壳可以忽略它。
 * <p>
 * <b>行数按外壳给的算，不自己另立一套</b>：外壳给 {@code TOP} 区域的内容行上限是 8 行
 * （{@code ChatLayout.PANEL_MAX_ROWS}），因此这里的 {@link #MAX_LINES} 也是 8——两边口径不一致时，
 * 插件精心折叠出来的「…另有 N 步未显示」永远轮不到触发，先被外壳换成通用的「… 还有 N 行」。
 * <p>
 * <b>标题行交代整体，正文交给 {@link WorkflowLayout}</b>：标题行说「进度、跑了多久、几个在跑」，
 * 正文画图。整块内容里唯一会自己变的就是这些数字，而外壳在回合进行中每秒会让插件内容失效一次
 * （见 {@code WorkflowTracker}），于是面板会自己跳——这就是那个「loading 状态」，且不需要插件起线程。
 * <p>
 * <b>没有在跑的编排时返回空贡献</b>：面板独占一整块区域，没内容就不该抢地盘。编排跑完即从台账移除，
 * 因此它会在结束那一刻消失——与内核的子代理面板同一行为。
 * <p>
 * <b>处理器是快且只读的</b>：它只读一次台账快照、拼几行文本，不做 I/O、不发失效事件
 * （那会形成「失效 → 收集 → 失效」的死循环；通知由 {@link WorkflowTracker} 在状态变化时发）。
 *
 * @author zcd
 */
final class WorkflowPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    static final String TITLE = "编排";

    /**
     * 面板内容行数上限（对齐外壳给 {@code TOP} 区域的内容行上限）。
     * <p>
     * <b>为什么必须与外壳一致</b>：外壳只画得下 8 行内容，插件若按 12 行自限，多出来的部分会先被外壳
     * 换成一句通用的「… 还有 N 行」，插件自己想表达的「…另有 N 步未显示」永远不生效。
     */
    static final int MAX_LINES = 8;

    /** 同时有多个编排时，每个至少留的行数（标题 + 一行正文）。 */
    private static final int MIN_WORKFLOW_LINES = 2;

    /** 编排台账。 */
    private final WorkflowTracker tracker;

    /** 取当前时刻的时钟（算「已跑多久」用）。 */
    private final LongSupplier clock;

    /**
     * 构造处理器。
     *
     * @param tracker 编排台账，不可为 {@code null}
     * @param clock   取当前时刻的时钟，不可为 {@code null}
     */
    WorkflowPanel(WorkflowTracker tracker, LongSupplier clock) {
        this.tracker = tracker;
        this.clock = clock;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        List<WorkflowProgress> active = tracker.snapshot(request.getSessionId());
        if (active.isEmpty()) {
            return PanelContribution.empty();
        }
        List<UiLine> lines = new ArrayList<UiLine>();
        int budget = MAX_LINES;
        int shown = 0;
        for (WorkflowProgress progress : active) {
            int left = active.size() - shown;
            if (budget < MIN_WORKFLOW_LINES) {
                break;
            }
            // 给后面还没画的编排留出下限，免得第一个编排把行吃光
            int share = Math.max(MIN_WORKFLOW_LINES, budget - (left - 1) * MIN_WORKFLOW_LINES);
            List<UiLine> block = blockOf(progress, share - 1);
            if (shown > 0 && budget - block.size() >= 1) {
                lines.add(UiLine.EMPTY);
                budget--;
            }
            lines.addAll(block);
            budget -= block.size();
            shown++;
        }
        if (shown < active.size()) {
            lines.add(UiLine.of(UiSegment.of(WorkflowText.ELLIPSIS + "另有 " + (active.size() - shown)
                    + " 个编排", UiEmphasis.DIM)));
        }
        return PanelContribution.of(TITLE, lines, UiRegion.TOP);
    }

    /**
     * 画一次编排：标题行 + 正文（+ 在汇总时的汇总行）。
     *
     * @param progress 编排快照
     * @param bodyRows 正文可用行数（不含标题）
     * @return 该编排的界面行，保证非 {@code null}
     */
    private List<UiLine> blockOf(WorkflowProgress progress, int bodyRows) {
        long now = clock.getAsLong();
        // 汇总行也要占一行，因此正文的预算要先把它让出来；它是最新发生的事，不能因为步骤多就消失
        int stepsRows = progress.isSummarizing() ? Math.max(1, bodyRows - 1) : Math.max(1, bodyRows);
        List<UiLine> lines = new ArrayList<UiLine>();
        lines.add(titleLine(progress, now));
        lines.addAll(WorkflowLayout.of(progress, now, stepsRows).getLines());
        if (progress.isSummarizing()) {
            String suffix = progress.getSummarizingSince() > 0L
                    ? " 已 " + WorkflowText.elapsed(now - progress.getSummarizingSince()) : "";
            lines.add(UiLine.of(UiSegment.of(StepState.RUNNING.mark() + "汇总中" + suffix,
                    UiEmphasis.ACCENT)));
        }
        return lines;
    }

    /**
     * 画标题行：名字、进度、整条编排已跑多久、几个在跑。
     * <p>
     * <b>「已跑」是整条编排的墙钟</b>：它回答的正是「它是不是还在动」；单步耗时只标在正在跑的那一步上
     * （见 {@link WorkflowLayout}），避免整块面板都是数字。有失败时整行转警示档位。
     *
     * @param progress 编排快照
     * @param now      当前时刻
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine titleLine(WorkflowProgress progress, long now) {
        StringBuilder text = new StringBuilder(progress.getName())
                .append(" · ").append(progress.settledCount()).append('/')
                .append(progress.getSteps().size()).append(" 步");
        if (progress.getStartedAt() > 0L) {
            text.append(" · 已跑 ").append(WorkflowText.elapsed(now - progress.getStartedAt()));
        }
        int running = progress.runningCount();
        if (running > 0) {
            text.append(" · ").append(running).append(" 在跑");
        }
        return UiLine.of(UiSegment.of(text.toString(),
                progress.hasFailure() ? UiEmphasis.WARN : UiEmphasis.NORMAL));
    }
}
