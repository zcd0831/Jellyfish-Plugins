package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 认领者的在场记录：谁还在做那条待办。
 * <p>
 * <b>为什么需要它</b>：待办里记的是认领者的 <b>run 标识</b>——那是一等公民的身份，但它对人不可读。
 * 「谁在做」要显示成子代理类型（{@code researcher}），而这层信息只有内核的 run 通知里有
 * （见 P3b 的 {@link AgentRunProgressEvent}）。本类把那些通知攒成一张 {@code runId → 类型 + 是否已结束} 的表。
 * <p>
 * <b>它认识的是内核，不是编排插件</b>：本类只订阅内核的事件类型，因此「编排里的子代理」与
 * 「{@code task} 派出去的子代理」在它眼里完全一样——这正是把展示数据下沉到内核中li面换来的解耦
 * （分册 D-P3-7）：删掉 workflow 插件，这里的代码与行为一个字都不变。
 * <p>
 * <b>通知可丢，所以必须自愈</b>：投递走的是异步通知通道，队列满即丢。因此本类不能假设
 * 「有开始就一定有结束」——超过 {@link #STALE_MILLIS} 没等到结束的条目<b>不再算「正在做」</b>，
 * 而是回落成「不知道」。否则一个丢失的结束事件会让面板永远显示「正在跑」，而那条待办可能早就没人管了。
 * <p>
 * 线程安全：读发生在渲染线程、写发生在发布线程，状态表因而用并发容器，单条状态对象不可变。
 *
 * @author zcd
 */
final class RunPresence {

    /**
     * 「还在做」的有效期。
     * <p>
     * 取得比内核的单 run 墙钟上限（默认 5 分钟）宽：超过它还没收到结束事件，才判定那条通知丢了。
     * 取得太短会把正常在跑的 run 说成「不知道」，取得太长则让面板在真丢事件后久久不修正。
     */
    static final long STALE_MILLIS = 15 * 60 * 1000L;

    /** 已知的 run → 在场状态。 */
    private final Map<String, Presence> presence = new ConcurrentHashMap<String, Presence>();

    /** 当前时间来源，便于测试推进时间。 */
    private final LongSupplier clock;

    /**
     * 构造在场记录。
     */
    RunPresence() {
        this(System::currentTimeMillis);
    }

    /**
     * 构造在场记录，并指定时间来源。
     *
     * @param clock 当前时间（epoch millis），不可为 {@code null}
     */
    RunPresence(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 记录一条 run 通知。
     * <p>
     * 不认识的 run 与不认识的事件都按「无事发生」处理：这只是展示数据，
     * 为一条通知的形状变化在这里抛错会让面板整块消失。
     *
     * @param event run 通知，可为 {@code null}
     */
    void on(AgentRunProgressEvent event) {
        if (event == null || event.getRunId() == null) {
            return;
        }
        if (event.isFinished()) {
            presence.put(event.getRunId(), new Presence(event.getAgentId(), now(), true));
            return;
        }
        presence.put(event.getRunId(), new Presence(event.getAgentId(), now(), false));
    }

    /**
     * 取某个 run 此刻的在场情况。
     * <p>
     * 超过 {@link #STALE_MILLIS} 没等到结束的条目在这里被判定为过期，并顺手清掉——
     * 读路径上顺带回收，面板就不需要另一个清理时机。
     *
     * @param runId run 标识，可为 {@code null}
     * @return 在场状态；不知道这个 run 时返回 {@link Presence#unknown()}
     */
    Presence stateOf(String runId) {
        if (runId == null) {
            return Presence.unknown();
        }
        Presence state = presence.get(runId);
        if (state == null) {
            return Presence.unknown();
        }
        if (!state.finished && now() - state.seenAt > STALE_MILLIS) {
            presence.remove(runId);
            return Presence.unknown();
        }
        return state;
    }

    /**
     * 取当前时间。
     *
     * @return 当前时间（epoch millis）
     */
    private long now() {
        return clock.getAsLong();
    }

    /**
     * 一个认领者此刻的在场情况。
     * <p>
     * 三种形态对应面板上的三种说法：还在做、已经结束（但那条待办没被标完成）、不知道。
     */
    static final class Presence {

        /** 子代理类型；不知道时为 {@code null}。 */
        private final String agentId;

        /** 最后一次听说它的时间。 */
        private final long seenAt;

        /** 是否已收到结束通知。 */
        private final boolean finished;

        /**
         * 构造在场状态。
         *
         * @param agentId  子代理类型，可为 {@code null}
         * @param seenAt   最后一次听说的时刻（epoch millis）
         * @param finished 是否已收到结束通知
         */
        private Presence(String agentId, long seenAt, boolean finished) {
            this.agentId = agentId;
            this.seenAt = seenAt;
            this.finished = finished;
        }

        /**
         * 构造「不知道」。
         *
         * @return 在场状态，保证非 {@code null}
         */
        static Presence unknown() {
            return new Presence(null, 0L, false);
        }

        /**
         * 获取子代理类型。
         *
         * @return 子代理类型；不知道时为 {@code null}
         */
        String getAgentId() {
            return agentId;
        }

        /**
         * 判断是否知道这个 run。
         *
         * @return 知道返回 {@code true}
         */
        boolean isKnown() {
            return agentId != null;
        }

        /**
         * 判断是否已结束。
         *
         * @return 收到过结束通知返回 {@code true}
         */
        boolean isFinished() {
            return finished;
        }
    }
}
