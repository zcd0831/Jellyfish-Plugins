package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.TurnBeforeRequest;
import zcd.jellyfish.api.extension.TurnDirective;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合开始前编解码的单元测试。
 * <p>
 * 要紧的是<b>拦下优先于改写输入</b>：想拦下就不该再谈把输入改成什么；以及放行必须是常态
 * （会话历史 append-only，不能因为一个观察者没说话就改变回合）。
 *
 * @author zcd
 */
@DisplayName("回合开始前编解码")
class TurnBeforeCodecTest {

    /** 被测 codec。 */
    private final TurnBeforeCodec codec = new TurnBeforeCodec();

    @Test
    @DisplayName("请求应带输入、嵌套标记与深度")
    void encodeRequest_should_carryInputAndDepth() {
        TurnBeforeRequest request = new TurnBeforeRequest("s-1", "coder", "干活", true, 2);

        assertEquals("干活", codec.encodeRequest(request).get("input").asText());
        assertTrue(codec.encodeRequest(request).get("nested").asBoolean());
        assertEquals(2, codec.encodeRequest(request).get("depth").asInt());
    }

    @Test
    @DisplayName("缺省、空对象与 {cancel:false} 都是放行")
    void decodeResult_should_proceed_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"cancel\":false}"), null).isCancelled());
    }

    @Test
    @DisplayName("True 与原因字符串都是拦下")
    void decodeResult_should_cancel_when_requested() {
        assertTrue(codec.decodeResult(ScriptJson.tree("true"), null).isCancelled());
        TurnDirective directive = codec.decodeResult(ScriptJson.tree("\"工作区是脏的\""), null);
        assertTrue(directive.isCancelled());
        assertEquals("工作区是脏的", directive.getReason());
    }

    @Test
    @DisplayName("{input} 是改写本轮输入，不是拦下")
    void decodeResult_should_replaceInput() {
        TurnDirective directive = codec.decodeResult(ScriptJson.tree("{\"input\":\"换个说法\"}"), null);

        assertFalse(directive.isCancelled());
        assertTrue(directive.hasInput());
        assertEquals("换个说法", directive.getInput());
    }

    @Test
    @DisplayName("同时给了 cancel 与 input 时以拦下为准")
    void decodeResult_should_preferCancel_when_bothGiven() {
        TurnDirective directive = codec.decodeResult(
                ScriptJson.tree("{\"cancel\":true,\"reason\":\"先别跑\",\"input\":\"换个说法\"}"), null);

        assertTrue(directive.isCancelled());
        assertEquals("先别跑", directive.getReason());
        assertFalse(directive.hasInput());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(TurnBeforeRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("turn_before", codec.typeName());
    }
}
