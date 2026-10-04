package zcd.jellyfish.plugin.sparkline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 本插件的配置：{@code plugins.configurations.jellyfish-sparkline} 段。
 * <p>
 * 只有两项，且都有保守的缺省值——本插件的价值在「有没有趋势可看」，不在可调性：
 * <ul>
 *     <li>{@code graphWidth}：每行画多少格。它决定面板内容宽度，而面板宽度决定外壳给侧栏分多宽列，
 *     因此默认值按「最小侧栏扣除边框后放得下标签与数值」反推（见 {@link #DEFAULT_GRAPH_WIDTH}）；</li>
 *     <li>{@code failureWindow}：失败率按最近几次调用算。太小会一个失误就封顶，太大则「越来越密」
 *     这个形状爬得太慢。</li>
 * </ul>
 * <p>
 * <b>配置可疑只告警、不中断启动</b>：不是数字、超出合理区间都回落缺省值并发一条
 * {@link ConfigWarningEvent}，与内核「配置好坏不阻断启动」的既有口径一致。
 * <p>
 * <b>为什么在这里把值夹进区间而不是直接报错</b>：这两项都是「观感参数」，
 * 一个写错的数字不该让插件起不来——那会让用户失去整块趋势面板，而生气的理由只是「宽度填了 0」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SparklineConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SparklineConfig.class);

    /** 图宽配置键。 */
    static final String KEY_GRAPH_WIDTH = "graphWidth";

    /** 失败率滑窗配置键。 */
    static final String KEY_FAILURE_WINDOW = "failureWindow";

    /**
     * 图宽缺省值。
     * <p>
     * 侧栏内容宽最小的情形是「终端刚过 80 列」——外壳给侧栏 20 列（含边框 2 列），内容只剩 18 列。
     * 一行是「标签(4) + 空格(1) + 图 + 空格(1) + 值」，值最长 4 列（{@code 100%}），
     * 因此图宽取 8 才能稳定不折行。肉眼看 8 格够读出「在变差」这个形状。
     */
    static final int DEFAULT_GRAPH_WIDTH = 8;

    /** 失败率滑窗缺省值。 */
    static final int DEFAULT_FAILURE_WINDOW = 8;

    /** 图宽下限：再短就只剩一个人眼读不出的点。 */
    static final int MIN_GRAPH_WIDTH = 4;

    /** 图宽上限：超过它一行会被外壳折行，而折行会吃掉面板的行数预算。 */
    static final int MAX_GRAPH_WIDTH = 16;

    /** 失败率滑窗下限。 */
    static final int MIN_FAILURE_WINDOW = 1;

    /** 失败率滑窗上限：再长，「最近的失败」就要追溯到很久以前才有意义。 */
    static final int MAX_FAILURE_WINDOW = 32;

    /** 生效的图宽。 */
    private final int graphWidth;

    /** 生效的失败率滑窗。 */
    private final int failureWindow;

    /**
     * 构造配置。
     *
     * @param graphWidth    图宽
     * @param failureWindow 失败率滑窗
     */
    private SparklineConfig(int graphWidth, int failureWindow) {
        this.graphWidth = graphWidth;
        this.failureWindow = failureWindow;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @param source        告警来源标识，通常是插件标识
     * @param warner        告警发布入口，可为 {@code null}（不告警）
     * @return 配置值对象，保证非 {@code null}
     */
    static SparklineConfig from(Map<String, Object> configuration, String source, Consumer<JellyfishEvent> warner) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap() : configuration;
        int width = intOf(values.get(KEY_GRAPH_WIDTH), DEFAULT_GRAPH_WIDTH,
                MIN_GRAPH_WIDTH, MAX_GRAPH_WIDTH, KEY_GRAPH_WIDTH, source, warner);
        int window = intOf(values.get(KEY_FAILURE_WINDOW), DEFAULT_FAILURE_WINDOW,
                MIN_FAILURE_WINDOW, MAX_FAILURE_WINDOW, KEY_FAILURE_WINDOW, source, warner);
        LOG.info("火花线插件已装配: graphWidth={} failureWindow={}", width, window);
        return new SparklineConfig(width, window);
    }

    /**
     * 取图宽。
     *
     * @return 图宽，保证在合理区间内
     */
    int graphWidth() {
        return graphWidth;
    }

    /**
     * 取失败率滑窗大小。
     *
     * @return 滑窗大小，保证在合理区间内
     */
    int failureWindow() {
        return failureWindow;
    }

    /**
     * 解析一个带区间的整数配置项，非法或越界时回落缺省值并告警。
     *
     * @param raw       配置原值，可为 {@code null}
     * @param fallback  缺省值
     * @param min       下限（含）
     * @param max       上限（含）
     * @param key       配置键，进告警文案便于定位
     * @param source    告警来源标识
     * @param warner    告警发布入口，可为 {@code null}
     * @return 生效值
     */
    private static int intOf(Object raw, int fallback, int min, int max, String key, String source,
                             Consumer<JellyfishEvent> warner) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number)) {
            warn(source, warner, key + " 应为数字，实际为 " + raw.getClass().getName()
                    + "；已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        int value = ((Number) raw).intValue();
        if (value < min || value > max) {
            warn(source, warner, key + " 应在 " + min + " 到 " + max + " 之间，实际为 " + value
                    + "；已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 发布一条配置告警。
     *
     * @param source  告警来源标识
     * @param warner  告警发布入口，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(String source, Consumer<JellyfishEvent> warner, String message) {
        if (warner == null) {
            return;
        }
        try {
            warner.accept(new ConfigWarningEvent(source, message));
        } catch (RuntimeException e) {
            // 告警失败不该让插件起不来：可观测性不是启动路径上的必要条件
            LOG.warn("配置告警发布失败: {}", message, e);
        }
    }
}
