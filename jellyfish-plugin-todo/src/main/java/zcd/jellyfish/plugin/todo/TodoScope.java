package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ToolCallRequest;

/**
 * 协作键：这次调用该读写哪一份待办。
 * <p>
 * <b>为什么需要它</b>：子代理有<b>自己的会话</b>（内核为每个 run 建一个临时会话），
 * 而待办是按会话归属的。于是子代理若直接拿 {@code getSessionId()} 去写，就会写到<b>自己那一份</b>里——
 * 父回合看不见它、它也看不见父回合的计划，盘上还会多出一个只属于那个 run 的文件。
 * 「共享一份工作列表」这件事因此卡在一个键上。
 * <p>
 * <b>规则只有一行：子代理落在父会话上，根会话落在自己身上。</b>
 * 键来自内核交给工具的调用期设施（{@code parentSessionId}），<b>不是模型能填的参数</b>——
 * 模型无法指定「写哪个会话」，因此这里不需要任何校验：写偏的入口根本不存在。
 * <p>
 * <b>为什么不给其他扩展点也套这一层</b>：面板、状态栏、回合上下文与命令拿到的都是<b>用户所在的那个会话</b>
 * （根会话），不存在「子代理会话」这一说；给它们套一层只会让「键是什么」多出一个分支。
 *
 * @author zcd
 */
final class TodoScope {

    /**
     * 工具私有构造器：本类是静态方法的容器。
     */
    private TodoScope() {
    }

    /**
     * 取这次工具调用该读写的待办归属。
     *
     * @param request 工具调用请求，不可为 {@code null}
     * @return 协作键；没有会话上下文时返回 {@code null}
     */
    static String collaborationKeyOf(ToolCallRequest request) {
        String parent = request.getParentSessionId();
        if (parent != null && !parent.trim().isEmpty()) {
            return parent;
        }
        String sessionId = request.getSessionId();
        return sessionId == null || sessionId.trim().isEmpty() ? null : sessionId;
    }
}
