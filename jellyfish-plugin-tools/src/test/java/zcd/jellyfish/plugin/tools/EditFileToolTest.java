package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invokeResult;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.summaryOf;

/**
 * {@link EditFileTool} 的单元测试。
 * <p>
 * 重点锁住「匹配唯一性」这条安全阀：匹配到多处而没声明 {@code replace_all} 时必须报错，
 * 否则一次替换会把不相关的地方一起改掉。同时锁住字面量语义——原文里的正则元字符
 * 必须当普通字符处理。
 *
 * @author zcd
 */
@DisplayName("EditFileTool 精确替换")
class EditFileToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具。 */
    private final EditFileTool tool = new EditFileTool();

    @Test
    @DisplayName("唯一匹配应被替换并报告替换处数")
    void handle_should_replaceOnce_when_matchIsUnique() throws Exception {
        Path file = write("a.txt", "hello world");

        String output = invoke(tool, args("path", file.toString(), "old_text", "world", "new_text", "jellyfish"));

        assertEquals("hello jellyfish", read(file));
        assertTrue(output.contains("替换 1 处"), output);
    }

    @Test
    @DisplayName("匹配到多处且未声明 replace_all 应报错，且不改动文件")
    void handle_should_fail_when_multipleMatchesWithoutReplaceAll() {
        Path file = write("a.txt", "x = 1\nx = 2\n");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "x", "new_text", "y")));

        assertTrue(failure.getMessage().contains("匹配到 2 处"), failure.getMessage());
        assertEquals("x = 1\nx = 2\n", read(file));
    }

    @Test
    @DisplayName("声明 replace_all 时应全部替换")
    void handle_should_replaceAll_when_replaceAllDeclared() throws Exception {
        Path file = write("a.txt", "x = 1\nx = 2\n");

        String output = invoke(tool, args("path", file.toString(), "old_text", "x",
                "new_text", "y", "replace_all", true));

        assertEquals("y = 1\ny = 2\n", read(file));
        assertTrue(output.contains("替换 2 处"), output);
    }

    @Test
    @DisplayName("新文本为空串等于删除匹配段")
    void handle_should_deleteMatch_when_newTextEmpty() throws Exception {
        Path file = write("a.txt", "keep\nremove\nkeep2\n");

        invoke(tool, args("path", file.toString(), "old_text", "remove\n", "new_text", ""));

        assertEquals("keep\nkeep2\n", read(file));
    }

    @Test
    @DisplayName("原文里的正则元字符必须按字面量处理")
    void handle_should_treatOldTextAsLiteral_when_containsRegexMetacharacters() throws Exception {
        Path file = write("a.txt", "total = a.b\n");

        invoke(tool, args("path", file.toString(), "old_text", "a.b", "new_text", "aXb"));

        assertEquals("total = aXb\n", read(file));
    }

    @Test
    @DisplayName("未匹配到内容应报错并提示核对原文")
    void handle_should_fail_when_noMatch() {
        Path file = write("a.txt", "hello world");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "missing", "new_text", "x")));

        assertTrue(failure.getMessage().contains("未找到 old_text"), failure.getMessage());
    }

    @Test
    @DisplayName("原文与新文本相同属于无效调用")
    void handle_should_fail_when_oldEqualsNew() {
        Path file = write("a.txt", "hello");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "hello", "new_text", "hello")));

        assertTrue(failure.getMessage().contains("无需修改"), failure.getMessage());
    }

    @Test
    @DisplayName("原文为空应引导改用 write_file")
    void handle_should_fail_when_oldTextEmpty() {
        Path file = write("a.txt", "hello");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "", "new_text", "x")));

        assertTrue(failure.getMessage().contains("write_file"), failure.getMessage());
    }

    @Test
    @DisplayName("文件不存在应报错")
    void handle_should_fail_when_fileMissing() {
        Path missing = tempDir.resolve("missing.txt");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", missing.toString(), "old_text", "a", "new_text", "b")));

        assertTrue(failure.getMessage().contains("文件不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("不是 UTF-8 的文件应明确拒绝，而不是「读成乱码再整份写回」")
    void handle_should_reject_when_fileIsNotUtf8() throws Exception {
        // Given：GBK 编码的「中文」——按 UTF-8 解码一定非法
        Path file = tempDir.resolve("gbk.txt");
        Files.write(file, "中文".getBytes("GBK"));

        // When
        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "中", "new_text", "新")));

        // Then：一次替换会把整份内容变成乱码，那是不可逆的损毁，因此宁可在这里失败
        assertTrue(failure.getMessage().contains("UTF-8"), failure.getMessage());
        assertEquals("中文", new String(Files.readAllBytes(file), "GBK"), "原文件不该被碰过");
    }

    @Test
    @DisplayName("读之后文件被改动过时应拒绝写入，而不是把别人的改动抹掉")
    void handle_should_reject_when_fileChangedSinceRead() throws Exception {
        Path file = write("a.txt", "hello");
        EditFileTool.Stamp before = EditFileTool.Stamp.of(file);
        // 模拟「读之后、写之前被别人改了」：内容与修改时间都换掉
        Files.write(file, "别人改的".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 10_000L));

        JellyfishException failure = expectFailure(() -> {
            tool.requireUnchanged(file, before);
            return null;
        });

        assertTrue(failure.getMessage().contains("被改动过"), failure.getMessage());
    }

    @Test
    @DisplayName("替换应保留原文件的权限，而不是换成临时文件的")
    void handle_should_keepFilePermissions() throws Exception {
        Path file = write("a.txt", "hello");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> expected =
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
        try {
            Files.setPosixFilePermissions(file, expected);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（Windows）：这一层测不了，跳过
            return;
        }

        invoke(tool, args("path", file.toString(), "old_text", "hello", "new_text", "world"));

        assertEquals(expected, Files.getPosixFilePermissions(file));
    }

    @Test
    @DisplayName("只读文件应拒绝写入：原子替换不该越过文件权限")
    void handle_should_reject_when_fileIsReadOnly() throws Exception {
        Path file = write("a.txt", "hello");
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException e) {
            return;
        }
        if (Files.isWritable(file)) {
            // 以 root 运行时只读位对写权限判定不起作用（容器里常见）：这一层测不了，跳过
            return;
        }

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "hello", "new_text", "world")));

        // 原子替换只受目录权限约束，会绕过文件自身的只读位；那与「不可写就别写」的直觉相反
        assertTrue(failure.getMessage().contains("不可写"), failure.getMessage());
    }

    @Test
    @DisplayName("新旧文本都必须存在，缺一个就报错")
    void handle_should_fail_when_newTextMissing() {
        Path file = write("a.txt", "hello");

        JellyfishException failure = expectFailure(() -> invoke(tool,
                args("path", file.toString(), "old_text", "hello")));

        assertEquals("缺少必需参数: new_text", failure.getMessage());
    }

    @Test
    @DisplayName("replace_all 传字符串 \"true\" 也应被接受")
    void handle_should_acceptStringBooleans_when_replaceAllIsString() throws Exception {
        Path file = write("a.txt", "x x");

        invoke(tool, args("path", file.toString(), "old_text", "x", "new_text", "y", "replace_all", "true"));

        assertEquals("y y", read(file));
    }

    @Test
    @DisplayName("摘要给出文件与替换处数")
    void handle_should_summarizeReplacement() throws Exception {
        Path file = write("a.txt", "a a");

        ToolCallResult result = invokeResult(tool, args("path", file.toString(),
                "old_text", "a", "new_text", "b", "replace_all", Boolean.TRUE));

        assertTrue(summaryOf(result).endsWith(" · 替换 2 处"), summaryOf(result));
    }

    /**
     * 在临时目录写入文件。
     *
     * @param name    文件名
     * @param content 内容
     * @return 文件路径
     */
    private Path write(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("测试前置写入失败", e);
        }
    }

    /**
     * 读取文件内容。
     *
     * @param file 文件路径
     * @return 内容
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试产物失败", e);
        }
    }
}
