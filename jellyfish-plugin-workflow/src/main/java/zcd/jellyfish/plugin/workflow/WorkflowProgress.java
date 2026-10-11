package zcd.jellyfish.plugin.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次正在跑的编排的只读快照：面板读它，引擎写 {@link WorkflowTracker}。
 * <p>
 * <b>为什么要快照而不是把活对象交出去</b>：面板处理器跑在渲染线程上，而引擎跑在工具执行线程上，
 * 两者并发。交一个可变对象出去，读方会看到中间态，也会引诱读方去改它——与内核的
 * {@code AgentRunSnapshot} 同一口径：读的时候拷贝一份。
 * <p>
 * <b>为什么快照里要带上「依赖」与「层号」</b>：面板要画的是一张图（谁等谁、哪些能并行），
 * 光有「名字 + 状态」只能画出一列清单。依赖与层号在 spec 里就有、且从编排开始到结束都不变，
 * 因此它们是快照的一部分，而不是面板去猜的东西。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class WorkflowProgress {

    /** 编排名称（spec 里那份，缺省时给占位）。 */
    private final String name;

    /** 各步骤的只读状态，保持声明顺序。 */
    private final List<Step> steps;

    /** 是否正在做汇总（{@code summarize} 那一次额外的委派）。 */
    private final boolean summarizing;

    /** 编排开始的时刻（epoch 毫秒）。 */
    private final long startedAt;

    /** 汇总开始的时刻；没有在汇总时为 0。 */
    private final long summarizingSince;

    /**
     * 构造快照。
     *
     * @param name             编排名称，不可为 {@code null}
     * @param steps            各步骤状态，不可为 {@code null}
     * @param summarizing      是否正在做汇总
     * @param startedAt        编排开始时刻（epoch 毫秒）
     * @param summarizingSince 汇总开始时刻；没有在汇总时为 0
     */
    WorkflowProgress(String name, List<Step> steps, boolean summarizing, long startedAt,
                     long summarizingSince) {
        this.name = name;
        this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
        this.summarizing = summarizing;
        this.startedAt = startedAt;
        this.summarizingSince = summarizingSince;
    }

    /**
     * 获取编排名称。
     *
     * @return 名称，保证非 {@code null}
     */
    String getName() {
        return name;
    }

    /**
     * 获取各步骤状态。
     *
     * @return 不可变列表（声明顺序），保证非 {@code null}
     */
    List<Step> getSteps() {
        return steps;
    }

    /**
     * 判断是否正在做汇总。
     *
     * @return 正在汇总返回 {@code true}
     */
    boolean isSummarizing() {
        return summarizing;
    }

    /**
     * 获取编排开始时刻。
     *
     * @return epoch 毫秒
     */
    long getStartedAt() {
        return startedAt;
    }

    /**
     * 获取汇总开始时刻。
     *
     * @return epoch 毫秒；没有在汇总时为 0
     */
    long getSummarizingSince() {
        return summarizingSince;
    }

    /**
     * 取已经了结的步骤数：不再处于「待执行 / 进行中」的那些。
     * <p>
     * <b>失败与跳过也算「了结」</b>：它们不会再跑第二次，因此进度该往前走——否则「3 步跑了 2 步、
     * 其中 1 步失败」会显示成 1/3，看起来像卡住了。失败这件事由标题行的警示档位与步骤标记表达，
     * 不靠进度数字。
     *
     * @return 已了结步数，保证非负
     */
    int settledCount() {
        int count = 0;
        for (Step step : steps) {
            if (step.getState() != StepState.PENDING && step.getState() != StepState.RUNNING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 取正在跑的步骤数。
     *
     * @return 步骤数，保证非负
     */
    int runningCount() {
        int count = 0;
        for (Step step : steps) {
            if (step.getState() == StepState.RUNNING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断是否有步骤失败。
     *
     * @return 有失败返回 {@code true}
     */
    boolean hasFailure() {
        for (Step step : steps) {
            if (step.getState() == StepState.FAILED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个步骤的只读状态。
     * <p>
     * 它是不可变的值类型，因此可以直接嵌在这里，不必为它单独开一个文件。
     */
    static final class Step {

        /** 步骤标识。 */
        private final String id;

        /** 人类可读的名字，可为 {@code null}。 */
        private final String name;

        /** 子代理类型。 */
        private final String agent;

        /** 依赖的步骤标识，保证非 {@code null}。 */
        private final List<String> needs;

        /** 静态层号（从 1 起）。 */
        private final int layer;

        /** 在 spec 里的声明次序（从 0 起），同层排序用。 */
        private final int order;

        /** 此刻的状态。 */
        private final StepState state;

        /** 开始执行的时刻；还没轮到它时为 0。 */
        private final long startedAt;

        /** 落终态的时刻；还没结束时为 0。 */
        private final long settledAt;

        /**
         * 构造步骤状态。
         *
         * @param id        步骤标识，不可为空白
         * @param name      人类可读的名字，可为 {@code null}
         * @param agent     子代理类型，不可为空白
         * @param needs     依赖的步骤标识，可为 {@code null}
         * @param layer     静态层号（从 1 起）
         * @param order     在 spec 里的声明次序（从 0 起）
         * @param state     状态，不可为 {@code null}
         * @param startedAt 开始时刻；还没开始为 0
         * @param settledAt 落终态的时刻；还没结束为 0
         */
        Step(String id, String name, String agent, List<String> needs, int layer, int order, StepState state,
             long startedAt, long settledAt) {
            this.id = id;
            this.name = name;
            this.agent = agent;
            this.needs = needs == null || needs.isEmpty()
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(needs));
            this.layer = layer;
            this.order = order;
            this.state = state;
            this.startedAt = startedAt;
            this.settledAt = settledAt;
        }

        /**
         * 获取步骤标识。
         *
         * @return 步骤标识
         */
        String getId() {
            return id;
        }

        /**
         * 获取人类可读的名字。
         *
         * @return 名字；没写时为 {@code null}
         */
        String getName() {
            return name;
        }

        /**
         * 获取子代理类型。
         *
         * @return 子代理类型
         */
        String getAgent() {
            return agent;
        }

        /**
         * 获取依赖的步骤标识。
         *
         * @return 不可变列表，保证非 {@code null}
         */
        List<String> getNeeds() {
            return needs;
        }

        /**
         * 获取静态层号。
         *
         * @return 层号，从 1 起
         */
        int getLayer() {
            return layer;
        }

        /**
         * 获取声明次序。
         *
         * @return 次序，从 0 起
         */
        int getOrder() {
            return order;
        }

        /**
         * 获取状态。
         *
         * @return 状态，保证非 {@code null}
         */
        StepState getState() {
            return state;
        }

        /**
         * 获取开始时刻。
         *
         * @return epoch 毫秒；还没开始为 0
         */
        long getStartedAt() {
            return startedAt;
        }

        /**
         * 获取终态时刻。
         *
         * @return epoch 毫秒；还没结束为 0
         */
        long getSettledAt() {
            return settledAt;
        }
    }
}
