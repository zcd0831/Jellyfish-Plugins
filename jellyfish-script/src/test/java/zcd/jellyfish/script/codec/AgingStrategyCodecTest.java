package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.AgingStrategy;
import zcd.jellyfish.api.extension.AgingStrategyRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老化策略编解码的单元测试。
 * <p>
 * 要紧的是<b>缺省落到 {@link AgingStrategy#none()}</b>，以及按工具分的 stub 文案里写坏一条
 * 不会让整次策略失败——那只是展示措辞。
 *
 * @author zcd
 */
@DisplayName("老化策略编解码")
class AgingStrategyCodecTest {

    /** 被测 codec。 */
    private final AgingStrategyCodec codec = new AgingStrategyCodec();

    @Test
    @DisplayName("请求应带规模、水位与缺省参数")
    void encodeRequest_should_carryScaleAndDefaults() {
        AgingStrategyRequest request = new AgingStrategyRequest("s-1", 40, 90000, 128000, 20, 8, 50);

        assertEquals(40, codec.encodeRequest(request).get("messageCount").asInt());
        assertEquals(90000, codec.encodeRequest(request).get("usedTokens").asInt());
        assertEquals(128000, codec.encodeRequest(request).get("budgetTokens").asInt());
        assertEquals(20, codec.encodeRequest(request).get("compressionBoundary").asInt());
        assertEquals(8, codec.encodeRequest(request).get("defaultKeepRecentMessages").asInt());
        assertEquals(50, codec.encodeRequest(request).get("defaultAgingPercent").asInt());
    }

    @Test
    @DisplayName("缺省与空对象都应落到「不改」")
    void decodeResult_should_beEmpty_when_scriptStaysSilent() {
        assertTrue(codec.decodeResult(null, null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
    }

    @Test
    @DisplayName("保留条数、百分比与 stub 文案应被还原")
    void decodeResult_should_restoreFields() {
        AgingStrategy strategy = codec.decodeResult(ScriptJson.tree(
                "{\"keepRecentMessages\":8,\"agingPercent\":30,\"stubText\":\"...\","
                        + "\"stubTextsByTool\":{\"shell\":\"命令输出已老化\"}}"), null);

        assertEquals(8, strategy.getKeepRecentMessages().intValue());
        assertEquals(30, strategy.getAgingPercent().intValue());
        assertEquals("...", strategy.getStubText());
        assertEquals("命令输出已老化", strategy.getStubTextsByTool().get("shell"));
    }

    @Test
    @DisplayName("stub 文案里写坏一条时只丢掉那一条，不让整次策略失败")
    void decodeResult_should_tolerateMalformedStubText() {
        AgingStrategy strategy = codec.decodeResult(ScriptJson.tree(
                "{\"agingPercent\":30,\"stubTextsByTool\":{\"shell\":\"ok\",\"bad\":123}}"), null);

        assertEquals(1, strategy.getStubTextsByTool().size());
        assertEquals("ok", strategy.getStubTextsByTool().get("shell"));
    }

    @Test
    @DisplayName("注册形态应为类型级贡献，且在热路径名单里")
    void requestTypeAndKind_should_beTypeLevelHotPath() {
        assertEquals(AgingStrategyRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertTrue(HotPathPoints.contains(codec.typeName()));
    }
}
