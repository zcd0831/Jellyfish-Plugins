package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginConfig} 的单元测试：缺省值、覆盖、{@code ~} 展开与越界配置。
 * <p>
 * 这里只有<b>一个</b>路径配置（{@code baseDir}），而且它断言的正是那条最稳定的约定
 * ——{@code ~/.jellyfish}。上一版逐个声明六个目录，那既要知道别的插件把数据放在哪儿、
 * 又会在别人改配置时安静地报错；改成扫描一个根目录之后，「配置对不对」这件事只有一个答案要维护。
 *
 * @author zcd
 */
@DisplayName("插件配置解析")
class PluginConfigTest {

    /** 用户主目录，用于 {@code ~} 展开断言。 */
    private static final String HOME = System.getProperty("user.home");

    /** 用于断言规范化路径时的临时目录。 */
    @TempDir
    Path directory;

    @Test
    @DisplayName("空配置段时根目录取约定的 ~/.jellyfish")
    void from_should_default_baseDir() {
        PluginConfig config = PluginConfig.from(Collections.<String, Object>emptyMap());

        assertEquals(Paths.get(HOME, ".jellyfish"), config.baseDir());
    }

    @Test
    @DisplayName("配置段为 null 也按缺省处理")
    void from_should_apply_defaults_when_null() {
        PluginConfig config = PluginConfig.from(null);

        assertEquals(PluginConfig.DEFAULT_SAMPLE_INTERVAL_MILLIS, config.sampleIntervalMillis());
        assertEquals(PluginConfig.DEFAULT_DISK_INTERVAL_MILLIS, config.diskScanIntervalMillis());
        assertEquals(PluginConfig.DEFAULT_DISK_ENTRIES, config.diskEntries());
        assertTrue(config.panel());
        assertTrue(config.autoRefresh());
        assertTrue(config.alerts());
    }

    @Test
    @DisplayName("显式配置覆盖根目录，相对路径规范化为绝对路径")
    void from_should_override_baseDir() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, directory.toString());

        PluginConfig config = PluginConfig.from(values);

        assertEquals(directory.toAbsolutePath().normalize(), config.baseDir());
    }

    @Test
    @DisplayName("间隔、深度与子项数从数字与字符串两种写法都能解析")
    void from_should_parse_numbers_from_both_forms() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SAMPLE_INTERVAL, 3000L);
        values.put(PluginConfig.KEY_DISK_INTERVAL, "120000");
        values.put(PluginConfig.KEY_WALK_MAX_DEPTH, 4);
        values.put(PluginConfig.KEY_DISK_ENTRIES, "20");

        PluginConfig config = PluginConfig.from(values);

        assertEquals(3000L, config.sampleIntervalMillis());
        assertEquals(120000L, config.diskScanIntervalMillis());
        assertEquals(4, config.walkMaxDepth());
        assertEquals(20, config.diskEntries());
    }

    @Test
    @DisplayName("阈值改动会同时影响面板配色与告警文本")
    void from_should_override_thresholds() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_ALERT_HEAP_PERCENT, 70);
        values.put(PluginConfig.KEY_ALERT_DISK_PERCENT, "95");

        PluginConfig config = PluginConfig.from(values);

        assertEquals(70.0, config.alertHeapPercent(), 0.001);
        assertEquals(95.0, config.alertDiskPercent(), 0.001);
        assertEquals(PluginConfig.DEFAULT_ALERT_FD_PERCENT, config.alertFdPercent(), 0.001);
    }

    @Test
    @DisplayName("布尔开关接受 JSON 布尔与文本两种写法")
    void from_should_parse_booleans() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_PANEL, false);
        values.put(PluginConfig.KEY_AUTO_REFRESH, "false");
        values.put(PluginConfig.KEY_ALERTS, "TRUE");

        PluginConfig config = PluginConfig.from(values);

        assertFalse(config.panel());
        assertFalse(config.autoRefresh());
        assertTrue(config.alerts());
    }

    @Test
    @DisplayName("采样间隔低于下界当场报错：每秒推几十次失效会把界面信箱刷满")
    void from_should_reject_too_fast_interval() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SAMPLE_INTERVAL, 1);

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("磁盘扫描间隔低于下界当场报错")
    void from_should_reject_too_fast_disk_interval() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_DISK_INTERVAL, 10);

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("面板子项数不给 0：关面板有 panel，不用这个开关表达")
    void from_should_reject_zero_diskEntries() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_DISK_ENTRIES, 0);

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("根目录写成空白字符串当场报错，不静默回退")
    void from_should_reject_blank_baseDir() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, "   ");

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("根目录写成数字当场报错")
    void from_should_reject_nonString_baseDir() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, 42);

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("整数配了非数字文本当场报错")
    void from_should_reject_nonNumeric_interval() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SAMPLE_INTERVAL, "两秒");

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("阈值超出 (0, 100] 当场报错")
    void from_should_reject_outOfRange_threshold() {
        Map<String, Object> zero = new HashMap<String, Object>();
        zero.put(PluginConfig.KEY_ALERT_HEAP_PERCENT, 0);
        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(zero));

        Map<String, Object> tooBig = new HashMap<String, Object>();
        tooBig.put(PluginConfig.KEY_ALERT_HEAP_PERCENT, 101);
        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(tooBig));
    }

    @Test
    @DisplayName("布尔配了别的文本当场报错")
    void from_should_reject_illegal_boolean() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_PANEL, "maybe");

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }
}
