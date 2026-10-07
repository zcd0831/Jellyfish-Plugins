package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolActivation;
import zcd.jellyfish.api.extension.ToolActivationRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.script.ScriptJson;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具激活编解码的单元测试。
 * <p>
 * 要紧的是<b>三态而不是布尔</b>：{@code None}（不管）与 {@code True}（明确要它可见）必须能分开，
 * 否则一个只想隐藏某个工具的插件会被前一个插件的「不管」冲掉。
 *
 * @author zcd
 */
@DisplayName("工具激活编解码")
class ToolActivationCodecTest {

    /** 被测 codec。 */
    private final ToolActivationCodec codec = new ToolActivationCodec();

    @Test
    @DisplayName("请求应带工具名与描述，不带参数 Schema")
    void encodeRequest_should_carryNameAndDescription() {
        ToolActivationRequest request = new ToolActivationRequest("s-1", "coder",
                new ToolDescriptor("mcp__x__y", "远程工具", null, Arrays.asList("a")));

        assertEquals("mcp__x__y", codec.encodeRequest(request).get("toolName").asText());
        assertEquals("远程工具", codec.encodeRequest(request).get("description").asText());
        assertFalse(codec.encodeRequest(request).has("parameters"));
    }

    @Test
    @DisplayName("None / 空对象是不表态；True 是明确可见；False 与字符串是隐藏")
    void decodeResult_should_distinguishAbstainFromVisible() {
        assertFalse(codec.decodeResult(null, null).isDecided());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isDecided());

        ToolActivation visible = codec.decodeResult(ScriptJson.tree("true"), null);
        assertTrue(visible.isDecided());
        assertFalse(visible.isHidden());

        ToolActivation hidden = codec.decodeResult(ScriptJson.tree("false"), null);
        assertTrue(hidden.isDecided());
        assertTrue(hidden.isHidden());

        ToolActivation reason = codec.decodeResult(ScriptJson.tree("\"服务未连通\""), null);
        assertTrue(reason.isHidden());
        assertEquals("服务未连通", reason.getReason());
    }

    @Test
    @DisplayName("对象写法：visible / hidden 两种明确表态")
    void decodeResult_should_acceptObjectForms() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"visible\":true}"), null).isDecided());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"visible\":true}"), null).isHidden());
        ToolActivation hidden = codec.decodeResult(
                ScriptJson.tree("{\"hidden\":true,\"reason\":\"项目约定\"}"), null);
        assertTrue(hidden.isHidden());
        assertEquals("项目约定", hidden.getReason());
        assertNull(codec.decodeResult(ScriptJson.tree("{\"other\":1}"), null).getReason());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(ToolActivationRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("tool_activation", codec.typeName());
    }
}
