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
 * 缺省值这一组断言看起来最没技术含量，却是这里最要紧的：它们钉住的是「内核与官方插件约定俗成的
 * 那几个落盘路径」。一旦哪天内核换了位置而本插件没跟上，这几条会失败并直接指出该改哪个常量，
 * 而不是让面板安静地报出别人的目录。
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
    @DisplayName("空配置段时全部取约定路径")
    void from_should_apply_defaults_when_empty() {
        PluginConfig config = PluginConfig.from(Collections.<String, Object>emptyMap());

        assertEquals(Paths.get(HOME, ".jellyfish", "sessions"), config.sessionsDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "tool-outputs"), config.toolOutputsDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "plugins"), config.pluginsDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "todos"), config.todosDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "gateway"), config.gatewayDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "jellyfish-tui.log"), config.logFile());
    }

    @Test
    @DisplayName("配置段为 null 也按缺省处理")
    void from_should_apply_defaults_when_null() {
        PluginConfig config = PluginConfig.from(null);

        assertEquals(PluginConfig.DEFAULT_SAMPLE_INTERVAL_MILLIS, config.sampleIntervalMillis());
        assertTrue(config.panel());
        assertTrue(config.autoRefresh());
        assertTrue(config.alerts());
    }

    @Test
    @DisplayName("显式配置覆盖缺省值，相对路径规范化为绝对路径")
    void from_should_override_paths() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, directory.toString());

        PluginConfig config = PluginConfig.from(values);

        assertEquals(directory.toAbsolutePath().normalize(), config.sessionsDir());
        assertEquals(Paths.get(HOME, ".jellyfish", "todos"), config.todosDir());
    }

    @Test
    @DisplayName("间隔与深度从数字与字符串两种写法都能解析")
    void from_should_parse_numbers_from_both_forms() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SAMPLE_INTERVAL, 3000L);
        values.put(PluginConfig.KEY_DISK_INTERVAL, "120000");
        values.put(PluginConfig.KEY_WALK_MAX_DEPTH, 4);

        PluginConfig config = PluginConfig.from(values);

        assertEquals(3000L, config.sampleIntervalMillis());
        assertEquals(120000L, config.diskScanIntervalMillis());
        assertEquals(4, config.walkMaxDepth());
    }

    @Test
    @DisplayName("阈值改动会同时影响面板配色与告警")
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
    @DisplayName("路径写成空白字符串当场报错，不静默回退")
    void from_should_reject_blank_path() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, "   ");

        assertThrows(zcd.jellyfish.api.JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("路径写成数字当场报错")
    void from_should_reject_nonString_path() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, 42);

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
