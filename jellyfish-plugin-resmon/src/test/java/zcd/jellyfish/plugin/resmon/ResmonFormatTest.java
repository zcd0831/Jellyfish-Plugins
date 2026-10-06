package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonFormat} 的单元测试：把每一个压缩规则钉在边界上。
 * <p>
 * 这些函数是整个插件里唯一会被反复显示给用户看的东西，且它们的输出直接决定面板会不会折行，
 * 因此值得逐个边界验：进位的那一格（1023B / 1K）、小数的取舍、以及「不可用」必须与「零」长得不一样。
 * <p>
 * 截断那一组按<b>显示宽度</b>而不是字符数验——全中文目录名按字符数截会让这一行的实际宽度翻倍，
 * 而那正是面板宽度预算被突破的方式。
 *
 * @author zcd
 */
@DisplayName("资源数字格式化")
class ResmonFormatTest {

    @Test
    @DisplayName("负数一律显示成不可用，而不是 -1B")
    void bytes_should_return_unknown_when_negative() {
        assertEquals(ResmonFormat.UNKNOWN, ResmonFormat.bytes(-1L));
    }

    @ParameterizedTest
    @CsvSource({
            "0,      0B",
            "890,    890B",
            "1023,   1023B",
            "1024,   1K",
            "1536,   1.5K",
            "10240,  10K",
            "1048576, 1M",
            "25165824, 24M",
            "1073741824, 1G",
            "1610612736, 1.5G",
            "1099511627776, 1T"
    })
    @DisplayName("字节数按 1024 进制压缩，单值不超过 4 个字符加单位")
    void bytes_should_scale_with_binary_units(long value, String expected) {
        assertEquals(expected, ResmonFormat.bytes(value));
    }

    @Test
    @DisplayName("百分比四舍五入到整数，负数与 NaN 都表示不可用")
    void percent_should_round_and_mark_unknown() {
        assertEquals("0%", ResmonFormat.percent(0.0));
        assertEquals("25%", ResmonFormat.percent(25.4));
        assertEquals("100%", ResmonFormat.percent(99.6));
        assertEquals(ResmonFormat.UNKNOWN, ResmonFormat.percent(-1.0));
        // Math.round(NaN) 是 0，所以这里必须显式拦下来，否则「没有数据」会显示成 0%
        assertEquals(ResmonFormat.UNKNOWN, ResmonFormat.percent(Double.NaN));
    }

    @ParameterizedTest
    @CsvSource({
            "0,        0ms",
            "999,      999ms",
            "1000,     1s",
            "1200,     1.2s",
            "45000,    45s",
            "60000,    1m0s",
            "3725000,  1h2m",
            "183600000, 2d3h"
    })
    @DisplayName("时长按量级切换单位")
    void duration_should_switch_units(long millis, String expected) {
        assertEquals(expected, ResmonFormat.duration(millis));
    }

    @Test
    @DisplayName("时长负数表示不可用")
    void duration_should_return_unknown_when_negative() {
        assertEquals(ResmonFormat.UNKNOWN, ResmonFormat.duration(-1L));
    }

    @Test
    @DisplayName("够短的名字原样返回")
    void clip_should_keep_short_text() {
        assertEquals("sessions", ResmonFormat.clip("sessions", 10));
        assertEquals("sessions", ResmonFormat.clip("sessions", 8));
    }

    @Test
    @DisplayName("超长的名字按显示宽度截断并以省略号收尾")
    void clip_should_truncate_long_text() {
        assertEquals("tool-outp\u2026", ResmonFormat.clip("tool-outputs", 10));
        assertEquals("jellyfis\u2026", ResmonFormat.clip("jellyfish-tui.log", 9));
    }

    @Test
    @DisplayName("全角字符按两列算：中文目录名不会被截得比预算宽")
    void clip_should_count_wide_characters_twice() {
        // 「会话目录」= 8 列，预算 6 只放得下两个字（4 列）+ 省略号
        String clipped = ResmonFormat.clip("会话目录名", 6);

        assertEquals("会话\u2026", clipped);
        assertTrue(ResmonFormat.displayWidth(clipped) <= 6);
    }

    @Test
    @DisplayName("截断结果永远不超过预算宽度")
    void clip_should_never_exceed_budget() {
        String[] names = {"sessions", "tool-outputs", "jellyfish-tui.log", "会话目录", "混合 name 混排",
                "a", "非常长的一个中文目录名字用来测试截断"};

        for (String name : names) {
            for (int budget = 1; budget <= 12; budget++) {
                String clipped = ResmonFormat.clip(name, budget);
                assertTrue(ResmonFormat.displayWidth(clipped) <= budget,
                        "预算 " + budget + " 却得到「" + clipped + "」");
            }
        }
    }

    @Test
    @DisplayName("null 与空串按空文本处理")
    void clip_should_tolerate_null() {
        assertEquals("", ResmonFormat.clip(null, 5));
        assertEquals("", ResmonFormat.clip("", 5));
        assertEquals(0, ResmonFormat.displayWidth(null));
    }

    @Test
    @DisplayName("时刻按秒显示，时间戳按完整格式显示")
    void clock_and_timestamp_should_format_epoch() {
        long epoch = 1600000000000L;
        // 不断言具体数字：格式化走系统默认时区，钉死时区会让这条断言的失败与功能的正确性无关
        assertTrue(ResmonFormat.clock(epoch).matches("\\d{2}:\\d{2}:\\d{2}"));
        assertTrue(ResmonFormat.timestamp(epoch).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
