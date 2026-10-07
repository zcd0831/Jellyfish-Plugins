package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowTracker} 的单元测试：状态流转、快照隔离、按会话过滤与变更通知。
 *
 * @author zcd
 */
@DisplayName("编排台账")
class WorkflowTrackerTest {

    /** 记录变更通知次数。 */
    private int changes;

    @Test
    @DisplayName("登记后所有步骤都是待执行，且已经发过一次通知")
    void started_shouldRegisterAllStepsAsPending() {
        WorkflowTracker tracker = tracker();

        String id = tracker.started("s-1", spec());

        List<WorkflowProgress> snapshot = tracker.snapshot("s-1");
        assertEquals(1, snapshot.size());
        assertEquals("小任务", snapshot.get(0).getName());
        assertEquals(3, snapshot.get(0).getSteps().size());
        assertEquals(0, snapshot.get(0).settledCount());
        for (WorkflowProgress.Step step : snapshot.get(0).getSteps()) {
            assertEquals(StepState.PENDING, step.getState());
        }
        // 登记本身也是一次变化：否则面板在第一步跑完之前一直是空的
        assertEquals(1, changes);
    }

    @Test
    @DisplayName("逐步流转：进行中 → 完成，计数跟着走")
    void stepStates_shouldMoveForward() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());

        tracker.stepStarted(id, "a");
        assertEquals(StepState.RUNNING, stateOf(tracker, "a"));
        tracker.stepFinished(id, "a", StepState.DONE);

        WorkflowProgress progress = tracker.snapshot("s-1").get(0);
        assertEquals(StepState.DONE, stateOf(tracker, "a"));
        assertEquals(1, progress.settledCount());
        assertFalse(progress.hasFailure());
    }

    @Test
    @DisplayName("失败与跳过都算「已决」，但只有失败会点亮警示")
    void failedAndSkipped_shouldBeDistinguished() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());

        tracker.stepFinished(id, "a", StepState.FAILED);
        tracker.stepFinished(id, "b", StepState.SKIPPED);

        WorkflowProgress progress = tracker.snapshot("s-1").get(0);
        assertEquals(2, progress.settledCount());
        assertTrue(progress.hasFailure());
    }

    @Test
    @DisplayName("汇总是一个独立的状态位，不是一步")
    void summarising_shouldBeVisible() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());

        tracker.summarising(id);

        assertTrue(tracker.snapshot("s-1").get(0).isSummarizing());
        // 它不计入步骤数：spec 里只有三步
        assertEquals(3, tracker.snapshot("s-1").get(0).getSteps().size());
    }

    @Test
    @DisplayName("快照是拷贝：台账后续变化不影响已经取到的那一份")
    void snapshot_shouldBeIndependentCopy() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());
        WorkflowProgress before = tracker.snapshot("s-1").get(0);

        tracker.stepFinished(id, "a", StepState.DONE);

        assertEquals(StepState.PENDING, before.getSteps().get(0).getState());
        assertEquals(0, before.settledCount());
    }

    @Test
    @DisplayName("只返回本会话的编排；会话为空时返回空表")
    void snapshot_shouldFilterBySession() {
        WorkflowTracker tracker = tracker();
        tracker.started("s-1", spec());
        tracker.started("s-2", spec());

        assertEquals(1, tracker.snapshot("s-1").size());
        assertEquals(1, tracker.snapshot("s-2").size());
        assertTrue(tracker.snapshot("s-3").isEmpty());
        assertTrue(tracker.snapshot(null).isEmpty());
    }

    @Test
    @DisplayName("跑完即移除：面板不该展示历史，条目也不该随会话时长增长")
    void finished_shouldDropTheEntry() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());
        int before = changes;

        tracker.finished(id);

        assertTrue(tracker.snapshot("s-1").isEmpty());
        assertEquals(before + 1, changes);
        // 移除一个不存在的条目不该再发通知（那只会让外壳白拉一次面板）
        tracker.finished(id);
        assertEquals(before + 1, changes);
    }

    @Test
    @DisplayName("未知编排或未知步骤上的更新是安全的空操作")
    void unknownIds_shouldBeIgnored() {
        WorkflowTracker tracker = tracker();
        String id = tracker.started("s-1", spec());
        int before = changes;

        tracker.stepStarted("ghost", "a");
        tracker.stepStarted(id, "ghost");
        tracker.stepFinished(id, "ghost", StepState.DONE);
        tracker.summarising("ghost");

        assertEquals(before, changes);
    }

    /**
     * 构造台账。
     *
     * @return 台账
     */
    private WorkflowTracker tracker() {
        return new WorkflowTracker(() -> changes++);
    }

    /**
     * 构造一份三步 spec。
     *
     * @return spec
     */
    private static WorkflowSpec spec() {
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        steps.add(new WorkflowStep("a", "任务A", "scout", "a", null, StepCondition.ALWAYS));
        steps.add(new WorkflowStep("b", null, "scout", "b", null, StepCondition.ALWAYS));
        steps.add(new WorkflowStep("c", "任务C", "planner", "c", Arrays.asList("a", "b"),
                StepCondition.ON_SUCCESS));
        return new WorkflowSpec("小任务", steps, AggregateMode.SUMMARIZE, "planner");
    }

    /**
     * 取某个步骤此刻的状态。
     *
     * @param tracker 台账
     * @param stepId  步骤标识
     * @return 状态
     */
    private static StepState stateOf(WorkflowTracker tracker, String stepId) {
        for (WorkflowProgress.Step step : tracker.snapshot("s-1").get(0).getSteps()) {
            if (step.getId().equals(stepId)) {
                return step.getState();
            }
        }
        throw new IllegalStateException("步骤不存在：" + stepId);
    }
}
