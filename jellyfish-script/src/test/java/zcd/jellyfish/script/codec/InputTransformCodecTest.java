package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入改写编解码的单元测试。
 * <p>
 * 三种结果必须能分开：不改 / 替换文本 / 接过去（不进对话）。最后一种最容易被误实现成
 * 「替换成空文本」，那会让用户的输入凭空消失。
 *
 * @author zcd
 */
@DisplayName("输入改写编解码")
class InputTransformCodecTest {

    /** 被测 codec。 */
    private final InputTransformCodec codec = new InputTransformCodec();

    @Test
    @DisplayName("请求应带输入原文、来源与会话有无")
    void encodeRequest_should_carryTextSourceAndSessionFlag() {
        InputTransformRequest request = new InputTransformRequest("s-1", "!ls",
                InputTransformRequest.Source.CLI, true);

        assertEquals("!ls", codec.encodeRequest(request).get("text").asText());
        assertEquals("CLI", codec.encodeRequest(request).get("source").asText());
        assertTrue(codec.encodeRequest(request).get("hasSession").asBoolean());
    }

    @Test
    @DisplayName("缺省、空对象与 {handled:false} 都是不改")
    void decodeResult_should_continue_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, null).isHandled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isHandled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"handled\":false}"), null).hasText());
    }

    @Test
    @DisplayName("字符串与 {text} 都是替换文本")
    void decodeResult_should_replaceText() {
        InputTransformResult fromString = codec.decodeResult(ScriptJson.tree("\"归一化后的输入\""), null);
        assertFalse(fromString.isHandled());
        assertTrue(fromString.hasText());
        assertEquals("归一化后的输入", fromString.getText());

        InputTransformResult fromObject = codec.decodeResult(
                ScriptJson.tree("{\"text\":\"改过的\"}"), null);
        assertEquals("改过的", fromObject.getText());
    }

    @Test
    @DisplayName("{handled:true} 是「接过去、不进对话」，不是把输入改成空")
    void decodeResult_should_handleWithoutReplacing() {
        InputTransformResult result = codec.decodeResult(
                ScriptJson.tree("{\"handled\":true,\"notice\":\"已按快捷指令处理\"}"), null);

        assertTrue(result.isHandled());
        assertFalse(result.hasText());
        assertEquals("已按快捷指令处理", result.getNotice());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(InputTransformRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("input_transform", codec.typeName());
    }
}
