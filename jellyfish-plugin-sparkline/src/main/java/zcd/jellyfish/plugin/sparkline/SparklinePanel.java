package zcd.jellyfish.plugin.sparkline;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板贡献处理器：把当前会话的三条线画成三行窄图。
 * <p>
 * <b>为什么是「趋势」而不是「快照」</b>：终端里的每个指标都只有当下值，而人真正会问的问题几乎全是趋势——
 * 这个会话是不是正在变慢？缓存是不是刚开始挺好、后来全烂了？失败是不是越来越密了？
 * 单点看不出形状，而形状才是信号。本面板把最近若干个采样点压成一行块字符，让那三个问题第一次有答案。
 * <p>
 * <b>每行都带当前值与单位</b>：图只表达形状、不表达刻度，两行之间也不可比高低。因此行尾那个数字
 * 不是装饰——没有它，图好看但没法用（这是设计上写死的边界，不是可以省的细节）。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：本处理器<b>完全合规</b>——只从内存台账里取一份快照、
 * 拼三行文本，不碰文件、不起进程、不发失效事件（那会形成「失效 → 收集 → 失效」的死循环；
 * 失效由事件回调在采样后发）。台账的读路径也不创建任何东西（见 {@link SparklineStore#existing}）。
 * <p>
     * <b>没有数据时返回空贡献</b>：空贡献等于「不占区域、不进 {@code /ui} 候选」，
 * 因此新会话在第一次模型调用之前不会白占一块地方，也不会把右栏从别的面板手里抢走。
 * <p>
 * 无状态（只持有台账与图宽），可安全跨线程调用。
 *
 * @author zcd
 */
final class SparklinePanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    static final String TITLE = "趋势";

    /** 缓存线标签。 */
    static final String LABEL_CACHE = "缓存";

    /** 失败线标签。 */
    static final String LABEL_FAILURE = "失败";

    /** 输入线标签。 */
    static final String LABEL_INPUT = "输入";

    /** 标签与图之间的分隔。 */
    private static final String SEPARATOR = " ";

    /** 面板建议落位。 */
    private static final UiRegion REGION = UiRegion.RIGHT;

    /** 采样台账。 */
    private final SparklineStore store;

    /** 图宽。 */
    private final int graphWidth;

    /**
     * 构造处理器。
     *
     * @param store      采样台账，不可为 {@code null}
     * @param graphWidth 每行的图宽
     */
    SparklinePanel(SparklineStore store, int graphWidth) {
        this.store = store;
        this.graphWidth = graphWidth;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        SessionSeries bucket = store.existing(request.getSessionId());
        if (bucket == null) {
            return PanelContribution.empty();
        }
        SessionSeries.Snapshot snapshot = bucket.snapshot();
        if (snapshot.getCache().isEmpty() && snapshot.getFailure().isEmpty() && snapshot.getInput().isEmpty()) {
            return PanelContribution.empty();
        }
        List<UiLine> lines = new ArrayList<UiLine>(3);
        lines.add(line(LABEL_CACHE, snapshot.getCache(), MetricSeries.Scale.RATIO,
                SparklineRender.percent(latest(snapshot.getCache())), UiEmphasis.ACCENT));
        lines.add(failureLine(snapshot.getFailure()));
        lines.add(line(LABEL_INPUT, snapshot.getInput(), MetricSeries.Scale.RELATIVE,
                SparklineRender.tokens(latest(snapshot.getInput())), UiEmphasis.NORMAL));
        return PanelContribution.of(TITLE, lines, REGION);
    }

    /**
     * 拼一行：标签 + 图 + 当前值。
     * <p>
     * 图用 {@link UiEmphasis#DIM}、值用调用方给的档位：图是背景形状，值才是要读的那个数。
     * 两段之间不留空格——最小侧栏的内容宽只有 18 列，一个空格就是从别人那里抢来的。
     *
     * @param label    标签
     * @param values   采样点
     * @param scale    刻度口径
     * @param text     行尾的数值文本
     * @param emphasis 数值的强调档位
     * @return 界面行，保证非 {@code null}
     */
    private UiLine line(String label, List<Double> values, MetricSeries.Scale scale, String text,
                        UiEmphasis emphasis) {
        return UiLine.of(UiSegment.of(label + SEPARATOR),
                UiSegment.of(SparklineRender.render(values, graphWidth, scale), UiEmphasis.DIM),
                UiSegment.of(SEPARATOR + text, emphasis));
    }

    /**
     * 拼失败线：不为零时把数值转成警示档。
     * <p>
     * 「有一次失败」与「失败率是 0」扫一眼就该分得开，因此强调档位跟着值走，而不是固定一档。
     *
     * @param values 失败率采样点
     * @return 界面行，保证非 {@code null}
     */
    private UiLine failureLine(List<Double> values) {
        Double latest = latest(values);
        boolean failing = latest != null && latest > 0.0;
        return line(LABEL_FAILURE, values, MetricSeries.Scale.RATIO, SparklineRender.percent(latest),
                failing ? UiEmphasis.WARN : UiEmphasis.DIM);
    }

    /**
     * 取最后一个采样点。
     *
     * @param values 采样点列表
     * @return 最后一个采样点；列表为空时返回 {@code null}
     */
    private static Double latest(List<Double> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }
}
