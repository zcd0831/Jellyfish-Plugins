package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 输入指令编解码：{@code InputDirectiveRequest} ↔ {@code InputDirectiveResult}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"marker":"!","input":"ls -la","sessionId":"s-1"}
 *   result  : {"toolName":"shell","arguments":{"command":"ls -la"}}   // 声明一次工具调用
 *           | {"unclaimed":true} | null                               // 不认领这行输入
 * </pre>
 * <b>它是带路由键的扩展点，路由键是标记本身（{@code !} / {@code @}）</b>：内核用它做精确查找，
 * 于是「两个插件都想管 {@code !}」在注册期就撞「同键唯一」，而不是在运行期变成一个取决于注册顺序的
 * 静默竞争。脚本因此要在清单的 {@code handlers} 里写出标记：
 * {@code {"type":"input_directive","route":"!"}}。
 * <p>
 * <b>不认领是常态</b>：{@code None}、{@code {"unclaimed": true}} 都表示「这行输入不归我」，
 * 内核继续问别的处理器。
 * <p>
 * <b>它不得起进程、不得写盘、不得阻塞</b>：真正的执行由内核按 {@code toolName} 走完整的
 * 权限与审批链——这里只回答「这行输入该当成哪次工具调用」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputDirectiveCodec
        implements ExtensionCodec<InputDirectiveRequest, InputDirectiveResult> {

    /** 协议类型名，同时是清单 {@code handlers} 的 {@code type}。 */
    public static final String TYPE_NAME = "input_directive";

    /** 协议字段：标记。 */
    private static final String FIELD_MARKER = "marker";

    /** 协议字段：标记之后的输入原文。 */
    private static final String FIELD_INPUT = "input";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 结果字段：不认领。 */
    private static final String FIELD_UNCLAIMED = "unclaimed";

    /** 结果字段：工具名。 */
    private static final String FIELD_TOOL_NAME = "toolName";

    /** 结果字段：工具参数。 */
    private static final String FIELD_ARGUMENTS = "arguments";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<InputDirectiveRequest> requestType() {
        return InputDirectiveRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 路由键是标记本身：同键唯一，两个插件抢同一个标记在注册期就撞上
        return false;
    }

    @Override
    public JsonNode encodeRequest(InputDirectiveRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_MARKER, request.getMarker());
        payload.put(FIELD_INPUT, request.getInput());
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public InputDirectiveResult decodeResult(JsonNode result, String routeKey) {
        if (result == null || result.isNull()) {
            return InputDirectiveResult.unclaimed();
        }
        if (result.isTextual()) {
            // 一句话就是「把它当成这个工具的一次调用」：参数由工具自己从 ctx 里取
            return InputDirectiveResult.toolCall(result.asText(), null);
        }
        if (!result.isObject() || Payloads.bool(result, FIELD_UNCLAIMED, false)) {
            return InputDirectiveResult.unclaimed();
        }
        String toolName = Payloads.text(result, FIELD_TOOL_NAME);
        if (toolName == null || toolName.trim().isEmpty()) {
            // 没给工具名的对象什么都没说：按「不认领」处理比抛错更合适——认领是可选动作
            return InputDirectiveResult.unclaimed();
        }
        return InputDirectiveResult.toolCall(toolName, Payloads.map(result.get(FIELD_ARGUMENTS)));
    }
}
