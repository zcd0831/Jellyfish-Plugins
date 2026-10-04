package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.RequestTuning;
import zcd.jellyfish.api.extension.RequestTuningRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求调优编解码的单元测试。
 * <p>
 * 这里是热路径扩展点，因此缺省必须干净地落到 {@link RequestTuning#empty()}（「不改」）——
 * 脚本没说话时不能改变任何请求参数。
 *
 * @author zcd
 */
@DisplayName("请求调优编解码")
class RequestTuningCodecTest {

    /** 被测 codec。 */
    private final RequestTuningCodec codec = new RequestTuningCodec();

    @Test
    @DisplayName("请求应带 provider、模型与规模")
    void encodeRequest_should_carryProviderModelAndScale() {
        RequestTuningRequest request = new RequestTuningRequest("s-1", "openai", "gpt-x", "key", 12, 5);

        assertEquals("openai", codec.encodeRequest(request).get("providerType").asText());
        assertEquals("gpt-x", codec.encodeRequest(request).get("modelId").asText());
        assertEquals("key", codec.encodeRequest(request).get("defaultCacheKey").asText());
        assertEquals(12, codec.encodeRequest(request).get("messageCount").asInt());
        assertEquals(5, codec.encodeRequest(request).get("toolCount").asInt());
    }

    @Test
    @DisplayName("缺省与空对象都应落到「不改」")
    void decodeResult_should_beEmpty_when_scriptStaysSilent() {
        assertTrue(codec.decodeResult(null, null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("\"oops\""), null).isEmpty());
    }

    @Test
    @DisplayName("三个字段应被还原")
    void decodeResult_should_restoreFields() {
        RequestTuning tuning = codec.decodeResult(ScriptJson.tree(
                "{\"cacheKey\":\"k\",\"cacheRetention\":\"long\",\"cacheBreakpoints\":1}"), null);

        assertEquals("k", tuning.getCacheKey());
        assertEquals("long", tuning.getCacheRetention());
        assertEquals(1, tuning.getCacheBreakpoints().intValue());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献，且在热路径名单里")
    void requestTypeAndKind_should_beTypeLevelHotPath() {
        assertEquals(RequestTuningRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertTrue(HotPathPoints.contains(codec.typeName()));
    }
}
