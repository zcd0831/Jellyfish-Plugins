package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowLayers} 的单元测试：静态分层，以及「算不动时也不抛错」。
 *
 * @author zcd
 */
@DisplayName("静态分层")
class WorkflowLayersTest {

    @Test
    @DisplayName("无依赖的步骤全在第一层：同层即可以并行")
    void of_shouldPutIndependentStepsOnFirstLayer() {
        Map<String, Integer> layers = WorkflowLayers.of(steps(step("a"), step("b"), step("c")));

        assertEquals(Integer.valueOf(1), layers.get("a"));
        assertEquals(Integer.valueOf(1), layers.get("b"));
        assertEquals(Integer.valueOf(1), layers.get("c"));
    }

    @Test
    @DisplayName("链式依赖逐层加一")
    void of_shouldLayerChainStepByStep() {
        Map<String, Integer> layers = WorkflowLayers.of(steps(step("a"), step("b", "a"), step("c", "b")));

        assertEquals(Integer.valueOf(1), layers.get("a"));
        assertEquals(Integer.valueOf(2), layers.get("b"));
        assertEquals(Integer.valueOf(3), layers.get("c"));
    }

    @Test
    @DisplayName("多个上游时取最深的那条路：同步点不会被算早")
    void of_shouldTakeDeepestUpstream() {
        // a（1）→ b（2）→ c（3），而 b 又是 c 的第二个上游：c 必须等 b，因此是第 3 层
        Map<String, Integer> layers = WorkflowLayers.of(
                steps(step("a"), step("b", "a"), step("c", "a", "b")));

        assertEquals(Integer.valueOf(1), layers.get("a"));
        assertEquals(Integer.valueOf(2), layers.get("b"));
        assertEquals(Integer.valueOf(3), layers.get("c"));
    }

    @Test
    @DisplayName("真有环时也停下来，并把层号夹在上限内——面板不该因为一份怪 spec 整块消失")
    void of_shouldStopOnCycle() {
        Map<String, Integer> layers = WorkflowLayers.of(steps(step("a", "b"), step("b", "a")));

        assertTrue(layers.get("a").intValue() <= 2);
        assertTrue(layers.get("b").intValue() <= 2);
    }

    /**
     * 造一组步骤。
     *
     * @param steps 步骤
     * @return 列表
     */
    private static List<WorkflowStep> steps(WorkflowStep... steps) {
        List<WorkflowStep> list = new ArrayList<WorkflowStep>();
        list.addAll(Arrays.asList(steps));
        return list;
    }

    /**
     * 造一个步骤。
     *
     * @param id    标识
     * @param needs 上游
     * @return 步骤
     */
    private static WorkflowStep step(String id, String... needs) {
        return new WorkflowStep(id, null, "scout", id,
                needs.length == 0 ? null : Arrays.asList(needs), StepCondition.ALWAYS);
    }
}
