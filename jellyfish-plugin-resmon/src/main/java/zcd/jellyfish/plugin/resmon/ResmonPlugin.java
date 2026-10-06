package zcd.jellyfish.plugin.resmon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方资源监控插件：把 JVM 与磁盘的占用变成「能随时看一眼」的东西，并且<b>只看不动</b>。
 * <p>
 * <b>它补的是什么空缺</b>：内核有一套指标（{@code MetricsRegistry}）与健康检查，但那些是内核自用的
 * 计数器——全仓没有一处读过 {@code ManagementFactory}，也没有任何地方统计目录体积；
 * 而内核的 {@code /status} 只报当前会话的模型、消息数与 token。于是「进程本身有多重、
 * 磁盘上的会话有没有在失控增长」这两件事此前完全不可见，而它们恰恰是长时间运行后最可能出问题的地方。
 * <p>
 * <b>三个面，两种粒度</b>：
 * <ul>
 *     <li>{@link PanelContributionRequest} 面板：8 行概览，落在侧栏，只在有交互界面的外壳里注册；</li>
 *     <li>{@link CommandRequest} {@code /resmon}：完整明细，三种外壳都能用，输出不被截断，
 *     也正好可以被复制给模型去评估「该清理什么」；</li>
 *     <li>{@link CommandOptionRequest} 候选：让外壳能弹出可选子命令。</li>
 * </ul>
 * <p>
 * <b>为什么必须是插件</b>：内核侧刻意不引第三方指标库、也不对外开 {@code /metrics}
 * （{@code docs/constraints.md} 的「诊断输出必须比被诊断对象更稳」一节）。而进程内的资源事实
 * <b>不需要</b>内核做任何改动：插件与内核同 JVM，{@code java.lang.management} 与 {@code java.nio}
 * 就能拿到全部数据，连一个第三方依赖都不必加。反过来，这也意味着它拿不到内核内部的计数器
 * （事件通道队列长度、react 池深度、在途子代理许可）——那些只有内核知道，本插件不去猜。
 * <p>
 * <b>唯一的已知短板</b>：磁盘统计的目录路径只能由本插件配置段给出（插件拿不到工作目录，
 * 也不允许自行读 {@code jellyfish.json}）。缺省值取的正是内核与官方插件都在用的约定路径；
 * 用户若改过那些配置，需要在 {@code plugins.configurations.jellyfish-plugin-resmon} 里对齐。
 * 这一点在 {@code /resmon disk} 的输出末尾也会主动写出来，不让用户把错的数字当权威。
 * <p>
 * <b>生命周期遵循既有纪律</b>：采样线程在 {@code start()} 里现造（PF4J 长期缓存插件实例，
 * 字段缓存会跨 {@code /reload} 残留），在 {@code stop()} 里先置停止标记再关线程池并等待它结束
 * ——后者是「插件停止后任何注册与投递都 fail-closed」这条契约对后台线程的要求。
 * <p>
 * 配置见 {@link PluginConfig}。
 *
 * @author zcd
 */
public final class ResmonPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ResmonPlugin.class);

    /** 运行中的采样器；未启动时为 {@code null}。 */
    private volatile ResmonSampler sampler;

    @Override
    public void start(PluginContext context) {
        PluginConfig config = PluginConfig.from(context.configuration());
        ResmonSampler created = new ResmonSampler(config, new ManagementJvmProbe(),
                new DirSizer(config.walkMaxDepth()), context);
        // 先让采样跑起来再注册：处理器一旦注册就可能被调用，那时快照必须已经有一份
        created.start();
        sampler = created;
        try {
            register(context, config, created);
        } catch (RuntimeException e) {
            // start() 抛错时框架不保证调用 stop()，因此半途申请到的资源必须在这里自己收掉，
            // 否则采样线程会在插件已经 FAILED 之后继续跑
            created.close();
            sampler = null;
            throw e;
        }
        LOG.info("资源监控插件已启动: panel={}, sessionsDir={}, toolOutputsDir={}",
                config.panel() && context.runtimeInfo().hasUI(),
                config.sessionsDir(), config.toolOutputsDir());
    }

    @Override
    public void stop() {
        ResmonSampler current = sampler;
        sampler = null;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 注册命令、候选与面板。
     *
     * @param context 插件上下文，不可为 {@code null}
     * @param config  插件配置，不可为 {@code null}
     * @param sampler 采样器，不可为 {@code null}
     */
    private static void register(PluginContext context, PluginConfig config, ResmonSampler sampler) {
        // sessionRequired=false：JVM 与磁盘都是进程级事实，与当前会话无关。这样 -cli 单次调用
        // 与还没有会话的首页也能用它——而那正是「发现磁盘满了」最可能发生的时刻。
        context.handle(CommandRequest.class, ResmonCommand.NAME,
                new CommandDescriptor("查看 JVM 与磁盘的资源占用（只读）",
                        "[jvm|disk|auto [on|off]|help]", null, false),
                new ResmonCommand(config, sampler));
        context.handle(CommandOptionRequest.class, ResmonCommand.NAME, new ResmonOptions(sampler));
        if (config.panel() && context.runtimeInfo().hasUI()) {
            // 没有交互界面的外壳（-cli / -server）根本不会问面板，注册了也只是一条永不触发的登记
            context.contribute(PanelContributionRequest.class, new ResmonPanel(config, sampler));
        }
    }
}
