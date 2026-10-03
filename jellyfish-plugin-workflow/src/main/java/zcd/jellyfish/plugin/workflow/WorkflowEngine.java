package zcd.jellyfish.plugin.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.subagent.SubAgentPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排引擎：按 spec 的依赖层序派生一批子代理，同一层并发执行，最后按声明聚合。
 * <p>
 * <b>并发靠「先全部派生、再逐个等待」，而不是自己起线程池</b>：{@link SubAgentPort#spawn(DelegationRequest)}
 * 立即返回，因此同一层的 N 个 run 已经在内核的 {@code agent-run} 池上并行跑；本引擎只是在调用方线程上
 * 逐个 {@code await}。并发度由内核的 governor 决定（超出的在核内排队），插件<b>不该有第二套并发控制</b>
 * ——两套上限互相不知道对方，就会出现「插件以为在并发、实际全在排队」这种查不出的现象。
 * <p>
 * <b>失败不中断整条编排</b>：一个步骤失败只影响它自己的下游（由各步的 {@code when} 决定），
 * 与它无依赖关系的步骤照常执行。这与「声明式 spec 的价值在于一次说清全貌」是一致的：
 * 让整批在第一步失败时全停，模型就得重新推演一遍剩下的部分。
 * <p>
 * <b>取消是可传播的</b>：每次派生都把父回合的取消令牌带下去，因此用户按 Esc 时所有在途 run 会被内核
 * 取消；本引擎在检测到取消后不再派发新步骤，并把没跑的步骤如实记为「已取消」。
 * <p>
 * 无状态（除持有的端口外），可安全跨线程调用：每次 {@link #run} 的状态都局限在该次调用的局部变量里。
 *
 * @author zcd
 */
final class WorkflowEngine {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 汇总时交给子代理的正文上限（字符）。 */
    private static final int MAX_DIGEST_CHARS = 20_000;

    /** 子代理委派端口，来自内核。 */
    private final SubAgentPort port;

    /**
     * 构造引擎。
     *
     * @param port 子代理委派端口，不可为 {@code null}
     */
    WorkflowEngine(SubAgentPort port) {
        this.port = port;
    }

    /**
     * 执行一次编排。
     *
     * @param spec            校验过的 spec，不可为 {@code null}
     * @param parentSessionId 父会话标识，不可为空白
     * @param token           父回合的取消令牌，不可为 {@code null}
     * @param sink            工具输出旁路（用于在「运行中的工具」区域写进度），可为 {@code null}
     * @return 全部结局，保证非 {@code null}
     * @throws JellyfishException 子代理类型未知等准入问题由内核以结果形式回报，不会抛到这里
     */
    WorkflowRun run(WorkflowSpec spec, String parentSessionId, CancellationToken token, ToolOutputSink sink) {
        Map<String, WorkflowStep> byId = new LinkedHashMap<String, WorkflowStep>();
        for (WorkflowStep step : spec.getSteps()) {
            byId.put(step.getId(), step);
        }
        Map<String, StepOutcome> decided = new LinkedHashMap<String, StepOutcome>();
        Set<String> settled = new LinkedHashSet<String>();

        while (settled.size() < spec.getSteps().size()) {
            List<WorkflowStep> layer = readyLayer(spec, settled);
            if (layer.isEmpty()) {
                // 解析期已拒环，这里只做防御：宁可少跑几步，也不要在这里转不出来
                LOG.warn("workflow 无法继续推进，剩余步骤按未执行处理: spec={}", spec.displayName());
                break;
            }
            Map<String, DelegationHandle> running = new LinkedHashMap<String, DelegationHandle>();
            for (WorkflowStep step : layer) {
                settled.add(step.getId());
                if (token.isCancelled()) {
                    StepOutcome stopped = StepOutcome.cancelled(step);
                    decided.put(step.getId(), stopped);
                    report(sink, stopped);
                    continue;
                }
                String blocked = blockReason(step, decided);
                if (blocked != null) {
                    StepOutcome skipped = StepOutcome.notRun(step, blocked);
                    decided.put(step.getId(), skipped);
                    report(sink, skipped);
                    continue;
                }
                progress(sink, "  · " + step.getId() + "（" + step.getAgent() + "）开始\n");
                running.put(step.getId(), port.spawn(new DelegationRequest(parentSessionId, step.getAgent(),
                        step.getPrompt(), token)));
            }
            for (Map.Entry<String, DelegationHandle> entry : running.entrySet()) {
                StepOutcome outcome = StepOutcome.run(byId.get(entry.getKey()), entry.getValue().await());
                decided.put(entry.getKey(), outcome);
                report(sink, outcome);
            }
        }

        List<StepOutcome> ordered = new ArrayList<StepOutcome>(spec.getSteps().size());
        for (WorkflowStep step : spec.getSteps()) {
            StepOutcome outcome = decided.get(step.getId());
            ordered.add(outcome == null ? StepOutcome.notRun(step, "依赖无法满足，未执行") : outcome);
        }
        String aggregate = collect(ordered);
        DelegationResult synthesis = null;
        if (spec.getAggregateMode() == AggregateMode.SUMMARIZE) {
            synthesis = summarize(spec, parentSessionId, token, sink, aggregate);
            if (synthesis != null && synthesis.hasText()) {
                aggregate = synthesis.getText().trim();
            }
        }
        return new WorkflowRun(spec, ordered, synthesis, aggregate);
    }

    /**
     * 挑出下一层可以执行的步骤：依赖都已决，且自己还没决。
     *
     * @param spec    生效的 spec
     * @param settled 已决的步骤标识
     * @return 该层的步骤（声明顺序），可能为空
     */
    private static List<WorkflowStep> readyLayer(WorkflowSpec spec, Set<String> settled) {
        List<WorkflowStep> layer = new ArrayList<WorkflowStep>();
        for (WorkflowStep step : spec.getSteps()) {
            if (!settled.contains(step.getId()) && settled.containsAll(step.getNeeds())) {
                layer.add(step);
            }
        }
        return layer;
    }

    /**
     * 判断一个步骤是否该被静态条件拦下。
     *
     * @param step    步骤
     * @param decided 已决步骤的结局
     * @return 不必执行时返回原因；应当执行时返回 {@code null}
     */
    private static String blockReason(WorkflowStep step, Map<String, StepOutcome> decided) {
        switch (step.getWhen()) {
            case ON_SUCCESS:
                // 无依赖时真空为真：它与 always 等价，模型写出来也没有坏处，因此不特别说明
                for (String need : step.getNeeds()) {
                    if (!decided.get(need).succeeded()) {
                        return "前置步骤未成功：" + need;
                    }
                }
                return null;
            case ON_FAILURE:
                for (String need : step.getNeeds()) {
                    if (decided.get(need).failed()) {
                        return null;
                    }
                }
                return "前置步骤没有失败";
            default:
                return null;
        }
    }

    /**
     * 把各步正文按声明顺序收集成一段文本。
     *
     * @param steps 各步结局（声明顺序）
     * @return 聚合文本，保证非 {@code null}（可能为空）
     */
    private static String collect(List<StepOutcome> steps) {
        StringBuilder text = new StringBuilder();
        for (StepOutcome outcome : steps) {
            if (!outcome.hasRun() || !outcome.getResult().hasText()) {
                continue;
            }
            text.append("## ").append(outcome.getStep().getId())
                    .append("（").append(outcome.getStep().getAgent()).append("）\n")
                    .append(outcome.getResult().getText().trim()).append("\n\n");
        }
        return text.toString().trim();
    }

    /**
     * 再派一个子代理，把各步正文汇成一段结论。
     * <p>
     * <b>没有任何正文时不做汇总</b>：全部步骤都失败或未执行时，汇总子代理只能拿到一段空材料，
     * 它要么编、要么反问，两者都比「如实汇报没有结果」更糟。
     *
     * @param spec            生效的 spec
     * @param parentSessionId 父会话标识
     * @param token           取消令牌
     * @param sink            工具输出旁路，可为 {@code null}
     * @param digest          各步正文
     * @return 汇总结果；没做汇总时为 {@code null}
     */
    private DelegationResult summarize(WorkflowSpec spec, String parentSessionId, CancellationToken token,
                                       ToolOutputSink sink, String digest) {
        if (digest.trim().isEmpty()) {
            return null;
        }
        String material = digest.length() > MAX_DIGEST_CHARS
                ? digest.substring(0, MAX_DIGEST_CHARS) + "\n\n（材料过长，以上已截断）"
                : digest;
        String prompt = "下面是同一次任务里几个子代理各自给出的结论。把它们汇总成一段结论："
                + "优先保留彼此一致的事实，明确指出相互冲突的地方，不要逐条复述，也不要编造材料里没有的信息。\n\n"
                + material;
        progress(sink, "  · 汇总（" + spec.getAggregateAgent() + "）开始\n");
        DelegationResult result = port.spawn(new DelegationRequest(parentSessionId, spec.getAggregateAgent(),
                prompt, token)).await();
        progress(sink, "  · 汇总 " + statusText(result.getStatus()) + "\n");
        return result;
    }

    /**
     * 在「运行中的工具」区域写一行进度。
     *
     * @param sink 工具输出旁路，可为 {@code null}
     * @param line 一行文本
     */
    private static void progress(ToolOutputSink sink, String line) {
        if (sink != null) {
            sink.write(line);
        }
    }

    /**
     * 写一个步骤的结局。
     *
     * @param sink    工具输出旁路，可为 {@code null}
     * @param outcome 结局
     */
    private static void report(ToolOutputSink sink, StepOutcome outcome) {
        if (!outcome.hasRun()) {
            progress(sink, "  · " + outcome.getStep().getId() + " 未执行：" + outcome.getNotRunReason() + "\n");
            return;
        }
        progress(sink, "  · " + outcome.getStep().getId() + " "
                + statusText(outcome.getResult().getStatus()) + "\n");
    }

    /**
     * 把委派终态翻成一行中文。
     *
     * @param status 终态
     * @return 可读文本
     */
    static String statusText(DelegationStatus status) {
        switch (status) {
            case COMPLETED:
                return "完成";
            case TRUNCATED:
                return "达到轮数上限";
            case CANCELLED:
                return "已取消";
            case REJECTED:
                return "未开始";
            default:
                return "失败";
        }
    }
}
