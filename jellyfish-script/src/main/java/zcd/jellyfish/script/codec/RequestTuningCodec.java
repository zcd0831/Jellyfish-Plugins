package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.RequestTuning;
import zcd.jellyfish.api.extension.RequestTuningRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求调优编解码：{@code RequestTuningRequest} ↔ {@code RequestTuning}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","providerType":"openai","modelId":"gpt-x",
 *              "defaultCacheKey":"...","messageCount":12,"toolCount":5}
 *   result  : {"cacheKey":"...","cacheRetention":"...","cacheBreakpoints":2}
 * </pre>
 * <b>它是热路径扩展点</b>：每次组装请求、即将发给厂商之前都会被问到。桥接层因此对它
 * <b>不冷启动</b>（见 {@code HotPathPoints}）——一次进程冷启动落在「每次发请求之前」不可接受，
 * 而热着的 worker 往返是毫秒级。缺省值 {@link RequestTuning#empty()} 表示「不改」。
 * <p>
 * <b>越界值由内核查</b>：{@code cacheBreakpoints} 有上限常量，构造器自己会校验；这里只负责
 * 把 JSON 还原成值对象，不在 codec 里再写一遍取值范围。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class RequestTuningCodec implements ExtensionCodec<RequestTuningRequest, RequestTuning> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "request_tuning";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：provider 类型。 */
    private static final String FIELD_PROVIDER_TYPE = "providerType";

    /** 协议字段：模型标识。 */
    private static final String FIELD_MODEL_ID = "modelId";

    /** 协议字段：缺省缓存键。 */
    private static final String FIELD_DEFAULT_CACHE_KEY = "defaultCacheKey";

    /** 协议字段：消息条数。 */
    private static final String FIELD_MESSAGE_COUNT = "messageCount";

    /** 协议字段：工具个数。 */
    private static final String FIELD_TOOL_COUNT = "toolCount";

    /** 结果字段：缓存键。 */
    private static final String FIELD_CACHE_KEY = "cacheKey";

    /** 结果字段：缓存保留策略。 */
    private static final String FIELD_CACHE_RETENTION = "cacheRetention";

    /** 结果字段：缓存断点个数。 */
    private static final String FIELD_CACHE_BREAKPOINTS = "cacheBreakpoints";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<RequestTuningRequest> requestType() {
        return RequestTuningRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(RequestTuningRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_PROVIDER_TYPE, request.getProviderType());
        payload.put(FIELD_MODEL_ID, request.getModelId());
        payload.put(FIELD_DEFAULT_CACHE_KEY, request.getDefaultCacheKey());
        payload.put(FIELD_MESSAGE_COUNT, Integer.valueOf(request.getMessageCount()));
        payload.put(FIELD_TOOL_COUNT, Integer.valueOf(request.getToolCount()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public RequestTuning decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return RequestTuning.empty();
        }
        return new RequestTuning(Payloads.text(result, FIELD_CACHE_KEY),
                Payloads.text(result, FIELD_CACHE_RETENTION),
                Payloads.integer(result, FIELD_CACHE_BREAKPOINTS));
    }
}
