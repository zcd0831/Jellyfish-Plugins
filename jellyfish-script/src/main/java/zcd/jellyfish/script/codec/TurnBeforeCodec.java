package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.TurnBeforeRequest;
import zcd.jellyfish.api.extension.TurnDirective;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回合开始前编解码：{@code TurnBeforeRequest} ↔ {@code TurnDirective}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","agentId":"coder","input":"<本轮输入>","nested":false,"depth":0}
 *   result  : {"cancel":true,"reason":"..."}    // 拦下这个回合
 *           | {"input":"..."}                  // 改写本轮输入
 *           | null                             // 放行
 * </pre>
 * <b>它排在追加用户消息之前</b>：会话历史是 append-only 的（缓存前缀与落盘都依赖这条性质），
 * 在追加之前拦，历史里干干净净，用户重发一次就好。也正因如此，<b>嵌套回合也会问</b>——
 * 子代理恰恰是最需要「脏仓库守护」这类拦截的地方。
 * <p>
 * <b>轮换与拦下互斥</b>：{@code cancel} 优先——想拦下就不该再谈把输入改成什么。
 * 放行（{@code None}）是常态。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnBeforeCodec implements ExtensionCodec<TurnBeforeRequest, TurnDirective> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "turn_before";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：本轮输入。 */
    private static final String FIELD_INPUT = "input";

    /** 协议字段：是否嵌套回合。 */
    private static final String FIELD_NESTED = "nested";

    /** 协议字段：嵌套深度。 */
    private static final String FIELD_DEPTH = "depth";

    /** 结果字段：是否拦下。 */
    private static final String FIELD_CANCEL = "cancel";

    /** 结果字段：拦下理由。 */
    private static final String FIELD_REASON = "reason";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<TurnBeforeRequest> requestType() {
        return TurnBeforeRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(TurnBeforeRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_INPUT, request.getInput());
        payload.put(FIELD_NESTED, Boolean.valueOf(request.isNested()));
        payload.put(FIELD_DEPTH, Integer.valueOf(request.getDepth()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public TurnDirective decodeResult(JsonNode result, String routeKey) {
        if (result == null || result.isNull()) {
            return TurnDirective.proceed();
        }
        if (result.isBoolean()) {
            return result.asBoolean() ? TurnDirective.cancel(null) : TurnDirective.proceed();
        }
        if (result.isTextual()) {
            return TurnDirective.cancel(result.asText());
        }
        if (!result.isObject()) {
            return TurnDirective.proceed();
        }
        if (Payloads.bool(result, FIELD_CANCEL, false)) {
            // 拦下优先：想拦下就不该再谈把输入改成什么
            return TurnDirective.cancel(Payloads.text(result, FIELD_REASON));
        }
        String replaced = Payloads.text(result, FIELD_INPUT);
        return replaced == null ? TurnDirective.proceed() : TurnDirective.replaceInput(replaced);
    }
}
