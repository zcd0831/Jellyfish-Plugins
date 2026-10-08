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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanState} 的单元测试：钉住「开关落在本插件的会话扩展条目上」、三态的倒向，以及沿父链判定。
 * <p>
 * 上下文用 mock（它只是内核给插件的一层门面），因此这里验证的是<b>调用姿势</b>：
 * key 用哪一个、值写成什么形状、读的时候认哪一条、判不出来时返回哪一态。
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
    void switchOf_should_beOff_whenNoEntry() {
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.<SessionExtensionEntry>emptyList());

        assertEquals(PlanState.Switch.OFF, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("条目里 enabled 为 true 时为开启")
    void switchOf_should_beOn_whenEntrySaysEnabled() {
        PluginContext context = context();
        when(context.extensionEntries("s-1"))
                .thenReturn(Collections.singletonList(entry(Boolean.TRUE)));

        assertEquals(PlanState.Switch.ON, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("条目里 enabled 为 false 时为关闭")
    void switchOf_should_beOff_whenEntrySaysDisabled() {
        PluginContext context = context();
        when(context.extensionEntries("s-1"))
                .thenReturn(Collections.singletonList(entry(Boolean.FALSE)));

        assertEquals(PlanState.Switch.OFF, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("值不是布尔（会话文件被手改过）时按「读不出来」处理，而不是按关闭")
    void switchOf_should_beUnknown_whenValueIsNotBoolean() {
        PluginContext context = context();
        when(context.extensionEntries("s-1"))
                .thenReturn(Collections.singletonList(entry("yes")));

        // 按「关」处理等于让一道按模式收窄的授权静默消失：这里必须把「判不出来」如实报上去
        assertEquals(PlanState.Switch.UNKNOWN, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("条目在但字段被删空时同样按「读不出来」处理")
    void switchOf_should_beUnknown_whenFieldIsMissing() {
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.singletonList(
                new SessionExtensionEntry(FULL_KEY, Collections.<String, Object>emptyMap(), 0L)));

        assertEquals(PlanState.Switch.UNKNOWN, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("别人的条目不算数：只认本插件命名空间下那一条")
    void switchOf_should_ignoreForeignEntries() {
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.singletonList(
                new SessionExtensionEntry("other-plugin::enabled",
                        Collections.<String, Object>singletonMap("enabled", Boolean.TRUE), 0L)));

        assertEquals(PlanState.Switch.OFF, new PlanState(context).switchOf("s-1"));
    }

    @Test
    @DisplayName("会话标识为空时按关闭：没有会话就没有开关，也就没有属于它的工具调用")
    void switchOf_should_beOff_whenSessionIdIsBlank() {
        PlanState state = new PlanState(context());

        assertEquals(PlanState.Switch.OFF, state.switchOf(null));
        assertEquals(PlanState.Switch.OFF, state.switchOf("  "));
    }

    @Test
    @DisplayName("读条目抛异常（会话已不存在、上下文失效）时按「读不出来」，而不是按关闭")
    void switchOf_should_beUnknown_whenReadFails() {
        PluginContext context = context();
        when(context.extensionEntries(any())).thenThrow(new JellyfishException("session not found: ghost"));

        assertEquals(PlanState.Switch.UNKNOWN, new PlanState(context).switchOf("ghost"));
    }

    @Test
    @DisplayName("沿父链判定：父会话开着 plan 时，子代理会话算开着")
    void switchOf_should_followParentChain() {
        // Given：父会话开着 plan，子代理会话自己没有这条条目
        PluginContext context = context();
        when(context.extensionEntries("parent")).thenReturn(Collections.singletonList(entry(Boolean.TRUE)));
        when(context.extensionEntries("child")).thenReturn(Collections.<SessionExtensionEntry>emptyList());
        when(context.parentSessionId("child")).thenReturn("parent");

        // Then：子会话算开着——不这样，模型借 task 派个子代理就把「只看不改」绕过去了
        assertEquals(PlanState.Switch.ON, new PlanState(context).switchOf("child"));
    }

    @Test
    @DisplayName("沿父链判定：链上都没有才算是关着")
    void switchOf_should_beOff_whenNoAncestorEnabled() {
        // Given：父会话也没有条目，再往上没有父
        PluginContext context = context();
        when(context.extensionEntries("parent")).thenReturn(Collections.<SessionExtensionEntry>emptyList());
        when(context.extensionEntries("child")).thenReturn(Collections.<SessionExtensionEntry>emptyList());
        when(context.parentSessionId("child")).thenReturn("parent");
        when(context.parentSessionId("parent")).thenReturn(null);

        assertEquals(PlanState.Switch.OFF, new PlanState(context).switchOf("child"));
    }

    @Test
    @DisplayName("沿父链判定：链上有一层读不出来时整条链读不出来")
    void switchOf_should_beUnknown_whenAncestorIsUnreadable() {
        // Given：本层与父层都读不出来（父层可能是开着的那一层）
        PluginContext context = context();
        when(context.extensionEntries("child")).thenReturn(Collections.<SessionExtensionEntry>emptyList());
        when(context.parentSessionId("child")).thenReturn("parent");
        when(context.extensionEntries("parent")).thenThrow(new JellyfishException("boom"));

        // Then：不能报「关」——那等于把父层可能的开启一笔勾销
        assertEquals(PlanState.Switch.UNKNOWN, new PlanState(context).switchOf("child"));
    }

    @Test
    @DisplayName("沿父链判定：本会话自己开着时不必往上问")
    void switchOf_should_notWalkUp_whenOwnEntryIsEnabled() {
        PluginContext context = context();
        when(context.extensionEntries("child")).thenReturn(Collections.singletonList(entry(Boolean.TRUE)));

        assertEquals(PlanState.Switch.ON, new PlanState(context).switchOf("child"));
        // 自己那一条就够，不该去问父链——那是一次多余的查询
        verify(context, never()).parentSessionId("child");
    }

    @Test
    @DisplayName("父链成环也不会转不出来：深度有上限，超出即按「读不出来」处理")
    void switchOf_should_beUnknown_whenChainLoops() {
        // Given：一个自己指自己的链（实际数据不会这样，但查询不该依赖那个假设）
        PluginContext context = context();
        when(context.extensionEntries("s-1")).thenReturn(Collections.<SessionExtensionEntry>emptyList());
        when(context.parentSessionId("s-1")).thenReturn("s-1");

        // Then：能返回（不挂住），且按「读不出来」处理——查不全就不放宽任何东西
        assertEquals(PlanState.Switch.UNKNOWN, new PlanState(context).switchOf("s-1"));
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
     * 构造一个只回答身份、父会话与扩展条目的插件上下文。
     *
     * @return 插件上下文
     */
    private static PluginContext context() {
        PluginContext context = Mockito.mock(PluginContext.class);
        lenient().when(context.pluginId()).thenReturn(PLUGIN_ID);
        // 默认不提供父会话：绝大多数用例关心的是「本会话」那条路径
        lenient().when(context.parentSessionId(any())).thenReturn(null);
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
