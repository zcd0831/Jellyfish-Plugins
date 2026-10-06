package zcd.jellyfish.plugin.resmon;

import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /resmon} 的只读候选查询：给出可选的子命令，并标出当前的自动刷新状态。
 * <p>
 * <b>为什么与命令执行分成两个扩展点</b>：候选查询发生在外壳「用户刚选中这条命令、还没决定参数」时，
 * 那一刻不该有任何副作用。而 {@code /resmon auto off} 是有副作用的（改了刷新开关），
 * 两者混在一条路径上会让「弹一下候选」也能把面板关掉。
 * <p>
 * <b>候选清单里没有「清理」</b>：本插件的定位是只读诊断，任何会删文件的能力都不在这里，
 * 也就不会出现在候选里被误触。
 * <p>
 * 无状态（采样器是线程安全的），可安全跨线程使用。
 *
 * @author zcd
 */
final class ResmonOptions implements ExtensionHandler<CommandOptionRequest, CommandOptions> {

    /** 采样器，用于读取当前自动刷新状态。 */
    private final ResmonSampler sampler;

    /**
     * 构造候选处理器。
     *
     * @param sampler 采样器，不可为 {@code null}
     */
    ResmonOptions(ResmonSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    public CommandOptions handle(CommandOptionRequest request) {
        boolean auto = sampler.autoRefresh();
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        choices.add(new CommandChoice(ResmonCommand.ARG_JVM, "jvm",
                "JVM 明细：堆、非堆、内存池、GC、线程、文件描述符、CPU", false));
        choices.add(new CommandChoice(ResmonCommand.ARG_DISK, "disk",
                "磁盘明细：六个已知目录的体积、文件数与路径", false));
        choices.add(new CommandChoice(ResmonCommand.VALUE_AUTO_ON, "auto on",
                "开启面板自动刷新（每轮采样后立刻重绘）", auto));
        choices.add(new CommandChoice(ResmonCommand.VALUE_AUTO_OFF, "auto off",
                "关闭面板自动刷新（面板仍会在命令与会话事件后更新）", !auto));
        choices.add(new CommandChoice(ResmonCommand.ARG_HELP, "help", "用法说明", false));
        return CommandOptions.of(choices);
    }
}
