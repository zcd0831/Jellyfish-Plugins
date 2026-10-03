package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanCommand} 的单元测试：钉住开关的三种形态（查询、开启、关闭）与参数校验。
 *
 * @author zcd
 */
@DisplayName("plan 命令")
class PlanCommandTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plan";

    /** 会话标识。 */
    private static final String SESSION_ID = "s-1";

    @Test
    @DisplayName("无参时报告当前状态并给出 on / off 候选")
    void handle_should_reportCurrentState_whenNoArgument() {
        CommandResult result = new PlanCommand(state(false)).handle(command(new String[0]));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("已关闭"), result.getOutput());
        assertEquals(2, result.getChoices().size());
        assertTrue(result.getChoices().get(1).isCurrent());
    }

    @Test
    @DisplayName("无参且已开启时把当前候选标在 on 上")
    void handle_should_markOnAsCurrent_whenEnabled() {
        CommandResult result = new PlanCommand(state(true)).handle(command(new String[0]));

        assertTrue(result.getOutput().contains("已开启"), result.getOutput());
        assertTrue(result.getChoices().get(0).isCurrent());
    }

    @Test
    @DisplayName("on 写入开关并回报结果")
    void handle_should_enablePlan_whenOnGiven() {
        PluginContext context = context();
        CommandResult result = new PlanCommand(new PlanState(context)).handle(command("on"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("已开启"), result.getOutput());
        verify(context).putExtensionEntry(eq(SESSION_ID), eq(PlanState.KEY_ENABLED), any());
    }

    @Test
    @DisplayName("off 写入关闭值：条目仍在，只是值为 false")
    void handle_should_disablePlan_whenOffGiven() {
        PluginContext context = context();
        new PlanCommand(new PlanState(context)).handle(command("off"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(context).putExtensionEntry(eq(SESSION_ID), eq(PlanState.KEY_ENABLED), captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().get("enabled"));
    }

    @Test
    @DisplayName("取值大小写不敏感：/plan ON 与 /plan on 等价")
    void handle_should_ignoreCase_whenValueGiven() {
        PluginContext context = context();

        CommandResult result = new PlanCommand(new PlanState(context)).handle(command("ON"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
    }

    @Test
    @DisplayName("非法取值报用法错误，且不写开关")
    void handle_should_reportUsage_whenValueIllegal() {
        PluginContext context = context();

        CommandResult result = new PlanCommand(new PlanState(context)).handle(command("yolo"));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/plan [on|off]"), result.getOutput());
        verify(context, never()).putExtensionEntry(any(), any(), any());
    }

    @Test
    @DisplayName("参数过多报用法错误")
    void handle_should_reportUsage_whenTooManyArguments() {
        CommandResult result = new PlanCommand(state(false)).handle(command("on", "extra"));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
    }

    @Test
    @DisplayName("没有会话时给出可读提示，而不是去写一个不存在的会话")
    void handle_should_reportError_whenSessionMissing() {
        PluginContext context = Mockito.mock(PluginContext.class);
        when(context.pluginId()).thenReturn(PLUGIN_ID);

        CommandResult result = new PlanCommand(new PlanState(context)).handle(
                new CommandRequest(PlanCommand.COMMAND_NAME, CommandArguments.EMPTY));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/new"), result.getOutput());
        verify(context, never()).putExtensionEntry(any(), any(), any());
    }

    /**
     * 构造会话内命令请求。
     *
     * @param tokens 参数
     * @return 命令请求
     */
    private static CommandRequest command(String... tokens) {
        return new CommandRequest(PlanCommand.COMMAND_NAME,
                new CommandArguments(Arrays.asList(tokens), String.join(" ", tokens)), SESSION_ID);
    }

    /**
     * 构造开关存取：会话未写过条目时即为关闭。
     *
     * @param enabled 是否已开启
     * @return 开关存取
     */
    private static PlanState state(boolean enabled) {
        PluginContext context = context();
        when(context.extensionEntries(SESSION_ID)).thenReturn(enabled
                ? Collections.singletonList(entry())
                : Collections.<SessionExtensionEntry>emptyList());
        return new PlanState(context);
    }

    /**
     * 构造一个只回答身份与条目的插件上下文。
     *
     * @return 插件上下文
     */
    private static PluginContext context() {
        PluginContext context = Mockito.mock(PluginContext.class);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        return context;
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
