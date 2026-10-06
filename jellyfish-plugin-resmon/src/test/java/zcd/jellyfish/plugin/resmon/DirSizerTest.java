package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * 因此断言里出现的数字都是可推导的（1024 + 512 = 1536）。
 * <p>
 * <b>版本库那一组是本类最容易搞错的地方</b>：体积要含 git 内部（否则与分区已用空间对不上），
 * 文件数要不含（否则会话目录会报出「769 个文件」而用户只有 37 次会话）。两条断言必须同时存在，
 * 只验其中一条都会漏掉这个不对称。
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
        PathUsage usage = new DirSizer(3).directory("sessions", root.resolve("nope"));

        assertFalse(usage.present());
        assertEquals(0L, usage.bytes());
        assertEquals(0L, usage.files());
    }

    @Test
    @DisplayName("空目录存在但零字节")
    void directory_should_report_present_when_empty() throws IOException {
        Path empty = Files.createDirectory(root.resolve("empty"));

        PathUsage usage = new DirSizer(3).directory("empty", empty);

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

        PathUsage usage = new DirSizer(5).directory("sessions", sessions);

        assertEquals(1536L, usage.bytes());
        assertEquals(2L, usage.files());
    }

    @Test
    @DisplayName("版本库内部的字节计入体积，但它的文件不计入文件数")
    void directory_should_exclude_vcs_files_from_count_but_not_from_bytes() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        write(sessions.resolve("a.json"), 100);
        write(sessions.resolve("b.json"), 200);
        Path git = Files.createDirectory(sessions.resolve(".git"));
        Path objects = Files.createDirectory(git.resolve("objects"));
        write(objects.resolve("obj1"), 5000);
        write(objects.resolve("obj2"), 6000);
        write(git.resolve("HEAD"), 20);

        PathUsage usage = new DirSizer(6).directory("sessions", sessions);

        assertEquals(11320L, usage.bytes(), "体积必须含版本库内部");
        assertEquals(2L, usage.files(), "文件数只算用户数据，37 个会话不该报成 769 个文件");
        assertEquals(11020L, usage.vcsBytes(), "版本库内部单独记账，用来解释目录为什么变大");
    }

    @Test
    @DisplayName("版本库是根目录自己时也不把它算成用户数据")
    void directory_should_treat_root_as_vcs_when_named_so() throws IOException {
        Path git = Files.createDirectory(root.resolve(".git"));
        write(git.resolve("obj"), 4096);

        PathUsage usage = new DirSizer(3).directory(".git", git);

        assertEquals(4096L, usage.bytes());
        assertEquals(0L, usage.files());
        assertEquals(4096L, usage.vcsBytes());
    }

    @Test
    @DisplayName("版本库之外的同名子串目录照常计数（不按子串判断）")
    void directory_should_not_treat_lookalike_as_vcs() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        Path lookalike = Files.createDirectory(sessions.resolve("git-notes"));
        write(lookalike.resolve("a.md"), 777);

        PathUsage usage = new DirSizer(4).directory("sessions", sessions);

        assertEquals(777L, usage.bytes());
        assertEquals(1L, usage.files());
        assertEquals(0L, usage.vcsBytes());
    }

    @Test
    @DisplayName("超过深度上限的子目录不计入，避免一次采样走遍全部历史")
    void directory_should_respect_max_depth() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        write(sessions.resolve("top.json"), 100);
        Path nested = Files.createDirectory(sessions.resolve("nested"));
        write(nested.resolve("deep.json"), 9900);

        PathUsage usage = new DirSizer(1).directory("sessions", sessions);

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

        PathUsage usage = new DirSizer(3).directory("sessions", file);

        assertFalse(usage.present());
    }

    @Test
    @DisplayName("child：目录走递归统计")
    void child_should_handle_directory() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        write(sessions.resolve("a.json"), 300);
        write(sessions.resolve("b.json"), 400);

        PathUsage usage = new DirSizer(4).child("sessions", sessions);

        assertTrue(usage.present());
        assertEquals(700L, usage.bytes());
        assertEquals(2L, usage.files());
    }

    @Test
    @DisplayName("child：普通文件连它的轮换档一起算（滚动日志）")
    void child_should_handle_file_with_rotated_siblings() throws IOException {
        Path log = root.resolve("jellyfish-tui.log");
        write(log, 2048);
        write(root.resolve("jellyfish-tui.log.1"), 10240);
        write(root.resolve("jellyfish-tui.log.2"), 10240);
        // 同目录下的无关文件必须排除，否则这个数就变成了「目录体积」
        write(root.resolve("jellyfish-tui.logx-other.txt"), 999999);
        Files.createDirectory(root.resolve("jellyfish-tui.log.dir"));

        PathUsage usage = new DirSizer(3).child("jellyfish-tui.log", log);

        assertTrue(usage.present());
        assertEquals(22528L, usage.bytes());
        assertEquals(3L, usage.files());
        assertEquals(0L, usage.vcsBytes());
    }

    @Test
    @DisplayName("child：只有当前文件时就是它自己的体积")
    void child_should_report_single_file() throws IOException {
        Path log = root.resolve("jellyfish.json");
        write(log, 2048);

        PathUsage usage = new DirSizer(3).child("jellyfish.json", log);

        assertTrue(usage.present());
        assertEquals(2048L, usage.bytes());
        assertEquals(1L, usage.files());
    }

    @Test
    @DisplayName("child：一个匹配文件都没有时报不存在")
    void child_should_report_missing_when_nothing_matches() {
        PathUsage usage = new DirSizer(3).child("nope.log", root.resolve("nope.log"));

        assertFalse(usage.present());
        assertEquals(0L, usage.bytes());
    }

    @Test
    @DisplayName("child：父目录不存在时报不存在，而不是抛异常")
    void child_should_report_missing_when_parentMissing() {
        PathUsage usage = new DirSizer(3).child("log", root.resolve("nowhere").resolve("x.log"));

        assertFalse(usage.present());
    }

    @Test
    @DisplayName("child：符号链接指向不存在的位置时不抛异常")
    void child_should_tolerate_dangling_symlink() throws IOException {
        Path link = root.resolve("dangling");
        try {
            Files.createSymbolicLink(link, root.resolve("missing-target"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        PathUsage usage = new DirSizer(3).child("dangling", link);

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
        Files.write(path, new byte[bytes]);
    }

    @Test
    @DisplayName("内容不参与统计，只有大小参与")
    void directory_should_ignore_content() throws IOException {
        Path sessions = Files.createDirectory(root.resolve("sessions"));
        Files.write(sessions.resolve("a.txt"), "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        PathUsage usage = new DirSizer(3).directory("sessions", sessions);

        assertEquals(5L, usage.bytes());
        assertEquals(1L, usage.files());
    }
}
