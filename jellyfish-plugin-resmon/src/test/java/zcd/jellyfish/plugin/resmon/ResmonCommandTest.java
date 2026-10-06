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
 * 因此这里既验分流（每个子命令只给自己的那部分），也验它不会在没数据时给出看起来正常的零。
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
        assertTrue(output.contains("会话文件"), output);
    }

    @Test
    @DisplayName("jvm 子命令只给 JVM 部分")
    void handle_should_report_jvmOnly() throws IOException {
        prepareSampled();

        String output = run("jvm");

        assertTrue(output.contains("内存池"), output);
        assertTrue(output.contains("GC "), output);
        assertFalse(output.contains("会话文件"), output);
    }

    @Test
    @DisplayName("disk 子命令只给磁盘部分")
    void handle_should_report_diskOnly() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("会话文件"), output);
        assertTrue(output.contains("合计"), output);
        assertFalse(output.contains("内存池"), output);
    }

    @Test
    @DisplayName("磁盘明细里给出每个目录的路径，用户要按它去清理")
    void handle_should_include_paths() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains(root.resolve("sessions").toString()), output);
    }

    @Test
    @DisplayName("磁盘明细末尾提醒路径来自本插件配置，避免把错的数字当权威")
    void handle_should_warn_about_path_source() throws IOException {
        prepareSampled();

        String output = run("disk");

        assertTrue(output.contains("配置段"), output);
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

    @Test
    @DisplayName("空参数走默认分支而不是报错")
    void handle_should_accept_empty_arguments() {
        CommandResult result = command.handle(new CommandRequest(ResmonCommand.NAME,
                new CommandArguments(Collections.<String>emptyList(), ""), "s-1"));

        assertEquals(CommandResult.Kind.OK, result.getKind());
    }
}
