package zcd.jellyfish.plugin.pet;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一个会话养出来的那只宠物：它的「身体」由这个会话里真实发生过的事累积而成。
 * <p>
 * <b>它解决什么隐形问题</b>：仪表盘上的数字会被划过去，镜子里的自己不会。人对「关于自己的数据」
 * 有一套天然的回避机制——数字可以略过、可以归咎于工具、可以明天再说；而一个<b>因为你的行为变了形</b>
 * 的东西绕过了这套回避：你不是在读一个数，你是在看一个结果落在自己身上。
 * <p>
 * <b>判据必须难以伪造</b>：全部指标都来自真实事件累积，没有一个是「敲一下加一点」的计数器——
 * 疲惫是墙钟时长（等不来也刷不出来）、肥胖是真实 token 消耗、伤痕是连续失败、
 * 警惕是被真正打断过。若用「工具调用次数」这类廉价信号，宠物会退化成一个刷数据的游戏。
 * <p>
 * <b>两条「会变」的度量，两种脾气</b>：
 * <ul>
 *     <li><b>疲惫</b>随墙钟走，因此它在<b>读的时候现算</b>而不是在事件里累加——事件不会每秒来一次，
 *     而「连续工作了多久」这件事每秒都在变。连续段的判定见 {@link #IDLE_RESET_MILLIS}；</li>
 *     <li><b>肥胖</b>只增不减（它记的是这个会话已经烧掉多少），因此事件里累加即可。</li>
 * </ul>
 * <p>
 * <b>不顺的时候它应当沉默</b>：本类只负责「它现在是什么样」，所有鼓励性文案都不在这里——
 * 情感反馈的价值在分寸不在频率，失败时被强行鼓励非常烦人，而一个在旁边安静待着的东西是安慰。
 * <p>
 * <b>线程安全</b>：写发生在事件派发线程、读发生在渲染线程，因此全部方法都是 {@code synchronized}。
 * <p>
 * <b>它不持久化</b>：宠物是这个进程里养出来的，会话恢复后从寻常重新开始。
 *
 * @author zcd
 */
final class SessionPet {

    /**
     * 连续活动段的空闲重置间隔。
     * <p>
     * <b>为什么必须有这条规则</b>：内核没有回合起止通知，连续工作时长只能从事件间隙推断。
     * 没有它，「连续工作」就退化成「会话开着多久了」——你去开了一小时会回来，宠物还在替你喊累，
     * 那时它就不再是镜子，只是一个计时器。
     * <p>
     * 五分钟取得足够宽：一次模型调用加一轮工具执行不会超过它（单个子代理的墙钟上限也才 5 分钟），
     * 因此正常的一串工作不会被误判成两段。
     */
    static final long IDLE_RESET_MILLIS = 5L * 60L * 1000L;

    /**
     * 形成伤痕所需的连续失败次数。
     * <p>
     * 一次失败是常事；连续三次是环境出了问题（编译不过、依赖没装、端口占用）。
     * 门槛放在「连续」上，因此偶发失败会随下一次成功清零，不会悄悄攒出伤来。
     */
    static final int WOUND_STREAK = 3;

    /**
     * 变得警惕所需的被打断次数。
     * <p>
     * <b>一次不算「频繁」</b>：一次打断往往只是试错（发现问错了、模型跑偏了）。
     * 而频繁打断是习惯，宠物对这种习惯的反馈是缩起来。同一次取消只会被内核上报一遍
     * （见 {@code TurnCancelledEvent}），因此这个计数是可信的。
     */
    static final int WARY_CANCELS = 2;

    /**
     * 疲惫到显出「疲惫」姿态的比例门槛。
     * <p>
     * 条是幅度、姿态是门槛：条爬到一半时宠物还撑得住，爬到四分之三就该看出来了。
     */
    static final double TIRED_RATIO = 0.75;

    /** 当前时间来源，便于测试推进时间。 */
    private final java.util.function.LongSupplier clock;

    /** 时区来源：判断「夜行」要按用户本地时间。 */
    private final ZoneId zone;

    /** 疲惫满格对应的时长（毫秒）。 */
    private final long fatigueFullMillis;

    /** 肥胖满格对应的 token 数。 */
    private final long obesityFullTokens;

    /** 认定「夜行」的小时（本地时间，含）。 */
    private final int nightHour;

    /** 当前连续活动段的起点（epoch 毫秒）；还没有任何活动时无意义。 */
    private long segmentStartAt;

    /**
     * 是否已经有过活动。
     * <p>
     * <b>不能用「{@code segmentStartAt == 0}」当哨兵</b>：{@code 0} 是一个合法时刻
     * （epoch 起点），把它当「还没有活动」会让钟从 0 开始的那一档测试与任何时钟被重置的环境
     * 静默算错——而错法是「疲惫永远少算一段时间」这种没人会怀疑到哨兵头上的样子。
     */
    private boolean active;

    /** 最后一次活动时刻（epoch 毫秒）；还没有任何活动时无意义。 */
    private long lastEventAt;

    /** 本会话累计消耗的 token。 */
    private long totalTokens;

    /** 当前连续失败次数。 */
    private int failureStreak;

    /** 是否已经留下伤痕（一旦形成就留下：伤痕是既成事实，不是瞬时状态）。 */
    private boolean wounded;

    /** 本会话被打断的次数。 */
    private int cancels;

    /** 是否在深夜工作过（本会话有过 23 点之后的活动）。 */
    private boolean night;

    /**
     * 构造宠物。
     *
     * @param clock             当前时间来源（epoch 毫秒），不可为 {@code null}
     * @param zone              本地时区，不可为 {@code null}
     * @param fatigueFullMillis 疲惫满格对应的时长（毫秒），小于 1 时按 1 处理
     * @param obesityFullTokens 肥胖满格对应的 token 数，小于 1 时按 1 处理
     * @param nightHour         认定夜行的小时（0..23）
     */
    SessionPet(java.util.function.LongSupplier clock, ZoneId zone, long fatigueFullMillis, long obesityFullTokens,
               int nightHour) {
        this.clock = clock;
        this.zone = zone;
        this.fatigueFullMillis = Math.max(1L, fatigueFullMillis);
        this.obesityFullTokens = Math.max(1L, obesityFullTokens);
        this.nightHour = Math.max(0, Math.min(23, nightHour));
    }

    /**
     * 记一次模型调用。
     *
     * @param totalTokens 本次调用的总 token 数，可为 {@code null}（厂商未上报时是「未知」而不是 0）
     */
    synchronized void onLlmCall(Integer totalTokens) {
        long now = markActivity();
        if (totalTokens != null && totalTokens > 0) {
            this.totalTokens += totalTokens;
        }
        // 模型调用失败不算「工具的连续失败」：那是两条不同的信号，混在一起会让伤痕来得莫名其妙
        this.lastEventAt = now;
    }

    /**
     * 记一次工具调用，失败会累积成伤痕。
     *
     * @param success 本次是否成功
     */
    synchronized void onToolCall(boolean success) {
        markActivity();
        if (success) {
            failureStreak = 0;
            return;
        }
        failureStreak++;
        if (failureStreak >= WOUND_STREAK) {
            wounded = true;
        }
    }

    /**
     * 记一次回合被用户打断。
     */
    synchronized void onTurnCancelled() {
        markActivity();
        cancels++;
    }

    /**
     * 取疲惫比例（{@code [0, 1]}）。
     * <p>
     * <b>空闲时归零，而不是接着涨</b>：超过 {@link #IDLE_RESET_MILLIS} 没有事件，说明人已经离开，
     * 此时宠物在休息——连「已工作时长」这个概念都不成立。这条判断放在读路径上（而不是写路径里
     * 重置起点）是为了让渲染线程仍然只读：它算一个数，不去改任何状态。
     *
     * @return 疲惫比例，保证在 {@code [0, 1]} 内
     */
    synchronized double fatigueRatio() {
        return (double) fatigueMillis() / fatigueFullMillis;
    }

    /**
     * 取当前连续活动段的时长。
     *
     * @return 时长（毫秒）；空闲或还没有活动时返回 0
     */
    synchronized long fatigueMillis() {
        if (!active) {
            return 0L;
        }
        long now = clock.getAsLong();
        if (now - lastEventAt > IDLE_RESET_MILLIS) {
            return 0L;
        }
        return Math.max(0L, now - segmentStartAt);
    }

    /**
     * 取肥胖比例（{@code [0, 1]}）。
     *
     * @return 肥胖比例，保证在 {@code [0, 1]} 内
     */
    synchronized double obesityRatio() {
        return Math.min(1.0, (double) totalTokens / obesityFullTokens);
    }

    /**
     * 取本会话累计消耗的 token。
     *
     * @return 累计 token 数，保证非负
     */
    synchronized long totalTokens() {
        return totalTokens;
    }

    /**
     * 判断是否留下了伤痕。
     *
     * @return 有过连续失败返回 {@code true}
     */
    synchronized boolean isWounded() {
        return wounded;
    }

    /**
     * 判断是否变得警惕。
     *
     * @return 被打断次数达到门槛返回 {@code true}
     */
    synchronized boolean isWary() {
        return cancels >= WARY_CANCELS;
    }

    /**
     * 判断是否在深夜工作过。
     *
     * @return 本会话有过 23 点之后的活动返回 {@code true}
     */
    synchronized boolean isNight() {
        return night;
    }

    /**
     * 判断是否疲惫到该换姿态。
     *
     * @return 疲惫比例达到 {@link #TIRED_RATIO} 返回 {@code true}
     */
    synchronized boolean isTired() {
        return fatigueRatio() >= TIRED_RATIO;
    }

    /**
     * 取当前姿态。
     * <p>
     * 优先级见 {@link Posture}：伤是既成事实、警惕是当下的应激、疲惫是缓慢累积，
     * 越靠前越该占据那张脸。
     *
     * @return 姿态，保证非 {@code null}
     */
    synchronized Posture posture() {
        if (wounded) {
            return Posture.WOUNDED;
        }
        if (isWary()) {
            return Posture.WARY;
        }
        if (isTired()) {
            return Posture.TIRED;
        }
        return Posture.ORDINARY;
    }

    /**
     * 记一次活动：维护连续活动段、空闲重置与「夜行」标记。
     *
     * @return 本次活动时刻（epoch 毫秒）
     */
    private long markActivity() {
        long now = clock.getAsLong();
        if (!active || now - lastEventAt > IDLE_RESET_MILLIS) {
            segmentStartAt = now;
        }
        active = true;
        lastEventAt = now;
        if (hourOf(now) >= nightHour) {
            night = true;
        }
        return now;
    }

    /**
     * 取某个时刻的本地小时。
     *
     * @param epochMillis 时刻（epoch 毫秒）
     * @return 本地小时（0..23）
     */
    private int hourOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).getHour();
    }
}
