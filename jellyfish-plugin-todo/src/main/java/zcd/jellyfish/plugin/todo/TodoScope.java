package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 协作键：这次调用该读写哪一份待办。
 * <p>
 * <b>为什么需要它</b>：子代理有<b>自己的会话</b>（内核为每个 run 建一个临时会话），
 * 而待办是按会话归属的。于是子代理若直接拿 {@code getSessionId()} 去写，就会写到<b>自己那一份</b>里——
 * 父回合看不见它、它也看不见父回合的计划，盘上还会多出一个只属于那个 run 的文件。
 * 「共享一份工作列表」这件事因此卡在一个键上。
 * <p>
 * <b>规则只有一行：键是「归属会话」</b>——由内核按会话种类算出来
 * （{@link PluginContext#ownerSessionId(String)}：沿父链只穿临时会话，遇到用户会话就停）。
 * 三个容易写错的地方就此都不必由本插件操心：
 * <ul>
 *     <li><b>根会话</b>归自己，与改造前一致；</li>
 *     <li><b>子代理会话</b>归派它的用户会话，因此父子读写同一份清单。委派可以嵌套
 *     （{@code maxDepth ≥ 2}），所以<b>不能拿「直接父会话」当键</b>——那会落在中间那层临时会话上，
 *     待办于是分裂成好几份，而根面板一份都看不见；</li>
 *     <li><b>分支会话</b>（用户自己分出来的）归自己。<b>它同样带着父标识</b>，因此拿父标识当键会把
 *     分支会话的待办写进源会话——这条歧义正是 {@code SessionKind} 存在的理由，
 *     也是这里必须问内核要「归属」而不是自己看「有没有父」的原因。</li>
 * </ul>
 * <p>
 * 键来自内核交给工具的调用期设施，<b>不是模型能填的参数</b>——模型无法指定「写哪个会话」，
 * 因此这里不需要任何校验：写偏的入口根本不存在。
 * <p>
 * <b>为什么不给其他扩展点也套这一层</b>：面板、状态栏与命令拿到的都是<b>用户所在的那个会话</b>，
 * 不存在「子代理会话」这一说；给它们套一层只会让「键是什么」多出一个分支，
 * 而它们每帧都被问一次。
 *
 * @author zcd
 */
final class TodoScope {

    /** 插件上下文：归属会话的唯一来源。 */
    private final PluginContext context;

    /**
     * 构造协作键解析。
     *
     * @param context 插件上下文，不可为 {@code null}
     */
    TodoScope(PluginContext context) {
        this.context = context;
    }

    /**
     * 取这次工具调用该读写的待办归属。
     *
     * @param request 工具调用请求，不可为 {@code null}
     * @return 协作键；没有会话上下文时返回 {@code null}
     */
    String collaborationKeyOf(ToolCallRequest request) {
        return collaborationKeyOf(request.getSessionId());
    }

    /**
     * 取某个会话该读写的待办归属。
     * <p>
     * 回合上下文那条路径用它：嵌套回合的请求带的是子代理自己的会话标识，而它要送达的是
     * <b>用户那一份</b>清单——不看父会话的话，子代理每轮看到的都是一份空清单，
     * 而它写进去的活却在父回合那份里。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 协作键；没有会话上下文时返回 {@code null}
     */
    String collaborationKeyOf(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        String owner = context.ownerSessionId(sessionId);
        return owner == null || owner.trim().isEmpty() ? null : owner;
    }
}
