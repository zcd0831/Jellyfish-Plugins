package zcd.jellyfish.plugin.todo;

import org.mockito.Mockito;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.Map;

/**
 * 测试用的协作键解析：把「归属会话」这件事从用例里显式说出来。
 * <p>
 * <b>为什么用桩而不是真会话域</b>：归属规则（沿父链只穿临时会话、分支会话归自己）住在内核，
 * 插件侧只该验证「我确实问了内核、并按它的回答读写」——真去造一棵会话树会把这条链
 * 从「插件有没有用对」变成「内核算得对不对」，而后者由内核自己的用例守着。
 * <p>
 * {@link #self()} 是绝大多数用例需要的：会话归自己，等价于「没有委派」的常见场景。
 * 需要表达「子代理写的是父回合那份清单」时用 {@link #of(Map)}，把子代理会话映到用户会话上。
 *
 * @author zcd
 */
final class TodoTestScope {

    /**
     * 工具类，禁止实例化。
     */
    private TodoTestScope() {
    }

    /**
     * 构造「每个会话都归自己」的解析。
     *
     * @return 协作键解析
     */
    static TodoScope self() {
        return of(Collections.<String, String>emptyMap());
    }

    /**
     * 构造带归属映射的解析。
     *
     * @param owners 会话标识 → 归属会话标识；未列出的会话归自己
     * @return 协作键解析
     */
    static TodoScope of(Map<String, String> owners) {
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.ownerSessionId(Mockito.anyString())).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            String owner = owners.get(sessionId);
            return owner == null ? sessionId : owner;
        });
        return new TodoScope(context);
    }

    /**
     * 构造「一个子代理会话归某个用户会话」的解析。
     * <p>
     * 单层委派下这就是内核会给的答案；多层委派（穿过多层临时会话）由内核自己的用例守。
     *
     * @param sessionId 子代理会话标识
     * @param ownerId   归属会话标识
     * @return 协作键解析
     */
    static TodoScope nested(String sessionId, String ownerId) {
        return of(Collections.singletonMap(sessionId, ownerId));
    }
}
