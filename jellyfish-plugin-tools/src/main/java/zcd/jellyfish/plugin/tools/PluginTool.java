package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 插件内单个工具的契约：把「工具名片 + 处理器」绑成一个可注册对象。
 * <p>
 * 存在的理由与内核的设计同源：{@code PluginContext.handle} 要求描述符与处理器<b>一起</b>落表，
 * 两者本就是同一件事的两面。把这条绑定收进一个接口，插件侧就只需遍历工具清单，
 * 不必在每次注册时重复「名字写两遍」——名片里的名字即路由键，避免两处不一致。
 * <p>
 * 实现类必须无状态或自行保证线程安全：工具在 ReAct 执行线程上被内联调用，
 * 同一个实例会被反复使用。
 *
 * @author zcd
 */
public interface PluginTool extends ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /**
     * 获取工具名片。
     *
     * @return 工具描述符，不可为 {@code null}
     */
    ToolDescriptor descriptor();

    /**
     * 获取工具名，即注册与调用的路由键。
     *
     * @return 工具名
     */
    default String name() {
        return descriptor().getName();
    }

    /**
     * 获取本工具对文件路径的访问性质。
     * <p>
     * <b>为什么由工具自己声明</b>：闸门要按「工具名 → 读/写」来判，而这份映射若在闸门里另列一份清单，
     * 就会与工具实现漂移——漂移的方向恰恰是闸门被绕过。声明放在工具类里，改名时编译器会拦住。
     * <p>
     * 缺省是 {@link PathAccess#NONE}（不碰路径），因此只有真正读写文件的工具需要覆写。
     *
     * @return 访问性质，保证非 {@code null}
     */
    default PathAccess pathAccess() {
        return PathAccess.NONE;
    }

    /**
     * 把自己注册进插件上下文。
     *
     * @param context 插件上下文，不可为 {@code null}
     * @return 注册句柄，插件卸载时由框架统一回收，通常无需自行持有
     */
    default Subscription register(PluginContext context) {
        return context.handle(ToolCallRequest.class, name(), descriptor(), this);
    }
}
