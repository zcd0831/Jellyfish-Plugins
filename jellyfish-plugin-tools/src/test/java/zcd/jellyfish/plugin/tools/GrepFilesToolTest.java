package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invokeResult;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.summaryOf;

/**
 * {@link GrepFilesTool} 的单元测试。
 * <p>
 * 重点锁住四件事：输出格式（文件:行号:内容）、跳过名单（构建产物不该污染结果）、
 * 二进制文件不参与匹配、以及达到上限时必须有截断提示——否则模型会把「只显示了 100 条」
 * 当成「总共就 100 条」。
 *
 * @author zcd
 */
@DisplayName("GrepFilesTool 文本搜索")
class GrepFilesToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /**
     * 探针文件的行数：要盖过行循环里那一次取消检查
     * （见 {@code GrepFilesTool.CANCEL_CHECK_LINES}）。
     */
    private static final int CANCEL_PROBE_LINES = 9000;

    /** 被测工具。 */
    private final GrepFilesTool tool = new GrepFilesTool();

    @Test
    @DisplayName("应按「文件:行号:内容」返回匹配行")
    void handle_should_returnFileLineAndText_when_matched() throws Exception {
        write("a.txt", "first\nTODO: 修一下\nlast");

        String output = invoke(tool, args("pattern", "TODO", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:2:TODO: 修一下"), output);
    }

    @Test
    @DisplayName("没有匹配时应明说，而不是返回空串")
    void handle_should_reportNoMatch_when_nothingMatched() throws Exception {
        write("a.txt", "hello");

        String output = invoke(tool, args("pattern", "zzz", "path", tempDir.toString()));

        assertEquals("没有匹配到任何内容。", output);
    }

    @Test
    @DisplayName("构建产物与版本控制目录应被跳过")
    void handle_should_skipBuildAndVcsDirectories() throws Exception {
        write("src/a.txt", "hit");
        write("target/b.txt", "hit");
        write("node_modules/c.txt", "hit");
        write(".git/d.txt", "hit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertFalse(output.contains("b.txt"), output);
        assertFalse(output.contains("c.txt"), output);
        assertFalse(output.contains("d.txt"), output);
    }

    @Test
    @DisplayName("二进制文件不应参与匹配")
    void handle_should_skipBinaryFiles() throws Exception {
        Path binary = tempDir.resolve("blob.bin");
        try (OutputStream output = Files.newOutputStream(binary)) {
            output.write(new byte[]{'h', 'i', 't', 0, 'h', 'i', 't'});
        }

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString()));

        assertEquals("没有匹配到任何内容。", output);
    }

    @Test
    @DisplayName("达到 max_results 时应截断并提示")
    void handle_should_truncate_when_maxResultsReached() throws Exception {
        write("a.txt", "hit\nhit\nhit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_results", 2));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertTrue(output.contains("已截断"), output);
        assertFalse(output.contains("a.txt:3:hit"), output);
    }

    @Test
    @DisplayName("匹配数刚好等于上限时不应报截断")
    void handle_should_notTruncate_when_matchesEqualLimit() throws Exception {
        write("a.txt", "hit\nhit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_results", 2));

        assertFalse(output.contains("已截断"), output);
    }

    @Test
    @DisplayName("起点是单个文件时只搜该文件")
    void handle_should_searchSingleFile_when_pathIsFile() throws Exception {
        Path file = write("a.txt", "hit");
        write("b.txt", "hit");

        String output = invoke(tool, args("pattern", "hit", "path", file.toString()));

        assertTrue(output.contains("a.txt:1:hit"), output);
        assertFalse(output.contains("b.txt"), output);
    }

    @Test
    @DisplayName("pattern 是正则而非字面量")
    void handle_should_treatPatternAsRegex() throws Exception {
        write("a.txt", "count = 42");

        String output = invoke(tool, args("pattern", "=\\s*\\d+", "path", tempDir.toString()));

        assertTrue(output.contains("a.txt:1:count = 42"), output);
    }

    @Test
    @DisplayName("正则非法应报错")
    void handle_should_fail_when_patternInvalid() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "[unclosed", "path", tempDir.toString())));

        assertTrue(failure.getMessage().contains("正则表达式非法"), failure.getMessage());
    }

    @Test
    @DisplayName("max_results 小于 1 属于调用错误")
    void handle_should_fail_when_maxResultsBelowOne() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "x", "path", tempDir.toString(), "max_results", 0)));

        assertTrue(failure.getMessage().contains("max_results 必须大于 0"), failure.getMessage());
    }

    @Test
    @DisplayName("路径不存在应报错")
    void handle_should_fail_when_pathMissing() {
        Path missing = tempDir.resolve("missing");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "x", "path", missing.toString())));

        assertTrue(failure.getMessage().contains("路径不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("缺少 pattern 应报错")
    void handle_should_fail_when_patternMissing() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.toString())));

        assertEquals("缺少必需参数: pattern", failure.getMessage());
    }

    @Test
    @DisplayName("超长匹配行应按 max_line_chars 截尾")
    void handle_should_truncateLongLine_when_maxLineCharsGiven() throws Exception {
        StringBuilder line = new StringBuilder("hit-");
        for (int i = 0; i < 200; i++) {
            line.append('x');
        }
        write("a.txt", line.toString());

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_line_chars", 8));

        assertTrue(output.contains("…"), output);
        assertFalse(output.contains("xxxxxxxxxxxxxx"), output);
    }

    @Test
    @DisplayName("达到 max_bytes 时应截断并至少保留第一处匹配")
    void handle_should_truncate_when_maxBytesReached() throws Exception {
        write("a.txt", "hit\nhit\nhit");

        String output = invoke(tool, args("pattern", "hit", "path", tempDir.toString(), "max_bytes", 1));

        assertTrue(output.contains(":1:hit"), output);
        assertTrue(output.contains("已截断"), output);
        assertFalse(output.contains(":3:hit"), output);
    }

    @Test
    @DisplayName("max_line_chars 小于 1 属于调用错误")
    void handle_should_fail_when_maxLineCharsBelowOne() {
        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("pattern", "x", "path", tempDir.toString(), "max_line_chars", 0)));

        assertTrue(failure.getMessage().contains("max_line_chars 必须大于 0"), failure.getMessage());
    }

    @Test
    @DisplayName("摘要给出「在哪儿、搜什么、命中多少」，长说明不进摘要")
    void handle_should_summarizeMatches() throws Exception {
        write("a.txt", "hello\nworld\n");

        ToolCallResult result = invokeResult(tool, args("pattern", "hello", "path", tempDir.toString()));

        String summary = summaryOf(result);
        assertTrue(summary.startsWith("hello @ "), summary);
        assertTrue(summary.endsWith(" · 1 处"), summary);
    }

    @Test
    @DisplayName("没有匹配时摘要也要说清是「无匹配」，而不是留空")
    void handle_should_summarizeNoMatch() throws Exception {
        write("a.txt", "hello\n");

        ToolCallResult result = invokeResult(tool, args("pattern", "nope", "path", tempDir.toString()));

        assertTrue(summaryOf(result).endsWith(" · 无匹配"), summaryOf(result));
    }

    @Test
    @DisplayName("访问文件数到顶就停，且明说「未扫完整棵树」——不能把没扫完说成没有匹配")
    void handle_should_stopAtFileLimit_whenTreeIsTooBig() throws Exception {
        // Given：上限 2 个文件，树里有 3 个（都命中），因此一定扫不完
        GrepFilesTool limited = new GrepFilesTool(2);
        write("a.txt", "hit");
        write("b.txt", "hit");
        write("c.txt", "hit");

        // When
        ToolCallResult result = invokeResult(limited, args("pattern", "hit", "path", tempDir.toString()));

        // Then：结果照常给出，但必须说清是「没扫完」而不是「就这么些」
        String output = String.valueOf(result.getOutput());
        assertTrue(output.contains("未扫完整棵树"), output);
        assertFalse(output.contains("没有匹配到任何内容"), output);
        assertTrue(summaryOf(result).contains("未扫完"), summaryOf(result));
    }

    @Test
    @DisplayName("一个文件都没扫完时，也不能说「没有匹配到任何内容」")
    void handle_should_notClaimNoMatch_whenFileLimitHitBeforeAnyMatch() throws Exception {
        // Given：上限 1，且第一个文件不命中——「没扫完」与「没有」必须分开说
        GrepFilesTool limited = new GrepFilesTool(1);
        write("a.txt", "nothing here");
        write("b.txt", "hit");

        // When
        String output = invoke(limited, args("pattern", "hit", "path", tempDir.toString()));

        // Then
        assertFalse(output.contains("没有匹配到任何内容"), output);
        assertTrue(output.contains("未扫完整棵树"), output);
    }

    @Test
    @DisplayName("文件数上限之内搜完了就不该报「未扫完」")
    void handle_should_notReportFileLimit_whenTreeFullyScanned() throws Exception {
        // Given：上限 3，树里只有 2 个文件
        GrepFilesTool limited = new GrepFilesTool(3);
        write("a.txt", "hit");
        write("b.txt", "hit");

        // When
        String output = invoke(limited, args("pattern", "hit", "path", tempDir.toString()));

        // Then
        assertFalse(output.contains("未扫完整棵树"), output);
        assertFalse(output.contains("已截断"), output);
    }

    @Test
    @DisplayName("已经取消的令牌：不搜，并说清「不代表真的没有」")
    void handle_should_stopImmediately_whenAlreadyCancelled() throws Exception {
        // Given：调用前用户就按了 Esc
        write("a.txt", "hit");
        ToolTestSupport.ManualToken token = new ToolTestSupport.ManualToken();
        token.cancel();

        // When
        String output = ToolTestSupport.invoke(tool, args("pattern", "hit", "path", tempDir.toString()), token);

        // Then：绝不能输出「没有匹配到任何内容」——那句话会让模型以为搜索真的做完了
        assertTrue(output.contains("已取消"), output);
        assertFalse(output.contains("没有匹配到任何内容"), output);
    }

    @Test
    @DisplayName("搜到一半被取消：结果带回去，但明说「可能还有遗漏」")
    void handle_should_keepPartialMatches_whenCancelledMidway() throws Exception {
        // Given：一个大到足以走到行循环里的取消检查的文件（每 8192 行查一次），
        // 上限都调大，确保截断不会先发生；令牌在第 2 次被问到时开始取消
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < CANCEL_PROBE_LINES; i++) {
            content.append("hit\n");
        }
        write("big.txt", content.toString());
        Path big = tempDir.resolve("big.txt");
        CancellingToken token = new CancellingToken(2);

        // When：直接指向那个文件（单文件搜索的检查点顺序是「先文件边界、再每 8192 行一次」）
        ToolCallResult result = invokeResult(tool, args("pattern", "hit", "path", big.toString(),
                "max_results", CANCEL_PROBE_LINES, "max_bytes", 8 * 1024 * 1024, "max_line_chars", 200), token);

        // Then：已找到的那些照常回灌（不白等一场），同时说清是被中止的
        String output = String.valueOf(result.getOutput());
        assertTrue(output.contains("big.txt:1:hit"), output);
        assertTrue(output.contains("已取消"), output);
        assertTrue(output.contains("可能还有遗漏"), output);
    }

    /**
     * 问到第 N 次时开始返回「已取消」的令牌。
     * <p>
     * <b>为什么要按调用次数触发</b>：取消本身是异步的，而用例需要「扫到一半」这个确定时刻。
     * 工具的检查点顺序是固定的（先文件边界、再每 8192 行一次），因此「第几次被问到」就是一个
     * 可比时间的刻度。代价是这条用例依赖检查点的位置——但那正是它要守的东西：
     * 行循环里若不再检查取消，它会红。
     *
     * @author zcd
     */
    private static final class CancellingToken implements CancellationToken {

        /** 第几次被问到时开始取消。 */
        private final int cancelAtQuery;

        /** 已被问过几次。 */
        private final AtomicInteger queries = new AtomicInteger();

        /**
         * 构造令牌。
         *
         * @param cancelAtQuery 第几次被问到时开始取消
         */
        CancellingToken(int cancelAtQuery) {
            this.cancelAtQuery = cancelAtQuery;
        }

        @Override
        public boolean isCancelled() {
            return queries.incrementAndGet() >= cancelAtQuery;
        }

        @Override
        public void onCancel(Runnable callback) {
            // 本用例只用「拉模型」这一半：工具在循环里自查，不需要推送
        }
    }

    /**
     * 在临时目录写入文件，自动创建父目录。
     *
     * @param relativePath 相对路径
     * @param content      内容
     * @return 文件路径
     */
    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
