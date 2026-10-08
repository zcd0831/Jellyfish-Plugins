package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PathPolicy} 的单元测试。
 * <p>
 * 重点钉住三件事：<b>缺省只管写且只允许工作目录</b>（开箱行为）、<b>允许清单按与工具相同的规则解析</b>
 * （否则配置里写的 {@code ~} 与工具看到的不是同一个目录）、以及<b>配置写错只回退并告警、不让插件起不来</b>。
 * <p>
 * 「工作目录外」的样本统一用 {@code @TempDir}（它在进程工作目录之外），这样用例不依赖具体机器布局。
 *
 * @author zcd
 */
@DisplayName("PathPolicy 路径策略")
class PathPolicyTest {

    /** 临时目录，充当「工作目录之外」的样本。 */
    @TempDir
    Path outside;

    @Test
    @DisplayName("缺省：写工作目录内应放行")
    void verdict_should_abstain_when_writeInsideWorkingDirectory() {
        PathPolicy policy = PathPolicy.from(null);

        PermissionVerdict verdict = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", "target/some-file.txt"));

        assertEquals(PermissionVerdict.Outcome.ABSTAIN, verdict.getOutcome());
    }

    @Test
    @DisplayName("缺省：写工作目录外要人工审批，且理由里带上目标与允许范围")
    void verdict_should_ask_when_writeOutsideWorkingDirectory() {
        PathPolicy policy = PathPolicy.from(null);

        PermissionVerdict verdict = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", outside.resolve("x.txt").toString()));

        assertEquals(PermissionVerdict.Outcome.ASK, verdict.getOutcome());
        assertNotNull(verdict.getReason());
    }

    @Test
    @DisplayName("缺省：读不设限，工作目录外照常放行")
    void verdict_should_abstain_forReadOutsideWorkingDirectory() {
        PathPolicy policy = PathPolicy.from(null);

        PermissionVerdict verdict = policy.verdict(PathAccess.READ, "read_file",
                ToolTestSupport.args("path", outside.resolve("x.txt").toString()));

        // 读别的仓库、读内核回灌的工具结果都是常规需求，默认拦它会把正常用法一起挡掉
        assertEquals(PermissionVerdict.Outcome.ABSTAIN, verdict.getOutcome());
    }

    @Test
    @DisplayName("路径参数缺失或不是字符串时无异议：那是工具该报的参数错，不是权限问题")
    void verdict_should_abstain_when_pathArgumentMissingOrNotText() {
        PathPolicy policy = PathPolicy.from(null);

        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                policy.verdict(PathAccess.WRITE, "write_file", Collections.<String, Object>emptyMap())
                        .getOutcome());
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                policy.verdict(PathAccess.WRITE, "write_file", ToolTestSupport.args("path", 42)).getOutcome());
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                policy.verdict(PathAccess.WRITE, "write_file", null).getOutcome());
    }

    @Test
    @DisplayName("空白路径按缺省值 . 处理，即工作目录内")
    void verdict_should_treatBlankPathAsWorkingDirectory() {
        PathPolicy policy = PathPolicy.from(null);

        PermissionVerdict verdict = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", "   "));

        assertEquals(PermissionVerdict.Outcome.ABSTAIN, verdict.getOutcome());
    }

    @Test
    @DisplayName("配了 allow 之后：清单内放行、清单外按 outside 处理")
    void verdict_should_honourConfiguredAllowList() {
        PathPolicy policy = PathPolicy.from(contextWith(rule("write", outside.toString(), "deny")));

        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                policy.verdict(PathAccess.WRITE, "write_file",
                        ToolTestSupport.args("path", outside.resolve("in.txt").toString())).getOutcome());
        assertEquals(PermissionVerdict.Outcome.DENY,
                policy.verdict(PathAccess.WRITE, "write_file",
                        ToolTestSupport.args("path", "target/out.txt")).getOutcome());
    }

    @Test
    @DisplayName("outside 配 allow 等于把该方向的门关掉")
    void verdict_should_abstain_when_outsideIsAllow() {
        PathPolicy policy = PathPolicy.from(contextWith(rule("write", ".", "allow")));

        PermissionVerdict verdict = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", outside.resolve("x.txt").toString()));

        assertEquals(PermissionVerdict.Outcome.ABSTAIN, verdict.getOutcome());
    }

    @Test
    @DisplayName("read 段不配就不设限，配上就按配置生效")
    void verdict_should_onlyApplyReadPolicy_when_readSectionConfigured() {
        PathPolicy off = PathPolicy.from(contextWith(rule("write", ".", "ask")));
        PathPolicy on = PathPolicy.from(contextWith(rule("read", ".", "deny")));

        String outsideFile = outside.resolve("x.txt").toString();
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                off.verdict(PathAccess.READ, "read_file", ToolTestSupport.args("path", outsideFile))
                        .getOutcome());
        assertEquals(PermissionVerdict.Outcome.DENY,
                on.verdict(PathAccess.READ, "read_file", ToolTestSupport.args("path", outsideFile))
                        .getOutcome());
    }

    @Test
    @DisplayName("allow 里的路径按与工具相同的规则解析：~ 展开成主目录")
    void verdict_should_expandTildeInAllowList() {
        String home = System.getProperty("user.home");
        PathPolicy policy = PathPolicy.from(contextWith(rule("write", "~/.jellyfish-tests", "deny")));

        PermissionVerdict inside = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", home + "/.jellyfish-tests/a.txt"));
        PermissionVerdict notInside = policy.verdict(PathAccess.WRITE, "write_file",
                ToolTestSupport.args("path", outside.resolve("a.txt").toString()));

        assertEquals(PermissionVerdict.Outcome.ABSTAIN, inside.getOutcome());
        assertEquals(PermissionVerdict.Outcome.DENY, notInside.getOutcome());
    }

    @Test
    @DisplayName("配置写错应回退缺省并告警，而不是抛异常")
    void from_should_fallBackAndWarn_when_configIsMalformed() {
        PluginContext context = contextWith(rule("write", "不是数组", "whatever"));

        PathPolicy policy = PathPolicy.from(context);

        // 回退到缺省：工作目录外仍是「要审批」，没有被配成 allow 而放行
        assertEquals(PermissionVerdict.Outcome.ASK,
                policy.verdict(PathAccess.WRITE, "write_file",
                        ToolTestSupport.args("path", outside.resolve("x.txt").toString())).getOutcome());
        verify(context).emit(any(ConfigWarningEvent.class));
    }

    @Test
    @DisplayName("pathPolicy 不是对象时应回退缺省并告警")
    void from_should_fallBackAndWarn_when_policyIsNotObject() {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("pathPolicy", "x");
        PluginContext context = contextWith(section);

        PathPolicy policy = PathPolicy.from(context);

        assertEquals(PermissionVerdict.Outcome.ASK,
                policy.verdict(PathAccess.WRITE, "write_file",
                        ToolTestSupport.args("path", outside.resolve("x.txt").toString())).getOutcome());
        verify(context).emit(any(ConfigWarningEvent.class));
    }

    @Test
    @DisplayName("不碰路径的工具一律无异议")
    void verdict_should_abstain_forToolWithoutPathAccess() {
        PathPolicy policy = PathPolicy.from(null);

        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                policy.verdict(PathAccess.NONE, "ask_user", ToolTestSupport.args("path", "/etc/passwd"))
                        .getOutcome());
    }

    /**
     * 构造一个方向的配置段。
     *
     * @param direction  方向名（{@code read} 或 {@code write}）
     * @param allow      允许清单里的路径
     * @param outside    范围之外的裁定
     * @return 插件配置段
     */
    private static Map<String, Object> rule(String direction, String allow, String outside) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("allow", Collections.<Object>singletonList(allow));
        values.put("outside", outside);
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put(direction, values);
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("pathPolicy", policy);
        return section;
    }

    /**
     * 构造返回指定配置段的上下文桩。
     *
     * @param section 配置段
     * @return 上下文桩
     */
    private static PluginContext contextWith(Map<String, Object> section) {
        PluginContext context = mock(PluginContext.class);
        when(context.configuration()).thenReturn(section);
        return context;
    }
}
