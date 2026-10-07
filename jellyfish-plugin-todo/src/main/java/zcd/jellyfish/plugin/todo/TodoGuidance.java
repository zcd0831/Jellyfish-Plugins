package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.PromptPlacement;

/**
 * 提示词贡献：什么时候该把活写进待办、让子代理去认领。
 * <p>
 * <b>为什么需要它</b>：待办原来只是「给人看的计划」。P3 之后它同时是一份**工作队列**——子代理能用
 * {@code todo_claim} 从里面领活。可是「分工该在开工前定死，还是让干得快的多拿」这件事，
 * 工具名片说不出来：名片只能描述一个工具能做什么，而这里要选的是<b>三种编排形态</b>。
 * <p>
 * <b>为什么放 {@code STATIC} 而不是随回合注入</b>：这段是选型规则，编译期就定下来、永不变化，
 * 放进可缓存的前缀最省；而「还剩几条」那类**状态**写在 {@link TodoText#promptBlock} 里随用户消息走
 * ——状态进 system prompt 会让每次勾掉一件事都作废整段缓存前缀。
 * <p>
 * <b>与工具名片的分工</b>：名片讲「怎么用」（参数、取值），这里讲「什么时候用」。
 * 两者都不复述对方，因此不会出现两份互相矛盾的说明。
 * <p>
 * 无状态，可安全跨线程调用。
 *
 * @author zcd
 */
final class TodoGuidance implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** 贡献给模型的文本。 */
    static final String TEXT = "要委派工作时先想清楚分工在什么时候定：只派一个子代理做一件事用 task；"
            + "分工在开工前就能定下来、步骤之间的依赖也清楚，用 workflow 的 spec；"
            + "分工要看谁快谁慢、或者活会边做边冒出来，就把它们写进待办（todo_write），"
            + "再派子代理去做——每个子代理会用 todo_claim 领一条、做完用 todo_done 标掉，"
            + "而它们读写的是同一份待办，所以你能立刻看见清单的变化。"
            + "做不了的那条用 todo_block 记下原因，不要让它被反复领走。";

    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        return PromptContribution.of(TEXT, PromptPlacement.STATIC);
    }
}
