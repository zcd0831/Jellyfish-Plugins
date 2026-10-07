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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonPanel} 的单元测试：行宽、顺序、子项列表与「没数据就不显示」。
 * <p>
 * <b>刻意不验行数上限</b>：侧栏高度是消息区全高，外壳按当前终端高度决定显出多少，
 * 面板这边不该有自设上限——早先那版把 {@code DOCK} / {@code TOP} 的 8 行规则错套到侧栏上，
 * 于是在高终端上也只给 8 行。这里反过来钉住「内容够全」。
 * <p>
 * 行宽那条断言看着琐碎，却是这个面板最容易「静默变坏」的地方：子项名是用户给的（可能是任意长度的
 * 目录名），而侧栏宽度按内容宽推出——名字多长一分，消息区就少一分。
 * <p>
 * <b>分区阈值在本类里被设成 100</b>：分区占用比例取自真实磁盘（临时目录就在用户盘上），
 * 若沿用缺省的 90，在没有越界用例里那一行也会变黄，「哪一行是什么档位」这类断言会随跑测机器的
 * 磁盘使用率而变。堆与死锁则完全由桩控制，用它来验「越界标黄」。
 *
 * @author zcd
 */
@DisplayName("资源面板")
class ResmonPanelTest {

    /** 面板行的显示宽度上限（含边框后落在侧栏的宽度预算里，不挤压消息区）。 */
    private static final int MAX_WIDTH = 22;

    /** 本类里把分区阈值设成不会触发的值，见类注释。 */
    private static final double DISK_THRESHOLD_NEVER = 100.0;

    /** 告警关闭时面板里 JVM 与磁盘固定部分的基线行数（不含子项行）。 */
    private static final int FIXED_ROWS = 12;

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
        config = config(root, 12, true);
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
        prepareChildren();
        prepareSample();
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        assertEquals(FIXED_ROWS + 3, lines.size(), "固定 12 行 + 3 个子项 + 合计");
        assertTrue(lines.size() > 8, "侧栏不受 8 行规则约束");
    }

    @Test
    @DisplayName("每一行都不超过 22 个显示列")
    void handle_should_keep_lines_narrow() throws IOException {
        prepareChildren();
        prepareSample();
        sampler.sampleNow();

        for (UiLine line : panel.handle(new PanelContributionRequest("s-1")).getLines()) {
            int width = ResmonFormat.displayWidth(line.text());
            assertTrue(width <= MAX_WIDTH, "行太宽（" + width + " 列）：" + line.text());
        }
    }

    @Test
    @DisplayName("超长的子项名被截断，但行宽仍然守住预算")
    void handle_should_clip_long_names() throws IOException {
        Files.createDirectories(root.resolve("a-very-long-directory-name-that-would-blow-the-budget"));
        prepareSample();
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        boolean sawClipped = false;
        for (UiLine line : lines) {
            assertTrue(ResmonFormat.displayWidth(line.text()) <= MAX_WIDTH, line.text());
            if (line.text().contains("a-very-lo") && line.text().contains("\u2026")) {
                sawClipped = true;
            }
        }
        assertTrue(sawClipped, "长名字应当被截断：" + text(lines));
    }

    @Test
    @DisplayName("名字的宽度预算按后缀自适应：后缀短的名字能完整显示，不会都变成同一个前缀")
    void handle_should_keep_short_names_intact() throws IOException {
        Files.write(root.resolve("jellyfish-tui.log"), new byte[4096]);
        Files.write(root.resolve("jellyfish.json"), new byte[1024]);
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")).getLines());

        // 固定 10 列预算会把这两个都截成「jellyfish…」，于是两行看不出区别
        assertTrue(text.contains("jellyfish-tui.log "), text);
        assertTrue(text.contains("jellyfish.json "), text);
    }

    @Test
    @DisplayName("轮换/备份文件不单独占一行：它们已经被算进主文件，再列一遍就是重复计算")
    void handle_should_not_doubleCount_rotatedFiles() throws IOException {
        Files.write(root.resolve("jellyfish-tui.log"), new byte[1024]);
        Files.write(root.resolve("jellyfish-tui.log.1"), new byte[4096]);
        Files.write(root.resolve("jellyfish.json"), new byte[512]);
        Files.write(root.resolve("jellyfish.json.bak"), new byte[256]);
        prepareSample();
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();
        String text = text(lines);

        // 轮换档折叠进主文件：每一项的文件数是 2（主文件 + 一个后缀文件），而不是各占一行
        assertTrue(text.contains("5K 2文件"), text);
        assertTrue(text.contains("768B 2文件"), text);
        assertFalse(text.contains("log.1"), "轮换档不该单独成行：" + text);
        assertFalse(text.contains("json.bak"), "备份不该单独成行：" + text);
        // 合计只算一次：1K + 4K + 512B + 256B = 5888B
        assertTrue(text.contains("合计 5.8K"), text);
        // 名字虽被截断，但两行必须能区分——固定 10 列预算时它们都会是「jellyfish…」
        List<String> jellyfishRows = new ArrayList<String>();
        for (UiLine line : lines) {
            if (line.text().contains("jellyfish")) {
                jellyfishRows.add(line.text());
            }
        }
        assertEquals(2, jellyfishRows.size(), text);
        assertFalse(jellyfishRows.get(0).equals(jellyfishRows.get(1)),
                "两行截断后不该长得一样：" + jellyfishRows);
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
    @DisplayName("内容包含堆、内存池、GC、线程、FD、CPU、分区与子项")
    void handle_should_include_all_sections() throws IOException {
        prepareChildren();
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")).getLines());

        for (String needle : new String[]{"堆 ", "非堆 ", "元空间 ", "类空间 ", "GC ", "老年代 ",
                "线程 ", "死锁 ", "FD ", "CPU ", "磁盘 ", "sessions", "tool-outp", "合计 "}) {
            assertTrue(text.contains(needle), "面板缺少「" + needle + "」：" + text);
        }
    }

    @Test
    @DisplayName("子项按体积从大到小排列：最大的永远在第一行")
    void handle_should_list_children_by_size() throws IOException {
        Files.createDirectories(root.resolve("small"));
        Files.write(root.resolve("small/a"), new byte[1024]);
        Files.createDirectories(root.resolve("big"));
        Files.write(root.resolve("big/a"), new byte[512 * 1024]);
        prepareSample();
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();
        int bigRow = rowOfPrefix(lines, "big ");
        int smallRow = rowOfPrefix(lines, "small ");

        assertTrue(bigRow > 0 && smallRow > 0, text(lines));
        assertTrue(bigRow < smallRow, "体积大的应当排在前面：" + text(lines));
    }

    @Test
    @DisplayName("子项数超过配置上限时补一行「还有 N 项」，而不是静默省略")
    void handle_should_summarize_when_childrenExceedLimit() throws IOException {
        PluginConfig limited = config(root, 2, true);
        ResmonSampler limitedSampler = new ResmonSampler(limited, probe, new DirSizer(4),
                Mockito.mock(PluginContext.class));
        Files.createDirectories(root.resolve("one"));
        Files.createDirectories(root.resolve("two"));
        Files.createDirectories(root.resolve("three"));
        prepareSample();
        limitedSampler.sampleNow();

        List<UiLine> lines = new ResmonPanel(limited, limitedSampler)
                .handle(new PanelContributionRequest("s-1")).getLines();

        assertTrue(text(lines).contains("\u2026 还有 1 项"), text(lines));
    }

    @Test
    @DisplayName("文件数大于 1 时标出来：单个大文件与一堆小文件该清理的对象不同")
    void handle_should_show_file_count() throws IOException {
        Files.createDirectories(root.resolve("sessions"));
        Files.write(root.resolve("sessions/a.json"), new byte[1024]);
        Files.write(root.resolve("sessions/b.json"), new byte[512]);
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")).getLines());

        assertTrue(text.contains("sessions 1.5K 2文件"), text);
    }

    @Test
    @DisplayName("子项不存在时显示短杠，而不是 0B（缺数据与真的是零不是一回事）")
    void handle_should_mark_missing_children() throws IOException {
        // 一个 dangling symlink：它出现在目录列表里，但没有可统计的目标
        Path link = root.resolve("dangling");
        try {
            Files.createSymbolicLink(link, root.resolve("missing-target"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        prepareSample();
        sampler.sampleNow();

        String text = text(panel.handle(new PanelContributionRequest("s-1")).getLines());

        assertTrue(text.contains("dangling " + ResmonFormat.UNKNOWN), text);
    }

    @Test
    @DisplayName("堆越阈值时那一行转警示档位")
    void handle_should_warn_when_heapOverThreshold() {
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .build());
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        assertEquals(UiEmphasis.WARN, lines.get(0).getSegments().get(0).getEmphasis());
        assertTrue(lines.get(0).text().startsWith("堆 "), lines.get(0).text());
    }

    @Test
    @DisplayName("没有告警行：越阈值只标黄那一项，不再多说一遍")
    void handle_should_notAddAlertLines() {
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .deadlocked(2L)
                .build());
        sampler.sampleNow();

        List<UiLine> lines = panel.handle(new PanelContributionRequest("s-1")).getLines();

        for (UiLine line : lines) {
            assertFalse(line.text().startsWith("! "), "不该有告警行：" + line.text());
            assertFalse(line.text().contains("超阈值"), "阈值不该单独占一行：" + line.text());
        }
        assertEquals(UiEmphasis.ERROR, emphasisOfRow(lines, "死锁 "));
    }

    @Test
    @DisplayName("关掉阈值标出后没有警示档位，读数照旧")
    void handle_should_hideEmphasis_when_alertsDisabled() {
        PluginConfig quiet = config(root, 12, false);
        JvmProbe quietProbe = Mockito.mock(JvmProbe.class);
        Mockito.when(quietProbe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(900L, 1000L, 1000L)
                .build());
        ResmonSampler quietSampler = new ResmonSampler(quiet, quietProbe, new DirSizer(4),
                Mockito.mock(PluginContext.class));
        quietSampler.sampleNow();

        List<UiLine> lines = new ResmonPanel(quiet, quietSampler)
                .handle(new PanelContributionRequest("s-1")).getLines();

        assertEquals(UiEmphasis.NORMAL, lines.get(0).getSegments().get(0).getEmphasis());
        assertTrue(lines.get(0).text().startsWith("堆 "), lines.get(0).text());
    }

    /**
     * 找到以某前缀开头的行号。
     *
     * @param lines  行列表，不可为 {@code null}
     * @param prefix 行前缀
     * @return 行号；找不到返回 -1
     */
    private static int rowOfPrefix(List<UiLine> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).text().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取以某前缀开头的那一行第一个文本段的强调档位。
     *
     * @param lines  行列表，不可为 {@code null}
     * @param prefix 行前缀
     * @return 强调档位
     */
    private static UiEmphasis emphasisOfRow(List<UiLine> lines, String prefix) {
        int row = rowOfPrefix(lines, prefix);
        assertTrue(row >= 0, "面板里没有以「" + prefix + "」开头的行");
        return lines.get(row).getSegments().get(0).getEmphasis();
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
     * 在根目录下造三个子项。
     *
     * @throws IOException 写入失败时抛出
     */
    private void prepareChildren() throws IOException {
        Files.createDirectories(root.resolve("sessions"));
        Files.write(root.resolve("sessions/a.json"), new byte[1536]);
        Files.createDirectories(root.resolve("tool-outputs"));
        Files.write(root.resolve("tool-outputs/a.bin"), new byte[24576]);
        Files.write(root.resolve("jellyfish-tui.log"), new byte[8192]);
    }

    /**
     * 把行列表拼成一段文本。
     *
     * @param lines 行列表，不可为 {@code null}
     * @return 各行的纯文本，用换行连接
     */
    private static String text(List<UiLine> lines) {
        StringBuilder out = new StringBuilder();
        for (UiLine line : lines) {
            out.append(line.text()).append('\n');
        }
        return out.toString();
    }

    /**
     * 构造以临时目录为根目录的配置。
     *
     * @param directory     根目录，不可为 {@code null}
     * @param diskEntries   面板上列出的子项数上限
     * @param alertsEnabled 是否标出越阈值的项
     * @return 配置
     */
    private static PluginConfig config(Path directory, int diskEntries, boolean alertsEnabled) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, directory.toString());
        values.put(PluginConfig.KEY_DISK_ENTRIES, diskEntries);
        values.put(PluginConfig.KEY_ALERTS, alertsEnabled);
        values.put(PluginConfig.KEY_ALERT_DISK_PERCENT, DISK_THRESHOLD_NEVER);
        return PluginConfig.from(values);
    }
}
