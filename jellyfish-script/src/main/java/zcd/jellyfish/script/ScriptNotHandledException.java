package zcd.jellyfish.script;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本这次「不表态」：有意没有给出结果，调用点应当走保守缺省。
 * <p>
 * <b>为什么它必须与「返回空结果」分开</b>：{@link ScriptCaller#call} 的返回值里 {@code null} 的
 * 契约含义是「脚本没有结果载荷」——那是一类<b>成功</b>（会话落盘、删除这类扩展点本来就没有返回值）。
 * 而「不表态」是另一回事：脚本侧根本没有去办这件事（热路径点在网关没热着时刻意不冷启动，
 * 见 {@code HotPathPoints}）。两者都表现为 {@code null} 时，熔断器就只能把「没打的电话」
 * 记成「打成了」：正常态下它会清零连续失败计数，半开态下它会把一次探测判成「已恢复」——
 * 前者让「失败—不表态—失败」交替的脚本永远到不了阈值，后者让坏脚本看起来已经好了。
 * <p>
 * <b>它是「无事可说」而不是「出错了」</b>：处理器在出口处把它翻回 {@code null}，
 * 这正是内核给处理器约定的「本插件不表态」。因此它<b>不计入熔断</b>（与
 * {@code ScriptCancelledException} 同一条理由：那不是脚本的毛病），也不该被当成失败上报给模型。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class ScriptNotHandledException extends JellyfishException {

    /**
     * 序列化版本号。
     * <p>
     * 脚本调用链上没有把异常跨进程序列化的路径，因此它眼下只满足可序列化类的规范。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 错误描述
     */
    public ScriptNotHandledException(String message) {
        super(message);
    }
}
