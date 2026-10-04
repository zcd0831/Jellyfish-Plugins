package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link PlanStatusLine} 的单元测试：开启时追加一段，关闭时不占位。
 *
 * @author zcd
 */
@DisplayName("plan 状态栏片段")
class PlanStatusLineTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-plan";

    @Test
    @DisplayName("开启时给出片段文本")
    void handle_should_returnFragment_whenEnabled() {
        StatusLineContribution contribution = new PlanStatusLine(state(true))
                .handle(new StatusLineContributionRequest("s-1"));

        assertEquals("plan", contribution.getText());
    }

    @Test
    @DisplayName("关闭时返回空贡献：常态不值得占一列")
    void handle_should_returnEmpty_whenDisabled() {
        StatusLineContribution contribution = new PlanStatusLine(state(false))
                .handle(new StatusLineContributionRequest("s-1"));

        assertTrue(contribution.isEmpty());
    }

    @Test
    @DisplayName("没有会话时不显示")
    void handle_should_returnEmpty_whenNoSession() {
        StatusLineContribution contribution = new PlanStatusLine(state(false))
                .handle(new StatusLineContributionRequest(null));

        assertTrue(contribution.isEmpty());
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
