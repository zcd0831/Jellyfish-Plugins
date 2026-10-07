package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.AgingStrategy;
import zcd.jellyfish.api.extension.AgingStrategyRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 老化策略编解码：{@code AgingStrategyRequest} ↔ {@code AgingStrategy}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","messageCount":40,"usedTokens":90000,"budgetTokens":128000,
 *              "compressionBoundary":20,"defaultKeepRecentMessages":20,"defaultAgingPercent":50}
 *   result  : {"keepRecentMessages":8,"agingPercent":30,"stubText":"...",
 *              "stubTextsByTool":{"shell":"..."}}
 * </pre>
 * <b>它是热路径扩展点</b>：每次组装请求、准备处理较早的工具结果之前都会被问到。桥接层因此对它
 * <b>不冷启动</b>（见 {@code HotPathPoints}）。缺省值 {@link AgingStrategy#none()} 表示「不改」。
 * <p>
 * <b>它只能改措辞与参数，不掌握「删哪条消息」</b>：老化算法是前缀不变量的守卫，而前缀不变量
 * 是全局性质；插件能说话的是自己的工具输出的 stub 文案（{@code stubTextsByTool}）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgingStrategyCodec implements ExtensionCodec<AgingStrategyRequest, AgingStrategy> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "aging_strategy";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：消息条数。 */
    private static final String FIELD_MESSAGE_COUNT = "messageCount";

    /** 协议字段：已用 token。 */
    private static final String FIELD_USED_TOKENS = "usedTokens";

    /** 协议字段：token 预算。 */
    private static final String FIELD_BUDGET_TOKENS = "budgetTokens";

    /** 协议字段：压缩边界。 */
    private static final String FIELD_COMPRESSION_BOUNDARY = "compressionBoundary";

    /** 协议字段：缺省保留条数。 */
    private static final String FIELD_DEFAULT_KEEP_RECENT = "defaultKeepRecentMessages";

    /** 协议字段：缺省老化百分比。 */
    private static final String FIELD_DEFAULT_AGING_PERCENT = "defaultAgingPercent";

    /** 结果字段：保留条数。 */
    private static final String FIELD_KEEP_RECENT = "keepRecentMessages";

    /** 结果字段：老化百分比。 */
    private static final String FIELD_AGING_PERCENT = "agingPercent";

    /** 结果字段：stub 文案。 */
    private static final String FIELD_STUB_TEXT = "stubText";

    /** 结果字段：按工具分的 stub 文案。 */
    private static final String FIELD_STUB_TEXTS_BY_TOOL = "stubTextsByTool";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<AgingStrategyRequest> requestType() {
        return AgingStrategyRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(AgingStrategyRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_MESSAGE_COUNT, Integer.valueOf(request.getMessageCount()));
        payload.put(FIELD_USED_TOKENS, Integer.valueOf(request.getUsedTokens()));
        payload.put(FIELD_BUDGET_TOKENS, Integer.valueOf(request.getBudgetTokens()));
        payload.put(FIELD_COMPRESSION_BOUNDARY, Integer.valueOf(request.getCompressionBoundary()));
        payload.put(FIELD_DEFAULT_KEEP_RECENT, Integer.valueOf(request.getDefaultKeepRecentMessages()));
        payload.put(FIELD_DEFAULT_AGING_PERCENT, Integer.valueOf(request.getDefaultAgingPercent()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public AgingStrategy decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return AgingStrategy.none();
        }
        return new AgingStrategy(Payloads.integer(result, FIELD_KEEP_RECENT),
                Payloads.integer(result, FIELD_AGING_PERCENT),
                Payloads.text(result, FIELD_STUB_TEXT),
                stubTexts(result.get(FIELD_STUB_TEXTS_BY_TOOL)));
    }

    /**
     * 解析按工具分的 stub 文案。
     * <p>
     * 不是对象、或值里有非字符串时，只收下能用的那几条——stub 文案是展示措辞，
     * 写坏一条不该让一次已经算好的老化策略整体失败。
     *
     * @param node 值节点，可为 {@code null}
     * @return 工具名 → 文案，保证非 {@code null}
     */
    private static Map<String, String> stubTexts(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> mapped = new LinkedHashMap<String, String>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue() != null && field.getValue().isTextual()) {
                mapped.put(field.getKey(), field.getValue().asText());
            }
        }
        return Collections.unmodifiableMap(mapped);
    }
}
