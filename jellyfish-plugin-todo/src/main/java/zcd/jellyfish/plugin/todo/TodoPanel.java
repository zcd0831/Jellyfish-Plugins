package zcd.jellyfish.plugin.todo;

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
 * 面板贡献处理器：把当前会话的待办清单放成一块常驻面板。
 * <p>
 * <b>为什么有了状态栏还要面板</b>：状态栏只有一行，能表达「还剩几件事」却表达不了「哪几件」；
 * 而「模型正在做什么、做到哪儿了」恰恰需要看见清单本身。两者是不同粒度，不是重复。
 * <p>
 * <b>建议落左栏（{@code LEFT}）而不是停靠区</b>：待办是纵向长的清单，放横向区域会一行只写一件、
 * 右边大片留白，还把消息区的行顶掉最多 10 行（横向区域的内容行数上限是 8）；侧栏则天然适合列表——
 * 宽度在下限 20 列与终端宽的 1/4 之间按内容取，高度是消息区全高，长清单不会被截成「… 还有 N 行」。
 * <p>
 * <b>它会占住左栏，但不再把右栏挤掉</b>：待办条目变长会把左栏顶到它自己的上限（终端宽的 1/4），
 * 而单侧上限与合计上限（1/3）只差 1/12——旧实现据此判定超预算并直接舍掉右栏，于是待办一出现，
 * 右栏那块常驻观测面板就整块消失（{@code 202} 列的宽终端也一样会撞上，与终端窄不窄无关）。
 * 现在外壳改为两栏各保下限、右栏先满足、余下给左栏（见 {@code ChatLayout.sidebarsOf}），
 * 只要求终端宽的三分之一装得下两个下限（{@code ≥ 120} 列）；再窄才保留舍右栏的旧取舍。
 * 代价是左栏在窄终端上会被压到 20 列，长条目折行、可能触到面板的 8 行上限而显示「… 还有 N 行」。
 * 用户仍可以用 {@code /ui left off} 把左栏整个让出去。当然这只是<b>软建议</b>，
 * 外壳可以忽略它，插件不得假设自己一定在左边。
 * <p>
 * <b>没有待办时返回空贡献</b>：面板要独占一整个区域，没内容就不该抢地盘——
 * 这一点比状态栏更严格（状态栏只是不占列，面板是占一块地方）。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：与 {@link TodoStatusLine} 同一处有意识的偏离
 * （{@link TodoStore} 每会话首次访问时懒加载一次，之后命中内存缓存）。
 *
 * @author zcd
 */
final class TodoPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    private static final String TITLE = "待办";

    /** 面板上原因的长度上限：面板每行只有一行的宽度，长原因会把内容挤没。 */
    private static final int MAX_REASON_CHARS = 40;

    /** 待办仓库。 */
    private final TodoStore store;

    /** 认领者的在场记录：把 run 标识翻成人看得懂的子代理类型与状态。 */
    private final RunPresence presence;

    /**
     * 构造处理器。
     *
     * @param store    待办仓库，不可为 {@code null}
     * @param presence 认领者在场记录，不可为 {@code null}
     */
    TodoPanel(TodoStore store, RunPresence presence) {
        this.store = store;
        this.presence = presence;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null) {
            return PanelContribution.empty();
        }
        List<TodoItem> items = store.itemsOf(sessionId);
        if (items.isEmpty()) {
            return PanelContribution.empty();
        }
        List<UiLine> lines = new ArrayList<UiLine>(items.size());
        for (TodoItem item : items) {
            lines.add(lineOf(item));
        }
        return PanelContribution.of(TITLE, lines, UiRegion.LEFT);
    }

    /**
     * 渲染一条待办。
     * <p>
     * 四态的强调档位各不相同：已完成用 {@link UiEmphasis#DIM}（面板的用处是「看还剩什么」，
     * 做完的事应当退到背景里）、进行中用 {@link UiEmphasis#ACCENT}（它是此刻正在发生的事，
     * 是扫一眼面板最想看到的那一行）、卡住用 {@link UiEmphasis#ERROR}（它不会自己往前走，
     * 得有人决定）、未开始用 {@link UiEmphasis#NORMAL}。
     *
     * @param item 待办项
     * @return 界面行
     */
    private UiLine lineOf(TodoItem item) {
        UiEmphasis emphasis = emphasisOf(item.status());
        UiLine line = UiLine.of(UiSegment.of(item.status().mark(), emphasis),
                UiSegment.of(item.content(), emphasis));
        if (item.status() == TodoStatus.BLOCKED) {
            // 卡住时「为什么」比「谁卡的」重要得多：面板一行放不下两者，而原因决定下一步动作
            return item.reason() == null ? line : withReason(line, item.reason());
        }
        return item.owner() == null ? line : withOwner(line, ownerSegment(item.owner()));
    }

    /**
     * 在行尾接上卡住的原因（超长截断）。
     *
     * @param line   已经渲染好的行，不可为 {@code null}
     * @param reason 原因，不可为空白
     * @return 新行，保证非 {@code null}
     */
    private static UiLine withReason(UiLine line, String reason) {
        String text = reason.replace('\n', ' ').trim();
        if (text.length() > MAX_REASON_CHARS) {
            text = text.substring(0, MAX_REASON_CHARS) + "…";
        }
        List<UiSegment> segments = new ArrayList<UiSegment>(line.getSegments());
        segments.add(UiSegment.of(" —— " + text, UiEmphasis.ERROR));
        return new UiLine(segments);
    }

    /**
     * 渲染「谁在做」那一段。
     * <p>
     * 三种形态说得不一样，因为它们的含义不同：
     * <ul>
     *     <li>已知且还在跑 → {@code · researcher}。这是最常见的形态；</li>
     *     <li>已知但已经结束、而这条待办还挂在「进行中」→ {@code · researcher（已结束）} 并转
     *     {@link UiEmphasis#WARN}：<b>这是唯一需要人管一眼的形态</b>——那个子代理没把活标完成就走了，
     *     要么它失败了，要么它忘了。用正常档位显示会让人以为一切照旧；</li>
     *     <li>不知道（通知丢了、或那个 run 早已不在）→ {@code · 认领者未知}。宁可承认不知道，
     *     也不要凭一个可能过期的记录说「正在跑」。</li>
     * </ul>
     *
     * @param runId 认领者的 run 标识
     * @return 认领者那一段
     */
    private UiSegment ownerSegment(String runId) {
        RunPresence.Presence state = presence.stateOf(runId);
        if (!state.isKnown()) {
            return UiSegment.of(" · 认领者未知", UiEmphasis.DIM);
        }
        if (state.isFinished()) {
            return UiSegment.of(" · " + state.getAgentId() + "（已结束）", UiEmphasis.WARN);
        }
        return UiSegment.of(" · " + state.getAgentId(), UiEmphasis.DIM);
    }

    /**
     * 在已有的行尾追加一段。
     *
     * @param line  已经渲染好的行，不可为 {@code null}
     * @param owner 要追加的那一段，不可为 {@code null}
     * @return 新行，保证非 {@code null}
     */
    private static UiLine withOwner(UiLine line, UiSegment owner) {
        List<UiSegment> segments = new ArrayList<UiSegment>(line.getSegments());
        segments.add(owner);
        return new UiLine(segments);
    }

    /**
     * 取某个状态在面板上的强调档位。
     *
     * @param status 待办状态，不可为 {@code null}
     * @return 强调档位
     */
    private static UiEmphasis emphasisOf(TodoStatus status) {
        if (status == TodoStatus.COMPLETED) {
            return UiEmphasis.DIM;
        }
        if (status == TodoStatus.IN_PROGRESS) {
            return UiEmphasis.ACCENT;
        }
        if (status == TodoStatus.BLOCKED) {
            return UiEmphasis.ERROR;
        }
        return UiEmphasis.NORMAL;
    }
}
