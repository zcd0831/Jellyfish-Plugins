package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PathGuardContribution} 的单元测试。
 * <p>
 * 权限扩展点是<b>类型级</b>的，内核会把每一次工具调用都送进来，因此最要紧的一条是「认领」：
 * 不属于本插件的工具名必须一律无异议，不能替别人的工具做判断。
 *
 * @author zcd
 */
@DisplayName("PathGuardContribution 路径闸门")
class PathGuardContributionTest {

    /** 临时目录，充当「工作目录之外」的样本。 */
    @TempDir
    Path outside;

    /** 被测闸门，缺省策略（写限工作目录、读不设限）。 */
    private final PathGuardContribution guard = new PathGuardContribution(PathPolicy.from(null), accessByTool());

    @Test
    @DisplayName("别家的工具名一律无异议：类型级扩展点会把别人的调用也送进来")
    void handle_should_abstain_forForeignTool() {
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("mcp__fs__write", outside.resolve("x").toString())).getOutcome());
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("shell", outside.resolve("x").toString())).getOutcome());
    }

    @Test
    @DisplayName("不碰路径的工具无异议")
    void handle_should_abstain_forToolWithoutPathAccess() {
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("ask_user", outside.resolve("x").toString())).getOutcome());
    }

    @Test
    @DisplayName("写工具：目录内放行、目录外要审批")
    void handle_should_gateWriteTools() {
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("write_file", "target/x.txt")).getOutcome());
        assertEquals(PermissionVerdict.Outcome.ASK,
                guard.handle(request("write_file", outside.resolve("x.txt").toString())).getOutcome());
        assertEquals(PermissionVerdict.Outcome.ASK,
                guard.handle(request("edit_file", outside.resolve("x.txt").toString())).getOutcome());
    }

    @Test
    @DisplayName("读工具：缺省不设限，目录外照常放行")
    void handle_should_notGateReadToolsByDefault() {
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("read_file", outside.resolve("x.txt").toString())).getOutcome());
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(request("grep_files", outside.resolve("x.txt").toString())).getOutcome());
    }

    @Test
    @DisplayName("缺 path 参数的读工具按工作目录处理，因此放行")
    void handle_should_abstain_when_readToolOmitsPath() {
        assertEquals(PermissionVerdict.Outcome.ABSTAIN,
                guard.handle(new PermissionCheckRequest("default", "list_dir",
                        Collections.<String, Object>emptyMap(), "s1")).getOutcome());
    }

    /**
     * 构造工具名到访问性质的映射，与插件启动时现取的那份一致。
     *
     * @return 映射
     */
    private static Map<String, PathAccess> accessByTool() {
        Map<String, PathAccess> access = new LinkedHashMap<String, PathAccess>();
        access.put("read_file", PathAccess.READ);
        access.put("write_file", PathAccess.WRITE);
        access.put("edit_file", PathAccess.WRITE);
        access.put("list_dir", PathAccess.READ);
        access.put("grep_files", PathAccess.READ);
        access.put("ask_user", PathAccess.NONE);
        return access;
    }

    /**
     * 构造一次带 {@code path} 参数的权限检查请求。
     *
     * @param toolName 工具名
     * @param path     路径
     * @return 请求
     */
    private static PermissionCheckRequest request(String toolName, String path) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", path);
        return new PermissionCheckRequest("default", toolName, arguments, "s1");
    }
}
