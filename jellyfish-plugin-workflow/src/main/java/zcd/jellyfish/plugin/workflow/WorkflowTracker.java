package zcd.jellyfish.plugin.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 正在跑的编排的台账：引擎往里写状态，面板从里读快照。
 * <p>
 * <b>为什么需要它</b>：面板是<b>拉取式</b>的（外壳在有理由相信内容变了时才问一次），
 * 而引擎在一个工具调用里同步跑完——两者的生命周期完全错开。台账是它们之间唯一的交汇点：
 * 引擎不关心有没有人看，面板也不需要知道引擎在哪一步。
 * <p>
 * <b>为什么写完还要通知一声</b>：外壳只在缓存失效时收集面板，而「某一步跑完了」这件事外壳自己看不到
 * （它发生在回合内部）。因此每次状态变化都回调一次 {@code onChange}，由插件把它变成一条
 * {@code UiInvalidatedEvent}——与 {@code jellyfish-plugin-todo} 写完待办后广播失效是同一件事。
 * <b>没有这条通知，面板会在编排结束后才第一次出现，而那时它已经空了。</b>
 * <p>
 * <b>跑完就移除</b>：与内核的子代理面板同一口径——面板展示的是「此刻在跑什么」，
 * 而不是历史。留着条目会让内存随会话时长线性增长，而历史另有归档（内核的 run 归档）。
 * <p>
 * 线程安全：全部状态由本对象自己的锁保护，对外只交出不可变快照。
 *
 * @author zcd
 */
final class WorkflowTracker {

    /** 正在跑的编排：workflowId → 条目。 */
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    /** 状态变化时的回调（用于通知外壳重新拉取面板）。 */
    private final Runnable onChange;

    /**
     * 构造台账。
     *
     * @param onChange 状态变化回调，不可为 {@code null}
     */
    WorkflowTracker(Runnable onChange) {
        this.onChange = onChange;
    }

    /**
     * 登记一次编排。
     * <p>
     * 在派生任何子代理之前调用，因此面板从一开始就能显示出「还有哪些步骤没跑」——
     * 只显示「已跑完的」会让它看起来像在倒退。
     *
     * @param sessionId 所属会话，不可为空白
     * @param spec      生效的 spec，不可为 {@code null}
     * @return 编排标识，用于后续更新与移除
     */
    synchronized String started(String sessionId, WorkflowSpec spec) {
        String workflowId = UUID.randomUUID().toString();
        List<Step> steps = new ArrayList<Step>();
        for (WorkflowStep step : spec.getSteps()) {
            steps.add(new Step(step.getId(), step.getName(), step.getAgent(), StepState.PENDING));
        }
        entries.put(workflowId, new Entry(sessionId, spec.displayName(), steps));
        notifyChanged();
        return workflowId;
    }

    /**
     * 记下某个步骤开始执行。
     *
     * @param workflowId 编排标识
     * @param stepId     步骤标识
     */
    synchronized void stepStarted(String workflowId, String stepId) {
        Step step = step(workflowId, stepId);
        if (step != null) {
            step.state = StepState.RUNNING;
            notifyChanged();
        }
    }

    /**
     * 记下某个步骤的结局。
     *
     * @param workflowId 编排标识
     * @param stepId     步骤标识
     * @param state      结局状态
     */
    synchronized void stepFinished(String workflowId, String stepId, StepState state) {
        Step step = step(workflowId, stepId);
        if (step != null) {
            step.state = state;
            notifyChanged();
        }
    }

    /**
     * 记下「汇总开始了」。
     *
     * @param workflowId 编排标识
     */
    synchronized void summarising(String workflowId) {
        Entry entry = entries.get(workflowId);
        if (entry != null) {
            entry.summarizing = true;
            notifyChanged();
        }
    }

    /**
     * 移除一次编排：它已经跑完，面板不再需要它。
     *
     * @param workflowId 编排标识
     */
    synchronized void finished(String workflowId) {
        if (entries.remove(workflowId) != null) {
            notifyChanged();
        }
    }

    /**
     * 取某会话正在跑的编排快照。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 不可变快照列表，保证非 {@code null}
     */
    synchronized List<WorkflowProgress> snapshot(String sessionId) {
        List<WorkflowProgress> result = new ArrayList<WorkflowProgress>();
        if (sessionId == null) {
            return result;
        }
        for (Entry entry : entries.values()) {
            if (sessionId.equals(entry.sessionId)) {
                result.add(entry.snapshot());
            }
        }
        return result;
    }

    /**
     * 找一个步骤。
     *
     * @param workflowId 编排标识
     * @param stepId     步骤标识
     * @return 步骤；找不到时返回 {@code null}
     */
    private Step step(String workflowId, String stepId) {
        Entry entry = entries.get(workflowId);
        if (entry == null) {
            return null;
        }
        for (Step step : entry.steps) {
            if (step.id.equals(stepId)) {
                return step;
            }
        }
        return null;
    }

    /**
     * 通知一次状态变化。
     * <p>
     * 在锁内调用是刻意的：回调只是发一条失效事件（入队），不会反过来读台账，因此不存在死锁风险；
     * 放到锁外反而会让「面板读到旧状态并缓存下来」的窗口变大。
     */
    private void notifyChanged() {
        onChange.run();
    }

    /** 台账条目：一次编排的活状态。 */
    private static final class Entry {

        /** 所属会话。 */
        private final String sessionId;

        /** 展示名。 */
        private final String name;

        /** 各步骤的活状态。 */
        private final List<Step> steps;

        /** 是否正在做汇总。 */
        private boolean summarizing;

        /**
         * 构造条目。
         *
         * @param sessionId 所属会话
         * @param name      展示名
         * @param steps     各步骤
         */
        private Entry(String sessionId, String name, List<Step> steps) {
            this.sessionId = sessionId;
            this.name = name;
            this.steps = steps;
        }

        /**
         * 生成只读快照。
         *
         * @return 快照，保证非 {@code null}
         */
        private WorkflowProgress snapshot() {
            List<WorkflowProgress.Step> copy = new ArrayList<WorkflowProgress.Step>(steps.size());
            for (Step step : steps) {
                copy.add(new WorkflowProgress.Step(step.id, step.name, step.agent, step.state));
            }
            return new WorkflowProgress(name, copy, summarizing);
        }
    }

    /** 步骤的活状态。 */
    private static final class Step {

        /** 步骤标识。 */
        private final String id;

        /** 人类可读的名字，可为 {@code null}。 */
        private final String name;

        /** 子代理类型。 */
        private final String agent;

        /** 当前状态。 */
        private StepState state;

        /**
         * 构造步骤。
         *
         * @param id    步骤标识
         * @param name  人类可读的名字，可为 {@code null}
         * @param agent 子代理类型
         * @param state 初始状态
         */
        private Step(String id, String name, String agent, StepState state) {
            this.id = id;
            this.name = name;
            this.agent = agent;
            this.state = state;
        }
    }
}
