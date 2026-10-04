package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SparklineRender} 的单元测试：块字符档位、左侧留白、数值格式化。
 * <p>
 * 这些断言守的是「图读不读得出来」——档位算错不会报错，只会让人少看见一次变差。
 *
 * @author zcd
 */
@DisplayName("块字符渲染")
class SparklineRenderTest {

    /** 最低档块字符。 */
    private static final char LOWEST = '\u2581';

    /** 最高档块字符。 */
    private static final char HIGHEST = '\u2588';

    @Test
    @DisplayName("没有采样点时整行留白，不硬画一个最低档")
    void render_should_leaveBlankWithoutSamples() {
        assertEquals("   ", SparklineRender.render(Collections.<Double>emptyList(), 3,
                MetricSeries.Scale.RATIO));
        assertEquals("   ", SparklineRender.render(null, 3, MetricSeries.Scale.RATIO));
    }

    @Test
    @DisplayName("采样点不足时左侧留白，最新的一格永远在最右")
    void render_should_padOnTheLeftWhenSamplesAreFewerThanWidth() {
        String row = SparklineRender.render(Arrays.asList(1.0), 4, MetricSeries.Scale.RATIO);

        assertEquals("   " + HIGHEST, row);
    }

    @Test
    @DisplayName("采样点多于图宽时只画最近的那些")
    void render_should_keepOnlyTheMostRecentSamples() {
        String row = SparklineRender.render(Arrays.asList(0.0, 0.0, 1.0), 2, MetricSeries.Scale.RATIO);

        // 最近两个是 0.0 与 1.0
        assertEquals("" + LOWEST + HIGHEST, row);
    }

    @Test
    @DisplayName("比率线固定用满量程：一直是 60% 就该画在中间高度，而不是被拉成满格")
    void render_should_useFullScaleForRatio() {
        String row = SparklineRender.render(Arrays.asList(0.6, 0.6), 2, MetricSeries.Scale.RATIO);

        // 0.6 * 7 = 4.2 → 四舍五入到第 4 档
        assertEquals("" + '\u2585' + '\u2585', row);
    }

    @Test
    @DisplayName("相对刻度用窗口内最大值当上界")
    void render_should_useRelativeScaleForUnboundedValues() {
        String row = SparklineRender.render(Arrays.asList(50.0, 100.0), 2, MetricSeries.Scale.RELATIVE);

        // 50/100 = 0.5 → 0.5 * 7 = 3.5 → 四舍五入到第 4 档
        assertEquals("" + '\u2585' + HIGHEST, row);
    }

    @Test
    @DisplayName("整行都是 0 时统一落到最低档，不谎报「很多」")
    void render_should_notInvertAllZeroRow() {
        String row = SparklineRender.render(Arrays.asList(0.0, 0.0), 2, MetricSeries.Scale.RELATIVE);

        assertEquals("" + LOWEST + LOWEST, row);
    }

    @Test
    @DisplayName("图宽小于 1 时按 1 处理，不返回空串")
    void render_should_clampWidthToOne() {
        assertEquals(1, SparklineRender.render(Arrays.asList(1.0), 0, MetricSeries.Scale.RATIO).length());
    }

    @Test
    @DisplayName("百分比格式化：四舍五入到整数并夹在 0..100")
    void percent_should_roundAndClamp() {
        assertEquals("62%", SparklineRender.percent(0.62));
        assertEquals("100%", SparklineRender.percent(1.4));
        assertEquals("0%", SparklineRender.percent(-0.2));
        assertEquals("--", SparklineRender.percent(null));
    }

    @Test
    @DisplayName("token 格式化：按量级换单位，宽度不超过 4 列")
    void tokens_should_switchUnitByMagnitude() {
        assertEquals("872", SparklineRender.tokens(872.0));
        assertEquals("9.8k", SparklineRender.tokens(9800.0));
        assertEquals("120k", SparklineRender.tokens(120000.0));
        assertEquals("1.2M", SparklineRender.tokens(1200000.0));
        assertEquals("--", SparklineRender.tokens(null));
    }
}
