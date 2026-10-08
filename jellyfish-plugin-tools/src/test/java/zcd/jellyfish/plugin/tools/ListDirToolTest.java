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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.args;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invoke;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.invokeResult;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.summaryOf;

/**
 * {@link ListDirTool} 的单元测试。
 * <p>
 * 重点锁住排序口径（目录优先、同类按名字）与「不递归」：这两点决定了模型怎么理解项目结构。
 *
 * @author zcd
 */
@DisplayName("ListDirTool 目录列举")
class ListDirToolTest {

    /** 每个用例独立的临时目录。 */
    @TempDir
    Path tempDir;

    /** 被测工具。 */
    private final ListDirTool tool = new ListDirTool();

    @Test
    @DisplayName("目录应排在同级文件之前，且带 / 后缀")
    void handle_should_listDirectoriesBeforeFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("zebra"));
        write("alpha.txt", "x");
        write("beta.txt", "x");

        String output = invoke(tool, args("path", tempDir.toString()));

        int zebra = output.indexOf("zebra/");
        int alpha = output.indexOf("alpha.txt");
        int beta = output.indexOf("beta.txt");
        assertTrue(zebra > 0, output);
        assertTrue(zebra < alpha, output);
        assertTrue(alpha < beta, output);
    }

    @Test
    @DisplayName("文件应带字节数，目录不带")
    void handle_should_showFileSizeOnly() throws Exception {
        write("a.txt", "abc");

        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("f  a.txt  (3 字节)"), output);
    }

    @Test
    @DisplayName("空目录应明说「空目录」而不是给一个空输出")
    void handle_should_reportEmpty_when_directoryHasNoEntries() throws Exception {
        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("空目录"), output);
    }

    @Test
    @DisplayName("只列一层：子目录里的内容不应出现在输出里")
    void handle_should_notRecurse() throws Exception {
        Path nested = Files.createDirectory(tempDir.resolve("nested"));
        Files.write(nested.resolve("deep.txt"), "x".getBytes(StandardCharsets.UTF_8));

        String output = invoke(tool, args("path", tempDir.toString()));

        assertTrue(output.contains("nested/"), output);
        assertFalse(output.contains("deep.txt"), output);
    }

    @Test
    @DisplayName("目标是文件时应提示改用 read_file")
    void handle_should_fail_when_pathIsFile() throws Exception {
        write("a.txt", "x");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.resolve("a.txt").toString())));

        assertTrue(failure.getMessage().contains("read_file"), failure.getMessage());
    }

    @Test
    @DisplayName("目录不存在应报错")
    void handle_should_fail_when_directoryMissing() {
        Path missing = tempDir.resolve("missing");

        JellyfishException failure = expectFailure(() -> invoke(tool, args("path", missing.toString())));

        assertTrue(failure.getMessage().contains("目录不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("超过 limit 时应分页并提示下一段 offset")
    void handle_should_paginate_when_limitReached() throws Exception {
        write("a.txt", "x");
        write("b.txt", "x");
        write("c.txt", "x");

        String output = invoke(tool, args("path", tempDir.toString(), "limit", 2));

        assertTrue(output.contains("共 3 项"), output);
        assertTrue(output.contains("已截断"), output);
        assertTrue(output.contains("offset=3"), output);
    }

    @Test
    @DisplayName("offset 应跳到指定条目，且只显示 limit 条")
    void handle_should_skipToOffset_when_offsetGiven() throws Exception {
        write("a.txt", "x");
        write("b.txt", "x");
        write("c.txt", "x");

        String output = invoke(tool, args("path", tempDir.toString(), "offset", 2, "limit", 1));

        assertTrue(output.contains("b.txt"), output);
        assertFalse(output.contains("a.txt"), output);
        assertFalse(output.contains("c.txt"), output);
    }

    @Test
    @DisplayName("offset 超出目录项数应报错并给出总数")
    void handle_should_fail_when_offsetBeyondEntries() throws Exception {
        write("a.txt", "x");

        JellyfishException failure = expectFailure(
                () -> invoke(tool, args("path", tempDir.toString(), "offset", 9)));

        assertTrue(failure.getMessage().contains("offset 超出目录项数"), failure.getMessage());
        assertTrue(failure.getMessage().contains("共 1 项"), failure.getMessage());
    }

    @Test
    @DisplayName("摘要给出「显示第几项 / 共几项」，分页时也能看出位置")
    void handle_should_summarizeDisplayedRange() throws Exception {
        write("a.txt", "x");
        write("b.txt", "y");

        ToolCallResult result = invokeResult(tool, args("path", tempDir.toString()));

        assertTrue(summaryOf(result).contains(" · 第 1-2 项，共 2 项"), summaryOf(result));
    }

    @Test
    @DisplayName("空目录的摘要说明是空目录")
    void handle_should_summarizeEmptyDirectory() throws Exception {
        ToolCallResult result = invokeResult(tool, args("path", tempDir.toString()));

        assertTrue(summaryOf(result).endsWith("（空目录）"), summaryOf(result));
    }

    @Test
    @DisplayName("条目数到顶就停，并明说「总数不是全量」——不能把没列全说成「共 N 项」")
    void handle_should_reportTruncation_whenEntryLimitReached() throws Exception {
        // Given：上限 3 项，目录里有 5 项
        ListDirTool limited = new ListDirTool(3);
        for (int i = 1; i <= 5; i++) {
            write("f" + i + ".txt", "x");
        }

        // When
        ToolCallResult result = invokeResult(limited, args("path", tempDir.toString()));

        // Then：给出拿到的那 3 项，并说清上面的总数不是全量
        String output = String.valueOf(result.getOutput());
        assertTrue(output.contains("共 3 项"), output);
        assertTrue(output.contains("只统计了前 3 项（目录可能还有更多）"), output);
        assertTrue(summaryOf(result).contains("未列全"), summaryOf(result));
    }

    @Test
    @DisplayName("条目数没到顶就不该报截断（正常目录一个字都不变）")
    void handle_should_notReportTruncation_whenUnderLimit() throws Exception {
        // Given：上限 3 项，目录里 2 项
        ListDirTool limited = new ListDirTool(3);
        write("a.txt", "x");
        write("b.txt", "x");

        // When
        String output = invoke(limited, args("path", tempDir.toString()));

        // Then
        assertFalse(output.contains("只统计了前"), output);
        assertTrue(output.contains("（共 2 项"), output);
    }

    @Test
    @DisplayName("已经取消的令牌：一个条目都不列，且明说被取消（不能说成「空目录」）")
    void handle_should_reportCancelled_whenTokenAlreadyCancelled() throws Exception {
        // Given：目录里其实有东西，但调用前用户就按了 Esc
        write("a.txt", "x");
        ToolTestSupport.ManualToken token = new ToolTestSupport.ManualToken();
        token.cancel();

        // When
        String output = ToolTestSupport.invoke(tool, args("path", tempDir.toString()), token);

        // Then：「空目录」是个事实断言，而这次我们根本没看——错误地这么说会让模型以为目录是空的
        assertTrue(output.contains("已被取消"), output);
        assertFalse(output.contains("是空目录"), output);
    }

    /**
     * 在临时目录写入文件。
     *
     * @param name    文件名
     * @param content 内容
     */
    private void write(String name, String content) {
        try {
            Files.write(tempDir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("测试前置写入失败", e);
        }
    }
}
