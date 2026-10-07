package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.SessionBeforeForkRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话分支前编解码的单元测试。
 * <p>
 * 它与关闭前共用 {@link zcd.jellyfish.api.extension.LifecycleVerdict}，因此这里额外钉住
 * <b>请求字段是分支特有的</b>（分支点与规模），而结果形状与关闭前逐字一致——
 * 两处各写一份解码迟早会漂移成「关会话能拦、分叉拦不住」。
 *
 * @author zcd
 */
@DisplayName("会话分支前编解码")
class SessionBeforeForkCodecTest {

    /** 被测 codec。 */
    private final SessionBeforeForkCodec codec = new SessionBeforeForkCodec();

    @Test
    @DisplayName("请求应带分支点与将要复制的消息条数")
    void encodeRequest_should_carryCutPointAndSize() {
        assertEquals("m-3", codec.encodeRequest(new SessionBeforeForkRequest("s-1", "coder", "m-3", 3, 10))
                .get("messageId").asText());
        assertEquals(3, codec.encodeRequest(new SessionBeforeForkRequest("s-1", "coder", "m-3", 3, 10))
                .get("cutIndex").asInt());
        assertEquals(10, codec.encodeRequest(new SessionBeforeForkRequest("s-1", "coder", "m-3", 3, 10))
                .get("messageCount").asInt());
        assertTrue(codec.encodeRequest(new SessionBeforeForkRequest("s-1", "coder", null, -1, 0))
                .get("messageId").isNull());
    }

    @Test
    @DisplayName("缺结果与空对象都是放行")
    void decodeResult_should_proceed_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isCancelled());
        assertNull(codec.decodeResult(ScriptJson.tree("{}"), null).getReason());
    }

    @Test
    @DisplayName("结果形状与关闭前逐字一致：{cancel, reason}")
    void decodeResult_should_shareVerdictShapeWithBeforeClose() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"cancel\":true,\"reason\":\"检查点没落盘\"}"), null)
                .isCancelled());
        assertEquals("检查点没落盘",
                codec.decodeResult(ScriptJson.tree("{\"cancel\":true,\"reason\":\"检查点没落盘\"}"), null)
                        .getReason());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(SessionBeforeForkRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("session_before_fork", codec.typeName());
    }
}
