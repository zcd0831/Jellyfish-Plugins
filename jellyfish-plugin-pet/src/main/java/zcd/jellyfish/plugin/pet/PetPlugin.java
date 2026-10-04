package zcd.jellyfish.plugin.pet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.time.ZoneId;

/**
 * 官方宠物插件：一只住在侧栏里的宠物，<b>它的样子是这个会话工作习惯的镜像</b>。
 * <p>
 * <b>它解决什么隐形问题</b>：人对「关于自己的数据」有一整套回避机制——数字可以略过、可以归咎于工具、
 * 可以明天再说。所以「你已经连续工作三小时」这类提示，无论做得多醒目，最终都会被无视：
 * 它是一句<b>关于你的事实</b>，却长成了一条可以关掉的通知。而一个<b>因为你的行为变了形</b>的东西
 * 绕过了这套回避：你不是在读一个数，你是在看一个结果落在自己身上。同样的数据，一个成了信息，
 * 一个成了处境。
 * <p>
 * <b>它靠什么变</b>（判据全部来自内核已有的通知，一条都不依赖别的插件）：
 * <ul>
 *     <li><b>疲惫</b> ← 连续工作时长（相邻事件间隔超过 {@link SessionPet#IDLE_RESET_MILLIS} 即视为新的一段）。
 *     它随墙钟走，所以是<b>读的时候现算</b>；</li>
 *     <li><b>肥胖</b> ← 本会话累计 token（{@link LlmCallCompletedEvent} 的 {@code totalTokens}）。</li>
 *     <li><b>伤痕</b> ← 工具连续失败（{@link ToolCallCompletedEvent#isSuccess()}）；</li>
 *     <li><b>警惕</b> ← 回合被打断（{@link TurnCancelledEvent}）；</li>
 *     <li><b>夜行</b> ← 本会话有过深夜的活动（事件自带的时刻）。</li>
 * </ul>
 * <p>
 * <b>判据必须难以伪造</b>：上面没有一个是「敲一下加一点」的计数器——疲惫是墙钟（等不来也刷不出来）、
 * 肥胖是真实消耗、伤痕是连续失败、警惕是被真正打断过。若用「工具调用次数」这类廉价信号，
 * 宠物会退化成一个刷数据的游戏；而一旦开始为了看反应去做无意义的事，它就从镜子变成了糖。
 * <p>
 * <b>它只做三件事，且都不是「鼓励」</b>：照实显示身体、变换姿态、沉默。失败时不去打气——
 * 情感反馈的价值在分寸不在频率，一个在旁边安静待着的东西是安慰，一个强行加油的东西是干扰。
 * <p>
 * <b>外形是插件固定的，不是模型生成的</b>：插件没有独立发起模型调用的通路（它能让模型看见东西的唯一
 * 方式是把内容投进正在跑的回合），因此「用户描述外貌 → 当场生成」这条链路并不存在。
 * 按固定精灵 + 行为演化成立——而这恰恰够了：<b>工作镜的力量在演化，不在初始外观</b>。
 * <p>
 * <b>它是「会变的宠物」，不是「会动的宠物」</b>：面板由外壳在特定时机来问一次，不是每帧连续渲染。
 * 所以它不会在框里平滑游走，只会在有事发生时换一个姿态。
 * <p>
 * <b>不持久化</b>：宠物是这个进程里养出来的，会话关闭即消失。跨会话累积需要自建文件与一套定义
 * 「哪些旧经历该留」的规则，而它的价值（今天过得怎么样）本来就是当下的。
 * <p>
 * 配置见 {@link PetConfig}：三项「什么时候算过头」的刻度。
 *
 * @author zcd
 */
public final class PetPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PetPlugin.class);

    /**
     * 面板在它建议的区域里的顺序。
     * <p>
     * 右栏还有别的常驻观测面板时（例如趋势），同一区域只显示 {@code order} 最小的那一个。
     * 趋势是「有数据就看得到」的客观信息，宠物是氛围，因此宠物排在后面——它被挤掉时损失最小，
     * 而需要它的人用 {@code /ui} 一次指定即可。
     */
    private static final int PANEL_ORDER = 10;

    /** 宠物台账；{@code start} 之前与 {@code stop} 之后为 {@code null}。 */
    private volatile PetStore store;

    /** 插件上下文，供事件回调广播失效通知；停止后置 {@code null}。 */
    private volatile PluginContext context;

    @Override
    public void start(PluginContext context) {
        // 先解析配置再订阅：处理器一旦挂上就可能被调用，刻度必须已经就绪
        PetConfig config = PetConfig.from(context.configuration(), context.pluginId(), context::emit);
        PetStore pets = new PetStore(System::currentTimeMillis, ZoneId.systemDefault(),
                config.fatigueFullMillis(), config.obesityFullTokens(), config.nightHour());
        this.store = pets;
        this.context = context;
        context.observe(LlmCallCompletedEvent.class, this::onLlmCallCompleted);
        context.observe(LlmCallFailedEvent.class, this::onLlmCallFailed);
        context.observe(ToolCallCompletedEvent.class, this::onToolCallCompleted);
        context.observe(TurnCancelledEvent.class, this::onTurnCancelled);
        context.observe(SessionClosedEvent.class, this::onSessionClosed);
        context.contribute(PanelContributionRequest.class, new PetPanel(pets), RegisterOptions.order(PANEL_ORDER));
        LOG.info("宠物插件已启动: fatigueFullMinutes={} obesityFullTokens={} nightHour={}",
                config.fatigueFullMillis() / 60000L, config.obesityFullTokens(), config.nightHour());
    }

    @Override
    public void stop() {
        // 没有后台线程要停，也没有资源要放——但宠物是插件自己的内存，随停止一起丢掉更干净
        PetStore pets = this.store;
        if (pets != null) {
            pets.clear();
        }
        this.store = null;
        this.context = null;
        LOG.info("宠物插件已停止");
    }

    /**
     * 记一次模型调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onLlmCallCompleted(LlmCallCompletedEvent event) {
        affect(() -> store().onLlmCallCompleted(event));
    }

    /**
     * 记一次失败的模型调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onLlmCallFailed(LlmCallFailedEvent event) {
        affect(() -> store().onLlmCallFailed(event));
    }

    /**
     * 记一次工具调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onToolCallCompleted(ToolCallCompletedEvent event) {
        affect(() -> store().onToolCallCompleted(event));
    }

    /**
     * 记一次被打断，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onTurnCancelled(TurnCancelledEvent event) {
        affect(() -> store().onTurnCancelled(event));
    }

    /**
     * 会话关闭时丢掉它养出来的那只宠物。
     * <p>
     * 不发失效通知：没有宠物就没有面板，而「这个会话已经不在了」这件事外壳自己知道。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onSessionClosed(SessionClosedEvent event) {
        PetStore pets = this.store;
        if (pets != null) {
            pets.onSessionClosed(event);
        }
    }

    /**
     * 让宠物经历一件事，然后通知外壳「我贡献的内容脏了」。
     * <p>
     * <b>两处容错都是刻意的</b>：记录本身绝不该把异常抛回事件通道（那只会换来一条 WARN，
     * 而宠物整只消失）；失效通知在插件停止后会当场抛错，而此时「刷新界面」已经无关紧要。
     * 展示数据的失败不该升级成业务失败。
     *
     * @param effect 对宠物的影响
     */
    private void affect(Runnable effect) {
        PetStore pets = this.store;
        if (pets == null) {
            // 停止之后事件仍可能在队列里排空：丢掉即可，不必往前追溯
            return;
        }
        try {
            effect.run();
        } catch (RuntimeException e) {
            LOG.warn("宠物记录失败，本次丢弃", e);
            return;
        }
        invalidate();
    }

    /**
     * 广播一条 UI 失效通知。
     */
    private void invalidate() {
        PluginContext current = this.context;
        if (current == null) {
            return;
        }
        try {
            current.emit(new UiInvalidatedEvent());
        } catch (RuntimeException e) {
            // 插件正在停止时上下文已失效：这一次刷新无关紧要，但也不该静默丢掉线索
            LOG.debug("宠物失效通知发送失败（插件可能正在停止）", e);
        }
    }

    /**
     * 取宠物台账。
     *
     * @return 台账，保证非 {@code null}
     * @throws IllegalStateException 插件未启动时抛出（调用点已先行判空，走到这里说明有编程错误）
     */
    private PetStore store() {
        PetStore pets = this.store;
        if (pets == null) {
            throw new IllegalStateException("pet plugin is not started");
        }
        return pets;
    }
}
