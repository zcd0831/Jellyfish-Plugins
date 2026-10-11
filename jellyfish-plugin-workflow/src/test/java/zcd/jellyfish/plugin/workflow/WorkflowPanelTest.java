package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowPanel} 的单元测试：无内容时不占区域、标题行、图形与清单的取舍、行数上限。
 * <p>
 * 断言写得逐字，是因为这块面板的价值全在「一眼能不能读懂」：改版式的人必须看到自己改动了哪张图。
 * 图形中的连线位置另有断言（上下两行的字形必须落同一列），那是「图能不能读懂」的底线。
 *
 * @author zcd
 */
@DisplayName("编排面板")
class WorkflowPanelTest {

    /** 记录变更通知次数（本测试不观察它，但台账要它）。 */
    private int changes;

    /** 当前时刻，由测试推进。 */
    private long now = 1_000L;

    /** 台账。 */
    private WorkflowTracker tracker;

    /** 被测面板。 */
    private WorkflowPanel panel;

    @BeforeEach
    void setUp() {
        tracker = new WorkflowTracker(new Runnable() {

            @Override
            public void run() {
                changes++;
            }
        }, () -> now);
        panel = new WorkflowPanel(tracker, () -> now);
    }

    @Test
    @DisplayName("没有在跑的编排时不占区域")
    void handle_shouldBeEmptyWithoutActiveWorkflow() {
        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
        // 没有任何会话上下文时也不该把别的会话的编排贴上来
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("标题行说清进度与耗时，正文把链压成一行；耗时只标在跑的那一步")
    void handle_shouldDrawChainWithTitleAndRunningElapsed() {
        String id = started(step("a", "调研 A", "scout"), step("b", "写方案", "planner", "a"),
                step("c", "落笔", "docForge", "b"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(WorkflowPanel.TITLE, contribution.getTitle());
        assertEquals(UiRegion.TOP, contribution.getPreferredRegion());
        assertEquals(Arrays.asList(
                "调研并写方案 · 1/3 步 · 已跑 39s · 1 在跑",
                "L1 [x] 调研 A（scout） ───> L2 [~] 写方案（planner） 已 38s",
                "… ───> L3 [ ] 落笔（docForge）"), textsOf(contribution));
    }

    @Test
    @DisplayName("汇合：下游画在第一个上游那一行的右端，其余上游行尾拐上去，且两行字形同列")
    void handle_shouldDrawJoinAndAlignColumns() {
        String id = started(step("a", "调研 A", "scout"), step("b", "调研 B", "scout"),
                step("c", "写方案", "planner", "a", "b"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertEquals("调研并写方案 · 1/3 步 · 已跑 39s · 1 在跑", texts.get(0));
        assertEquals("L1 [x] 调研 A（scout） ────────┬──> L2 [ ] 写方案（planner）", texts.get(1));
        assertEquals("L1 [~] 调研 B（scout） 已 38s ─┘", texts.get(2));
        assertEquals(columnOf(texts.get(1), '\u252c'), columnOf(texts.get(2), '\u2518'));
    }

    @Test
    @DisplayName("分叉：第一个下游接在行内，其余的从同一列往下引")
    void handle_shouldDrawFanOut() {
        String id = started(step("a", "调研 A", "scout"), step("b", "写方案", "planner", "a"),
                step("c", "复核", "reviewer", "a"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertEquals("L1 [x] 调研 A（scout） ───┬──> L2 [~] 写方案（planner） 已 38s", texts.get(1));
        assertTrue(texts.get(2).endsWith("└──> L2 [ ] 复核（reviewer）"), texts.get(2));
        assertEquals(columnOf(texts.get(1), '\u252c'), columnOf(texts.get(2), '\u2514'));
    }

    @Test
    @DisplayName("强调档位跟着状态走：跑着的用 ACCENT、完成的退到 DIM、失败的用 ERROR")
    void handle_shouldEmphasiseByState() {
        String id = started(step("a", "调研 A", "scout"), step("b", "调研 B", "scout"),
                step("c", "调研 C", "scout"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        tracker.stepFinished(id, "c", StepState.FAILED);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.DIM, emphasisOf(contribution, "[x] "));
        assertEquals(UiEmphasis.ACCENT, emphasisOf(contribution, "[~] "));
        assertEquals(UiEmphasis.ERROR, emphasisOf(contribution, "[!] "));
    }

    @Test
    @DisplayName("有步骤失败时标题行改用警示档位")
    void handle_shouldWarnOnTitleWhenFailed() {
        String id = started(step("a", "调研 A", "scout"));
        tracker.stepFinished(id, "a", StepState.FAILED);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.WARN, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("汇总中要单独说一句，并带上它自己跑了多久")
    void handle_shouldShowSummarising() {
        String id = started(step("a", "调研 A", "scout"));
        settled(id, "a");
        tracker.summarising(id);
        now += 12_000L;

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertEquals("[~] 汇总中 已 12s", texts.get(texts.size() - 1));
    }

    @Test
    @DisplayName("内容行数不超过外壳给的上限")
    void handle_shouldStayWithinLineBudget() {
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        for (int i = 0; i < 12; i++) {
            steps.add(step("s" + i, "步骤 " + i, "scout", i == 0 ? new String[0] : new String[] {"s" + (i - 1)}));
        }
        started(steps.toArray(new WorkflowStep[0]));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(texts.size() <= WorkflowPanel.MAX_LINES, String.valueOf(texts.size()));
    }

    @Test
    @DisplayName("多个编排之间用空行隔开，且只显示本会话的")
    void handle_shouldSeparateWorkflowsAndFilterBySession() {
        started(step("a", "调研 A", "scout"));
        started(step("b", "调研 B", "scout"));
        tracker.started("s-2", spec(step("c", "调研 C", "scout")));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        // 两个编排：标题 + 一行正文 + 空行 + 标题 + 一行正文（s-2 那个不该出现）
        assertEquals(5, texts.size(), texts.toString());
        assertEquals("", texts.get(2));
        assertTrue(texts.get(0).startsWith("调研并写方案 · 0/1 步"), texts.get(0));
        assertTrue(texts.get(3).startsWith("调研并写方案 · 0/1 步"), texts.get(3));
    }

    @Test
    @DisplayName("排不出图的形状退化成清单，依赖用行尾的 <- 标出来，而不是干脆丢掉")
    void handle_shouldFallBackToPlainList() {
        // a 分叉给 b、c；b 又分叉给 d、e——同一行上要出现两个分叉字形，画不准就不画
        String id = started(step("a", "调研 A", "scout"), step("b", "写方案", "planner", "a"),
                step("c", "复核", "reviewer", "a"), step("d", "落笔", "docForge", "b"),
                step("e", "归档", "docForge", "b"));
        settled(id, "a");

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(texts.contains("L1 [x] 调研 A（scout）"), texts.toString());
        assertTrue(texts.contains("L2 [ ] 写方案（planner） <- a"), texts.toString());
        assertTrue(texts.contains("L3 [ ] 落笔（docForge） <- b"), texts.toString());
    }

    /**
     * 登记一次编排（本会话，名字固定）。
     *
     * @param steps 步骤
     * @return 编排标识
     */
    private String started(WorkflowStep... steps) {
        return tracker.started("s-1", spec(steps));
    }

    /**
     * 造一份 spec。
     *
     * @param steps 步骤
     * @return spec
     */
    private static WorkflowSpec spec(WorkflowStep... steps) {
        return new WorkflowSpec("调研并写方案", Arrays.asList(steps), AggregateMode.COLLECT, null);
    }

    /**
     * 把某一步跑完（期间推进时钟，让耗时不为零）。
     *
     * @param id     编排标识
     * @param stepId 步骤标识
     */
    private void settled(String id, String stepId) {
        tracker.stepStarted(id, stepId);
        // 推进一点点，让这一步的起止时刻不同（不到 1 秒，因此不会污染后面按秒读的耗时文案）
        now += 1_000L;
        tracker.stepFinished(id, stepId, StepState.DONE);
    }

    /**
     * 造一个步骤。
     *
     * @param id    标识
     * @param name  名字
     * @param agent 类型
     * @param needs 上游
     * @return 步骤
     */
    private static WorkflowStep step(String id, String name, String agent, String... needs) {
        return new WorkflowStep(id, name, agent, id, needs.length == 0 ? null : Arrays.asList(needs),
                StepCondition.ALWAYS);
    }

    /**
     * 取面板内容行的文本。
     *
     * @param contribution 面板贡献
     * @return 文本列表
     */
    private static List<String> textsOf(PanelContribution contribution) {
        List<String> texts = new ArrayList<String>();
        for (UiLine line : contribution.getLines()) {
            texts.add(line.text());
        }
        return texts;
    }

    /**
     * 找某个字形所在的显示列。
     *
     * @param text  文本
     * @param glyph 字形
     * @return 显示列；找不到时返回 -1
     */
    private static int columnOf(String text, char glyph) {
        int column = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (codePoint == glyph) {
                return column;
            }
            column += WorkflowText.displayWidth(new String(Character.toChars(codePoint)));
            index += Character.charCount(codePoint);
        }
        return -1;
    }

    /**
     * 找带给定标记的那一段的强调档位（标记在节点文本里，因此按段找而不是按行首找）。
     *
     * @param contribution 面板贡献
     * @param mark         状态标记，如 {@code [x] }
     * @return 强调档位
     */
    private static UiEmphasis emphasisOf(PanelContribution contribution, String mark) {
        for (UiLine line : contribution.getLines()) {
            for (UiSegment segment : line.getSegments()) {
                if (segment.getText().contains(mark)) {
                    return segment.getEmphasis();
                }
            }
        }
        throw new IllegalStateException("没有带该标记的段：" + mark);
    }
}
