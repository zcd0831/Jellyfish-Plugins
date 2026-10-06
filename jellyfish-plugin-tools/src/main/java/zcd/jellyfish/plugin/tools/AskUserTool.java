package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 工具 {@code ask_user}：把一个问题连同若干选项摆到用户面前，等用户选一个或自己填。
 * <p>
 * <b>为什么它在本插件里而不在内核</b>：与其余工具同源——插件侧只能注册回调，工具的存续与内核
 * 生命周期无关，顺带还能获得插件段的配置与热部署。它需要的那条「把问题交给人」的能力不是注册，
 * 而是一条出向边：经 {@code PluginContext.askUser()} 拿到的 {@link AskPort}。因此本工具是
 * 本插件里<b>唯一持有外部依赖</b>的工具，其余文件工具仍然无状态。
 * <p>
 * <b>它不是审批</b>：选任何一项都不会让哪个工具获得执行权。这里的答案只回灌给模型当信息用，
 * 权限仍然只由 {@code PermissionManager} 收口。这条边界要写清楚，否则模型可能把它当成
 * 「让用户替它点同意」的捷径。
 * <p>
 * <b>拿不到答复不算失败</b>：没有交互界面的外壳（{@code -cli}）、子代理回合、等待超时，
 * 这三条都返回<b>成功</b>的工具结果，只是内容说明「没问到人」。做成失败会让模型以为环境出错，
 * 于是反复重试同一个提问；而正确行为是照自己的判断继续，并把它说出来。
 * <p>
 * 无状态（除注入的端口外），可安全复用。
 *
 * @author zcd
 */
public final class AskUserTool implements PluginTool {

    /** 工具名，即注册与调用的路由键。 */
    public static final String NAME = "ask_user";

    /** 选项个数下限：一个选项的问题没有可比较的备选，与「确认」无异。 */
    private static final int MIN_OPTIONS = 2;

    /** 选项个数上限：超出一个模态框能读完的量，用户会开始随便选。 */
    private static final int MAX_OPTIONS = 6;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            NAME,
            "向你（用户）提问，并给出若干选项让你挑一个，你也可以自己填答案。"
                    + "用于：关键决策前的确认、需求或偏好不明确、有多个都说得通的方案而你必须选一个、"
                    + "以及执行不可逆操作前想先跟你对一下。一次只问一个问题、选项 2~6 个。"
                    + "别用它确认你自己能查到的事实，也别把每一步都变成提问。"
                    + "拿不到答复时（无人可问、超时、子代理里）结果会说明原因，那时请自行判断并说明你的假设。",
            ToolSchema.properties(
                    "question", ToolSchema.string("你要问的问题，一句话说清在纠结什么"),
                    "options", ToolSchema.array("候选答案，2~6 个",
                            ToolSchema.object(
                                    ToolSchema.properties(
                                            "id", ToolSchema.string("选项标识，回传时用它；省略则按序号 1、2、3…"),
                                            "label", ToolSchema.string("给用户看的选项文案"),
                                            "description", ToolSchema.string("补充说明，帮用户分辨这个选项")),
                                    Arrays.<String>asList("label")))),
            Arrays.asList("question", "options"));

    /** 提问端口：本工具唯一的外部依赖。 */
    private final AskPort askPort;

    /**
     * 构造工具。
     *
     * @param askPort 提问端口，不可为 {@code null}；插件装配时来自 {@code PluginContext.askUser()}
     */
    public AskUserTool(AskPort askPort) {
        this.askPort = askPort;
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        String question = arguments.requireString("question");
        List<AskOption> options = parseOptions(arguments.requireObjectList("options"));
        AskAnswer answer = ask(request, question, options);
        return new ToolCallResult(name(), render(options, answer), ToolSummaries.of(summary(answer)));
    }

    /**
     * 发起提问，并在本上下文不允许提问时当场返回「问不到人」。
     * <p>
     * <b>为什么在子代理回合里必须当场拒绝、而不是让它去等</b>：外层界面按<b>当前会话</b>取待答项，
     * 而子代理有独立的会话，它的提问根本不会出现在任何一个界面上；若照旧进通道等待，
     * 那个子代理回合只会静默卡到超时。判据是 {@code runId} 非空——它表示这次调用发生在某个 run 上，
     * 也就是子代理回合。
     *
     * @param request  工具调用请求
     * @param question 问题原文
     * @param options  候选项
     * @return 答复，保证非 {@code null}
     */
    private AskAnswer ask(ToolCallRequest request, String question, List<AskOption> options) {
        if (request.getRunId() != null) {
            return AskAnswer.unavailable("子代理回合里无法向用户提问");
        }
        return askPort.ask(AskRequest.of(NAME, request.getSessionId(), question, options));
    }

    /**
     * 解析候选项：校验个数与文案，并把省略的标识按序号补齐。
     * <p>
     * <b>为什么由工具补齐而不是界面处理</b>：回传给模型的是一个稳定标识，缺了它就只能回传
     * 「第 2 项」这种随界面顺序漂移的东西；补齐发生在这里，模型与界面看到的就是同一份清单。
     *
     * @param raw 原始候选项
     * @return 规范化的候选项，保证非 {@code null}
     * @throws JellyfishException 个数越界或某项缺少文案时抛出
     */
    private static List<AskOption> parseOptions(List<Map<String, Object>> raw) {
        if (raw.size() < MIN_OPTIONS || raw.size() > MAX_OPTIONS) {
            throw new JellyfishException("options 需要 " + MIN_OPTIONS + '~' + MAX_OPTIONS
                    + " 个选项，实际是 " + raw.size() + " 个");
        }
        List<AskOption> options = new ArrayList<AskOption>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            ToolArguments item = new ToolArguments(raw.get(index));
            String label = item.requireString("label");
            String id = item.optionalString("id", String.valueOf(index + 1));
            options.add(AskOption.of(id, label, item.optionalString("description", null)));
        }
        return options;
    }

    /**
     * 把答复渲染成回灌给模型的文本。
     *
     * @param options 本次提问的候选项，用于把选中项折回它的文案
     * @param answer  答复，不可为 {@code null}
     * @return 单段说明文本，保证非 {@code null}
     */
    private static String render(List<AskOption> options, AskAnswer answer) {
        switch (answer.getStatus()) {
            case ANSWERED:
                if (answer.getOptionId() != null) {
                    return "用户选择了：" + labelOf(options, answer.getOptionId());
                }
                return "用户自己填了答案：" + answer.getText();
            case CANCELLED:
                return "用户取消了这次提问，没有回答。请自行判断，或把问题拆得更具体一点再问。";
            default:
                // 超时与不可用：说明已经带上具体原因（等了多少秒 / 为什么没人可问）
                return answer.getReason() + "。请自行判断或直接说明你的假设。";
        }
    }

    /**
     * 把选中项的标识折回「文案（说明）」。
     *
     * @param options  候选项
     * @param optionId 选中项标识
     * @return 展示文本；标识不在清单里时原样返回它
     */
    private static String labelOf(List<AskOption> options, String optionId) {
        for (AskOption option : options) {
            if (optionId.equals(option.getOptionId())) {
                String description = option.getDescription();
                return description == null ? option.getLabel() : option.getLabel() + "（" + description + "）";
            }
        }
        return optionId;
    }

    /**
     * 生成本次调用的单行摘要。
     *
     * @param answer 答复，不可为 {@code null}
     * @return 摘要文本，保证非 {@code null}
     */
    private static String summary(AskAnswer answer) {
        switch (answer.getStatus()) {
            case ANSWERED:
                return answer.getOptionId() != null ? "已作答" : "已作答（自己填的）";
            case CANCELLED:
                return "用户取消了提问";
            case TIMED_OUT:
                return "提问超时";
            default:
                return "无人可问";
        }
    }
}
