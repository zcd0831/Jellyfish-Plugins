package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpStdioTransport} 的两条「不外流」判据：交给子进程的环境变量，以及写进日志的参数。
 * <p>
 * 这两件事都刻意做成纯函数来测：真起一个进程去读它的环境，结论会取决于跑测试的机器上刚好设了什么。
 *
 * @author zcd
 */
@DisplayName("MCP 子进程的环境与日志")
class McpStdioTransportTest {

    @Test
    @DisplayName("单行读取：\\n 与 \\r\\n 都算换行，且不吞掉下一行的内容")
    void readLineWithinLimit_should_handleBothLineEndings() throws Exception {
        BufferedReader reader = reader("first\nsecond\r\nthird\rfourth");

        assertEquals("first", McpStdioTransport.readLineWithinLimit(reader, "s"));
        assertEquals("second", McpStdioTransport.readLineWithinLimit(reader, "s"));
        // 单个 \r 也算换行：后面那一行不能被吃掉
        assertEquals("third", McpStdioTransport.readLineWithinLimit(reader, "s"));
        // 最后一行没有换行符，仍要返回（子进程可能没写完就退出）
        assertEquals("fourth", McpStdioTransport.readLineWithinLimit(reader, "s"));
        assertNull(McpStdioTransport.readLineWithinLimit(reader, "s"));
    }

    @Test
    @DisplayName("单行读取：空行是合法的一行，不能与「流结束」混为一谈")
    void readLineWithinLimit_should_keepEmptyLineDistinctFromEndOfStream() throws Exception {
        BufferedReader reader = reader("\n");

        assertEquals("", McpStdioTransport.readLineWithinLimit(reader, "s"));
        assertNull(McpStdioTransport.readLineWithinLimit(reader, "s"));
    }

    @Test
    @DisplayName("超过上限的一行应报错而不是把它读进内存")
    void readLineWithinLimit_should_fail_when_lineExceedsLimit() {
        // Given：一行比上限还长、且不带换行——不受信的 server 一句 dump 就能这样
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4 * 1024 * 1024 + 1; i++) {
            huge.append('x');
        }
        BufferedReader reader = reader(huge.toString());

        // When / Then
        IOException failure = assertThrows(IOException.class,
                () -> McpStdioTransport.readLineWithinLimit(reader, "fs"));
        assertTrue(failure.getMessage().contains("超过上限"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fs"), failure.getMessage());
    }

    /**
     * 构造一个按给定文本读取的缓冲读取器。
     *
     * @param text 文本
     * @return 读取器
     */
    private static BufferedReader reader(String text) {
        return new BufferedReader(new StringReader(text));
    }

    @Test
    @DisplayName("父进程里的凭据不应跟过去：宿主 JVM 的 provider 密钥与 MCP server 无关")
    void resolveEnvironment_should_dropCredentials() {
        // Given：真实场景里这些变量就躺在 JVM 的环境里
        Map<String, String> parent = new LinkedHashMap<String, String>();
        parent.put("PATH", "/usr/bin:/bin");
        parent.put("ANTHROPIC_API_KEY", "sk-ant-secret");
        parent.put("OPENAI_API_KEY", "sk-openai-secret");
        parent.put("AWS_SECRET_ACCESS_KEY", "aws-secret");
        parent.put("GITHUB_PAT", "ghp_secret");
        parent.put("HTTP_PROXY", "http://user:pass@proxy:8080");

        // When
        Map<String, String> resolved = McpStdioTransport.resolveEnvironment(parent, null);

        // Then：只留下运行一个进程真正需要的那几个
        assertEquals("/usr/bin:/bin", resolved.get("PATH"));
        assertFalse(resolved.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(resolved.containsKey("OPENAI_API_KEY"));
        assertFalse(resolved.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(resolved.containsKey("GITHUB_PAT"));
        assertFalse(resolved.containsKey("HTTP_PROXY"));
    }

    @Test
    @DisplayName("运行必需的路径与会话变量应照常继承，区域设置按前缀给")
    void resolveEnvironment_should_keepRuntimeEssentials() {
        // Given
        Map<String, String> parent = new LinkedHashMap<String, String>();
        parent.put("PATH", "/usr/bin");
        parent.put("HOME", "/Users/someone");
        parent.put("TMPDIR", "/tmp");
        parent.put("LANG", "zh_CN.UTF-8");
        parent.put("LC_ALL", "zh_CN.UTF-8");
        parent.put("TERM_PROGRAM", "iTerm"); // 不在白名单：终端细节对 server 没有意义

        // When
        Map<String, String> resolved = McpStdioTransport.resolveEnvironment(parent, null);

        // Then
        assertEquals("/Users/someone", resolved.get("HOME"));
        assertEquals("/tmp", resolved.get("TMPDIR"));
        assertEquals("zh_CN.UTF-8", resolved.get("LC_ALL"));
        assertFalse(resolved.containsKey("TERM_PROGRAM"));
    }

    @Test
    @DisplayName("配置里显式写的变量一律生效——显式即允许，包括密钥与非白名单名字")
    void resolveEnvironment_should_applyOverrides() {
        // Given
        Map<String, String> parent = new LinkedHashMap<String, String>();
        parent.put("PATH", "/usr/bin");
        parent.put("ANTHROPIC_API_KEY", "sk-ant-secret");
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        overrides.put("ANTHROPIC_API_KEY", "sk-ant-explicitly-given");
        overrides.put("MY_SERVER_TOKEN", "t-1");
        overrides.put("PATH", "/opt/bin");

        // When
        Map<String, String> resolved = McpStdioTransport.resolveEnvironment(parent, overrides);

        // Then
        assertEquals("sk-ant-explicitly-given", resolved.get("ANTHROPIC_API_KEY"));
        assertEquals("t-1", resolved.get("MY_SERVER_TOKEN"));
        assertEquals("/opt/bin", resolved.get("PATH"));
    }

    @Test
    @DisplayName("空环境与空配置都应得到空结果，而不是抛错")
    void resolveEnvironment_should_tolerateNulls() {
        assertTrue(McpStdioTransport.resolveEnvironment(null, null).isEmpty());
    }

    @Test
    @DisplayName("--api-key=xxx 这类等号形式应按旗标名遮蔽值")
    void maskArgs_should_maskEqualsForm() {
        // When
        List<String> masked = McpStdioTransport.maskArgs(Arrays.asList(
                "-y", "@modelcontextprotocol/server-x", "--api-key=sk-ant-secret",
                "--access-token", "--authorization=Bearer abc"));

        // Then
        assertEquals("-y", masked.get(0));
        assertEquals("@modelcontextprotocol/server-x", masked.get(1));
        assertEquals("--api-key=***", masked.get(2));
        assertEquals("--access-token", masked.get(3));
        assertEquals("--authorization=***", masked.get(4));
    }

    @Test
    @DisplayName("--token sk-xxx 这类分离形式应按上一个旗标名遮蔽下一个参数")
    void maskArgs_should_maskSeparatedForm() {
        // When
        List<String> masked = McpStdioTransport.maskArgs(Arrays.asList(
                "--token", "sk-secret", "--path", "/tmp/root", "--debug"));

        // Then
        assertEquals("--token", masked.get(0));
        assertEquals("***", masked.get(1));
        // --path 不在敏感词表里：filesystem 类 server 的路径恰恰要留在日志里给人看
        assertEquals("--path", masked.get(2));
        assertEquals("/tmp/root", masked.get(3));
        assertEquals("--debug", masked.get(4));
    }

    @Test
    @DisplayName("分离形式只遮紧跟其后的那一个参数")
    void maskArgs_should_maskOnlyTheValueThatFollows() {
        // When
        List<String> masked = McpStdioTransport.maskArgs(Arrays.asList(
                "--token", "sk-secret", "positional", "another"));

        // Then
        assertEquals("***", masked.get(1));
        assertEquals("positional", masked.get(2));
        assertEquals("another", masked.get(3));
    }

    @Test
    @DisplayName("普通参数应原样保留：这条日志是排查「server 起不来」的第一眼线索")
    void maskArgs_should_keepOrdinaryArgs() {
        // When
        List<String> masked = McpStdioTransport.maskArgs(Arrays.asList("npx", "-y", "some-package"));

        // Then
        assertEquals(Collections.singletonList("npx"), Collections.singletonList(masked.get(0)));
        assertEquals("-y", masked.get(1));
        assertEquals("some-package", masked.get(2));
    }
}
