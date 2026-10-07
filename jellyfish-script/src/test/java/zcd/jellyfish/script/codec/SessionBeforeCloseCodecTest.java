package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.LifecycleVerdict;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话关闭前编解码的单元测试。
 * <p>
 * 要紧的是「否决只在用户主动关闭时生效」这条在内核侧的事实，因此请求里必须带上
 * {@code vetoSupported}——脚本据此少做无用功，而结果形状与分支前那个钩子完全一致。
 *
 * @author zcd
 */
@DisplayName("会话关闭前编解码")
class SessionBeforeCloseCodecTest {

    /** 被测 codec。 */
    private final SessionBeforeCloseCodec codec = new SessionBeforeCloseCodec();

    @Test
    @DisplayName("请求应带关闭原因与本次否决是否会被采纳")
    void encodeRequest_should_carryReasonAndVetoSupport() {
        assertEquals("RELOAD", codec.encodeRequest(new SessionBeforeCloseRequest("s-1", "coder",
                SessionBeforeCloseRequest.Reason.RELOAD)).get("reason").asText());
        assertFalse(codec.encodeRequest(new SessionBeforeCloseRequest("s-1", "coder",
                SessionBeforeCloseRequest.Reason.RELOAD)).get("vetoSupported").asBoolean());
        assertTrue(codec.encodeRequest(new SessionBeforeCloseRequest("s-1", "coder",
                SessionBeforeCloseRequest.Reason.USER_REQUEST)).get("vetoSupported").asBoolean());
    }

    @Test
    @DisplayName("null / false / 空对象都是放行")
    void decodeResult_should_proceed_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("false"), null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isCancelled());
    }

    @Test
    @DisplayName("true 与原因字符串都是拦下")
    void decodeResult_should_cancel_when_requested() {
        assertTrue(codec.decodeResult(ScriptJson.tree("true"), null).isCancelled());
        LifecycleVerdict verdict = codec.decodeResult(ScriptJson.tree("\"工作区有未提交改动\""), null);
        assertTrue(verdict.isCancelled());
        assertEquals("工作区有未提交改动", verdict.getReason());
        assertEquals("还有一个检查点在跑",
                codec.decodeResult(ScriptJson.tree("{\"cancel\":true,\"reason\":\"还有一个检查点在跑\"}"), null)
                        .getReason());
    }

    @Test
    @DisplayName("会话标识为空白应在构造期报错")
    void construction_should_rejectBlankSessionId() {
        assertThrows(zcd.jellyfish.api.JellyfishException.class,
                () -> new SessionBeforeCloseRequest("  ", null, null));
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(SessionBeforeCloseRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("session_before_close", codec.typeName());
    }
}
