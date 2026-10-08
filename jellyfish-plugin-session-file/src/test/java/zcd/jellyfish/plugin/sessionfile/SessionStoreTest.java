package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SessionStore} 的单元测试。
 * <p>
 * 重点锁住三件事：一个会话一个文件、内容未变不重写、会话标识不能把文件写到目录之外。
 *
 * @author zcd
 */
@DisplayName("会话文件仓库")
class SessionStoreTest {

    /** 用例独立的工作目录。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("落盘权限收到只有本人：会话正文不该让同机其他用户读到")
    void writeIfChanged_should_restrictPermissions() throws IOException {
        // Given：只在支持 POSIX 权限位的文件系统上断言（Windows 没有这一套）
        assumeTrue(supportsPosix());
        Path directory = tempDir.resolve("sessions");
        SessionStore store = new SessionStore(directory);

        // When
        assertTrue(store.writeIfChanged("s1", "{}"));

        // Then：目录 700、文件 600
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("s1.json"))));
    }

    @Test
    @DisplayName("既已存在的目录权限不动：那是用户的盘，他不一定希望被改")
    void writeIfChanged_should_notTouchExistingDirectoryPermissions() throws IOException {
        assumeTrue(supportsPosix());
        Path directory = tempDir.resolve("sessions");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

        new SessionStore(directory).writeIfChanged("s1", "{}");

        assertEquals("rwxrwxrwx", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    @Test
    @DisplayName("临时文件被预置成符号链接时写不进去，而不是跟着链接写到别处")
    void writeIfChanged_should_refuse_when_tempIsSymlink() throws IOException {
        assumeTrue(supportsPosix());
        Path directory = tempDir.resolve("sessions");
        Files.createDirectories(directory);
        Path outside = tempDir.resolve("outside.txt");
        Files.write(outside, "untouched".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(directory.resolve("s1.json.tmp"), outside);

        SessionStore store = new SessionStore(directory);

        // Then：写入失败（fail-closed），目标文件一个字节都没被改
        assertThrows(JellyfishException.class, () -> store.writeIfChanged("s1", "{}"));
        assertEquals("untouched", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
    }

    /**
     * 判断当前文件系统是否支持 POSIX 权限位。
     *
     * @return 支持返回 {@code true}
     */
    private static boolean supportsPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    @DisplayName("一个会话对应一个同名 JSON 文件")
    void fileOf_should_useSessionIdAsFileName() {
        Path file = new SessionStore(tempDir).fileOf("session-1");

        assertEquals(tempDir.resolve("session-1.json"), file);
    }

    @Test
    @DisplayName("首次落盘应真正写入，并自动创建目录")
    void writeIfChanged_should_writeAndCreateDirectory_when_firstTime() throws IOException {
        SessionStore store = new SessionStore(tempDir.resolve("nested"));

        assertTrue(store.writeIfChanged("session-1", "{\"a\":1}"));

        assertEquals("{\"a\":1}", new String(
                Files.readAllBytes(store.fileOf("session-1")), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("内容未变时不应重写文件")
    void writeIfChanged_should_skip_when_contentUnchanged() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");
        Path file = store.fileOf("session-1");
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(file);

        assertFalse(store.writeIfChanged("session-1", "{\"a\":1}"));

        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    @DisplayName("内容变化时应覆盖写入")
    void writeIfChanged_should_overwrite_when_contentChanged() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");

        assertTrue(store.writeIfChanged("session-1", "{\"a\":2}"));

        assertEquals("{\"a\":2}", new String(
                Files.readAllBytes(store.fileOf("session-1")), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("原子替换不应留下临时文件")
    void writeIfChanged_should_notLeaveTempFiles() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{\"a\":1}");
        store.writeIfChanged("session-1", "{\"a\":2}");

        try (java.util.stream.Stream<Path> paths = Files.list(tempDir)) {
            assertEquals(1, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @DisplayName("列举只认 .json，临时文件与其它文件都不算会话")
    void files_should_onlyReturnSessionFiles() throws IOException {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", "{}");
        store.writeIfChanged("session-2", "{}");
        Files.write(tempDir.resolve("session-3.json.tmp"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<Path> files = store.files();

        assertEquals(2, files.size());
        assertEquals("session-1.json", files.get(0).getFileName().toString());
        assertEquals("session-2.json", files.get(1).getFileName().toString());
    }

    @Test
    @DisplayName("目录不存在时列举为空而不是报错")
    void files_should_returnEmpty_when_directoryMissing() {
        assertTrue(new SessionStore(tempDir.resolve("missing")).files().isEmpty());
    }

    @Test
    @DisplayName("会话标识含路径分隔符或上跳片段时必须拒绝：它可能来自外部文件")
    void fileOf_should_rejectUnsafeSessionId() {
        SessionStore store = new SessionStore(tempDir);

        assertThrows(JellyfishException.class, () -> store.fileOf("../../etc/passwd"));
        assertThrows(JellyfishException.class, () -> store.fileOf("a/b"));
        assertThrows(JellyfishException.class, () -> store.fileOf("a\\b"));
        assertThrows(JellyfishException.class, () -> store.fileOf("  "));
    }

    @Test
    @DisplayName("读取应原样返回文件内容")
    void read_should_returnContent() {
        SessionStore store = new SessionStore(tempDir);
        SessionSnapshot snapshot = TestSnapshots.minimal("session-1");
        store.writeIfChanged("session-1", SnapshotJson.write(snapshot));

        assertEquals(SnapshotJson.write(snapshot), store.read(store.fileOf("session-1")));
    }

    @Test
    @DisplayName("删除应移除文件；文件不存在时返回 false 而不是抛错")
    void delete_should_removeFile_and_beIdempotent() {
        SessionStore store = new SessionStore(tempDir);
        store.writeIfChanged("session-1", SnapshotJson.write(TestSnapshots.minimal("session-1")));

        assertTrue(store.delete("session-1"));
        assertFalse(Files.exists(store.fileOf("session-1")));
        assertFalse(store.delete("session-1"));
    }
}
