package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanState} 的单元测试：钉住「开关落在本插件的会话扩展条目上」与读取失败的兜底语义。
 * <p>
 * 上下文用 mock（它只是内核给插件的一层门面），因此这里验证的是<b>调用姿势</b>：
 * key 用哪一个、值写成什么形状、读的时候认哪一条。
 *
 * @author zcd
 */
@DisplayName("plan 开关存取")
class PlanStateTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-plan";

    /** 完整 key（含 owner 前缀）。 */
    private static final String FULL_KEY = PLUGIN_ID + "::" + PlanState.KEY_ENABLED;

    @Test
    @DisplayName("未写过条目时为关闭")
    void isEnabled_should_beFalse_whenNoEntry() {
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.<SessionExtensionEntry>emptyList());

        assertFalse(new PlanState(context).isEnabled("s-1"));
    }

    @Test
    @DisplayName("条目里 enabled 为 true 时为开启")
    void isEnabled_should_beTrue_whenEntrySaysEnabled() {
        PluginContext context = context();
        when(context.extensionEntries("s-1"))
                .thenReturn(Collections.singletonList(entry(Boolean.TRUE)));

        assertTrue(new PlanState(context).isEnabled("s-1"));
    }

    @Test
    @DisplayName("值不是布尔（配置被手改过）时按关闭处理，而不是抛错")
    void isEnabled_should_beFalse_whenValueIsNotBoolean() {
        PluginContext context = context();
        when(context.extensionEntries("s-1"))
                .thenReturn(Collections.singletonList(entry("yes")));

        assertFalse(new PlanState(context).isEnabled("s-1"));
    }

    @Test
    @DisplayName("别人的条目不算数：只认本插件命名空间下那一条")
    void isEnabled_should_ignoreForeignEntries() {
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.singletonList(
                new SessionExtensionEntry("other-plugin::enabled",
                        Collections.<String, Object>singletonMap("enabled", Boolean.TRUE), 0L)));

        assertFalse(new PlanState(context).isEnabled("s-1"));
    }

    @Test
    @DisplayName("会话标识为空、会话不存在时都按关闭处理")
    void isEnabled_should_beFalse_whenSessionMissing() {
        PluginContext context = context();
        when(context.extensionEntries(any())).thenThrow(new JellyfishException("session not found: ghost"));

        PlanState state = new PlanState(context);

        assertFalse(state.isEnabled(null));
        assertFalse(state.isEnabled("  "));
        assertFalse(state.isEnabled("ghost"));
    }

    @Test
    @DisplayName("设置开关时用不带前缀的 key，值写成 {enabled: true}")
    void set_should_writeNamespacedEntry() {
        PluginContext context = context();

        new PlanState(context).set("s-1", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(context).putExtensionEntry(eq("s-1"), eq(PlanState.KEY_ENABLED), captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().get("enabled"));
    }

    @Test
    @DisplayName("关闭时值写成 false，仍然保留条目")
    void set_should_writeFalse_whenDisabled() {
        PluginContext context = context();

        new PlanState(context).set("s-1", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(context).putExtensionEntry(eq("s-1"), eq(PlanState.KEY_ENABLED), captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().get("enabled"));
    }

    @Test
    @DisplayName("写入失败原样上抛：开关没设上就必须让人知道，而不是静默按关闭继续")
    void set_should_propagateFailure() {
        PluginContext context = context();
        Mockito.doThrow(new JellyfishException("session not found: ghost"))
                .when(context).putExtensionEntry(any(), any(), any());

        assertThrows(JellyfishException.class, () -> new PlanState(context).set("ghost", true));
    }

    /**
     * 构造一个只回答身份与扩展条目的插件上下文。
     *
     * @return 插件上下文
     */
    private static PluginContext context() {
        PluginContext context = Mockito.mock(PluginContext.class);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        return context;
    }

    /**
     * 构造一条本插件的扩展条目。
     *
     * @param enabled 条目里的取值
     * @return 扩展条目
     */
    private static SessionExtensionEntry entry(Object enabled) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("enabled", enabled);
        return new SessionExtensionEntry(FULL_KEY, value, 0L);
    }
}
