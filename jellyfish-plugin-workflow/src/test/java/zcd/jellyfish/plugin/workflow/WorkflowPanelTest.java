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
    @DisplayName("渲染标题、进度与每个步骤的标记；落消息区上方")
    void handle_shouldRenderProgressAndSteps() {
        String id = tracker.started("s-1", spec(3));
        tracker.stepFinished(id, "step-0", StepState.DONE);
        tracker.stepStarted(id, "step-1");

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(WorkflowPanel.TITLE, contribution.getTitle());
        assertEquals(UiRegion.TOP, contribution.getPreferredRegion());
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

    @Test
    @DisplayName("有名字时显示名字，没有时退回标识")
    void handle_shouldPreferNameOverId() {
        tracker.started("s-1", namedSpec("任务A", null));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        // 标识是给机器用的键（被 needs 引用），面板是给人看的：给了名字就用名字
        assertEquals("[ ] 任务A（scout）", texts.get(1));
        // 没给名字时退回标识——它至少能把这一行与失败清单、材料标头对起来
        assertEquals("[ ] step-b（scout）", texts.get(2));
    }

    @Test
    @DisplayName("过长的名字由面板自己截短，不依赖外壳兜底")
    void handle_shouldClipLongName() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longName.append('长');
        }
        tracker.started("s-1", namedSpec(longName.toString(), null));

        List<String> texts = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        // 40 列的名字加「（scout）」会折行，而这块面板落消息区上方（全宽）就是为了不折行
        assertEquals(WorkflowPanel.MAX_NAME_CHARS + 1 + "（scout）".length(),
                texts.get(1).length() - "[ ] ".length());
        assertTrue(texts.get(1).endsWith("…（scout）"), texts.get(1));
    }

    @Test
    @DisplayName("截短不切开代理对：半个字符在终端上是乱码")
    void handle_shouldNotSplitSurrogatePairWhenClipping() {
        // 19 个半角 + 两个代理对：第 20 个字符（下标 MAX_NAME_CHARS）正好是某个代理对的后半个
        StringBuilder name = new StringBuilder("0123456789012345678");
        name.append("\uD83D\uDE00").append("\uD83D\uDE00");
        tracker.started("s-1", namedSpec(name.toString(), null));

        List<String> label = textsOf(panel.handle(new PanelContributionRequest("s-1")));

        String line = label.get(1);
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(Character.isLowSurrogate(line.charAt(i + 1)), "高位代理后面没有低位代理");
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(current), "出现了孤立低位代理：" + line);
            }
        }
        assertTrue(line.startsWith("[ ] 0123456789012345678…（scout）"), line);
    }

    /**
     * 构造两个步骤的 spec，可分别指定名字。
     *
     * @param firstName  第一步的名字，可为 {@code null}
     * @param secondName 第二步的名字，可为 {@code null}
     * @return spec
     */
    private static WorkflowSpec namedSpec(String firstName, String secondName) {
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        steps.add(new WorkflowStep("step-a", firstName, "scout", "step-a", null, StepCondition.ALWAYS));
        steps.add(new WorkflowStep("step-b", secondName, "scout", "step-b", null, StepCondition.ALWAYS));
        return new WorkflowSpec("小任务", steps, AggregateMode.COLLECT, null);
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
            steps.add(new WorkflowStep("step-" + i, null, agent, "step-" + i, needs, StepCondition.ALWAYS));
        }
        return new WorkflowSpec("小任务", steps, AggregateMode.SUMMARIZE, "planner");
    }
}
