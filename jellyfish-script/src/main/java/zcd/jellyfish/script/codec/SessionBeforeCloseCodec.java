package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.LifecycleVerdict;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话关闭前编解码：{@code SessionBeforeCloseRequest} ↔ {@code LifecycleVerdict}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","agentId":"coder","reason":"USER_REQUEST","vetoSupported":true}
 *   result  : {"cancel":true,"reason":"工作区还有未提交的改动"}
 * </pre>
 * <b>为什么要有这个落点</b>：插件常需要在会话结束时收尾（写检查点、导出记录、提示未提交的改动）。
 * 事件通道里的 {@code SessionClosedEvent} 是事后通知且可丢，在上面收尾一旦丢了就悄悄没做——
 * 本钩子是同步的、不丢的。
 * <p>
 * <b>否决只在用户主动关闭时生效</b>：{@code REASON} 为 {@code RELOAD} / {@code SHUTDOWN} / {@code INTERNAL}
 * 时内核忽略否决。{@code vetoSupported} 随请求下发，让脚本在明知不会被采纳时少做无用功
 * （不读它、照常返回 cancel 也是安全的）。
 * <p>
 * <b>必须快且不得阻塞</b>：handler 在关闭路径的调用线程上同步执行，而那条路径可能是进程收尾。
 * 脚本插件在这里天然吃亏（一次进程往返就是毫秒级），因此它只适合做「检查一件事然后拦下」，
 * 不适合做真正的收尾工作。
 * <p>
 * <b>处理器抛错按「放行」处理</b>：调用点 {@code SessionManager.beforeClose} 逐条捕获。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionBeforeCloseCodec
        implements ExtensionCodec<SessionBeforeCloseRequest, LifecycleVerdict> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "session_before_close";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：关闭原因。 */
    private static final String FIELD_REASON = "reason";

    /** 协议字段：本次否决是否会被采纳。 */
    private static final String FIELD_VETO_SUPPORTED = "vetoSupported";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<SessionBeforeCloseRequest> requestType() {
        return SessionBeforeCloseRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(SessionBeforeCloseRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_REASON, request.getReason().name());
        payload.put(FIELD_VETO_SUPPORTED, Boolean.valueOf(request.isVetoSupported()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public LifecycleVerdict decodeResult(JsonNode result, String routeKey) {
        return LifecycleVerdicts.decode(result);
    }
}
