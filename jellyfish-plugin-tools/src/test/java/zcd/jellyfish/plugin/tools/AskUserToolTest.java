package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskUserTool} 的单元测试：钉住「答复怎么变成回灌给模型的那段话」与「本上下文问不了时怎么办」。
 * <p>
 * 提问端口用 lambda 桩：本用例关心的是工具怎么解释答复、怎么组织文案，
 * 而不是通道怎么阻塞等待（那由 {@code AskChannelTest} 负责）。
 *
 * @author zcd
 */
@DisplayName("AskUserTool 向用户提问")
class AskUserToolTest {

    @Test
    @DisplayName("选中候选项时把该选项的文案与说明回灌给模型")
    void handle_should_render_choice_label() throws Exception {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("b"));

        // When
        String output = ToolTestSupport.invoke(tool, argsOf("先做通道还是先做 UI？"));

        // Then
        assertEquals("用户选择了：先做 UI", output);
    }

    @Test
    @DisplayName("选中项带说明时一并回灌，帮模型理解这个选择的含义")
    void handle_should_render_choice_description() throws Exception {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // When
        String output = ToolTestSupport.invoke(tool, argsOf("先做通道还是先做 UI？"));

        // Then
        assertEquals("用户选择了：先做通道（内核与插件先落地）", output);
    }

    @Test
    @DisplayName("用户自己填的答案原样回灌")
    void handle_should_render_custom_answer() throws Exception {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.custom("都不合适，改成先做 Server"));

        // When
        String output = ToolTestSupport.invoke(tool, argsOf("先做通道还是先做 UI？"));

        // Then
        assertEquals("用户自己填了答案：都不合适，改成先做 Server", output);
    }

    @Test
    @DisplayName("选项标识认不出来时原样回灌标识，不假装知道它对应哪一项")
    void handle_should_fall_back_to_raw_option_id() throws Exception {
        // Given：端口回了一个不在清单里的标识（真实场景里不会发生，但工具不该因此崩）
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("zzz"));

        // When
        String output = ToolTestSupport.invoke(tool, argsOf("先做通道还是先做 UI？"));

        // Then
        assertEquals("用户选择了：zzz", output);
    }

    @Test
    @DisplayName("用户放弃作答时告诉模型回合可以继续")
    void handle_should_render_cancelled() throws Exception {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.cancelled("用户取消了这次提问，没有回答"));

        // When
        String output = ToolTestSupport.invoke(tool, argsOf("先做通道还是先做 UI？"));

        // Then
        assertTrue(output.contains("用户取消了这次提问"), output);
        assertTrue(output.contains("请自行判断"), output);
    }

    @Test
    @DisplayName("超时与「无人可问」都带上具体原因，并让模型自己往下走")
    void handle_should_render_unanswered_states() throws Exception {
        // Given：两种「没问到人」的情形
        AskUserTool timedOut = new AskUserTool(request -> AskAnswer.timedOut("用户在 120 秒内没有回答"));
        AskUserTool unavailable = new AskUserTool(request -> AskAnswer.unavailable("当前外壳没有交互界面，无法把提问送达用户"));

        // When
        String first = ToolTestSupport.invoke(timedOut, argsOf("问题？"));
        String second = ToolTestSupport.invoke(unavailable, argsOf("问题？"));

        // Then：这条路径是「成功的工具结果」，模型据此继续，而不是把它当成执行失败去重试
        assertTrue(first.startsWith("用户在 120 秒内没有回答"), first);
        assertTrue(first.contains("请自行判断或直接说明你的假设"), first);
        assertTrue(second.contains("无法把提问送达用户"), second);
        assertTrue(second.contains("请自行判断或直接说明你的假设"), second);
    }

    @Test
    @DisplayName("子代理回合里当场给出「问不到」，不进通道等待")
    void handle_should_not_ask_when_running_in_a_sub_agent_run() throws Exception {
        // Given：一个必须不被调用的端口——子代理的提问不会出现在任何界面上，进通道只会卡到超时
        AtomicReference<AskRequest> called = new AtomicReference<AskRequest>();
        AskUserTool tool = new AskUserTool(request -> {
            called.set(request);
            return AskAnswer.answered("a");
        });

        // When
        ToolCallResult result = tool.handle(new ToolCallRequest("ask_user", argsOf("问题？"), "s1", null, null,
                "parent-1", "run-1", "root-1"));

        // Then
        assertNull(called.get(), "子代理回合里绝不能把提问交给通道");
        assertTrue(String.valueOf(result.getOutput()).contains("子代理回合里无法向用户提问"),
                String.valueOf(result.getOutput()));
    }

    @Test
    @DisplayName("把工具参数原样交给端口，并带上会话与来源")
    void handle_should_pass_request_through() throws Exception {
        // Given
        AtomicReference<AskRequest> captured = new AtomicReference<AskRequest>();
        AskUserTool tool = new AskUserTool(request -> {
            captured.set(request);
            return AskAnswer.answered("1");
        });

        // When
        tool.handle(new ToolCallRequest("ask_user", argsOf("先做通道还是先做 UI？"), "s-42", null, null,
                null, null, null));

        // Then
        assertEquals("ask_user", captured.get().getSource());
        assertEquals("s-42", captured.get().getSessionId());
        assertEquals("先做通道还是先做 UI？", captured.get().getQuestion());
        assertEquals(2, captured.get().getOptions().size());
        assertEquals("a", captured.get().getOptions().get(0).getOptionId());
    }

    @Test
    @DisplayName("省略 id 的候选项按序号补齐标识")
    void handle_should_assign_sequential_ids_when_omitted() throws Exception {
        // Given：候选只给了文案
        AtomicReference<AskRequest> captured = new AtomicReference<AskRequest>();
        AskUserTool tool = new AskUserTool(request -> {
            captured.set(request);
            return AskAnswer.answered("2");
        });
        Map<String, Object> arguments = ToolTestSupport.args("question", "选一个？",
                "options", labeledOnly("甲", "乙", "丙"));

        // When
        String output = ToolTestSupport.invoke(tool, arguments);

        // Then
        assertEquals(Arrays.asList("1", "2", "3"), idsOf(captured.get()));
        assertEquals("用户选择了：乙", output);
    }

    @Test
    @DisplayName("缺少问题原文时拒绝调用")
    void handle_should_reject_missing_question() {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // When
        JellyfishException error = ToolTestSupport.expectFailure(
                () -> tool.handle(new ToolCallRequest("ask_user", ToolTestSupport.args("options", options()))));

        // Then
        assertTrue(error.getMessage().contains("question"), error.getMessage());
    }

    @Test
    @DisplayName("候选个数越界时拒绝调用，并说明合法范围")
    void handle_should_reject_option_count_out_of_range() {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // When
        JellyfishException tooFew = ToolTestSupport.expectFailure(() -> tool.handle(new ToolCallRequest("ask_user",
                ToolTestSupport.args("question", "问题？", "options", labeledOnly("只有一个")))));
        JellyfishException tooMany = ToolTestSupport.expectFailure(() -> tool.handle(new ToolCallRequest("ask_user",
                ToolTestSupport.args("question", "问题？", "options", labeledOnly("1", "2", "3", "4", "5", "6", "7")))));

        // Then
        assertTrue(tooFew.getMessage().contains("2~6"), tooFew.getMessage());
        assertTrue(tooMany.getMessage().contains("2~6"), tooMany.getMessage());
    }

    @Test
    @DisplayName("候选项缺少文案时拒绝调用")
    void handle_should_reject_option_without_label() {
        // Given：第二项只有 id
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        options.add(ToolTestSupport.args("id", "a", "label", "甲"));
        options.add(ToolTestSupport.args("id", "b"));

        // When
        JellyfishException error = ToolTestSupport.expectFailure(() -> tool.handle(new ToolCallRequest("ask_user",
                ToolTestSupport.args("question", "问题？", "options", options))));

        // Then
        assertTrue(error.getMessage().contains("label"), error.getMessage());
    }

    @Test
    @DisplayName("候选不是数组时拒绝调用")
    void handle_should_reject_non_list_options() {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // When
        JellyfishException error = ToolTestSupport.expectFailure(() -> tool.handle(new ToolCallRequest("ask_user",
                ToolTestSupport.args("question", "问题？", "options", "甲、乙"))));

        // Then
        assertTrue(error.getMessage().contains("options"), error.getMessage());
    }

    @Test
    @DisplayName("摘要是一行、且不泄露问题原文")
    void handle_should_report_single_line_summary() throws Exception {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // When
        ToolCallResult result = ToolTestSupport.invokeResult(tool, argsOf("问题？"));

        // Then
        assertEquals("已作答", ToolTestSupport.summaryOf(result));
    }

    @Test
    @DisplayName("各种未作答情形的摘要各自成一句")
    void handle_should_distinguish_unanswered_summaries() throws Exception {
        // Given
        AskUserTool cancelled = new AskUserTool(request -> AskAnswer.cancelled(null));
        AskUserTool timedOut = new AskUserTool(request -> AskAnswer.timedOut("超时了"));
        AskUserTool unavailable = new AskUserTool(request -> AskAnswer.unavailable("没界面"));

        // When / Then
        assertEquals("用户取消了提问", ToolTestSupport.summaryOf(ToolTestSupport.invokeResult(cancelled, argsOf("问题？"))));
        assertEquals("提问超时", ToolTestSupport.summaryOf(ToolTestSupport.invokeResult(timedOut, argsOf("问题？"))));
        assertEquals("无人可问", ToolTestSupport.summaryOf(ToolTestSupport.invokeResult(unavailable, argsOf("问题？"))));
    }

    @Test
    @DisplayName("工具名片里的名字即路由键")
    void descriptor_should_carry_name_and_required_fields() {
        // Given
        AskUserTool tool = new AskUserTool(request -> AskAnswer.answered("a"));

        // Then
        assertEquals("ask_user", tool.name());
        assertEquals("ask_user", tool.descriptor().getName());
        assertEquals(Arrays.asList("question", "options"), tool.descriptor().getRequired());
        assertTrue(tool.descriptor().getParameters().containsKey("question"));
        assertTrue(tool.descriptor().getParameters().containsKey("options"));
    }

    /**
     * 构造标准参数：一个问题加两个候选项（第一项带说明）。
     *
     * @param question 问题原文
     * @return 参数映射
     */
    private static Map<String, Object> argsOf(String question) {
        return ToolTestSupport.args("question", question, "options", options());
    }

    /**
     * 构造两个候选项（第一项带说明）。
     *
     * @return 候选项
     */
    private static List<Map<String, Object>> options() {
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        options.add(ToolTestSupport.args("id", "a", "label", "先做通道", "description", "内核与插件先落地"));
        options.add(ToolTestSupport.args("id", "b", "label", "先做 UI"));
        return options;
    }

    /**
     * 构造只有文案的候选项。
     *
     * @param labels 文案
     * @return 候选项
     */
    private static List<Map<String, Object>> labeledOnly(String... labels) {
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        for (String label : labels) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", label);
            options.add(item);
        }
        return options;
    }

    /**
     * 取出请求里候选项的标识。
     *
     * @param request 提问请求
     * @return 标识列表
     */
    private static List<String> idsOf(AskRequest request) {
        List<String> ids = new ArrayList<String>();
        request.getOptions().forEach(option -> ids.add(option.getOptionId()));
        return ids;
    }
}
