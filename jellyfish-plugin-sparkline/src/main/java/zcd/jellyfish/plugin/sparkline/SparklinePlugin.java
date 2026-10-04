package zcd.jellyfish.plugin.sparkline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方火花线插件：把会话的趋势压成一行块字符，让终端里第一次有「形状」这个维度。
 * <p>
 * <b>它解决什么隐形问题</b>：终端里的每个指标都只有当下值，而人真正会问的问题几乎全是趋势问题——
 * 这个会话是不是正在变慢？缓存是不是刚开始挺好、后来全烂了？失败是不是越来越密了？
 * 这些此前一个都答不了，因为历史被丢掉了：数值一刷新，上一个值就没了。
 * <b>单点看不出形状，而形状才是信号。</b>
 * <p>
 * <b>数据全部来自内核已有的通知，不需要任何新扩展点</b>：
 * <ul>
 *     <li>缓存命中率与上下文规模 ← {@link LlmCallCompletedEvent} 的 {@code TokenUsageSnapshot}
 *     （{@code promptTokens} 是含缓存部分的<b>总输入</b>，因此命中率 = 命中 / 总输入，不需要再拼别的字段）；</li>
 *     <li>失败率 ← {@link LlmCallFailedEvent} 与 {@link ToolCallCompletedEvent#isSuccess()}；</li>
 *     <li>清桶 ← {@link SessionClosedEvent}。</li>
 * </ul>
 * <p>
 * <b>为什么不做「耗时」那条线</b>：内核没有单次模型调用的耗时通知，也没有回合级通知，
 * 唯一能拿到的是工具调用耗时（{@link ToolCallCompletedEvent#getDurationMillis()}）——而它回答的是
 * 「命令慢不慢」，不是「这个会话在不在变慢」。拿它冒充后者就是一条会撒谎的线，宁可不画。
 * <p>
 * <b>采样由事件驱动，不用定时器</b>：值只在调用结束时变，按固定节奏采样只是对同一个值重复采样
 * （图会被拉平），代价是多一条必须在 {@code stop()} 前停掉的后台线程。事件驱动顺带让采样频率
 * 天然跟着真实工作量走。
 * <p>
 * <b>采样后主动通知外壳</b>：外壳只在缓存失效时收集面板，而这些采样发生在事件回调里，
 * 外壳自己看不到。因此每次采样都广播一条 {@link UiInvalidatedEvent}（与待办插件写完待办同一件事）。
 * 代价是「一个回合里每次工具调用都会多一条失效事件」，而它的收益是「回合还没结束就能看见趋势开始变差」；
 * 失效事件本身极小，且事件通道有界、满了即丢，丢掉的只是这一次刷新。
 * <p>
 * <b>面板只建议落右栏</b>：它是一块窄而常驻的观测面，纵向栏正合适。这只是<b>软建议</b>——
 * 外壳可能忽略它（终端太窄时侧栏整体隐藏），也可能被用户用 {@code /ui} 改到别处，
 * 插件不得假设自己一定在那里，也不得假设一定显示。
 * <p>
 * 配置见 {@link SparklineConfig}：{@code graphWidth} 与 {@code failureWindow}，都有保守的缺省值。
 *
 * @author zcd
 */
public final class SparklinePlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SparklinePlugin.class);

    /**
     * 面板在它建议的区域里的顺序。
     * <p>
     * 右栏还有别的常驻观测面板时（例如宠物），同一区域只显示 {@code order} 最小的那一个。
     * 趋势是「有数据就看得到」的客观信息，因此让它排在氛围类面板之前；取 0 是缺省值，
     * 显式写出来是为了让「这个次序是有意定的」这件事留在代码里。
     */
    private static final int PANEL_ORDER = 0;

    /** 采样台账；{@code start} 之前与 {@code stop} 之后为 {@code null}。 */
    private volatile SparklineStore store;

    /** 插件上下文，供事件回调广播失效通知；停止后置 {@code null}。 */
    private volatile PluginContext context;

    @Override
    public void start(PluginContext context) {
        // 先解析配置再订阅：处理器一旦挂上就可能被调用，图宽必须已经就绪
        SparklineConfig config = SparklineConfig.from(context.configuration(), context.pluginId(), context::emit);
        SparklineStore ledger = new SparklineStore(config.graphWidth(), config.failureWindow());
        this.store = ledger;
        this.context = context;
        context.observe(LlmCallCompletedEvent.class, this::onLlmCallCompleted);
        context.observe(LlmCallFailedEvent.class, this::onLlmCallFailed);
        context.observe(ToolCallCompletedEvent.class, this::onToolCallCompleted);
        context.observe(SessionClosedEvent.class, this::onSessionClosed);
        context.contribute(PanelContributionRequest.class, new SparklinePanel(ledger, config.graphWidth()),
                RegisterOptions.order(PANEL_ORDER));
        LOG.info("火花线插件已启动: graphWidth={} failureWindow={}",
                config.graphWidth(), config.failureWindow());
    }

    @Override
    public void stop() {
        // 没有后台线程要停，也没有资源要放——但台账是插件自己的内存，随停止一起丢掉更干净
        SparklineStore ledger = this.store;
        if (ledger != null) {
            ledger.clear();
        }
        this.store = null;
        this.context = null;
        LOG.info("火花线插件已停止");
    }

    /**
     * 记一次成功的模型调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onLlmCallCompleted(LlmCallCompletedEvent event) {
        sample(() -> ledger().onLlmCallCompleted(event));
    }

    /**
     * 记一次失败的模型调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onLlmCallFailed(LlmCallFailedEvent event) {
        sample(() -> ledger().onLlmCallFailed(event));
    }

    /**
     * 记一次工具调用，并通知外壳刷新。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onToolCallCompleted(ToolCallCompletedEvent event) {
        sample(() -> ledger().onToolCallCompleted(event));
    }

    /**
     * 会话关闭时丢掉它的采样点。
     * <p>
     * 不发失效通知：没有采样点就没有面板，而「这个会话已经不在了」这件事外壳自己知道。
     *
     * @param event 事件，不可为 {@code null}
     */
    private void onSessionClosed(SessionClosedEvent event) {
        SparklineStore ledger = this.store;
        if (ledger != null) {
            ledger.onSessionClosed(event);
        }
    }

    /**
     * 采样一次，然后通知外壳「我贡献的内容脏了」。
     * <p>
     * <b>两处容错都是刻意的</b>：采样本身绝不该把异常抛回事件通道（那只会换来一条 WARN，
     * 而趋势面板整块消失）；失效通知在插件停止后会当场抛错，而此时「刷新界面」已经无关紧要。
     * 展示数据的失败不该升级成业务失败。
     *
     * @param sampling 采样动作
     */
    private void sample(Runnable sampling) {
        SparklineStore ledger = this.store;
        if (ledger == null) {
            // 停止之后事件仍可能在队列里排空：丢掉即可，不必往前追溯
            return;
        }
        try {
            sampling.run();
        } catch (RuntimeException e) {
            LOG.warn("火花线采样失败，本次丢弃", e);
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
            LOG.debug("火花线失效通知发送失败（插件可能正在停止）", e);
        }
    }

    /**
     * 取采样台账。
     *
     * @return 台账，保证非 {@code null}
     * @throws IllegalStateException 插件未启动时抛出（调用点已先行判空，走到这里说明有编程错误）
     */
    private SparklineStore ledger() {
        SparklineStore ledger = this.store;
        if (ledger == null) {
            throw new IllegalStateException("sparkline plugin is not started");
        }
        return ledger;
    }
}
