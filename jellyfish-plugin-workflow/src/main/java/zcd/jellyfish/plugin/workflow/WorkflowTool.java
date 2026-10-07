package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code workflow} 工具：模型提交一份声明式 spec，插件按它编排一批子代理。
 * <p>
 * <b>与 {@code task} 的分工</b>：{@code task} 只派一个子代理，适合「一件事做完就完」；
 * 本工具适合「几件事可以并行、且后一步要用前几步的结论」。选错不会出错，只是慢——因此
 * 工具的用途描述里写清了这条判据。
 * <p>
 * <b>参数是不可信输入</b>：模型可能传错类型、漏字段、成环、超出步数上限。这些一律在
 * {@link WorkflowSpecParser} 里当场拒绝并回显实际收到的值（见那里的注释），
 * 且<b>全部发生在派生任何子代理之前</b>。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class WorkflowTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "workflow";

    /** 元数据键：步骤数。 */
    private static final String META_STEPS = "workflowSteps";

    /** 元数据键：累计轮数。 */
    private static final String META_ROUNDS = "workflowRounds";

    /** 元数据键：累计 token。 */
    private static final String META_TOKENS = "workflowTokens";

    /**
     * 「这次编排没正常跑完」的终态取值。
     * <p>
     * {@link ToolMetadata#KEY_TERMINAL} 的约定是「缺省 = 正常跑完」，取值本身是工具自己的字符串，
     * 内核只做「有没有、是不是 COMPLETED」这层判断。
     */
    private static final String INCOMPLETE = "WORKFLOW_INCOMPLETE";

    /** 编排引擎。 */
    private final WorkflowEngine engine;

    /**
     * 构造工具。
     *
     * @param engine 编排引擎，不可为 {@code null}
     */
    WorkflowTool(WorkflowEngine engine) {
        this.engine = engine;
    }

    /**
     * 构造工具名片：名称、用途与参数 Schema。
     *
     * @return 工具描述符，保证非 {@code null}
     */
    static ToolDescriptor descriptor() {
        Map<String, Object> stepProperties = new LinkedHashMap<String, Object>();
        stepProperties.put("id", property("string", "步骤标识，spec 内唯一，被 needs 引用"));
        stepProperties.put("agent", property("string",
                "子代理类型：必须是可委派的 agent（见系统提示里的可委派类型清单）"));
        stepProperties.put("prompt", property("string",
                "这一步的完整任务描述。子代理看不到本次对话，它只有这段文字"));
        Map<String, Object> needs = property("array", "前置步骤的 id。这些步骤都结束后本步才开始；"
                + "留空表示可以立刻开始（因此会与其它无依赖的步骤并行）");
        needs.put("items", property("string", "前置步骤 id"));
        stepProperties.put("needs", needs);
        Map<String, Object> when = property("string",
                "always（缺省）：无条件执行；on_success：前置步骤全部成功才执行；"
                        + "on_failure：前置步骤里有失败才执行（此时必须声明 needs）");
        when.put("enum", Arrays.asList("always", "on_success", "on_failure"));
        stepProperties.put("when", when);

        Map<String, Object> stepSchema = new LinkedHashMap<String, Object>();
        stepSchema.put("type", "object");
        stepSchema.put("properties", stepProperties);
        stepSchema.put("required", Arrays.asList("id", "agent", "prompt"));

        Map<String, Object> steps = property("array",
                "步骤列表，最多 " + WorkflowSpecParser.MAX_STEPS + " 步。没有依赖关系的步骤会并行执行");
        steps.put("items", stepSchema);

        Map<String, Object> aggregateProperties = new LinkedHashMap<String, Object>();
        Map<String, Object> mode = property("string",
                "collect（缺省）：按顺序拼接各步结论；summarize：再派一个子代理把它们汇总成一段结论");
        mode.put("enum", Arrays.asList("collect", "summarize"));
        aggregateProperties.put("mode", mode);
        aggregateProperties.put("agent", property("string", "summarize 时用哪个子代理来汇总"));
        Map<String, Object> aggregate = property("object", "结果聚合方式，省略则按顺序拼接");
        aggregate.put("properties", aggregateProperties);

        Map<String, Object> specProperties = new LinkedHashMap<String, Object>();
        specProperties.put("name", property("string", "这次编排的名字，只用于展示"));
        specProperties.put("steps", steps);
        specProperties.put("aggregate", aggregate);

        Map<String, Object> spec = property("object", "编排定义");
        spec.put("properties", specProperties);
        spec.put("required", Collections.singletonList("steps"));

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(WorkflowSpecParser.ARG_SPEC, spec);

        return new ToolDescriptor(NAME,
                "按一份声明式 spec 编排多个子代理：没有依赖关系的步骤会并行执行，"
                        + "有 needs 的步骤可以拿到它直接依赖的那几步给出的结论"
                        + "（结论过长时会截断，材料里会给出完整记录所在的归档路径），"
                        + "最后按声明的方式聚合。"
                        + "适合「几件事可以同时做、再把结果合起来」的任务（并行调研几个方向、"
                        + "多角度审查同一份改动）；只派一个子代理做一件事时用 task 即可。"
                        + "spec 里不能写循环、条件表达式或运行期才决定的步骤——需要那样就重新提交一份 spec。",
                properties, Collections.singletonList(WorkflowSpecParser.ARG_SPEC));
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("workflow 需要会话上下文，当前没有会话");
        }
        WorkflowSpec spec = WorkflowSpecParser.parse(request.getArguments());
        WorkflowRun run = engine.run(spec, sessionId, request.getCancellationToken(), request.getOutputSink());
        return render(run);
    }

    /**
     * 把编排结局渲染成回灌文本与元数据。
     *
     * @param run 编排结局
     * @return 工具结果，保证非 {@code null}
     */
    private static ToolCallResult render(WorkflowRun run) {
        StringBuilder text = new StringBuilder(headerOf(run));
        String aggregate = run.getAggregate();
        if (!aggregate.trim().isEmpty()) {
            text.append('\n').append(aggregate);
        }
        String notes = notesOf(run);
        if (!notes.isEmpty()) {
            text.append("\n\n——\n").append(notes);
        }
        return new ToolCallResult(NAME, text.toString(), metadataOf(run));
    }

    /**
     * 组装回灌文本的首行结论。
     * <p>
     * <b>失败时把原因写进首行</b>：模型先读到的就是这一行，而「哪个步骤为什么没跑成」是它下一步
     * 决策最需要的信息；放在末尾会让它先看完整篇正文。
     *
     * @param run 编排结局
     * @return 首行文本，保证非空白
     */
    private static String headerOf(WorkflowRun run) {
        StringBuilder header = new StringBuilder("[workflow ").append(run.getSpec().displayName());
        if (!run.hasFailure() && !run.hasCancellation()) {
            header.append(" 完成");
        }
        header.append(" · ").append(run.getSteps().size()).append(" 步")
                .append(" · ").append(run.totalRounds()).append(" 轮")
                .append(" · ").append(run.totalTokens()).append(" tok");
        StepOutcome failure = run.firstFailure();
        if (failure != null) {
            header.append(" · ").append(failure.getStep().getId()).append(" 失败");
        } else if (run.hasCancellation()) {
            header.append(" · 已取消");
        }
        return header.append(']').toString();
    }

    /**
     * 组装失败与未执行步骤的清单。
     *
     * @param run 编排结局
     * @return 清单文本；没有可说的内容时返回空串
     */
    private static String notesOf(WorkflowRun run) {
        StringBuilder notes = new StringBuilder();
        for (StepOutcome outcome : run.getSteps()) {
            if (outcome.failed()) {
                notes.append(notes.length() == 0 ? "" : "\n")
                        .append("失败：").append(outcome.getStep().getId())
                        .append("（").append(outcome.getStep().getAgent()).append("）：")
                        .append(reasonOf(outcome.getResult()));
            } else if (!outcome.hasRun()) {
                notes.append(notes.length() == 0 ? "" : "\n")
                        .append("未执行：").append(outcome.getStep().getId())
                        .append("（").append(outcome.getNotRunReason()).append("）");
            }
        }
        if (run.getSynthesis() != null && !WorkflowSpecParser.isSuccess(run.getSynthesis())) {
            notes.append(notes.length() == 0 ? "" : "\n")
                    .append("汇总失败：").append(reasonOf(run.getSynthesis()));
        }
        return notes.toString();
    }

    /**
     * 取一个委派结果里可读的原因。
     *
     * @param result 委派结果
     * @return 原因文本，保证非空白
     */
    private static String reasonOf(zcd.jellyfish.api.subagent.DelegationResult result) {
        String error = result.getError();
        if (error != null && !error.trim().isEmpty()) {
            return error.trim();
        }
        return WorkflowEngine.statusText(result.getStatus());
    }

    /**
     * 组装结构化元数据。
     *
     * @param run 编排结局
     * @return 元数据，保证非 {@code null}
     */
    private static Map<String, Object> metadataOf(WorkflowRun run) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(META_STEPS, Integer.valueOf(run.getSteps().size()));
        metadata.put(META_ROUNDS, Integer.valueOf(run.totalRounds()));
        metadata.put(META_TOKENS, Long.valueOf(run.totalTokens()));
        metadata.put(ToolMetadata.KEY_SUMMARY, summaryOf(run));
        if (run.hasFailure() || run.hasCancellation()) {
            metadata.put(ToolMetadata.KEY_TERMINAL, INCOMPLETE);
        }
        return metadata;
    }

    /**
     * 组装轨迹行上的单行摘要。
     *
     * @param run 编排结局
     * @return 摘要文本，保证非空白
     */
    private static String summaryOf(WorkflowRun run) {
        StringBuilder summary = new StringBuilder("workflow ").append(run.getSpec().displayName())
                .append(" · ").append(run.getSteps().size()).append(" 步");
        if (run.totalRounds() > 0) {
            summary.append(" · ").append(run.totalRounds()).append(" 轮");
        }
        if (run.totalTokens() > 0L) {
            summary.append(" · ").append(run.totalTokens()).append(" tok");
        }
        return summary.toString();
    }

    /**
     * 构造一个 JSON Schema 片段。
     *
     * @param type        类型
     * @param description 描述
     * @return 片段，保证非 {@code null}
     */
    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

}
