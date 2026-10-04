package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolArgumentDecision;
import zcd.jellyfish.api.extension.ToolArgumentPreRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具参数改写编解码的单元测试。
 * <p>
 * 这里钉住的最要紧一条是<b>未知裁定必须报错</b>：脚本把 outcome 写错一个字母时静默当成「不改」，
 * 会让一道本该被改写的调用带着原参数跑下去，而调用者毫不知情。
 *
 * @author zcd
 */
@DisplayName("工具参数改写编解码")
class ToolArgumentPreCodecTest {

    /** 被测 codec。 */
    private final ToolArgumentPreCodec codec = new ToolArgumentPreCodec();

    @Test
    @DisplayName("请求应带工具名、参数、发起方与调用者身份")
    void encodeRequest_should_carryToolArgumentsAndSource() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "/tmp/a");

        JsonNode payload = codec.encodeRequest(new ToolArgumentPreRequest("coder", "read_file", arguments,
                ToolArgumentPreRequest.Source.DIRECTIVE, "s-1"));

        assertEquals("coder", payload.get("agentId").asText());
        assertEquals("read_file", payload.get("toolName").asText());
        assertEquals("/tmp/a", payload.get("arguments").get("path").asText());
        assertEquals("DIRECTIVE", payload.get("source").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
    }

    @Test
    @DisplayName("缺 outcome 应归一成不改，而不是报错")
    void decodeResult_should_abstain_when_outcomeIsAbsent() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isAbstain());
        assertTrue(codec.decodeResult(null, null).isAbstain());
    }

    @Test
    @DisplayName("REPLACE 应带上替换后的参数")
    void decodeResult_should_replaceArguments_when_outcomeIsReplace() {
        ToolArgumentDecision decision = codec.decodeResult(
                ScriptJson.tree("{\"outcome\":\"replace\",\"arguments\":{\"path\":\"/tmp/b\"}}"), null);

        assertTrue(decision.isReplace());
        assertEquals("/tmp/b", decision.getArguments().get("path"));
    }

    @Test
    @DisplayName("DENY 应带上理由")
    void decodeResult_should_denyWithReason() {
        ToolArgumentDecision decision = codec.decodeResult(
                ScriptJson.tree("{\"outcome\":\"DENY\",\"reason\":\"不许写 .env\"}"), null);

        assertTrue(decision.isDenied());
        assertEquals("不许写 .env", decision.getReason());
    }

    @Test
    @DisplayName("未知裁定应报错，而不是静默按不改处理")
    void decodeResult_should_rejectUnknownOutcome() {
        assertThrows(JellyfishException.class,
                () -> codec.decodeResult(ScriptJson.tree("{\"outcome\":\"REPLACEE\"}"), null));
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(ToolArgumentPreRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("tool_argument_pre", codec.typeName());
    }
}
