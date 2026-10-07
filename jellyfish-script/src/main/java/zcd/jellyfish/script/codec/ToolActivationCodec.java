package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.ToolActivation;
import zcd.jellyfish.api.extension.ToolActivationRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具激活编解码：{@code ToolActivationRequest} ↔ {@code ToolActivation}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","agentId":"coder","toolName":"mcp__x__y","description":"..."}
 *   result  : {"decided":true,"hidden":true,"reason":"服务未连通"}
 * </pre>
 * <b>只下发工具名与描述，不下发参数 Schema</b>：这个判定的语义是「这个工具该不该存在」，
 * 它依据的是身份与连通性，而不是参数长什么样；而冻结时每个工具都要问一遍，带上 Schema
 * 会让载荷随工具数线性膨胀。
 * <p>
 * <b>三态，不是布尔</b>：{@code None} = 不表态（后续插件继续）、{@code True} 或
 * {@code {"visible": true}} = 明确要它可见（**能压过后面的插件**）、{@code False} /
 * 原因字符串 / {@code {"hidden": true}} = 明确隐藏。布尔会让「不想管」与「明确要它可见」
 * 变成同一个值，一个只想隐藏某个工具的插件就会被前一个插件的「不管」冲掉。
 * <p>
 * <b>处理器必须快且不得做 I/O</b>：调用点在冻结清单的那一刻，每个工具一次；要探测外部服务
 * 应当在插件自己的后台线程上做，把结论缓存在内存里。
 * <p>
 * <b>合并规则是「第一个表了态的胜出」</b>，与压缩策略、请求调优同一口径；handler 抛错按
 * 「不表态」处理（内核调用点捕获）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolActivationCodec
        implements ExtensionCodec<ToolActivationRequest, ToolActivation> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "tool_activation";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：工具名。 */
    private static final String FIELD_TOOL_NAME = "toolName";

    /** 协议字段：工具描述。 */
    private static final String FIELD_DESCRIPTION = "description";

    /** 结果字段：是否明确要它可见。 */
    private static final String FIELD_VISIBLE = "visible";

    /** 结果字段：是否明确隐藏。 */
    private static final String FIELD_HIDDEN = "hidden";

    /** 结果字段：隐藏理由。 */
    private static final String FIELD_REASON = "reason";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ToolActivationRequest> requestType() {
        return ToolActivationRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(ToolActivationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_TOOL_NAME, request.getToolName());
        payload.put(FIELD_DESCRIPTION, request.getDescriptor() == null
                ? null : request.getDescriptor().getDescription());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ToolActivation decodeResult(JsonNode result, String routeKey) {
        if (result == null || result.isNull()) {
            return ToolActivation.abstain();
        }
        if (result.isBoolean()) {
            return result.asBoolean() ? ToolActivation.visible() : ToolActivation.hidden(null);
        }
        if (result.isTextual()) {
            // 一句话就是「隐藏它，理由是这句」：最自然的写法
            return ToolActivation.hidden(result.asText());
        }
        if (!result.isObject()) {
            return ToolActivation.abstain();
        }
        if (Payloads.bool(result, FIELD_VISIBLE, false)) {
            return ToolActivation.visible();
        }
        if (Payloads.bool(result, FIELD_HIDDEN, false)) {
            return ToolActivation.hidden(Payloads.text(result, FIELD_REASON));
        }
        return ToolActivation.abstain();
    }
}
