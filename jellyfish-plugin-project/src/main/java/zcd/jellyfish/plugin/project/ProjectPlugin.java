package zcd.jellyfish.plugin.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方项目约定插件：让模型知道工作目录下的 {@code AGENTS.md}，小文件直接给原文、大文件给路径。
 * <p>
 * <b>一个插件只占三个扩展点</b>：{@link PromptContributionRequest}（提示词贡献）、
 * {@link CommandRequest}（{@code /init} 命令）与 {@link SessionClosedEvent}（订阅会话结束，
 * 用于丢弃该会话的缓存）。没有工具、没有界面贡献——
 * 「读文件」这件事已经有 {@code jellyfish-plugin-tools} 的 {@code read_file} 了，本插件只负责
 * <b>决定把什么交给模型</b>，以及 {@code /init} 时<b>请模型写出那份文件</b>。
 * <p>
 * <b>为什么是插件而不是内置提示词</b>：项目约定属于「项目」而不属于「某个 agent」。
 * 写进 {@code jellyfish.md} 只能覆盖内置默认 agent，用户一旦用 {@code /agent} 换成自定义 agent 就失效；
 * 走插件则对所有 agent 生效，内核也不必开始处理「读工作目录里的文件」这类它本不该管的事。
 * <p>
 * <b>{@code /init} 为什么返回值是「接力文本」而不是自己写文件</b>：命令处理器碰不到模型、也起不了回合，
 * 只能声明「请把这段指令当成我这一句输入接着跑」（{@code CommandResult.handoff}）。于是模型据指令
 * 去读仓库、去写文件，而写入照旧走工具那条路、照旧过权限与审批——插件直接落盘等于绕过内核唯一的权限收口。
 * <p>
 * <b>配置段可选</b>：{@code plugins.configurations.jellyfish-plugin-project} 只有一项
 * {@link PluginConfig#KEY_MAX_INLINE_BYTES}；整段留空则用缺省的 32 KiB。
 * <p>
 * <b>查找基准固定为进程工作目录</b>，因此须从仓库根目录启动——{@code AGENTS.md} 的行业位置是仓库根。
 *
 * @author zcd
 */
public final class ProjectPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ProjectPlugin.class);

    @Override
    public void start(PluginContext context) {
        // 先装配再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        PluginConfig config = PluginConfig.of(context);
        ConventionFiles files = ConventionFiles.ofWorkingDirectory();
        ProjectPromptContribution contribution =
                new ProjectPromptContribution(files, config, new ContributionCache());
        context.contribute(PromptContributionRequest.class, contribution);
        // 会话结束即丢弃该会话的缓存：close() 与 delete() 都会广播这个事件，
        // 「一次会话读一次盘」的语义靠它闭环（缓存自身另有条数上限兜底）
        context.observe(SessionClosedEvent.class,
                event -> contribution.evict(event.getSessionId()));
        // 末尾显式声明 sessionRequired=true：接力要起回合，没有会话就没有可起的回合
        context.handle(CommandRequest.class, InitCommand.COMMAND_NAME,
                new CommandDescriptor("初始化项目约定 AGENTS.md（让模型读仓库后写出）", "[--force]", null, true),
                new InitCommand(files));
        LOG.info("项目约定插件已启动: 查找基准={} maxInlineBytes={}", files.baseDirectory(),
                config.maxInlineBytes());
    }
}
