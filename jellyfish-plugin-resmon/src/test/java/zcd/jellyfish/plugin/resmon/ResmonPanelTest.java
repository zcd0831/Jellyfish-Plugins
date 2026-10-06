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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonPanel} 的单元测试：行预算、行宽与「没数据就不显示」。
 * <p>
 * 行宽那条断言看着琐碎，却是这个面板唯一会「静默变坏」的地方：外壳把侧栏宽度按内容宽推出并夹进
 * {@code [20, 终端宽/4]}，内容一旦变宽，窄终端上就会折行，而折行会把 8 行的预算吃光、
 * 把磁盘那几行挤成「… 还有 N 行」——没有任何报错，只是信息没了。
 * <p>
 * 这里因此自带一份显示宽度计算（中日韩字符占两列）：插件侧不该算宽度（那是外壳的事），
 * 但<b>测试</b>必须能验证自己没把内容写宽，这份计算只活在测试里。
 *
 * @author zcd
 */
@DisplayName("资源面板")
class ResmonPanelTest {

    /** 面板内容行的显示宽度上限：侧栏下限 20 列减两侧边框。 */
    private static final int MAX_WIDTH = 20;

    /** 每个用例一个独立目录。 */
    @TempDir
    Path root;

    /** 采样端口桩。 */
    private JvmProbe probe;

    /** 插件配置。 */
    private PluginConfig config;

    /** 采样器。 */
    private ResmonSampler sampler;

    /** 被测处理器。 */
    private ResmonPanel panel;

    @BeforeEach
    void setUp() {
        probe = Mockito.mock(JvmProbe.class);
        config = config(root);
        sampler = new ResmonSampler(config, probe, new DirSizer(4), Mockito.mock(PluginContext.class));
        panel = new ResmonPanel(config, sampler);
    }

    @Test
    @DisplayName("还没采到数据时返回空贡献：一块空面板会被读成界面坏了")
    void handle_should_returnEmpty_when_noSampleYet() {
        assertTrue(panel.handle(new PanelContributionRequest("s-1")).isEmpty());
    }

    @Test
    @DisplayName("有数据时永远是 8 行以内")
    void handle_should_stayWithin_row_budget() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(typicalStats());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(8, contribution.getLines().size());
    }

    @Test
    @DisplayName("每一行都不超过 20 个显示列")
    void handle_should_keep_lines_narrow() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(typicalStats());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        for (UiLine line : contribution.getLines()) {
            int width = displayWidth(line.text());
            assertTrue(width <= MAX_WIDTH, "行太宽（" + width + " 列）：" + line.text());
        }
    }

    @Test
    @DisplayName("标题带采样时刻，关掉自动刷新时标出暂停")
    void title_should_carry_clock_and_pause_state() {
        Mockito.when(probe.probe()).thenReturn(typicalStats());
        sampler.sampleNow();

        PanelContribution running = panel.handle(new PanelContributionRequest("s-1"));
        sampler.autoRefresh(false);
        PanelContribution paused = panel.handle(new PanelContributionRequest("s-1"));

        assertTrue(running.getTitle().startsWith("资源 "), running.getTitle());
        assertTrue(running.getTitle().matches(".*\\d{2}:\\d{2}:\\d{2}.*"), running.getTitle());
        assertTrue(paused.getTitle().contains("暂停"), paused.getTitle());
    }

    @Test
    @DisplayName("内容包含堆、GC、线程、FD 与六个目录的短名")
    void handle_should_include_all_sections() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(typicalStats());
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(text.contains("堆 "), text);
        assertTrue(text.contains("GC "), text);
        assertTrue(text.contains("线程 "), text);
        assertTrue(text.contains("FD "), text);
        assertTrue(text.contains("磁盘 "), text);
        assertTrue(text.contains("会话"), text);
        assertTrue(text.contains("输出"), text);
        assertTrue(text.contains("插件"), text);
        assertTrue(text.contains("待办"), text);
        assertTrue(text.contains("网关"), text);
        assertTrue(text.contains("日志"), text);
    }

    @Test
    @DisplayName("目录不存在时显示短杠，而不是 0B（缺数据与真的是零不是一回事）")
    void handle_should_mark_missing_dirs() {
        Mockito.when(probe.probe()).thenReturn(typicalStats());
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")));

        assertTrue(text.contains("会话 " + ResmonFormat.UNKNOWN), text);
    }

    @Test
    @DisplayName("堆与 FD 越阈值时那一行转警示档位")
    void handle_should_warn_when_over_threshold() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1000L)
                .heap(900L, 1000L, 1000L)
                .fileDescriptors(900L, 1000L)
                .build());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.WARN, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
        assertEquals(UiEmphasis.WARN, contribution.getLines().get(3).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("出现死锁时线程那行转错误档位")
    void handle_should_mark_deadlock_as_error() throws IOException {
        prepareDisk();
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1000L)
                .threads(42L, 61L, 28L)
                .deadlocked(1L)
                .build());
        sampler.sampleNow();

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s-1"));

        assertEquals(UiEmphasis.ERROR, contribution.getLines().get(2).getSegments().get(0).getEmphasis());
    }

    /**
     * 造一份典型的 JVM 快照。
     *
     * @return 快照
     */
    private static JvmStats typicalStats() {
        return JvmStats.builder()
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
                .build();
    }

    /**
     * 在临时目录里造出六个占用项，体积都是可控的。
     * <p>
     * 刻意用 K 级而不是 M 级：面板上的宽度只取决于「几位数字 + 一个单位字符」，
     * 所以 {@code 8.2K} 与 {@code 8.2M} 占的列数完全一样，而写 120MB 会让这条用例变成秒级。
     *
     * @throws IOException 写入失败时抛出
     */
    private void prepareDisk() throws IOException {
        Files.createDirectories(root.resolve("sessions"));
        write(root.resolve("sessions/a.bin"), 1536);
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
     * @param directory 临时目录，不可为 {@code null}
     * @return 配置
     */
    private static PluginConfig config(Path directory) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_SESSIONS_DIR, directory.resolve("sessions").toString());
        values.put(PluginConfig.KEY_TOOL_OUTPUTS_DIR, directory.resolve("tool-outputs").toString());
        values.put(PluginConfig.KEY_PLUGINS_DIR, directory.resolve("plugins").toString());
        values.put(PluginConfig.KEY_TODOS_DIR, directory.resolve("todos").toString());
        values.put(PluginConfig.KEY_GATEWAY_DIR, directory.resolve("gateway").toString());
        values.put(PluginConfig.KEY_LOG_FILE, directory.resolve("jellyfish-tui.log").toString());
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
