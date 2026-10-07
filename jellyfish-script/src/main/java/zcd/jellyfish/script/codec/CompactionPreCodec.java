package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.CompactionDirective;
import zcd.jellyfish.api.extension.CompactionPreRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩前编解码：{@code CompactionPreRequest} ↔ {@code CompactionDirective}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1","trigger":"MANUAL|AUTO","messageCount":12,"tokensBefore":3400,
 *              "keepRecentMessages":20,"previousBoundaryMessageId":"m-5"}
 *   result  : {"cancel":true,"reason":"…"}    // 拦下
 *           | {"keepRecent":5}                // 改保留条数
 *           | null                            // 放行
 * </pre>
 * <b>请求里没有消息正文</b>：只给规模与触发原因。压缩策略扩展点早就立过这条边界——
 * 「怎么压」是策略、「这次要不要压」是钩子，两者都不该让插件开始理解对话内容。
 * <p>
 * <b>改保留条数为什么有价值</b>：压缩花的是模型的钱，而「这次该压多少」通常是领域知识——
 * 一个跑长任务的插件比内核更清楚「最近这几轮是同一个目标的连续步骤，压掉就断了」。
 * 越界的值由内核钳制，不会破坏工具调用配对的配对不变量。
 * <p>
 * <b>处理器抛错按「放行」处理</b>：调用点 {@code ConversationCompactor} 逐条捕获——
 * 压缩不该因为一个观察者坏了而失败。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionPreCodec
        implements ExtensionCodec<CompactionPreRequest, CompactionDirective> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "compaction_pre";

    /** 协议字段：会话标识。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** 协议字段：触发原因。 */
    private static final String FIELD_TRIGGER = "trigger";

    /** 协议字段：消息条数。 */
    private static final String FIELD_MESSAGE_COUNT = "messageCount";

    /** 协议字段：待压缩范围的 token 估算值。 */
    private static final String FIELD_TOKENS_BEFORE = "tokensBefore";

    /** 协议字段：策略算完后的保留条数。 */
    private static final String FIELD_KEEP_RECENT = "keepRecentMessages";

    /** 协议字段：上一次压缩的边界消息标识。 */
    private static final String FIELD_PREVIOUS_BOUNDARY = "previousBoundaryMessageId";

    /** 结果字段：是否拦下。 */
    private static final String FIELD_CANCEL = "cancel";

    /** 结果字段：拦下理由。 */
    private static final String FIELD_REASON = "reason";

    /** 结果字段：保留条数覆盖值。 */
    private static final String FIELD_KEEP_RECENT_OVERRIDE = "keepRecent";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<CompactionPreRequest> requestType() {
        return CompactionPreRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        return true;
    }

    @Override
    public JsonNode encodeRequest(CompactionPreRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_SESSION_ID, request.getSessionId());
        payload.put(FIELD_TRIGGER, request.getTrigger().name());
        payload.put(FIELD_MESSAGE_COUNT, Integer.valueOf(request.getMessageCount()));
        payload.put(FIELD_TOKENS_BEFORE, Integer.valueOf(request.getTokensBefore()));
        payload.put(FIELD_KEEP_RECENT, Integer.valueOf(request.getKeepRecentMessages()));
        payload.put(FIELD_PREVIOUS_BOUNDARY, request.getPreviousBoundaryMessageId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public CompactionDirective decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return CompactionDirective.proceed();
        }
        if (Payloads.bool(result, FIELD_CANCEL, false)) {
            // 拦下优先：想拦下就不该再谈保留多少条（那一次压根不压）
            return CompactionDirective.cancel(Payloads.text(result, FIELD_REASON));
        }
        Integer keepRecent = Payloads.integer(result, FIELD_KEEP_RECENT_OVERRIDE);
        return keepRecent == null ? CompactionDirective.proceed()
                : CompactionDirective.keepRecent(keepRecent.intValue());
    }
}
