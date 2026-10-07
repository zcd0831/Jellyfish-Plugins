package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入指令编解码的单元测试。
 * <p>
 * 要紧的两条：<b>路由键是标记本身</b>（同键唯一，两个插件抢同一个标记在注册期就撞上），
 * 以及<b>不认领是常态</b>——缺省与写坏的对象都按「不归我」处理，而不是抛错。
 *
 * @author zcd
 */
@DisplayName("输入指令编解码")
class InputDirectiveCodecTest {

    /** 被测 codec。 */
    private final InputDirectiveCodec codec = new InputDirectiveCodec();

    @Test
    @DisplayName("请求应带标记与标记之后的原文")
    void encodeRequest_should_carryMarkerAndInput() {
        InputDirectiveRequest request = new InputDirectiveRequest("!", "ls -la", "s-1");

        assertEquals("!", codec.encodeRequest(request).get("marker").asText());
        assertEquals("ls -la", codec.encodeRequest(request).get("input").asText());
        assertEquals("s-1", codec.encodeRequest(request).get("sessionId").asText());
    }

    @Test
    @DisplayName("缺省、空对象与 unclaimed 都是不认领")
    void decodeResult_should_beUnclaimed_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, "!").isToolCall());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), "!").isToolCall());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"unclaimed\":true}"), "!").isToolCall());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"other\":1}"), "!").isToolCall());
    }

    @Test
    @DisplayName("工具名字符串或 {toolName, arguments} 都是声明一次工具调用")
    void decodeResult_should_claimAsToolCall() {
        InputDirectiveResult fromString = codec.decodeResult(ScriptJson.tree("\"shell\""), "!");
        assertTrue(fromString.isToolCall());
        assertEquals("shell", fromString.getToolName());

        InputDirectiveResult fromObject = codec.decodeResult(
                ScriptJson.tree("{\"toolName\":\"shell\",\"arguments\":{\"command\":\"ls\"}}"), "!");
        assertTrue(fromObject.isToolCall());
        assertEquals("ls", fromObject.getArguments().get("command"));
    }

    @Test
    @DisplayName("注册形态应为带路由键的唯一处理器，路由键是标记")
    void requestTypeAndKind_should_beRouted() {
        assertEquals(InputDirectiveRequest.class, codec.requestType());
        assertFalse(codec.isTypeLevel());
        assertEquals("!", new InputDirectiveRequest("!", "ls", "s-1").getRouteKey());
        assertEquals("input_directive", codec.typeName());
    }
}
