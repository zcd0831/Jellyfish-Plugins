package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.ToolResultAdjustment;
import zcd.jellyfish.api.extension.ToolResultPostRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果整形编解码：{@code ToolResultPostRequest} ↔ {@code ToolResultAdjustment}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"agentId":"coder","toolName":"read_file","arguments":{...},
 *              "output": 任意 JSON, "metadata":{...}, "failed":false, "sessionId":"s-1"}
 *   result  : {"output": 任意 JSON, "metadata":{...}}
 * </pre>
 * <b>「哪一项不改」用「字段缺失或为 null」表达</b>，与 {@code ToolResultAdjustment.of} 的约定逐字一致：
 * 只给 {@code output} 就是只改正文、只给 {@code metadata} 就是只改元数据、两个都不给就是这个处理器
 * 不表态（不会把正文改成 null——那是一个无法与「不改」区分的值）。
 * <p>
 * <b>输出保持原始类型</b>：字符串就是字符串、结构化对象就是结构化对象。这里<b>不</b>把它序列化成文本，
 * 因为下游的截断要知道类型才能选对算法（见 {@code ToolOutputLimiter}）。
 * <p>
 * <b>能改的与不能改的</b>：{@code output} 与 {@code metadata} 可改；{@code failed} <b>只读</b>——
 * 它是界面判成败的唯一判据，放开它等于引入第二套判据。因此协议里没有它的出口。
 * <p>
 * <b>处理器抛错按「不改」处理</b>：调用点 {@code ToolExecutor.adjustResult} 逐条捕获。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolResultPostCodec
        implements ExtensionCodec<ToolResultPostRequest, ToolResultAdjustment> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "tool_result_post";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：工具名。 */
    private static final String FIELD_TOOL_NAME = "toolName";

    /** 协议字段：参数。 */
    private static final String FIELD_ARGUMENTS = "arguments";

    /** 协议字段：输出（保持原始类型）。 */
    private static final String FIELD_OUTPUT = "output";

    /** 协议字段：元数据。 */
    private static final String FIELD_METADATA = "metadata";

    /** 协议字段：是否值得警示（只读，仅下发给脚本）。 */
    private static final String FIELD_FAILED = "failed";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ToolResultPostRequest> requestType() {
        return ToolResultPostRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(ToolResultPostRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_TOOL_NAME, request.getToolName());
        payload.put(FIELD_ARGUMENTS, request.getArguments());
        payload.put(FIELD_OUTPUT, request.getOutput());
        payload.put(FIELD_METADATA, request.getMetadata());
        payload.put(FIELD_FAILED, Boolean.valueOf(request.isFailed()));
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ToolResultAdjustment decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return ToolResultAdjustment.abstain();
        }
        JsonNode output = result.get(FIELD_OUTPUT);
        boolean hasOutput = output != null && !output.isNull();
        Map<String, Object> metadata = Payloads.map(result.get(FIELD_METADATA));
        if (!hasOutput && metadata == null) {
            // 两个字段都没给：这个处理器没有意见。不把它当成「替换成空」——那会让正文凭空消失
            return ToolResultAdjustment.abstain();
        }
        return ToolResultAdjustment.of(hasOutput ? Payloads.value(output) : null, metadata);
    }
}
