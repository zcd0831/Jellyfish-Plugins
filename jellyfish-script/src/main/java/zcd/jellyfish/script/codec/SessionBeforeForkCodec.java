package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.LifecycleVerdict;
import zcd.jellyfish.api.extension.SessionBeforeForkRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话分支前编解码：{@code SessionBeforeForkRequest} ↔ {@code LifecycleVerdict}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","agentId":"coder","messageId":"m-3","cutIndex":3,"messageCount":10}
 *   result  : {"cancel":true,"reason":"正在跑的检查点还没落盘"}
 * </pre>
 * <b>为什么要有这个落点</b>：分支会复制一份会话快照，而复制之前是唯一能拦下的位置。没有它，插件
 * 只能在事后从 {@code SessionCreatedEvent} 发现，那时新会话已经在磁盘上了。
 * <p>
 * <b>拦下就是拦下</b>：与关闭前不同，本钩子拦的是「复制出一份新会话」这个动作，不是进程收尾路径，
 * 因此不存在「否决只会把资源留在表里」的顾虑。
 * <p>
 * <b>必须快且不得阻塞</b>：handler 在分支路径的调用线程上同步执行，只能做只读检查，
 * 不得回调内核、不得发布事件。
 * <p>
 * <b>处理器抛错按「放行」处理</b>：调用点 {@code SessionManager} 逐条捕获。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionBeforeForkCodec
        implements ExtensionCodec<SessionBeforeForkRequest, LifecycleVerdict> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "session_before_fork";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：agentId。 */
    private static final String FIELD_AGENT_ID = "agentId";

    /** 协议字段：分支点消息标识。 */
    private static final String FIELD_MESSAGE_ID = "messageId";

    /** 协议字段：对齐后的切点下标。 */
    private static final String FIELD_CUT_INDEX = "cutIndex";

    /** 协议字段：将要复制的消息条数。 */
    private static final String FIELD_MESSAGE_COUNT = "messageCount";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<SessionBeforeForkRequest> requestType() {
        return SessionBeforeForkRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(SessionBeforeForkRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_AGENT_ID, request.getAgentId());
        payload.put(FIELD_MESSAGE_ID, request.getMessageId());
        payload.put(FIELD_CUT_INDEX, Integer.valueOf(request.getCutIndex()));
        payload.put(FIELD_MESSAGE_COUNT, Integer.valueOf(request.getMessageCount()));
        return ScriptJson.treeOf(payload);
    }

    @Override
    public LifecycleVerdict decodeResult(JsonNode result, String routeKey) {
        return LifecycleVerdicts.decode(result);
    }
}
