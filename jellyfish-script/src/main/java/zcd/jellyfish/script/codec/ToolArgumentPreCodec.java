package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolArgumentDecision;
import zcd.jellyfish.api.extension.ToolArgumentPreRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具参数改写编解码：{@code ToolArgumentPreRequest} ↔ {@code ToolArgumentDecision}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"agentId":"coder","toolName":"write_file","arguments":{...},
 *              "source":"MODEL|DIRECTIVE","sessionId":"s-1"}
 *   result  : {"outcome":"ABSTAIN|REPLACE|DENY","arguments":{...},"reason":"..."}
 * </pre>
 * <b>三态里没有「放行」</b>：本扩展点表达不了权限结论，改写之后照旧要过
 * {@code PermissionCheckRequest}。{@code DENY} 的含义是「这条参数我不同意」，
 * 它与权限拒绝是两件事（也不进权限审计）。
 * <p>
 * <b>未知取值一律报错而不是静默当成 ABSTAIN</b>：脚本把 {@code "replace"} 写成 {@code "Replace "}之外的
 * 拼写时，静默按「不改」处理会让一道本该被改写的调用带着原参数跑下去，而调用者完全不知道。
 * 抛出的异常由内核调用点记 WARN 并跳过本次表态，因此降级行为不变、但错误是可见的。
 * <p>
 * <b>处理器抛错按「不改」处理</b>：调用点 {@code ToolExecutor.transformArguments} 逐条捕获，
 * 一个坏处理器不该让整次工具调用失败。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolArgumentPreCodec
        implements ExtensionCodec<ToolArgumentPreRequest, ToolArgumentDecision> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "tool_argument_pre";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：工具名。 */
    private static final String FIELD_TOOL_NAME = "toolName";

    /** 协议字段：参数。 */
    private static final String FIELD_ARGUMENTS = "arguments";

    /** 协议字段：发起方。 */
    private static final String FIELD_SOURCE = "source";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 结果字段：裁定结论。 */
    private static final String FIELD_OUTCOME = "outcome";

    /** 结果字段：拒绝理由。 */
    private static final String FIELD_REASON = "reason";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ToolArgumentPreRequest> requestType() {
        return ToolArgumentPreRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 针对「一次工具调用」这个整体，不按工具名分槽：按名字分槽会让两个插件撞「同键唯一」
        return true;
    }

    @Override
    public JsonNode encodeRequest(ToolArgumentPreRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_TOOL_NAME, request.getToolName());
        payload.put(FIELD_ARGUMENTS, request.getArguments());
        payload.put(FIELD_SOURCE, request.getSource().name());
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ToolArgumentDecision decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            // 不表态是常态而不是错误：脚本只在真的想改时才说话
            return ToolArgumentDecision.abstain();
        }
        String outcome = Payloads.text(result, FIELD_OUTCOME);
        if (outcome == null) {
            return ToolArgumentDecision.abstain();
        }
        String normalized = outcome.trim().toUpperCase(Locale.ROOT);
        if (ToolArgumentDecision.Outcome.ABSTAIN.name().equals(normalized)) {
            return ToolArgumentDecision.abstain();
        }
        if (ToolArgumentDecision.Outcome.REPLACE.name().equals(normalized)) {
            Map<String, Object> arguments = Payloads.map(result.get(FIELD_ARGUMENTS));
            return ToolArgumentDecision.replace(arguments == null
                    ? new LinkedHashMap<String, Object>() : arguments);
        }
        if (ToolArgumentDecision.Outcome.DENY.name().equals(normalized)) {
            return ToolArgumentDecision.deny(Payloads.text(result, FIELD_REASON));
        }
        throw new JellyfishException("参数改写裁定非法: " + outcome
                + "（只允许 ABSTAIN / REPLACE / DENY）");
    }
}
