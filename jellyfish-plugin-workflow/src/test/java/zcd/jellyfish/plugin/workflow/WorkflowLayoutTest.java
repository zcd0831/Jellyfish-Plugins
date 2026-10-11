package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowLayout} 的单元测试：排版的三条硬规则——连线同列、行数不够先撤已完成的层、
 * 画不出来就退化成清单，以及宽度预算。
 *
 * @author zcd
 */
@DisplayName("编排排版")
class WorkflowLayoutTest {

    /** 当前时刻，由测试推进。 */
    private long now = 1_000L;

    /** 台账。 */
    private WorkflowTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new WorkflowTracker(new Runnable() {

            @Override
            public void run() {
                // 排版测试不观察通知
            }
        }, () -> now);
    }

    @Test
    @DisplayName("汇合的竖线落在同一列：上下两行的字形必须对齐")
    void of_shouldAlignJoinColumns() {
        String id = started(step("a", "调研 A", "scout"), step("b", "调研 B", "scout"),
                step("c", "写方案", "planner", "a", "b"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        List<String> lines = layout(id, 7);

        assertEquals(2, lines.size(), lines.toString());
        assertEquals(columnOf(lines.get(0), '\u252c'), columnOf(lines.get(1), '\u2518'));
        assertTrue(lines.get(0).contains("┬──> L2 [ ] 写方案（planner）"), lines.get(0));
    }

    @Test
    @DisplayName("分叉的竖线落在同一列，落点在下一行行首")
    void of_shouldAlignFanColumns() {
        String id = started(step("a", "调研 A", "scout"), step("b", "写方案", "planner", "a"),
                step("c", "复核", "reviewer", "a"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        List<String> lines = layout(id, 7);

        assertEquals(2, lines.size(), lines.toString());
        assertEquals(columnOf(lines.get(0), '\u252c'), columnOf(lines.get(1), '\u2514'));
        assertTrue(lines.get(1).endsWith("└──> L2 [ ] 复核（reviewer）"), lines.get(1));
    }

    @Test
    @DisplayName("行数不够时先撤已完成的层，并在下游行首留「接自上方」的痕迹")
    void of_shouldDropSettledLayersWhenOutOfRows() {
        String id = started(chain(8));
        settled(id, "s0");
        settled(id, "s1");

        List<String> lines = layout(id, 3);

        assertEquals(3, lines.size(), lines.toString());
        for (String line : lines) {
            assertTrue(line.startsWith(WorkflowLayout.ENTRY), line);
        }
        // 撤掉的是已经跑完的前两层
        String joined = String.join("\n", lines);
        assertFalse(joined.contains("步骤 0"), joined);
        assertFalse(joined.contains("步骤 1"), joined);
        assertTrue(joined.contains("步骤 2"), joined);
        assertTrue(lines.get(2).contains("步骤 7"), joined);
    }

    @Test
    @DisplayName("撤不动了才截断，并如实说清还有几步没显示")
    void of_shouldTruncateAndReportHiddenSteps() {
        String id = started(chain(12));

        List<String> lines = layout(id, 2);

        assertEquals(2, lines.size(), lines.toString());
        // 12 步、每行放三步：第一行三步留在屏幕上，剩下 9 步只报个数
        assertEquals(WorkflowText.ELLIPSIS + "另有 9 步未显示", lines.get(1));
    }

    @Test
    @DisplayName("一行上要出现两个分叉字形时整条退化成清单，依赖用 <- 标出来")
    void of_shouldFallBackToPlainListWhenGraphIsTooDense() {
        String id = started(step("a", "调研 A", "scout"), step("b", "写方案", "planner", "a"),
                step("c", "复核", "reviewer", "a"), step("d", "落笔", "docForge", "b"),
                step("e", "归档", "docForge", "b"));
        settled(id, "a");

        List<String> lines = layout(id, 7);

        assertEquals(5, lines.size(), lines.toString());
        assertTrue(lines.contains("L2 [ ] 写方案（planner） <- a"), lines.toString());
        assertTrue(lines.contains("L3 [ ] 落笔（docForge） <- b"), lines.toString());
    }

    @Test
    @DisplayName("耗时只标在正在跑的那一步上")
    void of_shouldMarkElapsedOnRunningStepOnly() {
        String id = started(step("a", "调研 A", "scout"), step("b", "调研 B", "scout"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        now = 40_000L;

        List<String> lines = layout(id, 7);

        assertEquals(Arrays.asList("L1 [x] 调研 A（scout）", "L1 [~] 调研 B（scout） 已 38s"), lines);
    }

    @Test
    @DisplayName("过长的名字按行宽预算截短，且不切开代理对")
    void of_shouldClipLongNameWithoutSplittingSurrogates() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            name.append('长');
        }
        name.append("\uD83D\uDE00");
        String id = started(step("a", name.toString(), "scout"));

        List<String> lines = layout(id, 7);

        assertTrue(lines.get(0).endsWith("\u2026（scout）"), lines.get(0));
        assertFalse(lines.get(0).contains("\uD83D"), "代理对被切开了：" + lines.get(0));
        assertTrue(WorkflowText.displayWidth(lines.get(0)) <= WorkflowLayout.NODE_WIDTH_BUDGET,
                lines.get(0));
    }

    @Test
    @DisplayName("强调档位：跑着 ACCENT、完成 DIM、失败 ERROR、待执行 NORMAL")
    void of_shouldUseEmphasisByState() {
        String id = started(step("a", "调研 A", "scout"), step("b", "调研 B", "scout"),
                step("c", "调研 C", "scout"), step("d", "调研 D", "scout"));
        settled(id, "a");
        tracker.stepStarted(id, "b");
        tracker.stepFinished(id, "c", StepState.FAILED);

        List<UiLine> lines = WorkflowLayout.of(progress(id), now, 7).getLines();

        assertEquals(UiEmphasis.DIM, emphasisOf(lines, "[x] "));
        assertEquals(UiEmphasis.ACCENT, emphasisOf(lines, "[~] "));
        assertEquals(UiEmphasis.ERROR, emphasisOf(lines, "[!] "));
        assertEquals(UiEmphasis.NORMAL, emphasisOf(lines, "[ ] "));
    }

    @Test
    @DisplayName("没有任何步骤时不出行")
    void of_shouldBeEmptyWithoutSteps() {
        WorkflowProgress empty = new WorkflowProgress("空编排", new ArrayList<WorkflowProgress.Step>(),
                false, 1_000L, 0L);

        assertTrue(WorkflowLayout.of(empty, now, 7).getLines().isEmpty());
    }

    /**
     * 排一次版并取文本行。
     *
     * @param workflowId 编排标识
     * @param maxRows    正文行数预算
     * @return 文本行
     */
    private List<String> layout(String workflowId, int maxRows) {
        List<String> lines = new ArrayList<String>();
        for (UiLine line : WorkflowLayout.of(progress(workflowId), now, maxRows).getLines()) {
            lines.add(line.text());
        }
        return lines;
    }

    /**
     * 取某次编排的快照。
     *
     * @param workflowId 编排标识
     * @return 快照
     */
    private WorkflowProgress progress(String workflowId) {
        return tracker.snapshot("s-1").get(0);
    }

    /**
     * 把某一步跑完。
     *
     * @param id     编排标识
     * @param stepId 步骤标识
     */
    private void settled(String id, String stepId) {
        tracker.stepStarted(id, stepId);
        now += 1_000L;
        tracker.stepFinished(id, stepId, StepState.DONE);
    }

    /**
     * 造一条给定步数的链（标识 s0…、名字「步骤 N」）。
     *
     * @param count 步数
     * @return 步骤
     */
    private static WorkflowStep[] chain(int count) {
        WorkflowStep[] steps = new WorkflowStep[count];
        for (int i = 0; i < count; i++) {
            steps[i] = step("s" + i, "步骤 " + i, "scout",
                    i == 0 ? new String[0] : new String[] {"s" + (i - 1)});
        }
        return steps;
    }

    /**
     * 登记一次编排。
     *
     * @param steps 步骤
     * @return 编排标识
     */
    private String started(WorkflowStep... steps) {
        return tracker.started("s-1", new WorkflowSpec("调研并写方案", Arrays.asList(steps),
                AggregateMode.COLLECT, null));
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
     * 找带给定标记的那一段的强调档位。
     *
     * @param lines 行列表
     * @param mark  状态标记
     * @return 强调档位
     */
    private static UiEmphasis emphasisOf(List<UiLine> lines, String mark) {
        for (UiLine line : lines) {
            for (zcd.jellyfish.api.ui.UiSegment segment : line.getSegments()) {
                if (segment.getText().contains(mark)) {
                    return segment.getEmphasis();
                }
            }
        }
        throw new IllegalStateException("没有带该标记的段：" + mark);
    }
}
