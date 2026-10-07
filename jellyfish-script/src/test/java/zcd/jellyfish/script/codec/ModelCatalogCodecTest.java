package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ModelCatalogRequest;
import zcd.jellyfish.api.extension.ModelCatalogResult;
import zcd.jellyfish.api.extension.ModelDescriptor;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录编解码的单元测试。
 * <p>
 * 要紧的两条：<b>空列表是「我不表态」而不是「没有模型」</b>（回落成配置里那份），以及
 * <b>单条缺 id 只跳过那一条</b>——缺 id 的记录没有任何用处，但让整次发现失败会连带丢掉可用的模型。
 *
 * @author zcd
 */
@DisplayName("模型目录编解码")
class ModelCatalogCodecTest {

    /** 被测 codec。 */
    private final ModelCatalogCodec codec = new ModelCatalogCodec();

    @Test
    @DisplayName("请求应带 provider 名与类型")
    void encodeRequest_should_carryProviderNameAndType() {
        assertEquals("local", codec.encodeRequest(new ModelCatalogRequest("local", "openai"))
                .get("providerName").asText());
        assertEquals("openai", codec.encodeRequest(new ModelCatalogRequest("local", "openai"))
                .get("providerType").asText());
    }

    @Test
    @DisplayName("模型列表应被还原，缺规格按未知处理")
    void decodeResult_should_restoreModels() {
        ModelCatalogResult result = codec.decodeResult(ScriptJson.tree(
                "{\"models\":[{\"id\":\"m1\",\"name\":\"M 1\",\"contextLength\":32768,\"maxOutputTokens\":4096},"
                        + "{\"id\":\"m2\"}]}"), "local");

        assertEquals(2, result.getModels().size());
        ModelDescriptor first = result.getModels().get(0);
        assertEquals("m1", first.getId());
        assertEquals("M 1", first.getName());
        assertEquals(32768, first.getContextLength());
        assertEquals(4096, first.getMaxOutputTokens());
        assertEquals("m2", result.getModels().get(1).getId());
        assertEquals(0, result.getModels().get(1).getContextLength());
    }

    @Test
    @DisplayName("空列表与缺字段都是「不表态」，不是「没有模型」")
    void decodeResult_should_beEmpty_when_nothingDiscovered() {
        assertFalse(codec.decodeResult(null, "local").isPresent());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), "local").isPresent());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"models\":[]}"), "local").isPresent());
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"models\":\"oops\"}"), "local").isPresent());
    }

    @Test
    @DisplayName("单条缺 id 只跳过那一条，其余仍可用")
    void decodeResult_should_skipEntryWithoutId() {
        ModelCatalogResult result = codec.decodeResult(
                ScriptJson.tree("{\"models\":[{\"name\":\"没有 id\"},{\"id\":\"m2\"}]}"), "local");

        assertEquals(1, result.getModels().size());
        assertEquals("m2", result.getModels().get(0).getId());
    }

    @Test
    @DisplayName("注册形态应为带路由键的唯一处理器（路由键是 provider 名）")
    void requestTypeAndKind_should_beRouted() {
        assertEquals(ModelCatalogRequest.class, codec.requestType());
        assertFalse(codec.isTypeLevel());
        assertEquals("model_catalog", codec.typeName());
        assertEquals("local", new ModelCatalogRequest("local", "openai").getRouteKey());
    }
}
