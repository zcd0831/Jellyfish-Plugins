package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回合上下文编解码：{@code TurnContextRequest} ↔ {@code TurnContext}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","userInput":"把剩下的都做完","nested":false}
 *   result  : {"text":"待办 2/5：……"}        // null / 缺 text = 本轮没有要送的
 * </pre>
 * <b>形态是「拼接型」</b>：同一个回合允许多个插件各送一段，内核按 {@code order} 升序拼接。
 * <p>
 * <b>它的产物随本轮用户消息落盘</b>，与 {@code PromptContributionRequest} 的分工见该扩展点的注释：
 * 进 system prompt 的状态每变一次都会作废整个缓存前缀，而这里只影响本轮新产生的 token。
 * 因此<b>会变的即时状态走这里，会话内不变的东西才走提示词贡献</b>。
 * <p>
 * <b>空结果是正当用法</b>：插件每轮都会被问到，只有真的有话要说时才返回文本。
 * 因此 {@code null}、缺 {@code text}、空文本都归一成 {@link TurnContext#empty()}。
 * <p>
 * <b>处理器抛错按「跳过」处理</b>：调用点 {@code PromptAssembler} 逐条捕获并告警。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnContextCodec implements ExtensionCodec<TurnContextRequest, TurnContext> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "turn_context";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：本轮用户输入。 */
    private static final String FIELD_USER_INPUT = "userInput";

    /** 协议字段：是否嵌套回合。 */
    private static final String FIELD_NESTED = "nested";

    /** 结果字段：上下文文本。 */
    private static final String FIELD_TEXT = "text";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<TurnContextRequest> requestType() {
        return TurnContextRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(TurnContextRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_USER_INPUT, request.getUserInput());
        payload.put(FIELD_NESTED, Boolean.valueOf(request.isNested()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public TurnContext decodeResult(JsonNode result, String routeKey) {
        if (result == null) {
            return TurnContext.empty();
        }
        // 裸字符串也接受：脚本里最自然的写法就是「有话就说这句话」
        if (result.isTextual()) {
            return TurnContext.of(result.asText());
        }
        return TurnContext.of(Payloads.text(result, FIELD_TEXT));
    }
}
