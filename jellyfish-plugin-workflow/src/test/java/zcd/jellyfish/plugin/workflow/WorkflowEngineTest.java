package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.SubAgentPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowEngine} 的单元测试：层序调度、并发扇出、静态条件、聚合与取消。
 * <p>
 * <b>并发靠调用顺序证明，不靠计时</b>：假端口把每次 {@code spawn} 与 {@code await} 都记进一条轨迹，
 * 于是「同一层的派生全部发生在等待之前」变成一条可以直接断言的序列——
 * 若引擎写成了「派生一个等一个」，轨迹会是 {@code spawn:a, await:a, spawn:b, ...}。
 * 计时断言在 CI 上会随机翻车，这里一次都不用。
 * <p>
 * <b>不测内核行为</b>：governor、真实并发度、会话生命周期都在内核那边（{@code task} 与端口共用同一条
 * 路径），本测试只负责「引擎有没有把该并行的并行、该跳过的跳过」。
 *
 * @author zcd
 */
@DisplayName("编排引擎")
class WorkflowEngineTest {

    @Test
    @DisplayName("同层无依赖的步骤应先全部派生、再逐个等待（真扇出）")
    void run_shouldFanOutBeforeAwaiting() {
        // Given：三步都无依赖
        RecordingPort port = new RecordingPort();
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", null, null),
                step("c", "scout", "c", null, null)));

        // When
        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        // Then：三个派生都在任何等待之前
        assertEquals(Arrays.asList("spawn:a", "spawn:b", "spawn:c", "await:a", "await:b", "await:c"),
                port.getTrace());
        assertEquals(3, run.getSteps().size());
        assertFalse(run.hasFailure());
    }

    @Test
    @DisplayName("有依赖的步骤要等前置结束才开始：分层而不是一次全发")
    void run_shouldRespectDependencies() {
        RecordingPort port = new RecordingPort();
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("c", "scout", "c", Arrays.asList("a"), null)));

        new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertEquals(Arrays.asList("spawn:a", "await:a", "spawn:c", "await:c"), port.getTrace());
    }

    @Test
    @DisplayName("on_success：前置失败时跳过，并说明是哪个前置")
    void run_shouldSkipOnSuccessStepWhenDependencyFailed() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "planner", "b", Arrays.asList("a"), "on_success")));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertFalse(port.getTrace().contains("spawn:b"), port.getTrace().toString());
        StepOutcome skipped = run.getSteps().get(1);
        assertFalse(skipped.hasRun());
        assertEquals("前置步骤未成功：a", skipped.getNotRunReason());
        assertTrue(run.hasFailure());
    }

    @Test
    @DisplayName("on_failure：前置失败时才执行")
    void run_shouldRunOnFailureStepOnlyWhenDependencyFailed() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("fix", "planner", "fix", Arrays.asList("a"), "on_failure")));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(port.getTrace().contains("spawn:fix"), port.getTrace().toString());
        assertTrue(run.getSteps().get(1).succeeded());
    }

    @Test
    @DisplayName("on_failure：前置成功时不执行")
    void run_shouldSkipOnFailureStepWhenDependencySucceeded() {
        RecordingPort port = new RecordingPort();
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("fix", "planner", "fix", Arrays.asList("a"), "on_failure")));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertFalse(port.getTrace().contains("spawn:fix"), port.getTrace().toString());
        assertEquals("前置步骤没有失败", run.getSteps().get(1).getNotRunReason());
    }

    @Test
    @DisplayName("一个步骤失败不影响与它无依赖关系的步骤")
    void run_shouldKeepIndependentStepsRunningAfterFailure() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", null, null)));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(port.getTrace().contains("spawn:b"), port.getTrace().toString());
        assertTrue(run.getSteps().get(1).succeeded());
        assertTrue(run.hasFailure());
    }

    @Test
    @DisplayName("collect：按声明顺序拼接各步正文")
    void run_shouldCollectInDeclarationOrder() {
        RecordingPort port = new RecordingPort();
        port.answer("b", DelegationResult.completed("run-b", "第二段", 1, 1L));
        port.answer("a", DelegationResult.completed("run-a", "第一段", 1, 1L));
        WorkflowSpec spec = spec(steps(
                step("b", "scout", "b", null, null),
                step("a", "scout", "a", null, null)));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertEquals("## b（scout）\n第二段\n\n## a（scout）\n第一段", run.getAggregate());
    }

    @Test
    @DisplayName("summarize：再派一个汇总子代理，材料里带上各步正文，聚合取它的结论")
    void run_shouldSummarizeWithExtraDelegation() {
        RecordingPort port = new RecordingPort();
        port.answer("scout", DelegationResult.completed("run-x", "原始结论", 1, 5L));
        port.answer("planner", DelegationResult.completed("run-sum", "汇总结论", 2, 7L));
        WorkflowSpec spec = new WorkflowSpec("t", steps(step("a", "scout", "a", null, null)),
                AggregateMode.SUMMARIZE, "planner");

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        // 汇总是一次真实的委派：材料里必须带上前面的正文，否则它只能凭空编
        DelegationRequest synthesis = port.getRequests().get(port.getRequests().size() - 1);
        assertEquals("planner", synthesis.getAgentId());
        assertTrue(synthesis.getPrompt().contains("原始结论"), synthesis.getPrompt());
        assertEquals("汇总结论", run.getAggregate());
        assertTrue(WorkflowSpecParser.isSuccess(run.getSynthesis()));
        // 轮数与 token 含汇总那一次
        assertEquals(3, run.totalRounds());
        assertEquals(12L, run.totalTokens());
    }

    @Test
    @DisplayName("全部步骤都没产出正文时不做汇总：空材料只会让它编")
    void run_shouldSkipSummarizeWithoutMaterial() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = new WorkflowSpec("t", steps(step("a", "scout", "a", null, null)),
                AggregateMode.SUMMARIZE, "planner");

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertNull(run.getSynthesis());
        assertEquals("", run.getAggregate());
        // 只派过那一步：汇总那一次没有发生（材料是空的，汇总子代理只能编）
        assertEquals(1, port.getRequests().size(), port.getTrace().toString());
    }

    @Test
    @DisplayName("回合已取消时不再派发，并把没跑的步骤如实记为已取消")
    void run_shouldStopDispatchingWhenCancelled() {
        RecordingPort port = new RecordingPort();
        WorkflowSpec spec = spec(steps(step("a", "scout", "a", null, null)));
        CancellationToken cancelled = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onCancel(Runnable callback) {
                // 本用例只观察 isCancelled
            }
        };

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", cancelled, null);

        assertTrue(port.getTrace().isEmpty(), port.getTrace().toString());
        assertEquals(StepOutcome.CANCELLED_REASON, run.getSteps().get(0).getNotRunReason());
        assertTrue(run.getSteps().get(0).cancelled());
        assertTrue(run.hasCancellation());
    }

    @Test
    @DisplayName("取消令牌要透传给每一个派生的 run")
    void run_shouldForwardCancellationToken() {
        RecordingPort port = new RecordingPort();
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable callback) {
                // 本用例只观察令牌有没有被传下去
            }
        };

        new WorkflowEngine(port).run(spec(steps(step("a", "scout", "a", null, null))), "s-1", token, null);

        assertSame(token, port.getRequests().get(0).getCancellationToken());
    }

    @Test
    @DisplayName("被拒的步骤记为失败（不是静默跳过），原因是内核给的那句话")
    void run_shouldReportRejectedStepAsFailure() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）"));
        WorkflowSpec spec = spec(steps(step("a", "scout", "a", null, null)));

        WorkflowRun run = new WorkflowEngine(port).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(run.hasFailure());
        assertTrue(run.firstFailure().getResult().getError().contains("已被禁用"));
    }

    @Test
    @DisplayName("还在「运行中的工具」区域写进度：谁开始了、谁什么结局")
    void run_shouldWriteProgressToSink() {
        RecordingPort port = new RecordingPort();
        RecordingSink sink = new RecordingSink();

        new WorkflowEngine(port).run(spec(steps(step("a", "scout", "a", null, null))), "s-1",
                CancellationToken.NONE, sink);

        String text = sink.text();
        assertTrue(text.contains("a（scout）开始"), text);
        assertTrue(text.contains("a 完成"), text);
    }

    /**
     * 构造一份 spec。
     *
     * @param steps 步骤
     * @return spec
     */
    private static WorkflowSpec spec(List<WorkflowStep> steps) {
        return new WorkflowSpec("t", steps, AggregateMode.COLLECT, null);
    }

    /**
     * 包装步骤列表。
     *
     * @param steps 步骤
     * @return 列表
     */
    private static List<WorkflowStep> steps(WorkflowStep... steps) {
        return new ArrayList<WorkflowStep>(Arrays.asList(steps));
    }

    /**
     * 构造一个步骤。测试里把「步骤 id」与「任务原文」取成同一个值，假端口因此可以用 prompt 找回步骤。
     *
     * @param id    步骤标识
     * @param agent 子代理类型
     * @param prompt 任务原文
     * @param needs 依赖
     * @param when  条件
     * @return 步骤
     */
    private static WorkflowStep step(String id, String agent, String prompt, List<String> needs, String when) {
        return new WorkflowStep(id, agent, prompt, needs,
                StepCondition.fromWire(when, "when"));
    }

    /** 记录调度轨迹的假端口：{@code spawn} 与 {@code await} 各记一条。 */
    private static final class RecordingPort implements SubAgentPort {

        /** 调度轨迹，形如 {@code spawn:a} / {@code await:a}。 */
        private final List<String> trace = new ArrayList<String>();

        /** 收到的请求，按到达顺序。 */
        private final List<DelegationRequest> requests = new ArrayList<DelegationRequest>();

        /** 按 prompt（即步骤 id）给出的应答。 */
        private final Map<String, DelegationResult> answers = new LinkedHashMap<String, DelegationResult>();

        /**
         * 指定某个步骤的结果。
         *
         * @param prompt 步骤 id
         * @param result 结果
         */
        void answer(String prompt, DelegationResult result) {
            answers.put(prompt, result);
        }

        /**
         * 获取调度轨迹。
         *
         * @return 轨迹
         */
        List<String> getTrace() {
            return trace;
        }

        /**
         * 获取收到的请求。
         *
         * @return 请求列表
         */
        List<DelegationRequest> getRequests() {
            return requests;
        }

        @Override
        public DelegationHandle spawn(DelegationRequest request) {
            requests.add(request);
            trace.add("spawn:" + request.getPrompt());
            return new RecordingHandle(this, request);
        }
    }

    /** 假句柄：等待时记一条轨迹，然后给出预置结果。 */
    private static final class RecordingHandle implements DelegationHandle {

        /** 所属端口。 */
        private final RecordingPort port;

        /** 本次请求。 */
        private final DelegationRequest request;

        /**
         * 构造句柄。
         *
         * @param port    所属端口
         * @param request 请求
         */
        private RecordingHandle(RecordingPort port, DelegationRequest request) {
            this.port = port;
            this.request = request;
        }

        @Override
        public String runId() {
            return null;
        }

        @Override
        public DelegationResult await() {
            port.trace.add("await:" + request.getPrompt());
            // prompt 优先（测试里它就是步骤 id），其次按 agent（汇总那一步没有稳定的 prompt）
            DelegationResult result = port.answers.containsKey(request.getPrompt())
                    ? port.answers.get(request.getPrompt()) : port.answers.get(request.getAgentId());
            return result != null ? result
                    : DelegationResult.completed("run", "正文-" + request.getPrompt(), 1, 5L);
        }

        @Override
        public void cancel() {
            // 本测试不观察取消
        }
    }

    /** 记录进度的假输出旁路。 */
    private static final class RecordingSink implements ToolOutputSink {

        /** 累积的文本。 */
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(String chunk) {
            text.append(chunk);
        }

        @Override
        public void summary(String text) {
            // 本测试不写摘要
        }

        @Override
        public String finish() {
            return text.toString();
        }

        /**
         * 获取已写入的文本。
         *
         * @return 文本
         */
        String text() {
            return text.toString();
        }
    }
}
