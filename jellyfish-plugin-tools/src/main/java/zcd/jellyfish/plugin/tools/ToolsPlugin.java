package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.extension.InputReferenceDescriptor;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 官方工具插件：注册文件读写、目录列举、文本搜索这几个基础工具，以及向用户提问的工具。
 * <p>
 * <b>插件类只做一件事——把工具清单交给上下文</b>：注册窗口只在 {@code start} 内，
 * 之后框架按 {@code pluginId} 批量回收，因此这里不需要持有注册句柄，也不实现 {@code stop}。
 * <p>
 * <b>为什么工具不做成内核自带</b>：插件侧只能注册回调，工具的实现与内核生命周期无关；
 * 放在插件里还顺带获得两样东西——{@code jellyfish.json} 的逐插件配置段（例如 plan 插件的
 * {@code plugins.configurations.jellyfish-plugin-plan.readOnlyTools} 可以声明哪些工具在它开启时可用，
 * 而工具自己无法自称只读），以及热部署能力。
 * <p>
 * 工具实例全部无状态（{@code ask_user} 只额外持有一条提问端口），因此插件可以安全地与其它插件并发调用。
 *
 * @author zcd
 */
public final class ToolsPlugin implements JellyfishPlugin {

    /** 本插件提供的文件工具，顺序即注册顺序，无副作用。 */
    private static final List<PluginTool> TOOLS = Collections.unmodifiableList(Arrays.<PluginTool>asList(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ListDirTool(),
            new GrepFilesTool()));

    @Override
    public void start(PluginContext context) {
        for (PluginTool tool : TOOLS) {
            // 名字即路由键，工具名全局唯一这一点由 handle 的同键唯一语义保证
            tool.register(context);
        }
        // 提问工具进不了上面那份静态清单：它需要一条出向边（把问题摆到用户面前并把答复带回来），
        // 而那条边只能从上下文取得。它是本插件里唯一持有外部依赖的工具
        new AskUserTool(context.askUser()).register(context);
        // 输入框的行内引用：@ 只做补全，真正的读取仍由模型走 read_file，因此没有绕过权限面
        context.handle(InputReferenceRequest.class, FileReferenceCompletion.MARKER,
                new InputReferenceDescriptor("引用工作目录下的文件（补全路径）"),
                new FileReferenceCompletion());
        // 约定必须让模型知道，否则 @a.txt 在它眼里只是一段普通文字
        context.contribute(PromptContributionRequest.class, new FileReferencePromptContribution());
    }
}
