package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitRepository} 的单元测试：用真实的 git 跑一遍「初始化 → 提交」。
 * <p>
 * 之所以不 mock 进程：这里要验的是「命令拼得对不对、提交身份有没有真的带上、没有仓库时会不会自己建」，
 * 把 git 换成 mock 就等于把被测行为重写一遍，什么也测不到。
 * <p>
 * 机器上没有 git 时整类跳过（{@code Assumptions}），因为「没装 git」是受支持的环境，
 * 那种情况下插件只是不记版本历史，功能仍然正确。
 *
 * @author zcd
 */
@DisplayName("git 仓库封装")
class GitRepositoryTest {

    /** 用例独立的工作目录。 */
    @TempDir
    Path tempDir;

    /** 被测封装，用例结束时统一关闭（它带着一条提交线程）。 */
    private GitRepository git;

    @BeforeEach
    void requireGit() {
        Assumptions.assumeTrue(isGitAvailable(), "环境里没有 git，跳过");
        git = new GitRepository(tempDir, true);
    }

    /**
     * 关闭提交线程，避免用例失败时留下线程与残留 git。
     */
    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close(10_000L);
            git = null;
        }
    }

    @Test
    @DisplayName("首次提交应自动初始化仓库并留下一次提交")
    void commit_should_initRepositoryAndCommit() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");

        git.commit(file, "session(1): 1 条消息");
        git.close(10_000L);

        assertTrue(Files.isDirectory(tempDir.resolve(".git")));
        assertEquals(1, commitSubjects().size());
        assertEquals("session(1): 1 条消息", commitSubjects().get(0));
    }

    @Test
    @DisplayName("两次不同内容应产生两次提交")
    void commit_should_commitEachChange() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");
        git.commit(file, "第一条");
        assertTrue(git.flush(10_000L), "第一次提交没有做完");

        Files.write(file, "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        git.commit(file, "第二条");
        git.close(10_000L);

        assertEquals(Arrays.asList("第二条", "第一条"), commitSubjects());
    }

    @Test
    @DisplayName("内容没变时 git 会拒绝提交，这属于正常情况而不是错误")
    void commit_should_keepHistoryClean_when_nothingChanged() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");
        git.commit(file, "第一条");
        assertTrue(git.flush(10_000L), "第一次提交没有做完");

        git.commit(file, "第二条");
        git.close(10_000L);

        assertEquals(1, commitSubjects().size());
    }

    @Test
    @DisplayName("落盘路径只做记账：commit 立刻返回，活落在提交线程上")
    void commit_should_beNonBlocking() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");
        int before = commitThreadCount();

        long started = System.currentTimeMillis();
        git.commit(file, "提交");
        long elapsed = System.currentTimeMillis() - started;

        // 真正的判据是「活被挪到了另一条线程上」：同步实现根本不会有这条线程
        assertEquals(before + 1, commitThreadCount(), "commit 应当把提交交给专属线程");
        // 量级检查：记账不该有可观测的耗时
        assertTrue(elapsed < 200L, "commit 阻塞了 " + elapsed + " ms");
    }

    /**
     * 统计当前进程里的 git 提交线程数。
     * <p>
     * 用「前后差分」而不是「有没有」：同一个 JVM 里还跑着其它用例自己的提交线程。
     *
     * @return 存活的提交线程数
     */
    private static int commitThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && "jellyfish-git-commit".equals(thread.getName())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("同一文件连续排多次提交时只留最新一条：内容本来就以盘上那份为准")
    void commit_should_coalesce_when_sameFileQueuedTwice() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");
        // 第一笔先让它跑起来，第二、三笔落在同一文件的队列里（两者会被合并成一条）
        git.commit(file, "第一条");
        git.commit(file, "第二条");
        git.commit(file, "第三条");
        git.close(10_000L);

        List<String> subjects = commitSubjects();
        assertEquals(1, subjects.size(), subjects.toString());
        // 合并之后留下的是最后那一条（内容在盘上，早提交没有额外价值）
        assertEquals("第三条", subjects.get(0));
    }

    @Test
    @DisplayName("关闭之后再排提交应被忽略，不会留下跑不完的活")
    void commit_should_beIgnored_afterClose() throws IOException {
        git.close(10_000L);

        git.commit(write("session-1.json", "{\"a\":1}"), "不应出现");

        assertFalse(Files.exists(tempDir.resolve(".git")), "关闭之后不该再发起 git");
    }

    @Test
    @DisplayName("关闭时应把在途提交做完，而不是把它丢掉")
    void close_should_finishPendingCommit() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");
        git.commit(file, "提交");

        git.close(10_000L);

        assertEquals(1, commitSubjects().size(), "关闭时在途的提交不该被丢掉");
    }

    @Test
    @DisplayName("关闭 git 时不应创建仓库")
    void commit_should_doNothing_when_disabled() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");

        GitRepository disabled = new GitRepository(tempDir, false);
        disabled.commit(file, "不应出现");
        disabled.close(1000L);

        assertFalse(Files.exists(tempDir.resolve(".git")));
    }

    @Test
    @DisplayName("提交身份由命令行临时指定，不依赖用户全局 git 配置")
    void commit_should_usePluginCommitterIdentity() throws IOException {
        Path file = write("session-1.json", "{\"a\":1}");

        git.commit(file, "提交");
        git.close(10_000L);

        // 新机器上没有任何 user.name / user.email 也能提交，且不会把用户自己的身份写进本仓库
        assertEquals("jellyfish", runGit("log", "--format=%an").trim());
        assertEquals("jellyfish@localhost", runGit("log", "--format=%ae").trim());
    }

    /**
     * 在仓库目录写入文件。
     *
     * @param name    文件名
     * @param content 内容
     * @return 文件路径
     * @throws IOException 写入失败时抛出
     */
    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 读取仓库的提交主题，最近的排在前面。
     *
     * @return 提交主题列表
     */
    private List<String> commitSubjects() {
        String output = runGit("log", "--format=%s");
        List<String> subjects = new ArrayList<String>();
        for (String line : output.split("\n")) {
            if (!line.trim().isEmpty()) {
                subjects.add(line.trim());
            }
        }
        return subjects;
    }

    /**
     * 执行一条 git 命令并返回输出。
     *
     * @param args 子命令与参数
     * @return 合并输出
     */
    private String runGit(String... args) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(tempDir.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try (InputStream input = process.getInputStream()) {
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
            }
            process.waitFor(30, TimeUnit.SECONDS);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("测试执行 git 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试执行 git 被中断", e);
        }
    }

    /**
     * 探测环境里有没有 git。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            // 必须把输出读完再等结束：不等结束就关流会让进程拿到 SIGPIPE，退出码不反映真实能力
            byte[] chunk = new byte[256];
            try (InputStream input = process.getInputStream()) {
                while (input.read(chunk) >= 0) {
                    continue;
                }
            }
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
