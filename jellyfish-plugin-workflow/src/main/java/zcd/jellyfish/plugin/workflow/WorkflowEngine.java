package zcd.jellyfish.plugin.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationQuota;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.api.subagent.SubAgentPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排引擎：按 spec 的依赖层序派生一批子代理，同一层并发执行，最后按声明聚合。
 * <p>
 * <b>并发靠「先全部派生、再逐个等待」，而不是自己起线程池</b>：{@link SubAgentPort#spawn(DelegationRequest)}
 * 立即返回，因此同一层的 N 个 run 已经在内核的 {@code agent-run} 池上并行跑；本引擎只是在调用方线程上
 * 逐个 {@code await}。并发度由内核的 governor 决定（超出的在核内排队），插件<b>不该有第二套并发控制</b>
 * ——两套上限互相不知道对方，就会出现「插件以为在并发、实际全在排队」这种查不出的现象。
 * <p>
 * <b>失败不中断整条编排</b>：一个步骤失败只影响它自己的下游（由各步的 {@code when} 决定），
 * 与它无依赖关系的步骤照常执行。这与「声明式 spec 的价值在于一次说清全貌」是一致的：
 * 让整批在第一步失败时全停，模型就得重新推演一遍剩下的部分。
 * <p>
 * <b>上游结论靠本引擎转交</b>：子代理之间彼此隔离——它们看不到对方、也看不到父会话，所以一个步骤
 * 要在自己的任务里拿到 {@code needs} 的结论，只能由引擎在下发时拼进去（见 {@code promptFor}）。
 * 材料是<b>有损</b>的：每个依赖各有字符额度，超出部分只给头部，并在块首标注被截了多少字、
 * 完整记录在哪个归档文件里（内核把每次 run 的完整过程归档落盘，路径经委派结果传出来）。
 * 有损是刻意的，理由见 {@code promptFor}：怕的不是撑爆窗口，而是下游撞上 token 预算后连结论都没有。
 * <p>
 * <b>取消是可传播的</b>：每次派生都把父回合的取消令牌带下去，因此用户按 Esc 时所有在途 run 会被内核
 * 取消；本引擎在检测到取消后不再派发新步骤，并把没跑的步骤如实记为「已取消」。
 * <p>
 * 无状态（除持有的端口外），可安全跨线程调用：每次 {@link #run} 的状态都局限在该次调用的局部变量里。
 *
 * @author zcd
 */
final class WorkflowEngine {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    /**
     * 一次转交给某个子代理的材料上限（字符）。
     * <p>
     * 两处共用同一个口径：汇总时各步正文的总和，以及一个步骤转发给它<b>直接依赖</b>的那些结论。
     * 取 20000 是对齐内核 {@code react.maxToolOutputChars} 的默认值，量级上是「不至于让下游撞上
     * {@code subAgent.runTokenBudget}（默认 50 万）」的安全区——预算是累计口径，材料每轮的输入
     * 都要重付一次。
     * <p>
     * <b>注入那一份是「每个下游步骤各算一份」</b>，与汇总那份（全局一整段）不是同一个计数范围。
     */
    private static final int MAX_MATERIAL_CHARS = 20_000;

    /**
     * 单个依赖块的最低保留额度（字符）。
     * <p>
     * 依赖多时均分会让每块只剩几百字符，极端情况会被截成 0——而「一个字都没有的依赖」在下游
     * 看来与「这一步没产出」无法区分，它会基于空白编，或者干脆忽略。宁可总长略微超出
     * {@link #MAX_MATERIAL_CHARS}，也要保证每个依赖能说清自己讲了什么。
     */
    private static final int MIN_MATERIAL_CHARS = 800;

    /** 材料块的行首引导：说明这段是什么、以及为什么可能互相冲突。 */
    private static final String MATERIAL_LEAD = "下面是你依赖的步骤各自给出的结论（它们由不同的子代理独立完成，"
            + "彼此可能冲突，请自行判断）：\n\n";

    /**
     * 任务原文的行首引导。
     * <p>
     * 包私有是为了让同包的测试能从下发的文本里剥回步骤标识（注入材料之后，下游的 prompt
     * 不再等于 {@code promptFor} 收到的原任务）。
     */
    static final String TASK_LEAD = "你的任务：";

    /** 子代理委派端口，来自内核。 */
    private final SubAgentPort port;

    /** 编排台账：把每一步的状态写出去，供面板读取。 */
    private final WorkflowTracker tracker;

    /**
     * 构造引擎。
     *
     * @param port    子代理委派端口，不可为 {@code null}
     * @param tracker 编排台账，不可为 {@code null}
     */
    WorkflowEngine(SubAgentPort port, WorkflowTracker tracker) {
        this.port = port;
        this.tracker = tracker;
    }

    /**
     * 执行一次编排。
     *
     * @param spec            校验过的 spec，不可为 {@code null}
     * @param parentSessionId 父会话标识，不可为空白
     * @param token           父回合的取消令牌，不可为 {@code null}
     * @param sink            工具输出旁路（用于在「运行中的工具」区域写进度），可为 {@code null}
     * @return 全部结局，保证非 {@code null}
     * @throws JellyfishException 子代理类型未知等准入问题由内核以结果形式回报，不会抛到这里
     */
    WorkflowRun run(WorkflowSpec spec, String parentSessionId, CancellationToken token, ToolOutputSink sink) {
        // 额度要在派生任何子代理之前查：派到一半才发现额度用尽，钱已经花了，而且失败长得像
        // 「某几个步骤坏了」，而不是「这份编排超出了本回合的额度」
        requireQuota(spec);
        // 先登记再干活：面板从一开始就能显示「还剩哪些步骤没跑」，否则它会像在倒退
        String workflowId = tracker.started(parentSessionId, spec);
        try {
            return execute(spec, parentSessionId, token, sink, workflowId);
        } finally {
            // 跑完即移除：面板展示的是「此刻在跑什么」，历史另有归档
            tracker.finished(workflowId);
        }
    }

    /**
     * 确认本回合的派生额度装得下这份 spec。
     * <p>
     * <b>为什么要问内核</b>：额度按顶层回合累计（且与 {@code task} 等别的调用方共享），插件自己算不出来。
     * <p>
     * <b>为什么用上界而不是精确值</b>：额度在派生时才被真正占用，而哪些步骤真的会跑取决于 {@code when}
     * （前置失败时下游可能整片跳过）。静态条件下能给出的确定值只有「全跑一遍要几个」——宁可拒绝一份
     * 本来也跑得下的 spec，也不要在跑到一半时失败：前者模型立刻知道该改什么，后者它得看完一片
     * 步骤失败才能自己推断出「是额度不够」。
     * <p>
     * <b>整份拒绝而不是能跑几步算几步</b>：与解析期同一条原则——部分执行会让模型无从判断
     * 「哪些做完了」。
     *
     * @param spec 校验过的 spec
     * @throws JellyfishException 额度装不下时抛出，消息里给出实际数字与可执行的下一步
     */
    private void requireQuota(WorkflowSpec spec) {
        DelegationQuota quota = port.quota();
        int needed = spec.getSteps().size() + (spec.getAggregateMode() == AggregateMode.SUMMARIZE ? 1 : 0);
        if (quota.getRemainingSpawns() >= needed) {
            return;
        }
        String reason = quota.getBlockedReason();
        if (quota.isBlocked() && reason != null && !reason.trim().isEmpty()) {
            // 一个也派不了：原因本身就是那句话（开关关了 / 没有回合 / 已用尽），不要自己另编一句
            throw new JellyfishException("无法编排：" + reason.trim());
        }
        throw new JellyfishException("这份编排最多要派 " + needed + " 个子代理，而本回合只剩 "
                + quota.getRemainingSpawns() + " 个名额（subAgent.maxSpawnsPerTurn）。"
                + "请拆成几次 workflow，或调大该配置。");
    }

    /**
     * 执行编排的主体。
     *
     * @param spec            校验过的 spec
     * @param parentSessionId 父会话标识
     * @param token           取消令牌
     * @param sink            工具输出旁路，可为 {@code null}
     * @param workflowId      台账标识
     * @return 全部结局，保证非 {@code null}
     */
    private WorkflowRun execute(WorkflowSpec spec, String parentSessionId, CancellationToken token,
                                ToolOutputSink sink, String workflowId) {
        Map<String, WorkflowStep> byId = new LinkedHashMap<String, WorkflowStep>();
        for (WorkflowStep step : spec.getSteps()) {
            byId.put(step.getId(), step);
        }
        Map<String, StepOutcome> decided = new LinkedHashMap<String, StepOutcome>();
        Set<String> settled = new LinkedHashSet<String>();

        while (settled.size() < spec.getSteps().size()) {
            List<WorkflowStep> layer = readyLayer(spec, settled);
            if (layer.isEmpty()) {
                // 解析期已拒环，这里只做防御：宁可少跑几步，也不要在这里转不出来
                LOG.warn("workflow 无法继续推进，剩余步骤按未执行处理: spec={}", spec.displayName());
                break;
            }
            Map<String, DelegationHandle> running = new LinkedHashMap<String, DelegationHandle>();
            for (WorkflowStep step : layer) {
                settled.add(step.getId());
                if (token.isCancelled()) {
                    StepOutcome stopped = StepOutcome.cancelled(step);
                    decided.put(step.getId(), stopped);
                    tracker.stepFinished(workflowId, step.getId(), StepState.SKIPPED);
                    report(sink, stopped);
                    continue;
                }
                String blocked = blockReason(step, decided);
                if (blocked != null) {
                    StepOutcome skipped = StepOutcome.notRun(step, blocked);
                    decided.put(step.getId(), skipped);
                    tracker.stepFinished(workflowId, step.getId(), StepState.SKIPPED);
                    report(sink, skipped);
                    continue;
                }
                progress(sink, "  · " + step.getId() + "（" + step.getAgent() + "）开始\n");
                tracker.stepStarted(workflowId, step.getId());
                running.put(step.getId(), port.spawn(new DelegationRequest(parentSessionId, step.getAgent(),
                        promptFor(step, decided), token)));
            }
            for (Map.Entry<String, DelegationHandle> entry : running.entrySet()) {
                StepOutcome outcome = StepOutcome.run(byId.get(entry.getKey()), entry.getValue().await());
                decided.put(entry.getKey(), outcome);
                tracker.stepFinished(workflowId, entry.getKey(),
                        outcome.succeeded() ? StepState.DONE : StepState.FAILED);
                report(sink, outcome);
            }
        }

        List<StepOutcome> ordered = new ArrayList<StepOutcome>(spec.getSteps().size());
        for (WorkflowStep step : spec.getSteps()) {
            StepOutcome outcome = decided.get(step.getId());
            ordered.add(outcome == null ? StepOutcome.notRun(step, "依赖无法满足，未执行") : outcome);
        }
        String aggregate = collect(ordered);
        DelegationResult synthesis = null;
        if (spec.getAggregateMode() == AggregateMode.SUMMARIZE) {
            tracker.summarising(workflowId);
            synthesis = summarize(spec, parentSessionId, token, sink, aggregate);
            if (synthesis != null && synthesis.hasText()) {
                aggregate = synthesis.getText().trim();
            }
        }
        return new WorkflowRun(spec, ordered, synthesis, aggregate);
    }

    /**
     * 挑出下一层可以执行的步骤：依赖都已决，且自己还没决。
     *
     * @param spec    生效的 spec
     * @param settled 已决的步骤标识
     * @return 该层的步骤（声明顺序），可能为空
     */
    private static List<WorkflowStep> readyLayer(WorkflowSpec spec, Set<String> settled) {
        List<WorkflowStep> layer = new ArrayList<WorkflowStep>();
        for (WorkflowStep step : spec.getSteps()) {
            if (!settled.contains(step.getId()) && settled.containsAll(step.getNeeds())) {
                layer.add(step);
            }
        }
        return layer;
    }

    /**
     * 判断一个步骤是否该被静态条件拦下。
     *
     * @param step    步骤
     * @param decided 已决步骤的结局
     * @return 不必执行时返回原因；应当执行时返回 {@code null}
     */
    private static String blockReason(WorkflowStep step, Map<String, StepOutcome> decided) {
        switch (step.getWhen()) {
            case ON_SUCCESS:
                // 无依赖时真空为真：它与 always 等价，模型写出来也没有坏处，因此不特别说明
                for (String need : step.getNeeds()) {
                    if (!decided.get(need).succeeded()) {
                        return "前置步骤未成功：" + need;
                    }
                }
                return null;
            case ON_FAILURE:
                for (String need : step.getNeeds()) {
                    if (decided.get(need).failed()) {
                        return null;
                    }
                }
                return "前置步骤没有失败";
            default:
                return null;
        }
    }

    /**
     * 组装一个步骤真正下发的那段任务原文：依赖的结论 + 原任务。
     * <p>
     * <b>为什么由插件注入、而不是让模型自己写进去</b>：spec 是一次性提交的，模型写下下游 prompt 的
     * 那一刻上游还没跑，它没有可填的东西；而子代理之间彼此隔离（{@code SubAgentLauncher} 的刻意设计），
     * 上游的结论只有插件能替它转交。
     * <p>
     * <b>为什么只给直接 {@code needs}</b>：那是模型声明的数据依赖。给全部已决步骤会让链式依赖重复
     * 转发（A 的结论跟着 B 进 C，A 又直接进 C），材料体积随深度增长，而 A 的内容一个字没变。
     * <p>
     * <b>为什么有损是刻意的</b>：材料一旦进了下游的会话历史，<b>每一轮输入都要重付一次</b>。
     * 约束总量的目的不是「怕撑爆上下文窗口」（2 万字符离那还远），而是「怕下游撞上
     * {@code subAgent.runTokenBudget}（默认 50 万）而以「预算用尽」收敛」——那时它连结论都没有。
     * 被截断的依赖会在块首拿到归档路径，可以按需自取（见 {@link #blockOf}）。
     *
     * @param step    待执行的步骤
     * @param decided 已决步骤的结局
     * @return 下发给子代理的任务原文，保证非空白
     */
    private static String promptFor(WorkflowStep step, Map<String, StepOutcome> decided) {
        if (step.getNeeds().isEmpty()) {
            return step.getPrompt();
        }
        String material = materialOf(step, decided);
        return material.isEmpty() ? step.getPrompt() : material + "\n\n" + TASK_LEAD + step.getPrompt();
    }

    /**
     * 把该步骤依赖的结论拼成一段材料。
     * <p>
     * <b>均分而不是按需分配</b>：短结论吃不完的额度不会让给长结论。这样每个块的额度只取决于
     * 「有几个依赖」，模型据此就能预期自己拿到多少；而「先满足短的、再把结余给长的」虽然更省，
     * 却让额度变成一个要经过一轮分配才能算出的数。额度是上限而非预扣，因此实际总长不会超出预算。
     *
     * @param step    待执行的步骤
     * @param decided 已决步骤的结局
     * @return 材料文本，保证非空白
     */
    private static String materialOf(WorkflowStep step, Map<String, StepOutcome> decided) {
        List<String> needs = step.getNeeds();
        int quota = Math.max(MIN_MATERIAL_CHARS, MAX_MATERIAL_CHARS / needs.size());
        StringBuilder material = new StringBuilder(MATERIAL_LEAD);
        for (String need : needs) {
            material.append(blockOf(decided.get(need), quota)).append('\n');
        }
        return material.toString().trim();
    }

    /**
     * 渲染一个依赖块。
     * <p>
     * <b>截断标注放在块首而不是截断处</b>：模型从上往下读，埋在几千字之后的提示等于不存在，
     * 而「这份材料不完整」恰恰是它读这一段之前就该知道的事。
     * <p>
     * <b>保留头部</b>：与内核工具输出的「头 30% + 尾 70%」刻意相反——那边结论常在末尾（编译错误、
     * 测试结果），而这里给出去的是一段结论，重点在开头。
     * <p>
     * <b>被截断时附上归档路径</b>：那是这次 run 的完整 transcript（内核收尾时落盘）。没有它，
     * 截断就是纯丢失；有了它，下游可以按需自取——与工具结果落盘后在信封里留路径同一口径。
     * 未截断时不附，免得诱导它去读一份并不需要的文件。
     *
     * @param outcome 该依赖的结局
     * @param quota   该块的字符额度
     * @return 材料块，保证非空白
     */
    private static String blockOf(StepOutcome outcome, int quota) {
        WorkflowStep step = outcome.getStep();
        String header = "## " + step.getId() + "（" + step.getAgent() + "）";
        if (!outcome.hasRun()) {
            // 未执行不是「没产出」：写清原因，下游才能判断该不该继续
            return header + "｜未执行：" + outcome.getNotRunReason();
        }
        DelegationResult result = outcome.getResult();
        String text = result.getText() == null ? "" : result.getText().trim();
        if (text.isEmpty()) {
            return header + "｜没有给出文本（" + reasonOf(result) + "）";
        }
        if (text.length() <= quota) {
            return header + "\n" + text;
        }
        StringBuilder note = new StringBuilder("｜结论 ").append(text.length())
                .append(" 字，此处只给前 ").append(quota).append(" 字");
        String path = result.getArchivePath();
        if (path != null && !path.trim().isEmpty()) {
            note.append("；完整记录见 ").append(path.trim()).append("（可用 read_file 读取）");
        }
        return header + note + "\n" + head(text, quota) + "\n…（以上已截断）";
    }

    /**
     * 取一个委派结果里可读的原因。
     *
     * @param result 委派结果
     * @return 原因文本，保证非空白
     */
    private static String reasonOf(DelegationResult result) {
        String error = result.getError();
        if (error != null && !error.trim().isEmpty()) {
            return error.trim();
        }
        return statusText(result.getStatus());
    }

    /**
     * 保留头部地截断一段文本，切口不落在代理对中间。
     * <p>
     * <b>为什么不能直接 {@code substring}</b>：切开一个代理对会让下游收到半个字符，
     * 它既读不懂，也可能把那段乱码当成有效内容。
     *
     * @param text     原文本
     * @param maxChars 字符上限
     * @return 截断结果
     */
    private static String head(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        // charAt(end) 是第一个不含在内的字符；它是低位代理，说明它的高位被切在了界内
        if (Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 把各步正文按声明顺序收集成一段文本。
     *
     * @param steps 各步结局（声明顺序）
     * @return 聚合文本，保证非 {@code null}（可能为空）
     */
    private static String collect(List<StepOutcome> steps) {
        StringBuilder text = new StringBuilder();
        for (StepOutcome outcome : steps) {
            if (!outcome.hasRun() || !outcome.getResult().hasText()) {
                continue;
            }
            text.append("## ").append(outcome.getStep().getId())
                    .append("（").append(outcome.getStep().getAgent()).append("）\n")
                    .append(outcome.getResult().getText().trim()).append("\n\n");
        }
        return text.toString().trim();
    }

    /**
     * 再派一个子代理，把各步正文汇成一段结论。
     * <p>
     * <b>没有任何正文时不做汇总</b>：全部步骤都失败或未执行时，汇总子代理只能拿到一段空材料，
     * 它要么编、要么反问，两者都比「如实汇报没有结果」更糟。
     *
     * @param spec            生效的 spec
     * @param parentSessionId 父会话标识
     * @param token           取消令牌
     * @param sink            工具输出旁路，可为 {@code null}
     * @param digest          各步正文
     * @return 汇总结果；没做汇总时为 {@code null}
     */
    private DelegationResult summarize(WorkflowSpec spec, String parentSessionId, CancellationToken token,
                                       ToolOutputSink sink, String digest) {
        if (digest.trim().isEmpty()) {
            return null;
        }
        String material = digest.length() > MAX_MATERIAL_CHARS
                ? head(digest, MAX_MATERIAL_CHARS) + "\n\n（材料过长，以上已截断）"
                : digest;
        String prompt = "下面是同一次任务里几个子代理各自给出的结论。把它们汇总成一段结论："
                + "优先保留彼此一致的事实，明确指出相互冲突的地方，不要逐条复述，也不要编造材料里没有的信息。\n\n"
                + material;
        progress(sink, "  · 汇总（" + spec.getAggregateAgent() + "）开始\n");
        DelegationResult result = port.spawn(new DelegationRequest(parentSessionId, spec.getAggregateAgent(),
                prompt, token)).await();
        progress(sink, "  · 汇总 " + statusText(result.getStatus()) + "\n");
        return result;
    }

    /**
     * 在「运行中的工具」区域写一行进度。
     *
     * @param sink 工具输出旁路，可为 {@code null}
     * @param line 一行文本
     */
    private static void progress(ToolOutputSink sink, String line) {
        if (sink != null) {
            sink.write(line);
        }
    }

    /**
     * 写一个步骤的结局。
     *
     * @param sink    工具输出旁路，可为 {@code null}
     * @param outcome 结局
     */
    private static void report(ToolOutputSink sink, StepOutcome outcome) {
        if (!outcome.hasRun()) {
            progress(sink, "  · " + outcome.getStep().getId() + " 未执行：" + outcome.getNotRunReason() + "\n");
            return;
        }
        progress(sink, "  · " + outcome.getStep().getId() + " "
                + statusText(outcome.getResult().getStatus()) + "\n");
    }

    /**
     * 把委派终态翻成一行中文。
     *
     * @param status 终态
     * @return 可读文本
     */
    static String statusText(DelegationStatus status) {
        switch (status) {
            case COMPLETED:
                return "完成";
            case TRUNCATED:
                return "达到轮数上限";
            case CANCELLED:
                return "已取消";
            case REJECTED:
                return "未开始";
            default:
                return "失败";
        }
    }
}
