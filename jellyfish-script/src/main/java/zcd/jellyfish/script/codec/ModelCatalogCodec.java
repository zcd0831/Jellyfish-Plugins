package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.ModelCatalogRequest;
import zcd.jellyfish.api.extension.ModelCatalogResult;
import zcd.jellyfish.api.extension.ModelDescriptor;
import zcd.jellyfish.script.ScriptJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录编解码：{@code ModelCatalogRequest} ↔ {@code ModelCatalogResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"providerName":"local-llama","providerType":"openai"}
 *   result  : {"models":[{"id":"qwen2.5","name":"Qwen 2.5","contextLength":32768,"maxOutputTokens":4096}]}
 * </pre>
 * <b>它是带路由键的扩展点（{@code handle}）</b>：路由键是 provider 名而不是类型——
 * 同一个类型下可能配两个不同账号的 provider，按类型问会让插件分不清是在问哪一个。
 * <p>
 * <b>空列表的含义是「我不表态」</b>，回落成配置里 {@code models} 写的那份；它<b>不是</b>
 * 「这个 provider 一个模型都没有」。发现失败最坏的结果因此是「用回配置里的列表」，
 * 而不是「provider 突然没有模型可用了」。
 * <p>
 * <b>单条缺 {@code id} 就跳过那一条</b>：id 是模型标识，缺了它这条记录没有任何用处；
 * 跳过比让整次发现失败更合理（剩余模型仍然可用）。
 * <p>
 * <b>处理器抛错按「不表态」处理</b>：调用点 {@code ModelManager.askCatalog} 捕获并告警。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ModelCatalogCodec implements ExtensionCodec<ModelCatalogRequest, ModelCatalogResult> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "model_catalog";

    /** 协议字段：provider 名。 */
    private static final String FIELD_PROVIDER_NAME = "providerName";

    /** 协议字段：provider 类型。 */
    private static final String FIELD_PROVIDER_TYPE = "providerType";

    /** 结果字段：模型列表。 */
    private static final String FIELD_MODELS = "models";

    /** 模型字段：标识。 */
    private static final String FIELD_ID = "id";

    /** 模型字段：展示名。 */
    private static final String FIELD_NAME = "name";

    /** 模型字段：上下文窗口。 */
    private static final String FIELD_CONTEXT_LENGTH = "contextLength";

    /** 模型字段：最大输出。 */
    private static final String FIELD_MAX_OUTPUT = "maxOutputTokens";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<ModelCatalogRequest> requestType() {
        return ModelCatalogRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 路由键是 provider 名：同键唯一，一种 provider 只能有一个发现实现
        return false;
    }

    @Override
    public JsonNode encodeRequest(ModelCatalogRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_PROVIDER_NAME, request.getProviderName());
        payload.put(FIELD_PROVIDER_TYPE, request.getProviderType());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public ModelCatalogResult decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return ModelCatalogResult.empty();
        }
        JsonNode models = result.get(FIELD_MODELS);
        if (models == null || !models.isArray()) {
            return ModelCatalogResult.empty();
        }
        List<ModelDescriptor> discovered = new ArrayList<ModelDescriptor>();
        for (JsonNode element : models) {
            String id = Payloads.text(element, FIELD_ID);
            if (id == null || id.trim().isEmpty()) {
                // 缺 id 的那一条没有用处；跳过它比让整次发现失败更合理
                continue;
            }
            discovered.add(new ModelDescriptor(id.trim(), Payloads.text(element, FIELD_NAME),
                    intOf(element, FIELD_CONTEXT_LENGTH), intOf(element, FIELD_MAX_OUTPUT)));
        }
        return ModelCatalogResult.of(discovered);
    }

    /**
     * 取整数字段，缺失或类型不对时按 0（「未知」）处理。
     *
     * @param node  模型节点，可为 {@code null}
     * @param field 字段名
     * @return 整数值，未知时为 0
     */
    private static int intOf(JsonNode node, String field) {
        Integer value = Payloads.integer(node, field);
        return value == null ? 0 : value.intValue();
    }
}
