package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DirSizer} 的单元测试：用一个真实的临时目录验证求和、计数与四条边界。
 * <p>
 * 这里刻意用真文件系统而不是 mock：本类的全部价值就在「读真实的目录」，mock 掉
 * {@code Files} 之后测的只是「我以为文件系统会怎么回答」。写进去的字节数是自己算出来的，
 * 因此断言里出现的数字都是可推导的（1024 + 512 = 1536 → {@code 1.5K}）。
 *
 * @author zcd
 */
@DisplayName("目录体积统计")
class DirSizerTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path root;

    @Test
    @DisplayName("目录不存在时报不存在，而不是报 0 字节")
    void directory_should_report_missing_when_absent() {
        PathUsage usage = new DirSizer(3).directory(UsageKeys.SESSIONS, root.resolve("nope"));

        assertFalse(usage.present());
        assertEquals(0L, usage.bytes());
        assertEquals(0L, usage.files());
    }

    @Test
    @DisplayName("空目录存在但零字节")
    void directory_should_report_present_when_empty() throws IOException {
        Path empty = Files.createDirectory(root.resolve("empty"));

        PathUsage usage = new DirSizer(3).directory(UsageKeys.SESSIONS, empty);

        assertTrue(usage.present());
        assertEquals(0L, usage.bytes());
    }

    @Test
    @DisplayName("递归求和并数出文件个数")
    void directory_should_sum_files_recursively() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        write(sessions.resolve("a.json"), 1024);
        Path nested = Files.createDirectory(sessions.resolve("nested"));
        write(nested.resolve("b.json"), 512);
        Files.createDirectory(nested.resolve("deeper"));

        PathUsage usage = new DirSizer(5).directory(UsageKeys.SESSIONS, sessions);

        assertEquals(1536L, usage.bytes());
        assertEquals(2L, usage.files());
    }

    @Test
    @DisplayName("超过深度上限的子目录不计入，避免一次采样走遍全部历史")
    void directory_should_respect_max_depth() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        write(sessions.resolve("top.json"), 100);
        Path nested = Files.createDirectory(sessions.resolve("nested"));
        write(nested.resolve("deep.json"), 9900);

        PathUsage usage = new DirSizer(1).directory(UsageKeys.SESSIONS, sessions);

        assertEquals(100L, usage.bytes());
    }

    @Test
    @DisplayName("深度上限非法时回退到缺省值")
    void maxDepth_should_fall_back_when_illegal() {
        assertEquals(DirSizer.DEFAULT_MAX_DEPTH, new DirSizer(0).maxDepth());
        assertEquals(DirSizer.DEFAULT_MAX_DEPTH, new DirSizer(-3).maxDepth());
        assertEquals(2, new DirSizer(2).maxDepth());
    }

    @Test
    @DisplayName("被同名普通文件占住的路径按不存在处理，而不是抛异常")
    void directory_should_report_missing_when_pathIsFile() throws IOException {
        Path file = root.resolve("sessions");
        write(file, 10);

        PathUsage usage = new DirSizer(3).directory(UsageKeys.SESSIONS, file);

        assertFalse(usage.present());
    }

    @Test
    @DisplayName("统计单个文件连同它的轮换历史：日志滚过之后不能只数当前那一个")
    void withSiblings_should_include_rotated_files() throws IOException {
        Path log = root.resolve("jellyfish-tui.log");
        write(log, 2048);
        write(root.resolve("jellyfish-tui.log.1"), 10240);
        write(root.resolve("jellyfish-tui.log.2"), 10240);
        // 同目录下的无关文件必须排除，否则这个数就变成了「目录体积」
        write(root.resolve("jellyfish-tui.logx-other.txt"), 999999);
        Files.createDirectory(root.resolve("jellyfish-tui.log.dir"));

        PathUsage usage = new DirSizer(3).withSiblings(UsageKeys.LOG, log);

        assertTrue(usage.present());
        assertEquals(22528L, usage.bytes());
        assertEquals(3L, usage.files());
    }

    @Test
    @DisplayName("没有历史文件时就是那一个文件的体积：单文件是它的特例")
    void withSiblings_should_report_single_file() throws IOException {
        Path log = root.resolve("jellyfish-tui.log");
        write(log, 2048);

        PathUsage usage = new DirSizer(3).withSiblings(UsageKeys.LOG, log);

        assertTrue(usage.present());
        assertEquals(2048L, usage.bytes());
        assertEquals(1L, usage.files());
    }

    @Test
    @DisplayName("一个匹配文件都没有时报不存在")
    void withSiblings_should_report_missing_when_nothing_matches() {
        PathUsage usage = new DirSizer(3).withSiblings(UsageKeys.LOG, root.resolve("nope.log"));

        assertFalse(usage.present());
        assertEquals(0L, usage.bytes());
    }

    @Test
    @DisplayName("父目录不存在时报不存在，而不是抛异常")
    void withSiblings_should_report_missing_when_parentMissing() {
        PathUsage usage = new DirSizer(3).withSiblings(UsageKeys.LOG,
                root.resolve("nowhere").resolve("jellyfish-tui.log"));

        assertFalse(usage.present());
    }

    /**
     * 写一个指定字节数的文件。
     *
     * @param path  目标路径，不可为 {@code null}
     * @param bytes 字节数
     * @throws IOException 写入失败时抛出
     */
    private static void write(Path path, int bytes) throws IOException {
        byte[] content = new byte[bytes];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(path, content);
    }

    @Test
    @DisplayName("内容不参与统计，只有大小参与")
    void directory_should_ignore_content() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        Files.write(sessions.resolve("a.txt"), "hello".getBytes(StandardCharsets.UTF_8));

        PathUsage usage = new DirSizer(3).directory(UsageKeys.SESSIONS, sessions);

        assertEquals(5L, usage.bytes());
    }
}
