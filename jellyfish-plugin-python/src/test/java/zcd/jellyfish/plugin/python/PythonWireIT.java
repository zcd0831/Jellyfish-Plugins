package zcd.jellyfish.plugin.python;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分帧约定（{@code script_wire.py}）的端到端用例：只用真解释器跑它，不起网关。
 * <p>
 * <b>为什么要专门为它写一条：</b>{@code feed} 的行为（丢弃一行、但必须留痕）在 Java 侧无从验证，
 * 而它恰恰是「请求悬到超时却哪边都没有线索」这类悬案的第一现场。跑整个网关来验它太重、
 * 也太容易把别的失效混进来，因此这里把 wire 单独拎出来喂给它几行脏数据。
 * <p>
 * 机器上没装解释器时整类跳过（{@code assumeTrue}）：环境缺失与代码有 bug 是两件事。
 *
 * @author zcd
 */
@DisplayName("python 分帧约定")
class PythonWireIT {

    /** 子进程等待上限秒数。 */
    private static final long TIMEOUT_SECONDS = 30L;

    /** 解释器与 wire 的存放目录。 */
    @TempDir
    Path directory;

    /**
     * 跳过没有解释器的环境。
     */
    @BeforeEach
    void requirePython() {
        Assumptions.assumeTrue(interpreterAvailable(), "本机没有可用的 python3，跳过");
    }

    @Test
    @DisplayName("无法解析的协议行应被计数并在 stderr 留痕，而不是静默丢弃")
    void feed_should_countAndReportDroppedLines() throws Exception {
        // 帧用 utf-8 编码之后再喂：wire 收的是字节，而中文字面量在 python 的 bytes 字面量里写不出来
        Run run = runDriver("chunk = '{\"a\":1}\\n这不是 JSON\\n{\"b\":2}\\n'.encode('utf-8')\n"
                + "buffer, frames, dropped = wire.feed(b'', chunk)\n"
                + "sys.stdout.write('%d %d' % (len(frames), dropped))\n");

        assertEquals(0, run.exitCode, run.stderr);
        assertEquals("2 1", run.stdout.trim(), "应当切出两帧、记下一次丢弃");
        assertTrue(run.stderr.contains("丢弃无法解析的协议行"), run.stderr);
    }

    @Test
    @DisplayName("丢弃很凶时应逐条限流，并补一条总数")
    void feed_should_limitIndividualAlerts() throws Exception {
        Run run = runDriver("chunk = ''.join('垃圾行%d\\n' % index for index in range(7)).encode('utf-8')\n"
                + "buffer, frames, dropped = wire.feed(b'', chunk)\n"
                + "sys.stdout.write('%d' % dropped)\n");

        assertEquals(0, run.exitCode, run.stderr);
        assertEquals("7", run.stdout.trim());
        assertEquals(5, countOf(run.stderr, "丢弃无法解析的协议行"),
                "逐条告警应当被限流：" + run.stderr);
        assertTrue(run.stderr.contains("本次共丢弃 7 行"), run.stderr);
    }

    /**
     * 跑一段驱动脚本。
     * <p>
     * 用它而不是 {@code -c}：多行脚本写成文件更容易看清，也不必和 Java 字符串转义纠缠。
     *
     * @param body 驱动脚本体（已 import {@code sys} 与 {@code wire}）
     * @return 运行结果
     * @throws IOException          写文件或起进程失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private Run runDriver(String body) throws IOException, InterruptedException {
        copyWire();
        Path driver = directory.resolve("wire_driver.py");
        Files.write(driver, ("import sys\n"
                + "sys.path.insert(0, sys.argv[1])\n"
                + "import script_wire as wire\n"
                + body).getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(interpreter(), driver.toString(), directory.toString())
                .start();
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        return new Run(finished ? process.exitValue() : -1, stdout, stderr);
    }

    /**
     * 把分帧约定从测试类路径拷到临时目录。
     *
     * @throws IOException 拷贝失败时抛出
     */
    private void copyWire() throws IOException {
        try (InputStream source = PythonWireIT.class.getResourceAsStream("/script/script_wire.py")) {
            if (source == null) {
                throw new IOException("测试类路径上找不到 script/script_wire.py");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = source.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            Files.write(directory.resolve("script_wire.py"), buffer.toByteArray());
        }
    }

    /**
     * 读空一个流。
     *
     * @param stream 流
     * @return 文本
     * @throws IOException 读取失败时抛出
     */
    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try (InputStream input = stream) {
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 数一段文本里出现某个片段的次数。
     *
     * @param text   文本
     * @param needle 片段
     * @return 次数
     */
    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * 判断本机是否有可用的解释器。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean interpreterAvailable() {
        try {
            Process process = new ProcessBuilder(interpreter(), "--version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            process.destroyForcibly();
            return finished && code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 取解释器路径，允许用系统属性覆盖（与端到端用例同一口径）。
     *
     * @return 解释器路径或命令名
     */
    private static String interpreter() {
        return System.getProperty("jellyfish.test.python", PythonBridgePlugin.DEFAULT_INTERPRETER);
    }

    /**
     * 一次驱动脚本运行的结果。
     */
    private static final class Run {

        /** 退出码。 */
        private final int exitCode;

        /** 标准输出。 */
        private final String stdout;

        /** 标准错误。 */
        private final String stderr;

        /**
         * 构造结果。
         *
         * @param exitCode 退出码
         * @param stdout   标准输出
         * @param stderr   标准错误
         */
        private Run(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
