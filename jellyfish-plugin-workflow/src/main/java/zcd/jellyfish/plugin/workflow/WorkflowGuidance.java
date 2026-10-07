package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.PromptPlacement;

/**
 * 提示词贡献：告诉模型 spec 长什么样、什么时候值得用。
 * <p>
 * <b>为什么只给一个例子，不复述 schema</b>：字段与取值范围已经写在 {@code workflow} 工具名片里
 * （{@link WorkflowTool#descriptor()}），而工具名片每轮都在模型眼前。再复述一遍只会多花 token，
 * 还会在两边改动不同步时给出<b>两份互相矛盾的说明</b>——后者比前者严重得多。
 * 工具名片<em>表达不了</em>的只有两件事：一个完整可照抄的例句，以及「什么时候不该用它」。
 * 贡献的内容因此恰好是这两件。
 * <p>
 * <b>落位是 {@code STATIC}</b>：这段文字编译期就定下来了，永不变化，放进可缓存的前缀最省——
 * 放进 SESSION 会让它跟着会话级内容一起每次重算。
 * <p>
 * 无状态，可安全跨线程调用。
 *
 * @author zcd
 */
final class WorkflowGuidance implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** 贡献给模型的文本。 */
    static final String TEXT = "workflow 工具的 spec 是一份一次性说明。示例（先并行调研两个方向，"
            + "再结合结论写方案）：\n"
            + "{\"spec\":{\"steps\":["
            + "{\"id\":\"probe-a\",\"agent\":\"scout\",\"prompt\":\"调研 A 方向……\"},"
            + "{\"id\":\"probe-b\",\"agent\":\"scout\",\"prompt\":\"调研 B 方向……\"},"
            + "{\"id\":\"plan\",\"agent\":\"planner\",\"prompt\":\"结合 A 与 B 的结论给出方案\","
            + "\"needs\":[\"probe-a\",\"probe-b\"]}],"
            + "\"aggregate\":{\"mode\":\"summarize\",\"agent\":\"scout\"}}}\n"
            + "要点：没有 needs 的步骤立刻开始，因此它们并行执行；带 needs 的步骤会等到这几步结束，"
            + "并在自己的任务里收到它们给出的结论（结论过长会截断，并给出完整记录所在的归档路径）。"
            + "需要「失败以后再补救」时，把补救步骤的 when 写成 on_failure 并声明 needs。"
            + "spec 里没有循环、变量与表达式，也不做重试——要改主意就重新提交一份 spec。"
            + "一次编排派出的子代理数受本回合额度限制（subAgent.maxSpawnsPerTurn，缺省 12），"
            + "装不下会在派生任何子代理之前整份拒绝。"
            + "只派一个子代理做一件事时用 task，不必写 spec。";

    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        return PromptContribution.of(TEXT, PromptPlacement.STATIC);
    }
}
