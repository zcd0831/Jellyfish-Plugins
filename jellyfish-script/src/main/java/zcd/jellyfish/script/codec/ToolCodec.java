package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.script.ScriptInvoker;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用编解码：{@code ToolCallRequest} ↔ {@code ToolCallResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"tool":"jira_issue","arguments":{...},"sessionId":"s-1",
 *              "parentSessionId":null,"runId":"r-1","rootRunId":"r-1"}
 *   result  : {"output": 任意 JSON 值, "metadata": {"summary":"..."}}
 * </pre>
 * <b>调用者身份一起下发</b>：子代理有独立的会话与 run，而工具常需要知道「我此刻在替谁干活」
 * （协作状态落在哪个会话、这次改动属于哪次委派）。三个字段都可能是 {@code null}（根会话、
 * 不在任何 run 上），与内核侧同名缺省一致。
 * <p>
 * <b>结果可以带 metadata</b>：它有别于 {@code output}，是给界面与审计看的结构化事实，不是给模型的内容
 * （内核只把它透传给外壳，不进 {@code LlmMessage}）。最常见的两个键是 ``summary``（轨迹行上的一句话）
 * 与 ``terminal``（失败标记）。缺失时元数据为空映射——老脚本因此完全不受影响。
 * <b>{@code output} 的翻译规则与内核完全对齐</b>（{@code ReActLooper.serializeOutput}）：
 * 字符串原样回灌，其它 JSON 值由内核序列化成 JSON 文本，{@code null} 回灌空串。
 * 因此这里必须把节点还原成「字符串就是字符串、对象就是 Map」的形态，而不能一律转成文本——
 * 否则脚本返回结构化数据时会被二次转义，模型看到的是转义后的字符串。
 * <p>
 * <b>结果形状的宽容度</b>：结果是对象时必须有 {@code output} 键（缺失或 {@code null} 都表示空输出）；
 * 结果不是对象时（裸字符串、数字、数组）整个结果就是输出值。后者是给「忘记包一层」的脚本留的
 * 安全网，代价是对象形状下 {@code output} 成了保留键——想返回一个字面含 {@code output} 的对象，
 * 请写成 {@code {"output": {"output": ...}}}。
 *
 * @author zcd
 */
public final class ToolCodec implements ExtensionCodec<ToolCallRequest, ToolCallResult> {

    /** 协议类型名。 */
    public static final String TYPE_NAME = "tool";

    /** 结果载荷里的输出字段名。 */
    static final String FIELD_OUTPUT = "output";

    /** 结果载荷里的元数据字段名。 */
    static final String FIELD_METADATA = "metadata";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ToolCallRequest> requestType() {
        return ToolCallRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 工具名即路由键，同键唯一：内核用 handler(ToolCallRequest.class, 工具名) 查
        return false;
    }

    /**
     * 覆盖默认实现：把 {@link ToolCallRequest#getCancellationToken()} 交给运行时。
     * <p>
     * <b>为什么要专门为工具覆盖</b>：取消令牌是唯一一个「只在工具调用上有」的调用期设施
     * （见 {@code ToolCallRequest}），而默认实现只传 {@link CancellationToken#NONE}。
     * 不让其余 codec 在签名里背一个永远为空的参数，也不让 {@code ScriptInvoker} 知道
     * {@code ToolCallRequest} 这个具体类型——两者之间只需要一个令牌这个最小共识。
     *
     * @param invoker 调用通道，不可为 {@code null}
     * @return 处理器
     */
    @Override
    public ExtensionHandler<ToolCallRequest, ToolCallResult> handlerTo(ScriptInvoker invoker) {
        return request -> decodeResult(
                invoker.invoke(typeName(), encodeRequest(request), request.getCancellationToken()),
                request.getRouteKey());
    }

    @Override
    public JsonNode encodeRequest(ToolCallRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tool", request.getToolName());
        payload.put("arguments", request.getArguments());
        payload.put("sessionId", request.getSessionId());
        // 调用者身份：与内核侧同口径，缺省为 null（根会话 / 不在任何 run 上）
        payload.put("parentSessionId", request.getParentSessionId());
        payload.put("runId", request.getRunId());
        payload.put("rootRunId", request.getRootRunId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ToolCallResult decodeResult(JsonNode result, String routeKey) {
        JsonNode output;
        JsonNode metadata = null;
        if (result != null && result.isObject()) {
            output = result.get(FIELD_OUTPUT);
            metadata = result.get(FIELD_METADATA);
        } else {
            // 裸值：整个结果就是输出（见类注释的宽容度说明）
            output = result;
        }
        Object value = output == null || output.isNull() ? null : ScriptJson.treeToValue(output, Object.class);
        return new ToolCallResult(routeKey, value, metadataOf(metadata));
    }

    /**
     * 把结果载荷里的元数据还原成映射。
     * <p>
     * <b>不是对象时按「没有元数据」处理而不是报错</b>：元数据是给界面看的旁路信息，
     * 写坏它不该把一次已经成功的工具调用变成失败。真正的判据仍在
     * {@code ToolMetadata.failed} / {@code summaryOf}，它们本身就宽容处理坏数据。
     *
     * @param metadata 元数据节点，可为 {@code null}
     * @return 元数据映射；缺失或形状不对时为空映射，保证非 {@code null}
     */
    private static Map<String, Object> metadataOf(JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        Object value = ScriptJson.treeToValue(metadata, Object.class);
        if (!(value instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = (Map<String, Object>) value;
        return mapped;
    }
}
