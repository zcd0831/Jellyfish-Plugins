package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link PlanPermission} 的单元测试：钉住「开着 plan 时白名单外一律拒绝、白名单内原样放行」。
 * <p>
 * 三条语义各有一个用例：关闭时不表态（核心策略的结论原样生效）、白名单内不表态（别的策略还能继续收窄）、
 * 白名单外拒绝并给出可读理由（理由会经工具结果回灌给模型）。
 *
 * @author zcd
 */
@DisplayName("plan 权限拦截")
class PlanPermissionTest {

    /** 本插件的标识，与 plugin.properties 一致。 */
    private static final String PLUGIN_ID = "jellyfish-plan";

    /** 会话标识。 */
    private static final String SESSION_ID = "s-1";

    @Test
    @DisplayName("plan 关闭时不表态：判定结论仍由核心策略与其它插件给出")
    void handle_should_abstain_whenPlanDisabled() {
        PlanPermission permission = permission(false, "read_file");

        assertTrue(permission.handle(request("write_file")).isAbstain());
    }

    @Test
    @DisplayName("白名单内的工具不表态：本插件只负责收窄，不替别的策略下结论")
    void handle_should_abstain_whenToolIsWhitelisted() {
        PlanPermission permission = permission(true, "read_file");

        assertTrue(permission.handle(request("read_file")).isAbstain());
    }

    @Test
    @DisplayName("白名单外的工具直接拒绝，理由里带上白名单与「去哪儿声明」")
    void handle_should_deny_whenToolOutsideWhitelist() {
        PlanPermission permission = permission(true, "read_file", "list_dir");

        PermissionVerdict verdict = permission.handle(request("write_file"));

        assertTrue(verdict.isDenied());
        assertTrue(verdict.getReason().contains("read_file, list_dir"), verdict.getReason());
        assertTrue(verdict.getReason().contains("readOnlyTools"), verdict.getReason());
        assertFalse(verdict.isAsk());
    }

    @Test
    @DisplayName("白名单为空时也拒绝（白名单语义：「没表态」与「不准」是同一件事）")
    void handle_should_denyEveryTool_whenWhitelistEmpty() {
        PlanPermission permission = permission(true);

        PermissionVerdict verdict = permission.handle(request("read_file"));

        assertTrue(verdict.isDenied());
        assertTrue(verdict.getReason().contains("白名单为空"), verdict.getReason());
    }

    @Test
    @DisplayName("没有会话标识时不表态：没有会话就没有开关，不该拦下进程级判定")
    void handle_should_abstain_whenSessionMissing() {
        PlanPermission permission = permission(true, "read_file");

        PermissionVerdict verdict = permission.handle(
                new PermissionCheckRequest("coder", "write_file", null));

        assertTrue(verdict.isAbstain());
    }

    /**
     * 构造拦截处理器。
     *
     * @param enabled        会话是否开着 plan
     * @param readOnlyTools  用户声明的白名单
     * @return 处理器
     */
    private static PlanPermission permission(boolean enabled, String... readOnlyTools) {
        PluginContext context = Mockito.mock(PluginContext.class);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        List<SessionExtensionEntry> entries = enabled
                ? Collections.singletonList(entry())
                : Collections.<SessionExtensionEntry>emptyList();
        when(context.extensionEntries(SESSION_ID)).thenReturn(entries);
        return new PlanPermission(new PlanState(context), config(readOnlyTools));
    }

    /**
     * 构造带白名单的配置。
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

    /**
     * 构造一次会话内的工具调用权限检查请求。
     *
     * @param toolName 工具名
     * @return 请求
     */
    private static PermissionCheckRequest request(String toolName) {
        return new PermissionCheckRequest("coder", toolName, null, SESSION_ID);
    }
}
