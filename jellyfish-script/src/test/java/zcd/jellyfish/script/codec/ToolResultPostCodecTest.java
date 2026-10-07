package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolResultAdjustment;
import zcd.jellyfish.api.extension.ToolResultPostRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果整形编解码的单元测试。
 * <p>
 * 要紧的两条：<b>输出保持原始类型</b>（这里序列化成文本，下游截断就会选错算法），以及
 * <b>「哪一项不改」用字段缺失表达</b>（两个都不给就是不表态，不能把正文替换成 null）。
 *
 * @author zcd
 */
@DisplayName("工具结果整形编解码")
class ToolResultPostCodecTest {

    /** 被测 codec。 */
    private final ToolResultPostCodec codec = new ToolResultPostCodec();

    @Test
    @DisplayName("请求应带原始输出、元数据与只读的 failed 判据")
    void encodeRequest_should_carryOutputMetadataAndFailedFlag() {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("hits", 3);
        Map<String, Object> metadata = Collections.<String, Object>singletonMap("summary", "3 条");

        JsonNode payload = codec.encodeRequest(new ToolResultPostRequest("coder", "web_search",
                Collections.<String, Object>emptyMap(), output, metadata, true, "s-1"));

        assertEquals(3, payload.get("output").get("hits").asInt());
        assertEquals("3 条", payload.get("metadata").get("summary").asText());
        assertTrue(payload.get("failed").asBoolean());
    }

    @Test
    @DisplayName("两个字段都不给就是不表态，不能把正文替换成空")
    void decodeResult_should_abstain_when_nothingIsGiven() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isAbstain());
        assertTrue(codec.decodeResult(null, null).isAbstain());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"output\":null,\"metadata\":null}"), null).isAbstain());
    }

    @Test
    @DisplayName("只给 output 就是只改正文，元数据保持不变")
    void decodeResult_should_replaceOutputOnly() {
        ToolResultAdjustment adjustment = codec.decodeResult(
                ScriptJson.tree("{\"output\":\"脱敏后的正文\"}"), null);

        assertTrue(adjustment.hasOutput());
        assertFalse(adjustment.hasMetadata());
        assertEquals("脱敏后的正文", adjustment.getOutput());
    }

    @Test
    @DisplayName("只给 metadata 就是只改元数据，正文保持不变")
    void decodeResult_should_replaceMetadataOnly() {
        ToolResultAdjustment adjustment = codec.decodeResult(
                ScriptJson.tree("{\"metadata\":{\"summary\":\"重写了摘要\"}}"), null);

        assertFalse(adjustment.hasOutput());
        assertTrue(adjustment.hasMetadata());
        assertEquals("重写了摘要", adjustment.getMetadata().get("summary"));
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(ToolResultPostRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("tool_result_post", codec.typeName());
    }
}
