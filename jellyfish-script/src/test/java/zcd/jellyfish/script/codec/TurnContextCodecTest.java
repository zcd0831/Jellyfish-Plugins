package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合上下文编解码的单元测试。
 * <p>
 * 这里钉住的是「空结果是正当用法」：插件每轮都会被问到，绝大多数轮次它无话可说——
 * 缺 text、null、空串都必须归一成空结果，而不是变成一条空块。
 *
 * @author zcd
 */
@DisplayName("回合上下文编解码")
class TurnContextCodecTest {

    /** 被测 codec。 */
    private final TurnContextCodec codec = new TurnContextCodec();

    @Test
    @DisplayName("请求应带会话标识、本轮输入与嵌套标记")
    void encodeRequest_should_carryInputAndNestedFlag() {
        TurnContextRequest request = new TurnContextRequest("s-1", "把剩下的做完", true);

        assertEquals("s-1", codec.encodeRequest(request).get("sessionId").asText());
        assertEquals("把剩下的做完", codec.encodeRequest(request).get("userInput").asText());
        assertTrue(codec.encodeRequest(request).get("nested").asBoolean());
    }

    @Test
    @DisplayName("字符串结果与 {text} 等价")
    void decodeResult_should_acceptBareString() {
        assertEquals("待办 2/5", codec.decodeResult(ScriptJson.tree("\"待办 2/5\""), null).getText());
        assertEquals("待办 2/5",
                codec.decodeResult(ScriptJson.tree("{\"text\":\"待办 2/5\"}"), null).getText());
    }

    @Test
    @DisplayName("缺内容、null 与空串都归一成空结果")
    void decodeResult_should_beEmpty_when_nothingToSay() {
        assertTrue(codec.decodeResult(null, null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"text\":null}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"text\":\"  \"}"), null).isEmpty());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(TurnContextRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("turn_context", codec.typeName());
        assertEquals(TurnContext.class, new TurnContextRequest("s-1", "", false).getResultType());
    }
}
