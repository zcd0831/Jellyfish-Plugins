package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.subagent.DelegationResult;

import java.util.Collections;
import java.util.List;

/**
 * 一次 workflow 的全部结局：每个步骤各自的结果 + 聚合文本。
 * <p>
 * <b>聚合文本可能不是「收集」的结果</b>：声明了 {@code summarize} 时它是汇总子代理给出的那段结论，
 * 此时各步原文仍在本对象里（步骤列表），因此汇报时可以两者都给。
 * <p>
 * <b>轮数与 token 含汇总那一次</b>：它是这次编排真实花掉的钱，漏掉它会让「这次一共花了多少」
 * 少算最后也是最贵的一步。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class WorkflowRun {

    /** 生效的 spec。 */
    private final WorkflowSpec spec;

    /** 各步骤的结局（声明顺序），保证非 {@code null}。 */
    private final List<StepOutcome> steps;

    /** 汇总子代理的结果；没做汇总时为 {@code null}。 */
    private final DelegationResult synthesis;

    /** 聚合文本，保证非 {@code null}（可能为空）。 */
    private final String aggregate;

    /**
     * 构造结局。
     *
     * @param spec      生效的 spec，不可为 {@code null}
     * @param steps     各步骤的结局（声明顺序），不可为 {@code null}
     * @param synthesis 汇总子代理的结果，可为 {@code null}
     * @param aggregate 聚合文本，可为 {@code null}（按空串处理）
     */
    WorkflowRun(WorkflowSpec spec, List<StepOutcome> steps, DelegationResult synthesis, String aggregate) {
        this.spec = spec;
        this.steps = Collections.unmodifiableList(steps);
        this.synthesis = synthesis;
        this.aggregate = aggregate == null ? "" : aggregate;
    }

    /**
     * 获取生效的 spec。
     *
     * @return spec，保证非 {@code null}
     */
    WorkflowSpec getSpec() {
        return spec;
    }

    /**
     * 获取各步骤的结局。
     *
     * @return 不可变列表（声明顺序），保证非 {@code null}
     */
    List<StepOutcome> getSteps() {
        return steps;
    }

    /**
     * 获取汇总子代理的结果。
     *
     * @return 结果；没做汇总时为 {@code null}
     */
    DelegationResult getSynthesis() {
        return synthesis;
    }

    /**
     * 获取聚合文本。
     *
     * @return 聚合文本，保证非 {@code null}
     */
    String getAggregate() {
        return aggregate;
    }

    /**
     * 判断是否有步骤失败（或汇总失败）。
     *
     * @return 有失败返回 {@code true}
     */
    boolean hasFailure() {
        for (StepOutcome outcome : steps) {
            if (outcome.failed()) {
                return true;
            }
        }
        return synthesis != null && !WorkflowSpecParser.isSuccess(synthesis);
    }

    /**
     * 判断是否有步骤（或汇总）被取消。
     *
     * @return 被取消返回 {@code true}
     */
    boolean hasCancellation() {
        for (StepOutcome outcome : steps) {
            if (outcome.cancelled()) {
                return true;
            }
        }
        return synthesis != null
                && synthesis.getStatus() == zcd.jellyfish.api.subagent.DelegationStatus.CANCELLED;
    }

    /**
     * 累计轮数（含汇总）。
     *
     * @return 轮数，保证非负
     */
    int totalRounds() {
        int rounds = 0;
        for (StepOutcome outcome : steps) {
            if (outcome.hasRun()) {
                rounds += outcome.getResult().getRounds();
            }
        }
        if (synthesis != null) {
            rounds += synthesis.getRounds();
        }
        return rounds;
    }

    /**
     * 累计 token（含汇总）。
     *
     * @return token 用量，保证非负
     */
    long totalTokens() {
        long tokens = 0L;
        for (StepOutcome outcome : steps) {
            if (outcome.hasRun()) {
                tokens += outcome.getResult().getTotalTokens();
            }
        }
        if (synthesis != null) {
            tokens += synthesis.getTotalTokens();
        }
        return tokens;
    }

    /**
     * 取各步骤结局里第一个失败的那一个。
     *
     * @return 失败结局；没有失败时返回 {@code null}
     */
    StepOutcome firstFailure() {
        for (StepOutcome outcome : steps) {
            if (outcome.failed()) {
                return outcome;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "WorkflowRun{spec=" + spec.displayName() + ", steps=" + steps.size()
                + ", rounds=" + totalRounds() + ", tokens=" + totalTokens() + '}';
    }
}
