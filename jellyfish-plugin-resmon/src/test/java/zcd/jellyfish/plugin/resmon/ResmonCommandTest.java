package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.plugin.PluginContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /resmon} 命令的单元测试：子命令分流、输出内容与「未采集时不撒谎」。
 * <p>
 * 命令是唯一在三种外壳里都能用的面，也是用户把它复制给模型去评估清理方案的那份数据，
 * 因此这里既验分流（每个子命令只给自己的那部分），也验它不会在没数据时给出看起来正常的零，
 * 还验每条子项都带上<b>可直接粘贴的完整路径</b>——面板上只有截断的名字，真正要去清理时靠的是这里。
 *
 * @author zcd
 */
@DisplayName("resmon 命令")
class ResmonCommandTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path root;

    /** 采样端口桩。 */
    private JvmProbe probe;

    /** 采样器。 */
    private ResmonSampler sampler;

    /** 被测命令。 */
    private ResmonCommand command;

    /** 插件配置。 */
    private PluginConfig config;

    @BeforeEach
    void setUp() {
        probe = Mockito.mock(JvmProbe.class);
        config = config(root);
        sampler = new ResmonSampler(config, probe, new DirSizer(4), Mockito.mock(PluginContext.class));
        command = new ResmonCommand(config, sampler);
    }

    @Test
    @DisplayName("没有参数时给完整快照：JVM 与磁盘都在")
    void handle_should_report_both_sections_byDefault() throws IOException {
        prepareSampled();

        String output = run();

        assertTrue(output.contains("资源快照"), output);
        assertTrue(output.contains("JVM（采样于"), output);
        assertTrue(output.contains("磁盘（扫描于"), output);
    }

    @Test
    @DisplayName("jvm 子命令只给 JVM 部分")
    void handle_should_report_jvmOnly() throws IOException {
        prepareSampled();

        String output = run("jvm");

        assertTrue(output.contains("内存池"), output);
        assertFalse(output.contains("磁盘（扫描于"), output);
    }

    @Test
    @DisplayName("disk 子命令只给磁盘部分")
    void handle_should_report_diskOnly() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("磁盘（扫描于"), output);
        assertFalse(output.contains("内存池"), output);
    }

    @Test
    @DisplayName("磁盘明细列出每个一级子项的名字、体积、文件数与可直接粘贴的完整路径")
    void handle_should_list_children_with_paths() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("sessions"), output);
        assertTrue(output.contains(root.resolve("sessions").toString()), output);
        assertTrue(output.contains("个文件"), output);
    }

    @Test
    @DisplayName("版本库内部单独标出来：它解释了这个目录为什么比看上去大")
    void handle_should_expose_vcs_bytes() throws IOException {
        Path git = Files.createDirectories(root.resolve("sessions/.git"));
        Files.write(git.resolve("obj"), new byte[4096]);
        Files.write(root.resolve("sessions/a.json"), new byte[100]);
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("其中版本库 4K"), output);
    }

    @Test
    @DisplayName("磁盘段写明口径：体积含版本库、文件数不含")
    void handle_should_state_the_counting_rule() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("体积含版本库内部"), output);
    }

    @Test
    @DisplayName("磁盘段给出一级子项总数，明说这是 baseDir 下扫出来的")
    void handle_should_state_baseDir_scope() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("baseDir"), output);
        assertTrue(output.contains("个一级子项"), output);
    }

    @Test
    @DisplayName("还卷老年代与增长率的读数都在")
    void handle_should_report_deltas() throws IOException {
        prepareSampled();

        String output = run();

        assertTrue(output.contains("年轻代"), output);
        assertTrue(output.contains("老年代"), output);
        assertTrue(output.contains("文件描述符"), output);
    }

    @Test
    @DisplayName("没有任何采样时不报零，而是明说还没数据")
    void handle_should_admit_missing_data() {
        assertTrue(run("jvm").contains("尚未"), "未采集时 JVM 段应当承认没有数据");
        assertTrue(run("disk").contains("尚未"), "未扫描时磁盘段应当承认没有数据");
    }

    @Test
    @DisplayName("越阈值时输出开头有「告警」段，两种子视图里都在")
    void handle_should_report_alerts() throws IOException {
        prepareSampled();
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .heap(950L, 1000L, 1000L)
                .deadlocked(2L)
                .build());
        sampler.sampleNow();

        String full = run();
        String jvmOnly = run("jvm");
        String diskOnly = run("disk");

        assertTrue(full.contains("告警 堆已用 95%"), full);
        assertTrue(jvmOnly.startsWith("告警 堆已用 95%"), jvmOnly);
        assertTrue(diskOnly.startsWith("告警 堆已用 95%"), diskOnly);
        assertTrue(full.contains("死锁线程"), full);
    }

    @Test
    @DisplayName("没有越阈值时不写「告警」段，而不是写一行「无告警」")
    void handle_should_omit_alertSection_when_quiet() throws IOException {
        prepareSampled();

        assertFalse(run().contains("告警"), run());
    }

    @Test
    @DisplayName("auto 不带参数时只报状态，不改状态")
    void handle_should_report_auto_state() {
        CommandResult result = command.handle(request("auto"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("开"), result.getOutput());
        assertTrue(sampler.autoRefresh());
    }

    @Test
    @DisplayName("auto off 关掉主动刷新，auto on 再打开")
    void handle_should_toggle_auto() {
        command.handle(request("auto", "off"));
        assertFalse(sampler.autoRefresh());

        command.handle(request("auto", "on"));
        assertTrue(sampler.autoRefresh());
    }

    @Test
    @DisplayName("auto 参数写错时给用法，不静默忽略")
    void handle_should_reject_illegal_auto_argument() {
        CommandResult result = command.handle(request("auto", "maybe"));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("用法"), result.getOutput());
        assertTrue(sampler.autoRefresh());
    }

    @Test
    @DisplayName("未知子命令给用法并标错误")
    void handle_should_reject_unknown_argument() {
        CommandResult result = command.handle(request("clean"));

        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/resmon jvm"), result.getOutput());
    }

    @Test
    @DisplayName("help 给用法说明")
    void handle_should_print_usage() {
        CommandResult result = command.handle(request("help"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("/resmon disk"), result.getOutput());
    }

    @Test
    @DisplayName("空参数走默认分支而不是报错")
    void handle_should_accept_empty_arguments() {
        CommandResult result = command.handle(new CommandRequest(ResmonCommand.NAME,
                new CommandArguments(Collections.<String>emptyList(), ""), "s-1"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
    }

    /**
     * 让采样器先采一轮 JVM 与磁盘。
     *
     * @throws IOException 造目录失败时抛出
     */
    private void prepareSampled() throws IOException {
        Files.createDirectories(root.resolve("sessions"));
        Files.write(root.resolve("sessions/a.json"), new byte[1536]);
        Files.createDirectories(root.resolve("gateway"));
        Files.write(root.resolve("gateway/g.bin"), new byte[2048]);
        Mockito.when(probe.probe()).thenReturn(JvmStats.builder()
                .capturedAt(1600000000000L)
                .uptime(3600000L)
                .heap(536870912L, 1073741824L, 2147483648L)
                .nonHeap(100663296L)
                .memoryPools(75497472L, 12582912L, 8388608L)
                .gc(12L, 1200L, 1L, 300L, 2000L)
                .threads(42L, 61L, 28L)
                .deadlocked(0L)
                .loadedClasses(6821L)
                .fileDescriptors(210L, 8192L)
                .cpu(12.5, 34.0)
                .availableProcessors(8)
                .build());
        sampler.sampleNow();
    }

    /**
     * 执行一次命令。
     *
     * @param tokens 参数
     * @return 输出文本
     */
    private String run(String... tokens) {
        return command.handle(request(tokens)).getOutput();
    }

    /**
     * 构造命令请求。
     *
     * @param tokens 参数
     * @return 请求
     */
    private static CommandRequest request(String... tokens) {
        return new CommandRequest(ResmonCommand.NAME,
                new CommandArguments(Arrays.asList(tokens), String.join(" ", tokens)), "s-1");
    }

    /**
     * 构造以临时目录为根目录的配置。
     * <p>
     * <b>分区阈值被设成 100</b>：分区占用比例取自真实磁盘（临时目录就在用户盘上），
     * 若沿用缺省的 90，这台机器上（已用 92%）每条用例都会多出一行磁盘告警，
     * 「没有越阈值时不写告警段」那类断言于是随机器而变。
     *
     * @param directory 根目录，不可为 {@code null}
     * @return 配置
     */
    private static PluginConfig config(Path directory) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, directory.toString());
        values.put(PluginConfig.KEY_ALERT_DISK_PERCENT, 100);
        return PluginConfig.from(values);
    }
}
