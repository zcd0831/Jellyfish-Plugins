package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link PlanTurnContext} 的单元测试：开着 plan 时告诉模型「现在只能看不能改」，关闭时什么都不加。
 * <p>
 * 这条提示的价值在于省掉弯路：权限拦截只能让写操作失败，而模型看不到模式，
 * 于是它会一次次去调一个注定被拒的工具。
 *
 * @author zcd
 */
@DisplayName("plan 回合上下文")
class PlanTurnContextTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plan";

    @Test
    @DisplayName("开启时带上白名单与「怎么关掉」")
    void handle_should_describeModeAndWhitelist_whenEnabled() {
        TurnContext context = new PlanTurnContext(state(true), config("read_file", "list_dir"))
                .handle(new TurnContextRequest("s-1", "改一下配置", false));

        String text = context.getText();
        assertTrue(text.contains("plan"), text);
        assertTrue(text.contains("read_file, list_dir"), text);
        assertTrue(text.contains("/plan off"), text);
    }

    @Test
    @DisplayName("白名单为空时如实说明：所有工具都会被拒")
    void handle_should_explainEmptyWhitelist_whenEnabled() {
        TurnContext context = new PlanTurnContext(state(true), config())
                .handle(new TurnContextRequest("s-1", "改一下配置", false));

        assertTrue(context.getText().contains("未声明"), context.getText());
    }

    @Test
    @DisplayName("关闭时返回空结果：内核不会为它追加任何东西")
    void handle_should_returnEmpty_whenDisabled() {
        TurnContext context = new PlanTurnContext(state(false), config("read_file"))
                .handle(new TurnContextRequest("s-1", "改一下配置", false));

        assertTrue(context.isEmpty());
    }

    /**
     * 构造开关存取。
     *
     * @param enabled 是否已开启
     * @return 开关存取
     */
    private static PlanState state(boolean enabled) {
        PluginContext context = Mockito.mock(PluginContext.class);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        when(context.extensionEntries("s-1")).thenReturn(enabled
                ? Collections.singletonList(entry())
                : Collections.<SessionExtensionEntry>emptyList());
        return new PlanState(context);
    }

    /**
     * 构造白名单配置。
     *
     * @param readOnlyTools 白名单
     * @return 配置
     */
    private static PlanConfig config(String... readOnlyTools) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PlanConfig.KEY_READ_ONLY_TOOLS, Arrays.<Object>asList(readOnlyTools));
        return PlanConfig.from(values, PLUGIN_ID, null);
    }

    /**
     * 构造一条「已开启」的扩展条目。
     *
     * @return 扩展条目
     */
    private static SessionExtensionEntry entry() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("enabled", Boolean.TRUE);
        return new SessionExtensionEntry(PLUGIN_ID + "::" + PlanState.KEY_ENABLED, value, 0L);
    }
}
