package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 的 stdio 传输：一个子进程 + 换行分隔的 JSON-RPC 分帧。
 * <p>
 * <b>分帧就是「一行一条消息」</b>：协议明确要求消息内部不得出现换行（序列化器写单行），
 * 因此按行读就是完整的分帧实现，不需要长度头，也不需要自己维护缓冲区。
 * <p>
 * <b>stderr 必须单独排空</b>：MCP server 的日志走 stderr，把它并进 stdout 会让 JSON 流里混进
 * 「连不上数据库」这种人类文本，表现为「偶尔收到一帧解析不了」。因此这里不合并两条流，
 * 而是另起一条守护线程持续读 stderr——不读的话，日志写满管道缓冲区会把 server 卡死。
 * <p>
 * <b>关停是四步而不是一步</b>：关闭 stdin（告诉 server 可以收尾）→ 杀直接子进程 →
 * 尽力杀进程树 → 必要强杀。少了「杀进程树」这一步，{@code npx} 拉起的 node 会继续跑，
 * 而现场表现是「插件已经停了，那个 server 还占着端口」。
 * <p>
 * <b>环境与日志都是收敛的</b>：子进程只拿到白名单里的环境变量（宿主 JVM 里的各种 API key
 * 不会跟过去），启动日志里的参数值按旗标名遮蔽。详见 {@link #ENV_INHERIT} 与 {@link #maskArgs}。
 * <p>
 * 线程安全：{@link #send(String)} 同步写，{@link #readLine()} 由单条读线程调用。
 *
 * @author zcd
 */
final class McpStdioTransport implements McpTransport {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpStdioTransport.class);

    /** 优雅终止的宽限时间（毫秒）。 */
    private static final long GRACE_MILLIS = 2000L;

    /**
     * 允许从父进程继承的环境变量名（精确匹配）。
     * <p>
     * <b>为什么是白名单，而不是「继承 + 剔除敏感变量」</b>：shell 插件走的是后者，因为那里的命令是
     * 模型现场拼的，它需要看到一个像样的 shell 环境；而这里的进程是用户在配置里写死的一个 server，
     * 它需要什么是可以穷举的。而「继承」意味着 JVM 进程里的一切都会跟过去——包括
     * {@code ANTHROPIC_API_KEY}、{@code OPENAI_API_KEY} 这些本插件与之毫无关系的凭据，
     * 而对面是个不受信的第三方进程。方向反过来之后，漏一个变量只是「少给了一点便利」，
     * 而不是「多给了一份凭据」。
     * <p>
     * 真要传别的东西（含密钥）走配置里的 {@code env} 段——<b>显式即允许</b>，
     * 与脚本插件的 {@code ${ENV}} 插值同一条口径。
     */
    private static final List<String> ENV_INHERIT = Collections.unmodifiableList(Arrays.asList(
            "PATH", "HOME", "LANG", "TMPDIR", "TEMP", "TMP", "USER", "LOGNAME", "SHELL",
            "SystemRoot", "PATHEXT", "ComSpec", "windir"));

    /** 允许继承的环境变量名前缀（区域设置有多套键，逐个列会漏）。 */
    private static final String ENV_INHERIT_PREFIX = "LC_";

    /**
     * 参数名里出现这些词就认为它是「带值的凭据旗标」，值要遮蔽。
     * <p>
     * 刻意不放 {@code PAT}：它会命中 {@code --path}（文件系统类 server 的常用参数），
     * 而那个值恰恰是要留在日志里给人看的东西。
     */
    private static final String[] SENSITIVE_FLAG_WORDS = {
        "KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSPHRASE", "AUTH",
    };

    /** 遮蔽后的占位。 */
    static final String MASKED = "***";

    /** 子进程。 */
    private final Process process;

    /** 标准输入写入器。 */
    private final BufferedWriter writer;

    /** 标准输出读取器。 */
    private final BufferedReader reader;

    /** stderr 排空线程。 */
    private final Thread stderrDrainer;

    /** 服务标识，仅用于日志。 */
    private final String serverId;

    /**
     * 构造传输。
     *
     * @param serverId      服务标识
     * @param process       子进程
     * @param writer        标准输入写入器
     * @param reader        标准输出读取器
     * @param stderrDrainer stderr 排空线程
     */
    private McpStdioTransport(String serverId, Process process, BufferedWriter writer,
                             BufferedReader reader, Thread stderrDrainer) {
        this.serverId = serverId;
        this.process = process;
        this.writer = writer;
        this.reader = reader;
        this.stderrDrainer = stderrDrainer;
    }

    /**
     * 启动一个 MCP server 子进程。
     * <p>
     * <b>环境是「白名单继承 + 配置叠加」</b>：父进程里只有 {@link #ENV_INHERIT} 列出的名字
     * （以及 {@link #ENV_INHERIT_PREFIX} 开头的区域设置）会跟过去，其余一律不带——JVM 的环境里
     * 有各 provider 的 API key，而对面是个不受信的第三方进程。要传别的东西就在配置的
     * {@code env} 段里显式写：<b>显式即允许</b>。完整理由见 {@link #ENV_INHERIT}。
     *
     * @param config 服务配置，不可为 {@code null}
     * @return 传输
     * @throws IOException 子进程启动失败时抛出
     */
    static McpStdioTransport start(McpServerConfig config) throws IOException {
        List<String> commandLine = new ArrayList<String>();
        commandLine.add(config.command());
        commandLine.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        // 刻意不合并两条流：stderr 里是服务端日志，混进 stdout 会破坏分帧
        builder.redirectErrorStream(false);
        applyEnvironment(builder, config);
        Process process = builder.start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread drainer = drainStderr(config.id(), process);
        LOG.info("MCP server 进程已启动: id={} command={} args={}", config.id(), config.command(),
                maskArgs(config.args()));
        return new McpStdioTransport(config.id(), process, writer, reader, drainer);
    }

    /**
     * 把环境变量交给子进程。
     *
     * @param builder 进程构建器，不可为 {@code null}
     * @param config  服务配置，不可为 {@code null}
     */
    private static void applyEnvironment(ProcessBuilder builder, McpServerConfig config) {
        Map<String, String> environment = builder.environment();
        // 先清空：ProcessBuilder 初始拿到的就是父进程环境的一份拷贝，「只叠加不清理」等于全量继承
        environment.clear();
        environment.putAll(resolveEnvironment(System.getenv(), config.env()));
    }

    /**
     * 算出交给子进程的环境变量：白名单继承 + 配置叠加。
     * <p>
     * 逻辑独立成一个纯函数，是为了让「哪些变量过不去」这件事可以被断言——靠真起一个进程去读它的
     * 环境来验证，会依赖跑测试的那台机器上刚好设了什么变量。
     *
     * @param parent    父进程环境，可为 {@code null}
     * @param overrides 配置里显式写的变量，可为 {@code null}
     * @return 子进程环境，保证非 {@code null}
     */
    static Map<String, String> resolveEnvironment(Map<String, String> parent, Map<String, String> overrides) {
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        if (parent != null) {
            for (Map.Entry<String, String> entry : parent.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && isInheritable(entry.getKey())) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (overrides != null) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return resolved;
    }

    /**
     * 判断一个环境变量名是否可以继承。
     *
     * @param name 变量名，不可为 {@code null}
     * @return 可以继承返回 {@code true}
     */
    private static boolean isInheritable(String name) {
        return ENV_INHERIT.contains(name) || name.startsWith(ENV_INHERIT_PREFIX);
    }

    /**
     * 把命令行参数里的疑似凭据值替换成 {@link #MASKED}，用于日志。
     * <p>
     * <b>为什么这条日志必须留下、但不能原样留</b>：它记的是「起了哪个 server、怎么起的」——
     * 排查「server 起不来」时第一眼要看的就是它。而参数里常见 {@code --token sk-xxx}、
     * {@code --api-key=...}（用户按 server 的文档抄的），原样打印等于把凭据写进日志文件。
     * <p>
     * <b>它只认旗标名，不认值的样子</b>：靠「值看起来像密钥」来猜会既漏又误伤，
     * 而旗标名是配置里写定的。因此 {@code --header "Authorization: Bearer x"} 这种
     * 「密钥藏在普通旗标的值里」的情形认不出来——那是用户的写法问题，不是这里能兜住的。
     *
     * @param args 原始参数，不可为 {@code null}
     * @return 遮蔽后的参数列表，保证非 {@code null}
     */
    static List<String> maskArgs(List<String> args) {
        List<String> masked = new ArrayList<String>(args.size());
        boolean previousFlagIsSensitive = false;
        for (String arg : args) {
            if (arg == null) {
                masked.add(null);
                previousFlagIsSensitive = false;
                continue;
            }
            int equals = arg.indexOf('=');
            if (arg.startsWith("-") && equals > 0) {
                String flag = arg.substring(0, equals);
                boolean sensitive = isSensitiveFlag(flag);
                masked.add(sensitive ? flag + "=" + MASKED : arg);
                previousFlagIsSensitive = false;
                continue;
            }
            if (arg.startsWith("-")) {
                masked.add(arg);
                previousFlagIsSensitive = isSensitiveFlag(arg);
                continue;
            }
            // 分离形式（--token sk-xxx）：值本身看不出是什么，靠上一个旗标名判定
            masked.add(previousFlagIsSensitive ? MASKED : arg);
            previousFlagIsSensitive = false;
        }
        return masked;
    }

    /**
     * 判断一个旗标名是否表示「它的值是一份凭据」。
     *
     * @param flag 旗标名，形如 {@code --api-key}，不可为 {@code null}
     * @return 是凭据旗标返回 {@code true}
     */
    private static boolean isSensitiveFlag(String flag) {
        String upper = flag.toUpperCase(Locale.ROOT);
        for (String word : SENSITIVE_FLAG_WORDS) {
            if (upper.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写一条消息。
     *
     * @param json 单行 JSON 文本，不可为 {@code null}
     * @throws IOException 写入失败（通常是子进程已退出）时抛出
     */
    @Override
    public void send(String json) throws IOException {
        synchronized (writer) {
            writer.write(json);
            writer.write('\n');
            writer.flush();
        }
    }

    /**
     * 阻塞读取一条消息。
     *
     * @return 一条消息文本；流结束（子进程退出）时返回 {@code null}
     * @throws IOException 读取失败时抛出
     */
    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    /**
     * 判断子进程是否仍在运行。
     *
     * @return 仍在运行返回 {@code true}
     */
    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            // 先关 stdin：多数 server 看到 EOF 会自行收尾，这是代价最小的一种结束方式
            synchronized (writer) {
                writer.close();
            }
        } catch (IOException e) {
            LOG.debug("关闭 MCP server stdin 失败（通常无害）: id={}", serverId, e);
        }
        if (process.isAlive()) {
            process.destroy();
            boolean exited = awaitExit();
            if (!exited) {
                killDescendants(process);
                process.destroyForcibly();
                awaitExit();
            }
        }
        joinQuietly(stderrDrainer);
        LOG.info("MCP server 进程已结束: id={} exitCode={}", serverId, exitCode());
    }

    /**
     * 等待子进程退出。
     *
     * @return 已退出返回 {@code true}
     */
    private boolean awaitExit() {
        try {
            return process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /**
     * 取退出码。
     *
     * @return 退出码；尚未退出时返回 {@code -1}
     */
    private int exitCode() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /**
     * 尽力终止子进程的整棵进程树。
     * <p>
     * <b>为什么需要它</b>：最常见的 MCP server 用法是 {@code npx -y <package>}，
     * 真正干活的是 npx 拉起的 node 孙进程。只杀直接子进程，表现是「插件已经停了，
     * server 还占着端口与文件句柄」。
     * <p>
     * <b>为什么是「尽力」</b>：JDK 8 没有 {@code ProcessHandle.descendants()}，
     * 只能靠 {@code pgrep -P} 递归；它不存在或没权限时就只能杀到直接子进程为止。
     * 这是已知边界，日志里有痕迹。
     *
     * @param process 子进程
     */
    private static void killDescendants(Process process) {
        try {
            long pid = pidOf(process);
            if (pid <= 0) {
                LOG.debug("取不到子进程 PID，进程树只能杀到直接子进程");
                return;
            }
            for (String child : childrenOf(pid)) {
                signal("TERM", child);
            }
        } catch (RuntimeException e) {
            LOG.debug("终止 MCP server 进程树失败（尽力而为）", e);
        }
    }

    /**
     * 取进程 PID。
     * <p>
     * <b>为什么不是一句 {@code process.pid()}</b>：它是 Java 9 才有的方法，而本项目跑在 Java 8 上。
     * 因此先反射试 {@code pid()}（新 JDK 走这条），失败再退到 JDK 8 的 {@code UNIXProcess}
     * 打印格式。两条都失败就放弃进程树——那是已知边界，而不是出错。
     *
     * @param process 子进程
     * @return PID；取不到时返回 {@code -1}
     */
    private static long pidOf(Process process) {
        try {
            return ((Number) Process.class.getMethod("pid").invoke(process)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Process.pid() 不可用，改用 toString 解析", e);
        }
        String text = String.valueOf(process);
        int start = text.indexOf("pid=");
        if (start < 0) {
            return -1L;
        }
        int end = start + "pid=".length();
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return parsePid(text.substring(start + "pid=".length(), end));
    }

    /**
     * 列出某个进程的直接子进程 PID。
     *
     * @param pid 父进程 PID
     * @return 子进程 PID 文本列表；{@code pgrep} 不可用时返回空列表
     */
    private static List<String> childrenOf(long pid) {
        List<String> children = new ArrayList<String>();
        if (pid <= 0) {
            return children;
        }
        Process pgrep = null;
        try {
            pgrep = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = output.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    children.add(trimmed);
                }
            }
            pgrep.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.debug("pgrep 不可用，进程树只能杀到直接子进程", e);
        } finally {
            if (pgrep != null) {
                pgrep.destroy();
            }
        }
        // 先递归再信号：拿到的是「父在前」的列表，从最深处开始杀更接近终态
        List<String> deepFirst = new ArrayList<String>();
        for (String child : children) {
            deepFirst.addAll(childrenOf(parsePid(child)));
        }
        deepFirst.addAll(children);
        return deepFirst;
    }

    /**
     * 解析 PID 文本。
     *
     * @param text PID 文本
     * @return 数值；无法解析时返回 {@code -1}（{@code pgrep -P} 对它会返回空）
     */
    private static long parsePid(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * 给一个 PID 发信号。
     *
     * @param signal 信号名（{@code TERM} / {@code KILL}）
     * @param pid    PID 文本
     */
    private static void signal(String signal, String pid) {
        try {
            new ProcessBuilder("kill", "-" + signal, pid).start().waitFor(GRACE_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.debug("kill -{} {} 失败（进程可能已经退出）", signal, pid, e);
        }
    }

    /**
     * 起一条守护线程排空 stderr。
     *
     * @param serverId 服务标识，用于日志
     * @param process  子进程
     * @return 排空线程
     */
    private static Thread drainStderr(String serverId, Process process) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    LOG.debug("[mcp:{}] {}", serverId, line);
                }
            } catch (IOException e) {
                LOG.debug("读取 MCP server 的 stderr 结束: id={}", serverId, e);
            }
        }, "mcp-stderr-" + serverId);
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    /**
     * 安静地等待一条线程结束。
     *
     * @param thread 线程，可为 {@code null}
     */
    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
