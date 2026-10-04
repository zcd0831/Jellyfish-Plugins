package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CompactionDirective;
import zcd.jellyfish.api.extension.CompactionPreRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩前编解码的单元测试。
 * <p>
 * 要紧的两条：<b>拦下优先于改保留条数</b>（那一次压根不压），以及<b>请求里没有消息正文</b>——
 * 只给规模与触发原因，「怎么压」不该让插件开始理解对话内容。
 *
 * @author zcd
 */
@DisplayName("压缩前编解码")
class CompactionPreCodecTest {

    /** 被测 codec。 */
    private final CompactionPreCodec codec = new CompactionPreCodec();

    @Test
    @DisplayName("请求应带规模与触发原因，且不含消息正文")
    void encodeRequest_should_carryScaleWithoutContent() {
        CompactionPreRequest request = new CompactionPreRequest("s-1", CompactionTrigger.AUTO,
                12, 3400, 20, "m-5");

        assertEquals("AUTO", codec.encodeRequest(request).get("trigger").asText());
        assertEquals(12, codec.encodeRequest(request).get("messageCount").asInt());
        assertEquals(3400, codec.encodeRequest(request).get("tokensBefore").asInt());
        assertEquals(20, codec.encodeRequest(request).get("keepRecentMessages").asInt());
        assertEquals("m-5", codec.encodeRequest(request).get("previousBoundaryMessageId").asText());
        assertFalse(codec.encodeRequest(request).has("messages"));
    }

    @Test
    @DisplayName("null 与空对象都是放行")
    void decodeResult_should_proceed_when_scriptStaysSilent() {
        assertFalse(codec.decodeResult(null, null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isCancelled());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).hasKeepRecent());
    }

    @Test
    @DisplayName("keepRecent 应能被改写")
    void decodeResult_should_overrideKeepRecent() {
        CompactionDirective directive = codec.decodeResult(ScriptJson.tree("{\"keepRecent\":5}"), null);

        assertTrue(directive.hasKeepRecent());
        assertEquals(5, directive.getKeepRecent().intValue());
    }

    @Test
    @DisplayName("拦下优先：同时给了 cancel 与 keepRecent 时以拦下为准")
    void decodeResult_should_preferCancel_when_bothGiven() {
        CompactionDirective directive = codec.decodeResult(
                ScriptJson.tree("{\"cancel\":true,\"reason\":\"这几轮还没走完\",\"keepRecent\":5}"), null);

        assertTrue(directive.isCancelled());
        assertEquals("这几轮还没走完", directive.getReason());
        assertFalse(directive.hasKeepRecent());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(CompactionPreRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
        assertEquals("compaction_pre", codec.typeName());
    }
}
