package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowSpecParser} 的单元测试：每一条拒绝路径都要有。
 * <p>
 * <b>为什么错误消息也要断言</b>：这些消息是给<b>模型</b>看的唯一的反馈通道。它看不到这份实现，
 * 只说「参数不对」它改不动，会原样重试；因此每条断言都检查「消息里有没有带上实际收到的值」。
 *
 * @author zcd
 */
@DisplayName("spec 解析与校验")
class WorkflowSpecParserTest {

    @Test
    @DisplayName("合法 spec 应解析出步骤、依赖、条件与聚合声明")
    void parse_shouldAcceptValidSpec() {
        Map<String, Object> spec = validSpec();

        WorkflowSpec parsed = WorkflowSpecParser.parse(spec);

        assertEquals("调研并写方案", parsed.getName());
        assertEquals(3, parsed.getSteps().size());
        WorkflowStep plan = parsed.getSteps().get(2);
        assertEquals("plan", plan.getId());
        assertEquals(Arrays.asList("probe-a", "probe-b"), plan.getNeeds());
        assertEquals(StepCondition.ON_SUCCESS, plan.getWhen());
        assertEquals(AggregateMode.SUMMARIZE, parsed.getAggregateMode());
        assertEquals("planner", parsed.getAggregateAgent());
    }

    @Test
    @DisplayName("step 的 name 可省；写了就解析出来（只用于展示）")
    void parse_shouldAcceptOptionalStepName() {
        Map<String, Object> named = step("probe-a", "scout", "调研 A");
        named.put("name", "调研 A 方向");
        Map<String, Object> unnamed = step("probe-b", "scout", "调研 B");

        WorkflowSpec parsed = WorkflowSpecParser.parse(specOf(named, unnamed));

        assertEquals("调研 A 方向", parsed.getSteps().get(0).getName());
        assertNull(parsed.getSteps().get(1).getName());
    }

    @Test
    @DisplayName("name 长了也不拒：它只影响展示，为它设限只会让能跑的 spec 被拒")
    void parse_shouldAcceptLongStepName() {
        Map<String, Object> longNamed = step("a", "scout", "做一件事");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            name.append('长');
        }
        longNamed.put("name", name.toString());

        WorkflowSpec parsed = WorkflowSpecParser.parse(singleStep(longNamed));

        assertEquals(name.toString(), parsed.getSteps().get(0).getName());
    }

    @Test
    @DisplayName("name 类型不对时当场拒绝，并回显实际收到的值")
    void parse_shouldRejectNonStringStepName() {
        Map<String, Object> bad = step("a", "scout", "做一件事");
        bad.put("name", Integer.valueOf(7));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(singleStep(bad)));

        assertTrue(error.getMessage().contains(".name"), error.getMessage());
        assertTrue(error.getMessage().contains("7"), error.getMessage());
    }

    @Test
    @DisplayName("可选字段缺省：无条件、按顺序收集")
    void parse_shouldDefaultConditionAndAggregate() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("spec", argumentsOf(stepsOf(step("only", "scout", "做一件事"))));

        WorkflowSpec parsed = WorkflowSpecParser.parse(spec);

        assertEquals(StepCondition.ALWAYS, parsed.getSteps().get(0).getWhen());
        assertTrue(parsed.getSteps().get(0).getNeeds().isEmpty());
        assertEquals(AggregateMode.COLLECT, parsed.getAggregateMode());
        assertNull(parsed.getAggregateAgent());
        assertNull(parsed.getName());
    }

    @Test
    @DisplayName("没有 spec 对象时拒绝，并回显收到的类型")
    void parse_shouldRejectMissingSpec() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(new LinkedHashMap<String, Object>()));

        assertTrue(error.getMessage().contains("spec"), error.getMessage());
        assertTrue(error.getMessage().contains("null"), error.getMessage());
    }

    @Test
    @DisplayName("spec 传成字符串时拒绝，并回显实际值")
    void parse_shouldRejectNonObjectSpec() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", "{\"steps\":[]}");

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(arguments));

        assertTrue(error.getMessage().contains("必须是对象"), error.getMessage());
        assertTrue(error.getMessage().contains("steps"), error.getMessage());
    }

    @Test
    @DisplayName("步骤为空或缺失时拒绝")
    void parse_shouldRejectEmptySteps() {
        Map<String, Object> missing = new LinkedHashMap<String, Object>();
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        missing.put("spec", spec);

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(missing))
                .getMessage().contains("非空数组"));

        spec.put("steps", new ArrayList<Object>());
        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(missing))
                .getMessage().contains("非空数组"));
    }

    @Test
    @DisplayName("超过步数上限时拒绝，并说明收到多少步")
    void parse_shouldRejectTooManySteps() {
        List<Object> steps = new ArrayList<Object>();
        for (int i = 0; i <= WorkflowSpecParser.MAX_STEPS; i++) {
            steps.add(step("s" + i, "scout", "任务"));
        }
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("spec", argumentsOf(steps));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(spec));

        assertTrue(error.getMessage().contains(String.valueOf(WorkflowSpecParser.MAX_STEPS)),
                error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(WorkflowSpecParser.MAX_STEPS + 1)),
                error.getMessage());
    }

    @Test
    @DisplayName("步骤 id 重复时拒绝并指出是哪一个")
    void parse_shouldRejectDuplicateId() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("spec", argumentsOf(stepsOf(step("dup", "scout", "a"), step("dup", "scout", "b"))));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(spec));

        assertTrue(error.getMessage().contains("重复：dup"), error.getMessage());
    }

    @Test
    @DisplayName("必填字段缺失时拒绝并给出字段路径")
    void parse_shouldRejectMissingRequiredFields() {
        Map<String, Object> noAgent = new LinkedHashMap<String, Object>();
        noAgent.put("id", "a");
        noAgent.put("prompt", "任务");
        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(
                singleStep(noAgent))).getMessage().contains("steps[0].agent"));

        Map<String, Object> blankPrompt = new LinkedHashMap<String, Object>();
        blankPrompt.put("id", "a");
        blankPrompt.put("agent", "scout");
        blankPrompt.put("prompt", "   ");
        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(
                singleStep(blankPrompt))).getMessage().contains("steps[0].prompt"));
    }

    @Test
    @DisplayName("依赖了不存在的步骤时拒绝，并列出可用步骤")
    void parse_shouldRejectUnknownDependency() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> step = step("plan", "planner", "写方案");
        step.put("needs", Arrays.asList("ghost"));
        spec.put("spec", argumentsOf(stepsOf(step)));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(spec));

        assertTrue(error.getMessage().contains("不存在的步骤：ghost"), error.getMessage());
        assertTrue(error.getMessage().contains("plan"), error.getMessage());
    }

    @Test
    @DisplayName("依赖自己时拒绝")
    void parse_shouldRejectSelfDependency() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> step = step("a", "scout", "任务");
        step.put("needs", Arrays.asList("a"));
        spec.put("spec", argumentsOf(stepsOf(step)));

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(spec))
                .getMessage().contains("不能依赖自己"));
    }

    @Test
    @DisplayName("依赖成环时拒绝，并点明环里有谁")
    void parse_shouldRejectCycle() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> first = step("a", "scout", "任务");
        first.put("needs", Arrays.asList("b"));
        Map<String, Object> second = step("b", "scout", "任务");
        second.put("needs", Arrays.asList("a"));
        spec.put("spec", argumentsOf(stepsOf(first, second)));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(spec));

        assertTrue(error.getMessage().contains("成环"), error.getMessage());
        // 环里的每一步都在等别人，谁也跑不了——消息必须让模型看懂这一点
        assertTrue(error.getMessage().contains("a"), error.getMessage());
        assertTrue(error.getMessage().contains("b"), error.getMessage());
    }

    @Test
    @DisplayName("when 取值非法时拒绝并回显实际值")
    void parse_shouldRejectUnknownCondition() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> step = step("a", "scout", "任务");
        step.put("when", "if_failed");
        spec.put("spec", argumentsOf(stepsOf(step)));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> WorkflowSpecParser.parse(spec));

        assertTrue(error.getMessage().contains("if_failed"), error.getMessage());
        assertTrue(error.getMessage().contains("on_success"), error.getMessage());
    }

    @Test
    @DisplayName("on_failure 没有 needs 时拒绝：真空为假会让「什么都没发生」无从排查")
    void parse_shouldRejectOnFailureWithoutNeeds() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> step = step("a", "scout", "任务");
        step.put("when", "on_failure");
        spec.put("spec", argumentsOf(stepsOf(step)));

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(spec))
                .getMessage().contains("必须声明 needs"));
    }

    @Test
    @DisplayName("summarize 缺少汇总 agent 时拒绝")
    void parse_shouldRejectSummarizeWithoutAgent() {
        Map<String, Object> spec = validSpec();
        Map<String, Object> definition = (Map<String, Object>) spec.get("spec");
        Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
        aggregate.put("mode", "summarize");
        definition.put("aggregate", aggregate);

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(spec))
                .getMessage().contains("aggregate.agent"));
    }

    @Test
    @DisplayName("aggregate.mode 取值非法时拒绝并回显实际值")
    void parse_shouldRejectUnknownAggregateMode() {
        Map<String, Object> spec = validSpec();
        Map<String, Object> definition = (Map<String, Object>) spec.get("spec");
        Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
        aggregate.put("mode", "merge");
        definition.put("aggregate", aggregate);

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(spec))
                .getMessage().contains("merge"));
    }

    @Test
    @DisplayName("needs 不是数组时拒绝并说明收到的是对象")
    void parse_shouldRejectNonArrayNeeds() {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        Map<String, Object> step = step("a", "scout", "任务");
        step.put("needs", "b");
        spec.put("spec", argumentsOf(stepsOf(step)));

        assertTrue(assertThrows(JellyfishException.class, () -> WorkflowSpecParser.parse(spec))
                .getMessage().contains("必须是数组"));
    }

    /**
     * 构造一份合法的 spec 参数（三步：两个并行调研 → 一个依赖它们的方案）。
     *
     * @return 工具参数
     */
    private static Map<String, Object> validSpec() {
        Map<String, Object> probeA = step("probe-a", "scout", "调研 A");
        Map<String, Object> probeB = step("probe-b", "scout", "调研 B");
        Map<String, Object> plan = step("plan", "planner", "写方案");
        plan.put("needs", Arrays.asList("probe-a", "probe-b"));
        plan.put("when", "on_success");

        Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
        aggregate.put("mode", "summarize");
        aggregate.put("agent", "planner");

        Map<String, Object> definition = argumentsOf(stepsOf(probeA, probeB, plan));
        definition.put("name", "调研并写方案");
        definition.put("aggregate", aggregate);

        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", definition);
        return arguments;
    }

    /**
     * 构造只含一步的工具参数。
     *
     * @param step 步骤
     * @return 工具参数
     */
    private static Map<String, Object> singleStep(Map<String, Object> step) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", argumentsOf(stepsOf(step)));
        return arguments;
    }

    /**
     * 构造 spec 对象。
     *
     * @param steps 步骤列表
     * @return spec 对象
     */
    private static Map<String, Object> argumentsOf(List<Object> steps) {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("steps", steps);
        return definition;
    }

    /**
     * 包装步骤列表。
     *
     * @param steps 步骤
     * @return 列表
     */
    private static List<Object> stepsOf(Map<String, Object>... steps) {
        return new ArrayList<Object>(Arrays.asList(steps));
    }

    /**
     * 构造含多个步骤的工具参数（{@link #singleStep(Map)} 的多步版本）。
     *
     * @param steps 步骤
     * @return 工具参数
     */
    private static Map<String, Object> specOf(Map<String, Object>... steps) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", argumentsOf(stepsOf(steps)));
        return arguments;
    }

    /**
     * 构造一个步骤对象。
     *
     * @param id     步骤标识
     * @param agent  子代理类型
     * @param prompt 任务原文
     * @return 步骤对象
     */
    private static Map<String, Object> step(String id, String agent, String prompt) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("id", id);
        step.put("agent", agent);
        step.put("prompt", prompt);
        return step;
    }
}
