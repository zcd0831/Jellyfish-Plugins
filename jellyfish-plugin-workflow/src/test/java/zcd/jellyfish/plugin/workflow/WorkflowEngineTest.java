package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationQuota;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

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

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", cancelled, null);

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

        new WorkflowEngine(port, tracker()).run(spec(steps(step("a", "scout", "a", null, null))), "s-1", token, null);

        assertSame(token, port.getRequests().get(0).getCancellationToken());
    }

    @Test
    @DisplayName("取消中途时 on_failure 的补救步骤不该跑：取消不是失败")
    void run_should_notRunOnFailureStep_when_cancelledMidway() {
        RecordingPort port = new RecordingPort();
        // 「按下 Esc」的时刻：步骤 a 已经跑完（await:a 记进了轨迹）之后
        CancellationToken midway = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return port.getTrace().contains("await:a");
            }

            @Override
            public void onCancel(Runnable callback) {
                // 本用例只观察 isCancelled
            }
        };
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", java.util.Collections.singletonList("a"), "on_failure")));

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", midway, null);

        assertTrue(run.getSteps().get(0).succeeded(), String.valueOf(run.getSteps().get(0).getResult()));
        // 取消是用户主权，不是「前置失败了」：补救步骤不该被触发
        assertFalse(port.getTrace().contains("spawn:b"), port.getTrace().toString());
        assertFalse(run.getSteps().get(1).failed(), String.valueOf(run.getSteps().get(1).getResult()));
        assertTrue(run.getSteps().get(1).cancelled(), String.valueOf(run.getSteps().get(1).getResult()));
    }

    @Test
    @DisplayName("取消之后不再派汇总子代理：材料照原样返回")
    void run_should_skipSummarize_when_cancelledMidway() {
        RecordingPort port = new RecordingPort();
        CancellationToken midway = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return port.getTrace().contains("await:a");
            }

            @Override
            public void onCancel(Runnable callback) {
                // 本用例只观察 isCancelled
            }
        };
        WorkflowSpec spec = new WorkflowSpec("t",
                steps(step("a", "scout", "a", null, null)), AggregateMode.SUMMARIZE, null);

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", midway, null);

        // 汇总是一次子代理 run：已经取消了就不该再花一次（轨迹里只该有 a 的那两条）
        assertEquals(2, port.getTrace().size(), port.getTrace().toString());
        assertNull(run.getSynthesis());
        assertTrue(run.getAggregate().contains("a"), run.getAggregate());
    }

    @Test
    @DisplayName("被拒的步骤记为失败（不是静默跳过），原因是内核给的那句话")
    void run_shouldReportRejectedStepAsFailure() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）"));
        WorkflowSpec spec = spec(steps(step("a", "scout", "a", null, null)));

        WorkflowRun run = new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(run.hasFailure());
        assertTrue(run.firstFailure().getResult().getError().contains("已被禁用"));
    }

    @Test
    @DisplayName("还在「运行中的工具」区域写进度：谁开始了、谁什么结局")
    void run_shouldWriteProgressToSink() {
        RecordingPort port = new RecordingPort();
        RecordingSink sink = new RecordingSink();

        new WorkflowEngine(port, tracker()).run(spec(steps(step("a", "scout", "a", null, null))), "s-1",
                CancellationToken.NONE, sink);

        String text = sink.text();
        assertTrue(text.contains("a（scout）开始"), text);
        assertTrue(text.contains("a 完成"), text);
    }

    @Test
    @DisplayName("下游步骤的任务里带着它依赖的那几步给出的结论")
    void run_shouldInjectDependencyConclusionsIntoDownstreamPrompt() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.completed("run-a", "A 的结论", 1, 1L));
        port.answer("b", DelegationResult.completed("run-b", "B 的结论", 1, 1L));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", null, null),
                step("c", "planner", "c", Arrays.asList("a", "b"), null)));

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        // 子代理之间彼此隔离，上游结论只能由引擎转交——不转交，下游手里就什么都没有
        String prompt = port.requestFor("c").getPrompt();
        assertTrue(prompt.contains("## a（scout）"), prompt);
        assertTrue(prompt.contains("A 的结论"), prompt);
        assertTrue(prompt.contains("B 的结论"), prompt);
        // 原任务原样保留在材料之后
        assertTrue(prompt.endsWith(WorkflowEngine.TASK_LEAD + "c"), prompt);
    }

    @Test
    @DisplayName("没有依赖的步骤拿到的就是原样的任务，不被加料")
    void run_shouldNotDecoratePromptWithoutNeeds() {
        RecordingPort port = new RecordingPort();

        new WorkflowEngine(port, tracker()).run(spec(steps(step("a", "scout", "a", null, null))), "s-1",
                CancellationToken.NONE, null);

        assertEquals("a", port.requestFor("a").getPrompt());
    }

    @Test
    @DisplayName("依赖结论过长时只给头部，并在块首标注被截了多少字与完整记录的位置")
    void run_shouldTruncateLongDependencyConclusion() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.completed("run-a", repeat("x", 30_000), 1, 1L)
                .withArchivePath("/tmp/runs/run-a.json"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("c", "planner", "c", Arrays.asList("a"), null)));

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        String prompt = port.requestFor("c").getPrompt();
        // 标注必须落在块首：埋在两千字之后的提示，模型根本读不到
        int header = prompt.indexOf("## a（scout）");
        assertTrue(prompt.substring(header).startsWith("## a（scout）｜结论 30000 字，此处只给前 20000 字"),
                prompt.substring(header, header + 80));
        // 截断不是纯丢失：完整记录的路径一起给出去，下游可以按需自取
        assertTrue(prompt.contains("完整记录见 /tmp/runs/run-a.json"), prompt);
        assertTrue(prompt.contains("…（以上已截断）"), prompt);
        assertTrue(prompt.length() < 25_000, String.valueOf(prompt.length()));
    }

    @Test
    @DisplayName("未截断时不附归档路径，免得诱导下游去读并不需要的文件")
    void run_shouldNotAttachArchivePathWhenNotTruncated() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.completed("run-a", "短结论", 1, 1L)
                .withArchivePath("/tmp/runs/run-a.json"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("c", "planner", "c", Arrays.asList("a"), null)));

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        assertFalse(port.requestFor("c").getPrompt().contains("/tmp/runs/run-a.json"),
                port.requestFor("c").getPrompt());
    }

    @Test
    @DisplayName("依赖失败时如实写原因，不让下游以为那一步给出了结论")
    void run_shouldSpellOutFailedDependency() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("c", "planner", "c", Arrays.asList("a"), null)));

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(port.requestFor("c").getPrompt()
                .contains("## a（scout）｜没有给出文本（模型不可用）"), port.requestFor("c").getPrompt());
    }

    @Test
    @DisplayName("依赖被跳过时写明未执行的原因")
    void run_shouldSpellOutSkippedDependency() {
        RecordingPort port = new RecordingPort();
        port.answer("a", DelegationResult.failed("run-a", "模型不可用"));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", Arrays.asList("a"), "on_success"),
                step("c", "planner", "c", Arrays.asList("b"), null)));

        new WorkflowEngine(port, tracker()).run(spec, "s-1", CancellationToken.NONE, null);

        assertTrue(port.requestFor("c").getPrompt()
                .contains("## b（scout）｜未执行：前置步骤未成功：a"), port.requestFor("c").getPrompt());
    }

    @Test
    @DisplayName("额度不够时在派生任何子代理之前整份拒绝，并给出实际数字")
    void run_shouldRejectBeforeSpawningWhenQuotaTooSmall() {
        RecordingPort port = new RecordingPort();
        port.quota(DelegationQuota.of(2));
        WorkflowSpec spec = spec(steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", null, null),
                step("c", "scout", "c", null, null)));

        JellyfishException error = assertThrows(JellyfishException.class, () -> new WorkflowEngine(port, tracker())
                .run(spec, "s-1", CancellationToken.NONE, null));

        // 派到一半才发现额度用尽的话，钱已经花了，而且失败长得像「某几个步骤坏了」
        assertTrue(error.getMessage().contains("最多要派 3 个子代理"), error.getMessage());
        assertTrue(error.getMessage().contains("只剩 2 个名额"), error.getMessage());
        assertTrue(error.getMessage().contains("subAgent.maxSpawnsPerTurn"), error.getMessage());
        assertTrue(port.getTrace().isEmpty(), port.getTrace().toString());
    }

    @Test
    @DisplayName("汇总那一次也算额度：三步 spec 若要汇总就需要四个名额")
    void run_shouldCountSynthesizeAgainstQuota() {
        RecordingPort port = new RecordingPort();
        port.quota(DelegationQuota.of(3));
        WorkflowSpec spec = new WorkflowSpec("t", steps(
                step("a", "scout", "a", null, null),
                step("b", "scout", "b", null, null),
                step("c", "scout", "c", null, null)), AggregateMode.SUMMARIZE, "planner");

        JellyfishException error = assertThrows(JellyfishException.class, () -> new WorkflowEngine(port, tracker())
                .run(spec, "s-1", CancellationToken.NONE, null));

        assertTrue(error.getMessage().contains("最多要派 4 个子代理"), error.getMessage());
        assertTrue(port.getTrace().isEmpty(), port.getTrace().toString());
    }

    @Test
    @DisplayName("一个也派不了时直接用内核对原因的说法，不另编一句")
    void run_shouldRelyOnKernelReasonWhenBlocked() {
        RecordingPort port = new RecordingPort();
        port.quota(DelegationQuota.blocked("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）"));
        WorkflowSpec spec = spec(steps(step("a", "scout", "a", null, null)));

        JellyfishException error = assertThrows(JellyfishException.class, () -> new WorkflowEngine(port, tracker())
                .run(spec, "s-1", CancellationToken.NONE, null));

        assertTrue(error.getMessage().contains("已被禁用"), error.getMessage());
        assertTrue(port.getTrace().isEmpty(), port.getTrace().toString());
    }

    /**
     * 构造一个不带回调的台账：本测试只关心调度顺序，不观察面板状态。
     *
     * @return 台账
     */
    private static WorkflowTracker tracker() {
        return new WorkflowTracker(() -> {
            // 本测试不观察状态变化通知
        });
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
     * 构造一个步骤。测试里把「步骤 id」与「任务原文」取成同一个值，假端口因此能从下发的 prompt
     * 里找回该步（有依赖时引擎会在前面拼上材料，见 {@code RecordingPort.idOf}）。
     *
     * @param id    步骤标识
     * @param agent 子代理类型
     * @param prompt 任务原文
     * @param needs 依赖
     * @param when  条件
     * @return 步骤
     */
    private static WorkflowStep step(String id, String agent, String prompt, List<String> needs, String when) {
        return new WorkflowStep(id, null, agent, prompt, needs,
                StepCondition.fromWire(when, "when"));
    }

    /**
     * 重复一个单元串若干次（JDK 8 没有 {@code String.repeat}）。
     *
     * @param unit  单元串
     * @param times 次数
     * @return 拼接结果
     */
    private static String repeat(String unit, int times) {
        StringBuilder text = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            text.append(unit);
        }
        return text.toString();
    }

    /** 记录调度轨迹的假端口：{@code spawn} 与 {@code await} 各记一条。 */
    private static final class RecordingPort implements SubAgentPort {

        /** 调度轨迹，形如 {@code spawn:a} / {@code await:a}。 */
        private final List<String> trace = new ArrayList<String>();

        /** 收到的请求，按到达顺序。 */
        private final List<DelegationRequest> requests = new ArrayList<DelegationRequest>();

        /** 按 prompt（即步骤 id）给出的应答。 */
        private final Map<String, DelegationResult> answers = new LinkedHashMap<String, DelegationResult>();

        /** 本端口报告的派生额度；用例可换成「没额度」来验预检。 */
        private DelegationQuota quota = DelegationQuota.of(64);

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
         * 换掉本端口报告的派生额度。
         *
         * @param value 额度
         */
        void quota(DelegationQuota value) {
            this.quota = value;
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

        /**
         * 找某个步骤收到的那份请求。
         *
         * @param id 步骤标识
         * @return 请求；没派过时返回 {@code null}
         */
        DelegationRequest requestFor(String id) {
            for (DelegationRequest request : requests) {
                if (idOf(request).equals(id)) {
                    return request;
                }
            }
            return null;
        }

        @Override
        public DelegationHandle spawn(DelegationRequest request) {
            requests.add(request);
            trace.add("spawn:" + idOf(request));
            return new RecordingHandle(this, request);
        }

        @Override
        public DelegationQuota quota() {
            // 额度充裕：本测试关心调度，额度拒绝另有专门用例（把 quota 换掉即可）
            return quota;
        }

        /**
         * 从下发的任务原文里取回步骤标识。
         * <p>
         * 引擎会把依赖结论拼在任务原文之前（见 {@code WorkflowEngine.promptFor}），因此下游步骤的
         * prompt 不再等于 {@link #step} 里给的那个值——而本测试用「prompt = 步骤 id」的约定找回
         * 结果，所以要先剥掉注入的材料。汇总那一次没有注入，返回整段 prompt（它匹配不到任何
         * answer key），落到按 agent 兜底。
         *
         * @param request 委派请求
         * @return 步骤标识
         */
        private static String idOf(DelegationRequest request) {
            String prompt = request.getPrompt() == null ? "" : request.getPrompt();
            int marker = prompt.lastIndexOf(WorkflowEngine.TASK_LEAD);
            return marker < 0 ? prompt.trim()
                    : prompt.substring(marker + WorkflowEngine.TASK_LEAD.length()).trim();
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
            String id = RecordingPort.idOf(request);
            port.trace.add("await:" + id);
            // 步骤 id 优先，其次按 agent（汇总那一步没有稳定的 prompt）
            DelegationResult result = port.answers.containsKey(id)
                    ? port.answers.get(id) : port.answers.get(request.getAgentId());
            return result != null ? result
                    : DelegationResult.completed("run", "正文-" + id, 1, 5L);
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
