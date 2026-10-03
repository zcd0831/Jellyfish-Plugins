package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.subagent.DelegationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * spec 的解析与校验：把工具参数里那份不可信结构变成 {@link WorkflowSpec}。
 * <p>
 * <b>校验全部发生在派生任何 run 之前</b>：一份写错的 spec 不该在烧掉几个子代理之后才被拒绝。
 * 因此这里做完所有的结构性检查（类型、必填、唯一、引用存在、无环、能力上限），
 * 执行期才不需要再为「参数不对」写分支。
 * <p>
 * <b>越界即整份拒绝，不做部分执行</b>：声明式 spec 的价值是「模型一次说清要做什么」，
 * 部分执行会让它无从判断「哪些做完了」——而那正是它下一步决策所需要的。
 * <p>
 * 无状态，可安全跨线程调用。
 *
 * @author zcd
 */
final class WorkflowSpecParser {

    /** 工具参数里承载 spec 的键。 */
    static final String ARG_SPEC = "spec";

    /**
     * 步数上限。
     * <p>
     * 它同时是「一次 workflow 最多烧掉多少个子代理」的上限：内核的 governor 管的是全局并发，
     * 而这里管的是单次编排的规模。取 12 是权衡——足够表达「调研 → 三路并行 → 复核 → 汇总」这类
     * 常见形状，又不至于让一次调用在没人盯着的情况下跑上很久。
     */
    static final int MAX_STEPS = 12;

    /**
     * 工具类，禁止实例化。
     */
    private WorkflowSpecParser() {
    }

    /**
     * 解析并校验一份 spec。
     *
     * @param arguments 工具参数，可为 {@code null}
     * @return 校验过的 spec，保证非 {@code null}
     * @throws JellyfishException 任何一项校验不通过时抛出（消息面向模型，带实际收到的值）
     */
    static WorkflowSpec parse(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get(ARG_SPEC);
        Map<?, ?> spec = SpecValues.optionalMap(raw, ARG_SPEC);
        if (spec == null) {
            throw new JellyfishException("workflow 需要一个 " + ARG_SPEC + " 对象（收到："
                    + SpecValues.describe(raw) + "）");
        }
        String name = SpecValues.optionalText(spec.get("name"), ARG_SPEC + ".name");
        List<WorkflowStep> steps = parseSteps(spec.get("steps"));

        AggregateMode mode = AggregateMode.COLLECT;
        String aggregateAgent = null;
        Map<?, ?> aggregate = SpecValues.optionalMap(spec.get("aggregate"), ARG_SPEC + ".aggregate");
        if (aggregate != null) {
            mode = AggregateMode.fromWire(aggregate.get("mode"), ARG_SPEC + ".aggregate.mode");
            aggregateAgent = SpecValues.optionalText(aggregate.get("agent"), ARG_SPEC + ".aggregate.agent");
            if (mode == AggregateMode.SUMMARIZE && aggregateAgent == null) {
                throw new JellyfishException(ARG_SPEC + ".aggregate.mode 为 summarize 时必须同时给出 "
                        + ARG_SPEC + ".aggregate.agent（用哪个子代理来汇总）");
            }
        }
        return new WorkflowSpec(name, steps, mode, aggregateAgent);
    }

    /**
     * 解析并校验步骤列表。
     *
     * @param rawSteps 原始步骤值，可为 {@code null}
     * @return 步骤列表（声明顺序），保证非 {@code null} 且非空
     * @throws JellyfishException 校验不通过时抛出
     */
    private static List<WorkflowStep> parseSteps(Object rawSteps) {
        if (!(rawSteps instanceof List) || ((List<?>) rawSteps).isEmpty()) {
            throw new JellyfishException(ARG_SPEC + ".steps 必须是非空数组（收到："
                    + SpecValues.describe(rawSteps) + "）");
        }
        List<?> list = (List<?>) rawSteps;
        if (list.size() > MAX_STEPS) {
            throw new JellyfishException("步数上限是 " + MAX_STEPS + "，收到 " + list.size()
                    + " 步：拆成几次 workflow，或把同类的几步合成一步。");
        }
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>(list.size());
        Set<String> ids = new LinkedHashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            String where = ARG_SPEC + ".steps[" + i + "]";
            Map<?, ?> step = SpecValues.optionalMap(list.get(i), where);
            if (step == null) {
                throw new JellyfishException(where + " 必须是对象");
            }
            String id = SpecValues.requiredText(step.get("id"), where + ".id");
            if (!ids.add(id)) {
                throw new JellyfishException(where + ".id 与前面某一步重复：" + id + "（步骤 id 必须唯一）");
            }
            String agent = SpecValues.requiredText(step.get("agent"), where + ".agent");
            String prompt = SpecValues.requiredText(step.get("prompt"), where + ".prompt");
            List<String> needs = SpecValues.textList(step.get("needs"), where + ".needs");
            if (needs.contains(id)) {
                throw new JellyfishException(where + ".needs 不能依赖自己：" + id);
            }
            StepCondition when = StepCondition.fromWire(step.get("when"), where + ".when");
            if (when == StepCondition.ON_FAILURE && needs.isEmpty()) {
                // 真空为假的步骤不会执行，模型只会看到「什么都没发生」而查不出原因——当场说清楚
                throw new JellyfishException(where + ".when 为 on_failure 时必须声明 needs："
                        + "没有前置步骤就不会有失败可等");
            }
            steps.add(new WorkflowStep(id, agent, prompt, needs, when));
        }
        for (WorkflowStep step : steps) {
            for (String need : step.getNeeds()) {
                if (!ids.contains(need)) {
                    throw new JellyfishException("步骤 " + step.getId() + " 依赖了不存在的步骤：" + need
                            + "（已声明的步骤：" + ids + "）");
                }
            }
        }
        rejectCycles(steps);
        return steps;
    }

    /**
     * 拒绝依赖成环：环意味着那些步骤永远就绪不了，整份 spec 的死活取决于模型能否看懂这句话。
     *
     * @param steps 步骤列表
     * @throws JellyfishException 存在环时抛出
     */
    private static void rejectCycles(List<WorkflowStep> steps) {
        Map<String, WorkflowStep> byId = new LinkedHashMap<String, WorkflowStep>();
        for (WorkflowStep step : steps) {
            byId.put(step.getId(), step);
        }
        Set<String> visiting = new LinkedHashSet<String>();
        Set<String> done = new LinkedHashSet<String>();
        for (WorkflowStep step : steps) {
            if (!done.contains(step.getId())) {
                visit(step, byId, visiting, done);
            }
        }
    }

    /**
     * 深度优先地找出环。
     *
     * @param step     当前步骤
     * @param byId     步骤索引
     * @param visiting 正在访问的路径
     * @param done     已访问完的步骤
     * @throws JellyfishException 遇到环时抛出
     */
    private static void visit(WorkflowStep step, Map<String, WorkflowStep> byId, Set<String> visiting,
                              Set<String> done) {
        if (!visiting.add(step.getId())) {
            throw new JellyfishException("依赖成环：" + String.join(" → ", visiting) + " → " + step.getId()
                    + "。声明式 spec 不做循环——环里的每一步都在等别人，谁也跑不了。");
        }
        for (String need : step.getNeeds()) {
            WorkflowStep next = byId.get(need);
            if (next != null && !done.contains(next.getId())) {
                visit(next, byId, visiting, done);
            }
        }
        visiting.remove(step.getId());
        done.add(step.getId());
    }

    /**
     * 判断一个委托结果是否算「成功」。
     * <p>
     * <b>达到轮数上限也算成功</b>：那个子代理确实跑完了，只是没收敛，它的正文（哪怕不完整）
     * 对后续步骤仍然有用；把它当成失败会让「结论不完整」连带把整条下游都跳过。
     *
     * @param result 委派结果，不可为 {@code null}
     * @return 成功返回 {@code true}
     */
    static boolean isSuccess(DelegationResult result) {
        switch (result.getStatus()) {
            case COMPLETED:
            case TRUNCATED:
                return true;
            default:
                return false;
        }
    }

}
