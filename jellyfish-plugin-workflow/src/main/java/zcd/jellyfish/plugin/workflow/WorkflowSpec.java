package zcd.jellyfish.plugin.workflow;

import java.util.Collections;
import java.util.List;

/**
 * 一份校验过的声明式 spec：一组带依赖的步骤 + 一条聚合声明。
 * <p>
 * <b>能构造出它的只有 {@link WorkflowSpecParser}</b>：本类不做任何校验，它的存在前提是
 * 「已经校验过」。因此解析与执行之间不存在「一半合法的 spec」这种状态。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class WorkflowSpec {

    /** 名称，仅用于日志与结果首行，可为 {@code null}。 */
    private final String name;

    /** 步骤，保证非 {@code null} 且保持声明顺序。 */
    private final List<WorkflowStep> steps;

    /** 聚合方式。 */
    private final AggregateMode aggregateMode;

    /** 汇总用的子代理类型；非 {@link AggregateMode#SUMMARIZE} 时为 {@code null}。 */
    private final String aggregateAgent;

    /**
     * 构造 spec。
     *
     * @param name           名称，可为 {@code null}
     * @param steps          步骤，不可为 {@code null} 或空
     * @param aggregateMode  聚合方式，不可为 {@code null}
     * @param aggregateAgent 汇总用的子代理类型，可为 {@code null}
     */
    WorkflowSpec(String name, List<WorkflowStep> steps, AggregateMode aggregateMode, String aggregateAgent) {
        this.name = name;
        this.steps = Collections.unmodifiableList(steps);
        this.aggregateMode = aggregateMode;
        this.aggregateAgent = aggregateAgent;
    }

    /**
     * 获取名称。
     *
     * @return 名称，可能为 {@code null}
     */
    String getName() {
        return name;
    }

    /**
     * 获取步骤。
     *
     * @return 不可变列表（声明顺序），保证非 {@code null}
     */
    List<WorkflowStep> getSteps() {
        return steps;
    }

    /**
     * 获取聚合方式。
     *
     * @return 聚合方式，保证非 {@code null}
     */
    AggregateMode getAggregateMode() {
        return aggregateMode;
    }

    /**
     * 获取汇总用的子代理类型。
     *
     * @return agent 标识；非 {@code summarize} 时为 {@code null}
     */
    String getAggregateAgent() {
        return aggregateAgent;
    }

    /**
     * 取用于展示的名称：没写名字时给一个稳定的占位。
     *
     * @return 展示名，保证非空白
     */
    String displayName() {
        return name == null ? "未命名" : name;
    }

    @Override
    public String toString() {
        return "WorkflowSpec{name=" + name + ", steps=" + steps.size()
                + ", aggregate=" + aggregateMode + '}';
    }
}
