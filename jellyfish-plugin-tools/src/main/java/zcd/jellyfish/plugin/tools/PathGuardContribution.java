package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 路径闸门：在权限扩展点上把「这个路径在不在允许范围内」表态成一次裁定。
 * <p>
 * <b>为什么在处理器里再查一次工具名</b>：{@code PermissionCheckRequest} 是<b>类型级</b>扩展点
 * （路由键恒为 {@code null}），内核会把<b>每一次</b>工具调用都送到这里——包括不属于本插件的工具。
 * 因此第一件事是认领：不在本表里的工具名一律「无异议」，绝不替别人的工具做判断。
 * <p>
 * <b>为什么键是工具名而不是访问性质</b>：{@link PathAccess} 只有三档，但工具名是唯一的；
 * 由工具名映射到档位，才能回答「这个具体工具这次要写的路径是什么」。映射在插件 {@code start()} 时
 * 从工具实例上取（见 {@link ToolsPlugin}），因此没有第二份需要同步维护的清单。
 * <p>
 * <b>只可能更严</b>：返回值是 {@link PermissionVerdict}，它没有「放行」这一态；本类最多把一次调用
 * 升级为人工审批或直接拒绝，核心策略给出的结论不受影响。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PathGuardContribution implements ExtensionHandler<PermissionCheckRequest, PermissionVerdict> {

    /** 路径策略。 */
    private final PathPolicy policy;

    /** 工具名 → 路径访问性质。 */
    private final Map<String, PathAccess> accessByTool;

    /**
     * 构造闸门。
     *
     * @param policy      路径策略，不可为 {@code null}
     * @param accessByTool 工具名到访问性质的映射，不可为 {@code null}
     */
    PathGuardContribution(PathPolicy policy, Map<String, PathAccess> accessByTool) {
        this.policy = policy;
        this.accessByTool = Collections.unmodifiableMap(new LinkedHashMap<String, PathAccess>(accessByTool));
    }

    @Override
    public PermissionVerdict handle(PermissionCheckRequest request) {
        PathAccess access = accessByTool.get(request.getToolName());
        if (access == null || access == PathAccess.NONE) {
            return PermissionVerdict.abstain();
        }
        return policy.verdict(access, request.getToolName(), request.getArguments());
    }
}
