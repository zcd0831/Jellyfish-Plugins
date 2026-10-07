package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 输入改写编解码：{@code InputTransformRequest} ↔ {@code InputTransformResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","text":"<用户按回车时的原文>","source":"TUI|HEADLESS","hasSession":true}
 *   result  : {"handled":false,"replaced":false,"text":"...","notice":"..."}
 * </pre>
 * <b>它是热路径扩展点</b>：每次用户提交都会问一遍（人级频率）。因此桥接层照常允许冷启动，
 * 但处理函数必须快——它排在命令判定之后、指令解析之前。
 * <p>
 * <b>三种结果</b>（与 {@code InputTransformResult} 的工厂一一对应）：
 * <ul>
 *     <li>{@code None} / {@code {"handled": false}} → 不改，照常继续；</li>
 *     <li>字符串 / {@code {"text": "..."}} → 替换用户输入（可把它归一化成另一段文本）；</li>
 *     <li>{@code {"handled": true, "notice": "..."}} → <b>接过去、不进对话</b>，只给用户一句说明
 *     （命令那样）。它不会绕过权限：接过去之后真实执行什么仍由内核按声明走。</li>
 * </ul>
 * <b>它排在命令判定之后</b>：一个插件不能把 {@code /help} 改写成别的东西——用户看到的与执行的
 * 必须是同一件事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputTransformCodec
        implements ExtensionCodec<InputTransformRequest, InputTransformResult> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "input_transform";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：输入原文。 */
    private static final String FIELD_TEXT = "text";

    /** 协议字段：来源。 */
    private static final String FIELD_SOURCE = "source";

    /** 协议字段：是否已有会话。 */
    private static final String FIELD_HAS_SESSION = "hasSession";

    /** 结果字段：是否被接过去。 */
    private static final String FIELD_HANDLED = "handled";

    /** 结果字段：是否替换了文本。 */
    private static final String FIELD_REPLACED = "replaced";

    /** 结果字段：替换后的文本。 */
    private static final String FIELD_REPLACEMENT = "replacedText";

    /** 结果字段：接过去之后给用户的说明。 */
    private static final String FIELD_NOTICE = "notice";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<InputTransformRequest> requestType() {
        return InputTransformRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(InputTransformRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_TEXT, request.getText());
        payload.put(FIELD_SOURCE, request.getSource().name());
        payload.put(FIELD_HAS_SESSION, Boolean.valueOf(request.hasSession()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public InputTransformResult decodeResult(JsonNode result, String routeKey) {
        if (result == null || result.isNull()) {
            return InputTransformResult.continueAsIs();
        }
        if (result.isTextual()) {
            // 一句话就是「把它改成这句」：最自然的写法
            return InputTransformResult.replace(result.asText());
        }
        if (!result.isObject()) {
            return InputTransformResult.continueAsIs();
        }
        if (Payloads.bool(result, FIELD_HANDLED, false)) {
            return InputTransformResult.handled(Payloads.text(result, FIELD_NOTICE));
        }
        String replacement = Payloads.text(result, FIELD_TEXT);
        if (replacement != null && Payloads.bool(result, FIELD_REPLACED, true)) {
            return InputTransformResult.replace(replacement);
        }
        return InputTransformResult.continueAsIs();
    }
}
