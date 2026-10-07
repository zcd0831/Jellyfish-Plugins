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

/**
 * 面板贡献：把正在跑的编排及其步骤状态放成一块常驻面板。
 * <p>
 * <b>为什么需要它</b>：一次编排可以在「没有输出」的状态下跑上好几分钟（几个子代理各自在调模型），
 * 而它<b>是并行</b>的——工具行上滚动的那几行进度看不出「哪一步在跑、哪些已经完了、还剩几步」。
 * 面板把这段沉默期变成一张可数的进度表。
 * <p>
 * <b>建议落消息区上方（{@code TOP}）而不是停靠区</b>：停靠区里已经排了内核那块「子代理」面板
 * （它声明了更小的 {@code order}），而一次编排<b>就是</b>批量派子代理——两块面板恰好在同一时刻
 * 都要看（那边讲「谁在跑」，这里讲「编排走到第几步」），同区域等于永远只能看见一个。
 * 上方的行数预算与停靠区完全相同（同一套「内容行数上限 8、区域上限 min(终端高/3, 12)」），
 * 换区域不多花一行，只是把冲突消掉。
 * <p>
 * <b>顺带解决折行</b>：纵向栏的内容宽只有 18 列左右，而步骤行是「标记 + 标识 + 类型」，
 * 稍长就折成两行（还依赖模型写的标识够短）；上方是全宽，不折行。
 * 当然这只是<b>软建议</b>，外壳可以忽略它。
 * <p>
 * <b>行数上限由插件自己先收一道</b>：外壳会给面板兜底（折行、截断、行数上限），但插件不该
 * 依赖那个兜底——15 个步骤加两行标题就能把消息区顶掉小半屏。这里收在
 * {@link #MAX_LINES} 行，超出部分折成一句「还有 N 步」。
 * <p>
 * <b>没有在跑的编排时返回空贡献</b>：面板独占一整块区域，没内容就不该抢地盘。
 * 编排跑完即从台账移除，因此它会在结束那一刻消失——与内核的子代理面板同一行为。
 * <p>
 * <b>处理器是快且只读的</b>：它只读一次台账快照、拼几行文本，不做 I/O、不发失效事件
 * （那会形成「失效 → 收集 → 失效」的死循环；通知由 {@link WorkflowTracker} 在状态变化时发）。
 *
 * @author zcd
 */
final class WorkflowPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    static final String TITLE = "编排";

    /** 面板行数上限（含标题行与汇总行）。 */
    static final int MAX_LINES = 12;

    /**
     * 步骤名在面板上的显示上限（字符）。
     * <p>
     * <b>宽度也由插件自己先收一道</b>，与 {@link #MAX_LINES} 同一条理由：外壳会给面板兜底
     * （折行、截断），但插件不该依赖那个兜底。这一行是「标记 + 名字（类型）」，终端常见宽度下中文
     * 按 2 列算，20 字已占 40 列；再长就会折行，而这块面板特意建议落消息区上方（全宽）就是为了不折行。
     * <p>
     * <b>只截展示、不改数据</b>：截断发生在渲染这一步，{@code WorkflowProgress} 里的名字始终是完整的。
     */
    static final int MAX_NAME_CHARS = 20;

    /** 名字被截断时追加的标记。 */
    private static final String ELLIPSIS = "…";

    /** 编排台账。 */
    private final WorkflowTracker tracker;

    /**
     * 构造处理器。
     *
     * @param tracker 编排台账，不可为 {@code null}
     */
    WorkflowPanel(WorkflowTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        List<WorkflowProgress> active = tracker.snapshot(request.getSessionId());
        if (active.isEmpty()) {
            return PanelContribution.empty();
        }
        // 留一行给「还有 N 步未显示」：先画满再把话说不完，比画到一半被外壳截断更好读
        int limit = MAX_LINES - 1;
        List<UiLine> lines = new ArrayList<UiLine>();
        int skipped = 0;
        for (WorkflowProgress progress : active) {
            if (!lines.isEmpty()) {
                lines.add(UiLine.EMPTY);
            }
            skipped += appendWorkflow(lines, progress, limit);
        }
        if (skipped > 0) {
            lines.add(UiLine.of(UiSegment.of("…另有 " + skipped + " 步未显示", UiEmphasis.DIM)));
        }
        return PanelContribution.of(TITLE, lines, UiRegion.TOP);
    }

    /**
     * 追加一次编排的标题行、汇总行与步骤行。
     *
     * @param lines    累积的行
     * @param progress 编排快照
     * @param limit    行数上限
     * @return 因为上限而没画出来的步骤数，保证非负
     */
    private static int appendWorkflow(List<UiLine> lines, WorkflowProgress progress, int limit) {
        String header = progress.getName() + " · " + progress.settledCount() + "/"
                + progress.getSteps().size() + " 步";
        lines.add(UiLine.of(UiSegment.of(header,
                progress.hasFailure() ? UiEmphasis.WARN : UiEmphasis.NORMAL)));
        int skipped = 0;
        for (WorkflowProgress.Step step : progress.getSteps()) {
            if (lines.size() >= limit) {
                skipped++;
                continue;
            }
            lines.add(lineOf(step));
        }
        if (progress.isSummarizing() && lines.size() < limit) {
            lines.add(UiLine.of(UiSegment.of("[~] 汇总中", UiEmphasis.ACCENT)));
        }
        return skipped;
    }

    /**
     * 渲染一个步骤：标记 + 名字（类型）。
     * <p>
     * 强调档位跟着状态走：跑着的用 {@link UiEmphasis#ACCENT}（扫一眼最想知道的那一行），
     * 已完成的退到 {@link UiEmphasis#DIM}（面板的用处是「还差什么」），失败的用
     * {@link UiEmphasis#ERROR}（它需要立刻被看见）。
     *
     * @param step 步骤快照
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine lineOf(WorkflowProgress.Step step) {
        UiEmphasis emphasis = emphasisOf(step.getState());
        return UiLine.of(UiSegment.of(step.getState().mark(), emphasis),
                UiSegment.of(labelOf(step) + "（" + step.getAgent() + "）", emphasis));
    }

    /**
     * 取一个步骤在面板上的显示名。
     * <p>
     * <b>没写名字时退回标识</b>：标识至少能把面板上的这一行与失败清单、材料标头对起来，
     * 比显示一个空位有用。
     *
     * @param step 步骤快照
     * @return 显示名，保证非空白
     */
    private static String labelOf(WorkflowProgress.Step step) {
        String name = step.getName();
        if (name == null || name.trim().isEmpty()) {
            return step.getId();
        }
        return clip(name.trim());
    }

    /**
     * 按 {@link #MAX_NAME_CHARS} 截短一个名字，切口不落在代理对中间。
     * <p>
     * <b>为什么不能直接 {@code substring}</b>：切开一个代理对会让终端收到半个字符，显示成乱码，
     * 而外壳算列宽时也可能因此错位。
     *
     * @param name 名字
     * @return 截短结果
     */
    private static String clip(String name) {
        if (name.length() <= MAX_NAME_CHARS) {
            return name;
        }
        int end = MAX_NAME_CHARS;
        // charAt(end) 是第一个不含在内的字符；它是低位代理，说明它的高位被切在了界内
        if (Character.isLowSurrogate(name.charAt(end))) {
            end--;
        }
        return name.substring(0, end) + ELLIPSIS;
    }

    /**
     * 取状态对应的强调档位。
     *
     * @param state 状态
     * @return 强调档位，保证非 {@code null}
     */
    private static UiEmphasis emphasisOf(StepState state) {
        switch (state) {
            case RUNNING:
                return UiEmphasis.ACCENT;
            case DONE:
            case SKIPPED:
                return UiEmphasis.DIM;
            case FAILED:
                return UiEmphasis.ERROR;
            default:
                return UiEmphasis.NORMAL;
        }
    }
}
