package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link PlanOptions} 的单元测试：候选只读、标出当前取值、首页也能回答。
 *
 * @author zcd
 */
@DisplayName("plan 候选查询")
class PlanOptionsTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plan";

    /** 会话标识。 */
    private static final String SESSION_ID = "s-1";

    @Test
    @DisplayName("候选为 on / off，并把当前那个标出来")
    void handle_should_markCurrentChoice() {
        CommandOptions options = new PlanOptions(state(true)).handle(new CommandOptionRequest("plan", SESSION_ID));

        List<CommandChoice> choices = options.getChoices();
        assertEquals(2, choices.size());
        assertEquals(PlanCommand.VALUE_ON, choices.get(0).getValue());
        assertTrue(choices.get(0).isCurrent());
        assertEquals(PlanCommand.VALUE_OFF, choices.get(1).getValue());
    }

    @Test
    @DisplayName("关闭时 on 不是当前项")
    void handle_should_notMarkOn_whenDisabled() {
        CommandOptions options = new PlanOptions(state(false)).handle(new CommandOptionRequest("plan", SESSION_ID));

        assertTrue(options.getChoices().get(1).isCurrent());
    }

    @Test
    @DisplayName("没有会话（首页）时按关闭给出候选：总比回一片空白有用")
    void handle_should_offerChoices_whenNoSession() {
        CommandOptions options = new PlanOptions(state(false)).handle(new CommandOptionRequest("plan"));

        assertEquals(2, options.getChoices().size());
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
        when(context.extensionEntries(SESSION_ID)).thenReturn(enabled
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
