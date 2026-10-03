package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowPanel} 的单元测试：无内容时不占区域、内容与强调档位、行数上限。
 *
 * @author zcd
 */
@DisplayName("编排面板")
class WorkflowPanelTest {

    /** 记录变更通知（本测试不观察它，但台账要它）。 */
    private int changes;

    /** 台账。 */
    private WorkflowTracker tracker;

    /** 被测面板。 */
    private WorkflowPanel panel;

    @BeforeEach
    void setUp() {
        tracker = new WorkflowTracker(() -> changes++);
        panel = new WorkflowPanel(tracker);
    }

    @Test
    @DisplayName("没有在跑的编排时不占区域")
    void handle_shouldBeEmptyWithoutActiveWorkflow() {
        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
        // 没有任何会话上下文时也不该把别的会话的编排贴上来
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("渲染标题、进度与每个步骤的标记；落停靠区")
    void handle_shouldRenderProgressAndSteps() {
        String id = tracker.started("s-1", spec(3));
        tracker.stepFinished(id, "step-0", StepState.DONE);
        tracker.stepStarted(id, "step-1");

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(WorkflowPanel.TITLE, contribution.getTitle());
        assertEquals(UiRegion.DOCK, contribution.getPreferredRegion());
        List<String> texts = textsOf(contribution);
        assertEquals("小任务 · 1/3 步", texts.get(0));
        assertEquals("[x] step-0（scout）", texts.get(1));
        assertEquals("[~] step-1（scout）", texts.get(2));
        assertEquals("[ ] step-2（planner）", texts.get(3));
    }

    @Test
    @DisplayName("强调档位跟着状态走：跑着的用 ACCENT、完成的退到 DIM、失败的用 ERROR")
    void handle_shouldEmphasiseByState() {
        String id = tracker.started("s-1", spec(3));
        tracker.stepFinished(id, "step-0", StepState.DONE);
        tracker.stepStarted(id, "step-1");
        tracker.stepFinished(id, "step-2", StepState.FAILED);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.DIM, emphasisOf(contribution, "[x] "));
        assertEquals(UiEmphasis.ACCENT, emphasisOf(contribution, "[~] "));
        assertEquals(UiEmphasis.ERROR, emphasisOf(contribution, "[!] "));
    }

    @Test
    @DisplayName("有步骤失败时标题行改用警示档位")
    void handle_shouldWarnOnTitleWhenFailed() {
        String id = tracker.started("s-1", spec(1));
        tracker.stepFinished(id, "step-0", StepState.FAILED);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.WARN, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("汇总中要单独说一句：它是 spec 之外的又一次委派")
    void handle_shouldShowSummarising() {
        String id = tracker.started("s-1", spec(1));
        tracker.summarising(id);

        assertTrue(textsOf(panel.handle(new PanelContributionRequest("s-1"))).contains("[~] 汇总中"));
    }

    @Test
    @DisplayName("步骤多到放不下时截断，并说明还有几步没显示")
    void handle_shouldCapLines() {
        // 20 步远超面板能放的行数
        tracker.started("s-1", spec(20));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(texts.size() <= WorkflowPanel.MAX_LINES, String.valueOf(texts.size()));
        assertTrue(texts.get(texts.size() - 1).startsWith("…另有 "), texts.toString());
    }

    @Test
    @DisplayName("多个编排之间用空行隔开，且只显示本会话的")
    void handle_shouldSeparateWorkflowsAndFilterBySession() {
        tracker.started("s-1", spec(1));
        tracker.started("s-1", spec(1));
        tracker.started("s-2", spec(1));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        // 两个编排：标题 + 步骤 + 空行 + 标题 + 步骤（s-2 那个不该出现）
        assertEquals(5, texts.size(), texts.toString());
        assertEquals("", texts.get(2));
        assertEquals("小任务 · 0/1 步", texts.get(0));
        assertEquals("小任务 · 0/1 步", texts.get(3));
        assertFalse(texts.contains(null));
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
     * 找以给定标记开头的第一行的强调档位。
     *
     * @param contribution 面板贡献
     * @param mark         行首标记
     * @return 强调档位
     */
    private static UiEmphasis emphasisOf(PanelContribution contribution, String mark) {
        for (UiLine line : contribution.getLines()) {
            if (line.text().startsWith(mark)) {
                return line.getSegments().get(0).getEmphasis();
            }
        }
        throw new IllegalStateException("没有以该标记开头的行：" + mark);
    }

    /**
     * 构造一份给定步数的 spec（步骤依次依赖前一步，便于断言顺序）。
     *
     * @param count 步数
     * @return spec
     */
    private static WorkflowSpec spec(int count) {
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        for (int i = 0; i < count; i++) {
            List<String> needs = i == 0 ? null : Arrays.asList("step-" + (i - 1));
            String agent = i == count - 1 ? "planner" : "scout";
            steps.add(new WorkflowStep("step-" + i, agent, "step-" + i, needs, StepCondition.ALWAYS));
        }
        return new WorkflowSpec("小任务", steps, AggregateMode.SUMMARIZE, "planner");
    }
}
