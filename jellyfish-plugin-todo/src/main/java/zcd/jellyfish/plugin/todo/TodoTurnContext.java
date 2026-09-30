package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;

/**
 * 回合上下文：把当前会话的待办随<b>本轮用户消息</b>一起送达。
 * <p>
 * <b>为什么不是在 system prompt 里</b>：待办是模型在会话中途反复改写的状态，而 system prompt 是
 * 缓存前缀的第 0 个 token——把它放进去，意味着模型每勾掉一件事，<b>整个请求（连同全部历史）</b>
 * 都要从第 0 个 token 起重新计费一次。改成随用户消息落盘之后它是 append-only 的：
 * 只影响本轮新产生的那几个 token，已经发送过的内容一个字节都不动。
 * <p>
 * <b>没有待办时返回空结果</b>：本处理器每轮都会被问到，而多数轮次并没有待办要送——
 * {@link TurnContext#empty()} 正是这个扩展点为「无事可说」留的表达，内核不会为它追加任何东西，
 * 用户消息原样下发。
 * <p>
 * <b>刻意不做「状态没变就不送」的去重</b>：那看起来更省，却引入一个难查的失败模式——上一次送的
 * 内容可能已经落进被压缩掉的那一段，于是模型从此再也看不到自己的计划，表现是「它突然忘了自己
 * 在做什么」，而日志里什么都看不出来。而待办块只有几行、又落在已缓存的前缀之后，
 * 重复送的代价远小于那个失败模式。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoTurnContext implements ExtensionHandler<TurnContextRequest, TurnContext> {

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造处理器。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoTurnContext(TodoStore store) {
        this.store = store;
    }

    @Override
    public TurnContext handle(TurnContextRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return TurnContext.empty();
        }
        return TurnContext.of(TodoText.promptBlock(store.itemsOf(sessionId)));
    }
}
