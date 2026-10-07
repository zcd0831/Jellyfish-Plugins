package zcd.jellyfish.script.protocol;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本调用被取消：宿主侧的 {@code CancellationToken} 触发了，在途调用被中止。
 * <p>
 * <b>为什么它必须与「脚本自己失败」分开</b>：取消是<b>用户主权</b>的表达（TUI 上按下 Esc），
 * 不是脚本出了问题。把它当成普通失败计入熔断，会出现「用户按了几次 Esc，某个脚本就被熔断冷却」
 * 这种荒谬的结果——而那正是「失败判据必须只有一处定义」要防的东西。
 * <p>
 * <b>为什么取消的动作是「杀掉 worker」而不是「给脚本一个标志」</b>：脚本进程是单线程的
 * （Python 阻塞在 HTTP 调用上、Node 的同步 handler 占着事件循环），一次在途调用期间它<b>读不到</b>
 * 任何新帧，因此一个可轮询的取消标志在模型上就不成立。唯一统一的语义是终止这次调用所在的进程，
 * 与超时路径走同一条「网关杀、宿主只发指令」的链路。代价是脚本拿不到取消通知——
 * 这是进程边界决定的，不是省事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class ScriptCancelledException extends JellyfishException {

    /**
     * 构造异常。
     *
     * @param message 错误描述
     */
    public ScriptCancelledException(String message) {
        super(message);
    }

    /**
     * 构造异常，保留原始失败原因。
     *
     * @param message 错误描述
     * @param cause   原始失败
     */
    public ScriptCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
