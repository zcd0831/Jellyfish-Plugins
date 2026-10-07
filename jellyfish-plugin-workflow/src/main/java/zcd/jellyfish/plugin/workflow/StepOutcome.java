package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.subagent.DelegationResult;

/**
 * 一个步骤的结局：跑过（带委派结果）或者没跑（带原因）。
 * <p>
 * <b>「没跑」必须带上原因</b>：静态条件不满足与回合被取消是两件完全不同的事，前者是 spec 说好的，
 * 后者是外部中断。把它们压成同一个「跳过」会让汇报与排障都无从下手。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class StepOutcome {

    /** 「因回合被取消而没跑」的固定原因。 */
    static final String CANCELLED_REASON = "回合已取消";

    /** 对应的步骤。 */
    private final WorkflowStep step;

    /** 委派结果；没跑时为 {@code null}。 */
    private final DelegationResult result;

    /** 没跑的原因；跑过时为 {@code null}。 */
    private final String notRunReason;

    /** 是否因回合被取消而没跑：与「条件不满足」是两件完全不同的事。 */
    private final boolean cancelled;

    /**
     * 构造结局。
     *
     * @param step         步骤，不可为 {@code null}
     * @param result       委派结果，可为 {@code null}
     * @param notRunReason 没跑的原因，可为 {@code null}
     * @param cancelled    是否因回合被取消而没跑
     */
    private StepOutcome(WorkflowStep step, DelegationResult result, String notRunReason, boolean cancelled) {
        this.step = step;
        this.result = result;
        this.notRunReason = notRunReason;
        this.cancelled = cancelled;
    }

    /**
     * 构造「跑过」的结局。
     *
     * @param step   步骤，不可为 {@code null}
     * @param result 委派结果，不可为 {@code null}
     * @return 结局，保证非 {@code null}
     */
    static StepOutcome run(WorkflowStep step, DelegationResult result) {
        return new StepOutcome(step, result, null, false);
    }

    /**
     * 构造「没跑」的结局。
     *
     * @param step   步骤，不可为 {@code null}
     * @param reason 原因，不可为空白
     * @return 结局，保证非 {@code null}
     */
    static StepOutcome notRun(WorkflowStep step, String reason) {
        return new StepOutcome(step, null, reason, false);
    }

    /**
     * 构造「因回合被取消而没跑」的结局。
     *
     * @param step 步骤，不可为 {@code null}
     * @return 结局，保证非 {@code null}
     */
    static StepOutcome cancelled(WorkflowStep step) {
        return new StepOutcome(step, null, CANCELLED_REASON, true);
    }

    /**
     * 获取步骤。
     *
     * @return 步骤，保证非 {@code null}
     */
    WorkflowStep getStep() {
        return step;
    }

    /**
     * 获取委派结果。
     *
     * @return 委派结果；没跑时为 {@code null}
     */
    DelegationResult getResult() {
        return result;
    }

    /**
     * 获取没跑的原因。
     *
     * @return 原因；跑过时为 {@code null}
     */
    String getNotRunReason() {
        return notRunReason;
    }

    /**
     * 判断是否跑过。
     *
     * @return 跑过返回 {@code true}
     */
    boolean hasRun() {
        return result != null;
    }

    /**
     * 判断是否成功。
     *
     * @return 跑过且拿到了正文时返回 {@code true}
     */
    boolean succeeded() {
        return result != null && WorkflowSpecParser.isSuccess(result);
    }

    /**
     * 判断是否失败。
     *
     * @return 跑过但没成功时返回 {@code true}
     */
    boolean failed() {
        return result != null && !WorkflowSpecParser.isSuccess(result);
    }

    /**
     * 判断是否被取消：跑过但被取消，或压根没来得及跑。
     *
     * @return 被取消返回 {@code true}
     */
    boolean cancelled() {
        return cancelled
                || (result != null && result.getStatus() == zcd.jellyfish.api.subagent.DelegationStatus.CANCELLED);
    }

    @Override
    public String toString() {
        return "StepOutcome{" + step.getId() + ", "
                + (result == null ? "notRun=" + notRunReason : "status=" + result.getStatus()) + '}';
    }
}
