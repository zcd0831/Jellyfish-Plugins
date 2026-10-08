package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolPaths} 的单元测试。
 * <p>
 * 重点锁住展示口径：工作目录内显示相对路径（模型才能把两次调用的输出对上），
 * 目录外退回绝对路径（否则无法定位）。
 *
 * @author zcd
 */
@DisplayName("ToolPaths 路径约定")
class ToolPathsTest {

    /** 进程工作目录，与实现保持同一口径。 */
    private static final Path WORKING_DIRECTORY = Paths.get("").toAbsolutePath().normalize();

    @Test
    @DisplayName("相对路径应按工作目录解析成绝对路径")
    void resolve_should_resolveAgainstWorkingDirectory() {
        assertEquals(WORKING_DIRECTORY.resolve("a/b.txt"), ToolPaths.resolve("a/b.txt"));
    }

    @Test
    @DisplayName("路径应做规范化：多余的 . 与 .. 被消掉")
    void resolve_should_normalize() {
        assertEquals(WORKING_DIRECTORY.resolve("b.txt"), ToolPaths.resolve("./a/../b.txt"));
    }

    @Test
    @DisplayName("行首的 ~ 应按用户主目录展开：工具结果的回灌路径就是这种形式")
    void resolve_should_expandTilde_toUserHome() {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();

        assertEquals(home.resolve(".jellyfish/tool-outputs/x.txt"),
                ToolPaths.resolve("~/.jellyfish/tool-outputs/x.txt"));
        assertEquals(home, ToolPaths.resolve("~"));
    }

    @Test
    @DisplayName("~other 不展开：解析别人的主目录是 shell 的能力，插件不猜")
    void expandHome_should_keepTilde_when_itNamesAnotherUser() {
        assertEquals("~other/x.txt", ToolPaths.expandHome("~other/x.txt"));
    }

    @Test
    @DisplayName("工作目录内的路径应显示为相对路径")
    void display_should_returnRelativePath_when_insideWorkingDirectory() {
        assertEquals("src/Main.java", ToolPaths.display(WORKING_DIRECTORY.resolve("src/Main.java")));
    }

    @Test
    @DisplayName("工作目录自身应显示为 .")
    void display_should_returnDot_when_pathIsWorkingDirectory() {
        assertEquals(".", ToolPaths.display(WORKING_DIRECTORY));
    }

    @Test
    @DisplayName("工作目录外的路径应显示为绝对路径")
    void display_should_returnAbsolutePath_when_outsideWorkingDirectory() {
        Path outside = WORKING_DIRECTORY.getParent().resolve("elsewhere.txt");

        String display = ToolPaths.display(outside);

        assertTrue(Paths.get(display).isAbsolute(), display);
    }

    @Test
    @DisplayName("落在允许目录内的路径应判为在内，含目录自身与深层子路径")
    void insideAny_should_detectPathsUnderRoot() {
        Path root = WORKING_DIRECTORY.resolve("target");

        assertTrue(ToolPaths.insideAny(WORKING_DIRECTORY.resolve("target"), Collections.singletonList(root)));
        assertTrue(ToolPaths.insideAny(
                WORKING_DIRECTORY.resolve("target/classes/a.class"), Collections.singletonList(root)));
    }

    @Test
    @DisplayName("用 .. 跳出去的路径不在内：字面规范化必须发生在比较之前")
    void insideAny_should_rejectEscapingPath() {
        Path root = WORKING_DIRECTORY.resolve("target");

        assertFalse(ToolPaths.insideAny(
                WORKING_DIRECTORY.resolve("target/../outside.txt"), Collections.singletonList(root)));
    }

    @Test
    @DisplayName("同前缀的兄弟目录不在内：按路径段比，不按字符串前缀")
    void insideAny_should_notMatchSiblingDirectoryWithSamePrefix() {
        Path root = WORKING_DIRECTORY.resolve("target");

        assertFalse(ToolPaths.insideAny(
                WORKING_DIRECTORY.resolve("target-other/a.txt"), Collections.singletonList(root)));
    }

    @Test
    @DisplayName("指向允许目录之外的符号链接不在内：这是这道判据存在的理由")
    void insideAny_should_rejectSymlinkPointingOutside() throws IOException {
        Path root = WORKING_DIRECTORY.resolve("target");
        Files.createDirectories(root);
        Path linked = root.resolve("escape-" + System.nanoTime() + ".txt");
        Path elsewhere = Files.createTempFile("jellyfish-symlink", ".txt");
        try {
            Files.createSymbolicLink(linked, elsewhere);
        } catch (IOException | UnsupportedOperationException e) {
            // 无权限建链接的文件系统（常见于 Windows）：这一层防线测不了，跳过
            Files.deleteIfExists(elsewhere);
            return;
        }
        try {
            assertFalse(ToolPaths.insideAny(linked, Collections.singletonList(root)), linked.toString());
        } finally {
            Files.deleteIfExists(linked);
            Files.deleteIfExists(elsewhere);
        }
    }

    @Test
    @DisplayName("尚不存在的路径：解开已存在的那一段，尾段原样保留")
    void realPath_should_resolveExistingPrefix_andKeepMissingTail() {
        Path root = WORKING_DIRECTORY.resolve("target");

        Path real = ToolPaths.realPath(root.resolve("not-created-yet/a.txt"));

        assertTrue(real.startsWith(ToolPaths.realPath(root)), real.toString());
        assertTrue(real.endsWith(Paths.get("not-created-yet/a.txt")), real.toString());
    }
}
