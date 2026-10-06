package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonPanel} 的单元测试：行宽、优先级顺序与告警行。
 * <p>
 * <b>刻意不验行数上限</b>：侧栏高度是消息区全高，外壳按当前终端高度决定显出多少，
 * 面板这边不该有自设上限——上一版把 {@code DOCK} / {@code TOP} 的 8 行规则错套到侧栏上，
 * 于是在高终端上也只给 8 行。这里反过来钉住「内容够全」。
 * <p>
 * 行宽那条断言看着琐碎，却是这个面板唯一会「静默变坏」的地方：侧栏宽度按内容宽推出
 * （夹进 {@code [20, 终端宽/4]}），内容一旦变宽，就从消息区拿走越多列——面板是锦上添花，
 * 消息区是主体。这里自带一份显示宽度计算（中日韩占两列）来守这条线。
 * <p>
 * <b>分区阈值在本类里被设成 100</b>：分区占用比例取自真实磁盘（临时目录就在用户盘上），
 * 若沿用缺省的 90，在没有告警的用例里也会冒出一条磁盘告警，于是「第一行是什么」这类断言
 * 会随跑测机器的磁盘使用率而变。堆与死锁则完全由桩控制，用它来验告警渲染。
 *
 * @author zcd
 */
@DisplayName("资源面板")
class ResmonPanelTest {

    /** 面板内容行的显示宽度上限（含边框后落在 22 列左右，不挤压消息区）。 */
    private static final int MAX_WIDTH = 22;

    /** 本类里把分区阈值设成不会触发的值，见类注释。 */
    private static final double DISK_THRESHOLD_NEVER = 100.0;

    /** 告警关闭时面板的基线行数：读数行一条不少。 */
    private static final int BASELINE_ROWS = 18;

    /** 每个用例一个独立目录。 */
    @TempDir
    Path root;

    /** 采样端口桩。 */
    private JvmProbe probe;

    /** 被测处理器。 */
    private ResmonPanel panel;

    /** 采样器。 */
    private ResmonSampler sampler;

    /** 插件配置。 */
    private PluginConfig config;

    @BeforeEach
    void setUp() {
        probe = Mockito.mock(JvmProbe.class);
        config = config(root, true);
        sampler = new ResmonSampler(config, probe, new DirSizer(4), Mockito.mock(PluginContext.class));
        panel = new ResmonPanel(config, sampler);
    }

    @Test
    @DisplayName("还没采到数据时返回空贡献：一块空面板会被读成界面坏了")
    void handle_should_returnEmpty_when_noSampleYet() {
        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
    }

    @Test
    @DisplayName("完整读数都给出去，不自我截断到 8 行")
    void handle_should_notCapRows() throws IOException {
        prepareDisk();
        prepareSample();
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(BASELINE_ROWS, contribution.getLines().size());
        assertTrue(contribution.getLines().size() > 8, "侧栏不受 8 行规则约束");
    }

    @Test
    @DisplayName("每一行都不超过 22 个显示列")
    void handle_should_keep_lines_narrow() throws IOException {
        prepareDisk();
        prepareSample();
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        for (UiLine line : lines) {
            int width = displayWidth(line.text());
            assertTrue(width <= MAX_WIDTH, "行太宽（" + width + " 列）：" + line.text());
        }
    }

    @Test
    @DisplayName("告警行也要守住宽度：它是最长的一行")
    void handle_should_keep_alert_lines_narrow() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .deadlocked(3L)
                .build());
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        assertEquals(2, alertCount(lines), "堆与死锁各一条");
        for (UiLine line : lines) {
            int width = displayWidth(line.text());
            assertTrue(width <= MAX_WIDTH, "行太宽（" + width + " 列）：" + line.text());
        }
    }

    @Test
    @DisplayName("标题带采样时刻，关掉自动刷新时标出暂停")
    void title_should_carry_clock_and_pause_state() {
        prepareSample();
        sampler.sampleNow();

        PanelContribution running = panel.handle(new PanelContributionRequest("s-1"));
        sampler.autoRefresh(false);
        PanelContribution paused = panel.handle(new PanelContributionRequest("s-1"));

        assertTrue(running.getTitle().startsWith("资源 "), running.getTitle());
        assertTrue(running.getTitle().matches(".*\\d{2}:\\d{2}:\\d{2}.*"), running.getTitle());
        assertTrue(paused.getTitle().contains("暂停"), paused.getTitle());
    }

    @Test
    @DisplayName("内容包含堆、内存池、GC、线程、FD、CPU 与六个目录的短名")
    void handle_should_include_all_sections() throws IOException {
        prepareDisk();
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")));

        for (String needle : new String[]{"堆 ", "非堆 ", "元空间 ", "类空间 ", "GC ", "老年代 ",
                "线程 ", "死锁 ", "FD ", "CPU ", "磁盘 ", "会话 ", "输出 ", "插件 ", "待办 ",
                "网关 ", "日志 ", "合计 "}) {
            assertTrue(text.contains(needle), "面板缺少「" + needle + "」：" + text);
        }
    }

    @Test
    @DisplayName("目录不存在时显示短杠，而不是 0B（缺数据与真的是零不是一回事）")
    void handle_should_mark_missing_dirs() {
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(text.contains("会话 " + ResmonFormat.UNKNOWN), text);
    }

    @Test
    @DisplayName("文件数大于 1 时标出来：单个大文件与一堆小文件该清理的对象不同")
    void handle_should_show_file_count() throws IOException {
        prepareDisk();
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(text.contains("会话 1.5K 2文件"), text);
    }

    @Test
    @DisplayName("告警排在读数之前，用警示档位")
    void handle_should_putAlertsFirst() throws IOException {
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .build());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));
        List<UiLine> lines = contribution.getLines();

        assertEquals("! 堆 90% 超阈值 85%", lines.get(0).text());
        assertEquals(UiEmphasis.WARN, lines.get(0).getSegments().get(0).getEmphasis());
        assertTrue(lines.get(1).text().startsWith("堆 900B/1000B"), lines.get(1).text());
    }

    @Test
    @DisplayName("死锁告警之后，读数里的死锁那行转错误档位")
    void handle_should_mark_deadlock_as_error() {
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .threads(42L, 61L, 28L)
                .deadlocked(1L)
                .build());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals("! 死锁 1 个线程", contribution.getLines().get(0).text());
        assertEquals(UiEmphasis.ERROR, emphasisOfRow(contribution, "死锁 "));
    }

    @Test
    @DisplayName("关掉告警后没有告警行，读数照旧一条不少")
    void handle_should_hideAlerts_when_disabled() throws IOException {
        prepareDisk();
        PluginConfig quiet = config(root, false);
        JvmProbe quietProbe = Mockito.mock(JvmProbe.class);
        Mockito.when(quietProbe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .build());
        ResmonSampler quietSampler = new ResmonSampler(quiet, quietProbe, new DirSizer(4),
                Mockito.mock(PluginContext.class));
        quietSampler.sampleNow();

        PanelContribution contribution = new ResmonPanel(quiet, quietSampler)
                .handle(new PanelContributionRequest("s-1"));

        assertEquals(0, alertCount(contribution.getLines()));
        assertEquals(BASELINE_ROWS, contribution.getLines().size());
    }

    /**
     * 数出面板里的告警行条数。
     *
     * @param lines 面板行列表，不可为 {@code null}
     * @return 以「! 」开头的行数
     */
    private static int alertCount(List<UiLine> lines) {
        int count = 0;
        for (UiLine line : lines) {
            if (line.text().startsWith("! ")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 取以某前缀开头的那一行第一个文本段的强调档位。
     *
     * @param contribution 面板贡献，不可为 {@code null}
     * @param prefix       行前缀
     * @return 强调档位
     */
    private static UiEmphasis emphasisOfRow(PanelContribution contribution, String prefix) {
        for (UiLine line : contribution.getLines()) {
            if (line.text().startsWith(prefix)) {
                return line.getSegments().get(0).getEmphasis();
            }
        }
        throw new AssertionError("面板里没有以「" + prefix + "」开头的行");
    }

    /**
     * 让采样端口返回一份典型的 JVM 快照（堆 25%，远低于阈值）。
     */
    private void prepareSample() {
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .uptime(11520000L)
                .heap(536870912L, 1073741824L, 2147483648L)
                .nonHeap(100663296L)
                .memoryPools(75497472L, 12582912L, 8388608L)
                .gc(12L, 1200L, 0L, 0L, 2000L)
                .threads(42L, 61L, 28L)
                .deadlocked(0L)
                .loadedClasses(6821L)
                .fileDescriptors(210L, 8192L)
                .cpu(12.5, 34.0)
                .availableProcessors(8)
                .build());
    }

    /**
     * 在临时目录里造出六个占用项。
     *
     * @throws IOException 写入失败时抛出
     */
    private void prepareDisk() throws IOException {
        Files.createDirectories(root.resolve("sessions"));
        write(root.resolve("sessions/a.bin"), 1536);
        // 第二个文件是 0 字节：既让「文件数」这一项出现在面板上，又不改变体积（仍是 1.5K）
        write(root.resolve("sessions/b.bin"), 0);
        Files.createDirectories(root.resolve("tool-outputs"));
        write(root.resolve("tool-outputs/a.bin"), 8396);
        Files.createDirectories(root.resolve("plugins"));
        write(root.resolve("plugins/a.bin"), 24576);
        Files.createDirectories(root.resolve("todos"));
        write(root.resolve("todos/a.bin"), 12288);
        Files.createDirectories(root.resolve("gateway"));
        write(root.resolve("gateway/a.bin"), 90112);
        write(root.resolve("jellyfish-tui.log"), 210 * 1024);
    }

    /**
     * 写一个指定字节数的文件。
     *
     * @param path  目标路径，不可为 {@code null}
     * @param bytes 字节数
     * @throws IOException 写入失败时抛出
     */
    private static void write(Path path, int bytes) throws IOException {
        Files.write(path, new byte[bytes]);
    }

    /**
     * 把面板内容拼成一段文本。
     *
     * @param contribution 面板贡献，不可为 {@code null}
     * @return 各行的纯文本，用换行连接
     */
    private static String text(PanelContribution contribution) {
        StringBuilder out = new StringBuilder();
        for (UiLine line : contribution.getLines()) {
            out.append(line.text()).append('\n');
        }
        return out.toString();
    }

    /**
     * 构造把六个路径都指向临时目录的配置。
     *
     * @param directory     临时目录，不可为 {@code null}
     * @param alertsEnabled 是否标出越阈值的项
     * @return 配置
     */
    private static PluginConfig config(Path directory, boolean alertsEnabled) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, directory.resolve("sessions").toString());
        values.put(PluginConfig.KEY_TOOL_OUTPUTS_DIR, directory.resolve("tool-outputs").toString());
        values.put(PluginConfig.KEY_PLUGINS_DIR, directory.resolve("plugins").toString());
        values.put(PluginConfig.KEY_TODOS_DIR, directory.resolve("todos").toString());
        values.put(PluginConfig.KEY_GATEWAY_DIR, directory.resolve("gateway").toString());
        values.put(PluginConfig.KEY_LOG_FILE, directory.resolve("jellyfish-tui.log").toString());
        values.put(PluginConfig.KEY_ALERTS, alertsEnabled);
        values.put(PluginConfig.KEY_ALERT_DISK_PERCENT, DISK_THRESHOLD_NEVER);
        return PluginConfig.from(values);
    }

    /**
     * 计算一行文本的终端显示宽度：中日韩等全角字符占两列。
     *
     * @param text 文本，不可为 {@code null}
     * @return 显示列数
     */
    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += isWide(text.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    /**
     * 判断一个字符在终端里是否占两列。
     *
     * @param character 字符
     * @return 占两列返回 {@code true}
     */
    private static boolean isWide(char character) {
        return character >= 0x1100 && (character <= 0x115F
                || character == 0x2329 || character == 0x232A
                || (character >= 0x2E80 && character <= 0xA4CF && character != 0x303F)
                || (character >= 0xAC00 && character <= 0xD7A3)
                || (character >= 0xF900 && character <= 0xFAFF)
                || (character >= 0xFE30 && character <= 0xFE6F)
                || (character >= 0xFF00 && character <= 0xFF60)
                || (character >= 0xFFE0 && character <= 0xFFE6));
    }

    @Test
    @DisplayName("显示宽度计算自身可被验证：全角按两列、半角按一列")
    void displayWidth_should_count_wide_characters_twice() {
        assertEquals(4, displayWidth("会话"));
        assertEquals(4, displayWidth("1.4G"));
        assertEquals(9, displayWidth("会话 1.4G"));
    }
}
