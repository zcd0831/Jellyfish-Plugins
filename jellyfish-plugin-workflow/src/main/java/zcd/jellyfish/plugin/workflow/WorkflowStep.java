package zcd.jellyfish.plugin.workflow;

import java.util.Collections;
import java.util.List;

/**
 * spec 里的一个步骤：让某个子代理去做一件事，并声明它的依赖与执行条件。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class WorkflowStep {

    /** 步骤标识，spec 内唯一，用于被别的步骤依赖。 */
    private final String id;

    /**
     * 人类可读的名字；{@code null} 表示没写。
     * <p>
     * <b>与 {@link #id} 的分工</b>：{@code id} 是机器用的键（被 {@code needs} 引用），模型不必迁就
     * 可读性，起成 {@code probe-a} 就够；而面板是给人看的，{@code step-1} / {@code probe-a} 这种
     * 标识读起来没有意义。因此允许另给一个只用于展示的名字，缺省时展示退回标识。
     * <p>
     * <b>它不进任何机器可读的位置</b>：下游任务里的材料标头、失败清单、{@code needs} 引用一律用
     * {@code id}——那些地方一旦跟着展示名走，「谁依赖谁」就失去了稳定依据。
     */
    private final String name;

    /** 子代理类型。 */
    private final String agent;

    /** 任务原文。 */
    private final String prompt;

    /** 依赖的步骤标识，保证非 {@code null}（无依赖时为空列表）。 */
    private final List<String> needs;

    /** 执行条件。 */
    private final StepCondition when;

    /**
     * 构造步骤。
     *
     * @param id     步骤标识，不可为空白
     * @param name   人类可读的名字，可为 {@code null}
     * @param agent  子代理类型，不可为空白
     * @param prompt 任务原文，不可为空白
     * @param needs  依赖的步骤标识，可为 {@code null}
     * @param when   执行条件，不可为 {@code null}
     */
    WorkflowStep(String id, String name, String agent, String prompt, List<String> needs,
                 StepCondition when) {
        this.id = id;
        this.name = name;
        this.agent = agent;
        this.prompt = prompt;
        this.needs = needs == null || needs.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(needs);
        this.when = when;
    }

    /**
     * 获取步骤标识。
     *
     * @return 步骤标识
     */
    String getId() {
        return id;
    }

    /**
     * 获取人类可读的名字。
     *
     * @return 名字；没写时为 {@code null}
     */
    String getName() {
        return name;
    }

    /**
     * 获取子代理类型。
     *
     * @return 子代理类型
     */
    String getAgent() {
        return agent;
    }

    /**
     * 获取任务原文。
     *
     * @return 任务原文
     */
    String getPrompt() {
        return prompt;
    }

    /**
     * 获取依赖的步骤标识。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<String> getNeeds() {
        return needs;
    }

    /**
     * 获取执行条件。
     *
     * @return 执行条件，保证非 {@code null}
     */
    StepCondition getWhen() {
        return when;
    }

    @Override
    public String toString() {
        return "WorkflowStep{" + id + (name == null ? "" : "/" + name) + " -> " + agent
                + ", needs=" + needs + ", when=" + when + '}';
    }
}
