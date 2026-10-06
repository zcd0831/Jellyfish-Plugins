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
    @DisplayName("占用项在面板上用两个汉字的短名，在明细里用全名")
    void labels_should_differ_between_panel_and_detail() {
        assertEquals("会话", ResmonFormat.shortLabel(UsageKeys.SESSIONS));
        assertEquals("会话文件", ResmonFormat.label(UsageKeys.SESSIONS));
        assertEquals("输出", ResmonFormat.shortLabel(UsageKeys.TOOL_OUTPUTS));
        assertEquals("工具输出", ResmonFormat.label(UsageKeys.TOOL_OUTPUTS));
    }

    @Test
    @DisplayName("未知键原样返回：新目录在补标签之前也看得见")
    void label_should_fall_back_to_key_when_unknown() {
        assertEquals("unknown", ResmonFormat.label("unknown"));
        assertEquals("unknown", ResmonFormat.shortLabel("unknown"));
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
