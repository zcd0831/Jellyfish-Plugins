package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具 {@code grep_files}：在目录树里按正则逐行搜索文本文件。
 * <p>
 * <b>为什么是逐行正则而不是字面量</b>：这个工具的主要用途是「定位」，模型需要用它把范围收窄
 * （找方法名、找引用、找 TODO），正则比字面量一次性表达力强得多；也正因为它只返回匹配的<b>行</b>，
 * 误匹配的代价很小。
 * <p>
 * <b>三重上限</b>：匹配数（{@code max_results}）、单行长度（{@code max_line_chars}）与总字节数
 * （{@code max_bytes}）。少任何一项都能把结果撑爆——一个压缩过的单行 JS 里搜一个常见词，
 * 一行就够长了；一百处这种匹配又能把一百行拼成一份巨大的输出。
 * <p>
 * <b>但那三项管的是结果大小，管不住耗时</b>：搜一个不存在的词时一处匹配都不会有，
 * 三项上限一次都用不上，而遍历要走完整棵树——在超大目录树或网络挂载上就是几分钟，
 * 期间它占着一条 react 线程。因此另有两道只管时间的闸门：
 * <ul>
 *     <li><b>访问文件数上限</b>（{@value #MAX_VISITED_FILES}）：到顶即停，并明确说「未扫完整棵树」。
 *     这条守的是「一次工具调用总得能结束」；上限取常量、不配置化，与跳过名单同一个理由。</li>
 *     <li><b>取消令牌</b>：{@link ToolCallRequest#getCancellationToken()} 在目录访问、每个文件、
 *     以及每 {@value #CANCEL_CHECK_LINES} 行各查一次，用户按 Esc 就能中止。
 *     取消的处置与 shell 插件一致——<b>返回已经找到的匹配并说明被中止</b>，而不是抛异常：
 *     已经把一部分工作做完了，丢掉它只会让用户白等一场。</li>
 * </ul>
 * <p>
 * 搜索时会跳过版本控制与构建产物目录（{@code .git}、{@code target}、{@code node_modules} 等）：
 * 这些目录里的内容不是「项目的源码」，搜进去只会用噪声淹没结果。跳过名单写在常量里，
 * 不配置化——配置项一多，模型与用户对「为什么搜不到」的预期就会分叉。
 * <p>
 * <b>提前停下时一律说明是哪一种停</b>：「结果收满了」「树没扫完」「被取消」是三件不同的事，
 * 而它们在模型眼里都会表现为「结果看起来是完整的」。含糊其辞的代价是它据此下结论。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class GrepFilesTool implements PluginTool {

    /** 搜索时整体跳过的目录名。 */
    private static final Set<String> SKIPPED_DIRECTORIES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(".git", ".idea", "target", "build", "node_modules")));

    /** 二进制探测读取的字节数。 */
    private static final int BINARY_PROBE_BYTES = 8192;

    /** 缺省最大匹配数。 */
    private static final int DEFAULT_MAX_RESULTS = 100;

    /** 缺省单行最大显示字符数。 */
    private static final int DEFAULT_MAX_LINE_CHARS = 500;

    /** 缺省结果总字节上限。 */
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;

    /**
     * 一次搜索最多访问多少个文件。
     * <p>
     * <b>它管的是时间，不是结果</b>：匹配数 / 单行长度 / 总字节数那三项上限都要「先有匹配」才生效，
     * 而搜一个不存在的词时一处匹配都不会有——遍历要走完整棵树，在超大目录树或网络挂载上就是几分钟，
     * 期间占着一条 react 线程。
     * <p>
     * 取值是个折中：正常项目的源码树远小于它（跳过了 {@code .git} / {@code target} / {@code node_modules}），
     * 而真撞上上限时用户得到的是一句明确的「未扫完整棵树」加上收窄 path 的建议——
     * 比一声不响地跑十分钟好得多。不配置化，与跳过名单同一个理由。
     */
    private static final int MAX_VISITED_FILES = 20000;

    /**
     * 每扫多少行查一次取消。
     * <p>
     * 取值要使得「检查的开销」远小于「扫这些行本身」：单次检查是一次 volatile 读，
     * 而扫 8192 行至少是几十微秒。取太小（每行都查）会让热循环多一次分支；
     * 取太大则 Esc 之后要等一会儿才有反应——两者都在毫秒级，这里取的是「不必犹豫」的那个数。
     */
    private static final int CANCEL_CHECK_LINES = 8192;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "grep_files",
            "在文件或目录树中按正则表达式逐行搜索，返回「文件:行号:该行内容」。会跳过 .git/target/node_modules 等目录。",
            ToolSchema.properties(
                    "pattern", ToolSchema.string("Java 正则表达式，对每一行做查找（不是整文件匹配）"),
                    "path", ToolSchema.string("搜索起点，可以是文件或目录，相对路径按进程工作目录解析；缺省为当前工作目录"),
                    "max_results", ToolSchema.integer("最多返回多少处匹配，缺省 " + DEFAULT_MAX_RESULTS),
                    "max_line_chars", ToolSchema.integer("单行最多显示多少字符，超出部分截尾，缺省 "
                            + DEFAULT_MAX_LINE_CHARS),
                    "max_bytes", ToolSchema.integer("结果总字节上限，缺省 " + DEFAULT_MAX_BYTES)),
            Arrays.asList("pattern"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** 本次实例的文件数上限。 */
    private final int maxVisitedFiles;

    /**
     * 构造工具，使用缺省上限。
     */
    public GrepFilesTool() {
        this(MAX_VISITED_FILES);
    }

    /**
     * 构造工具，并指定文件数上限（测试用：上限是两万，真造那么多文件既慢又无谓）。
     * <p>
     * 接缝只开给上限，不开放「是否检查取消」那类开关——后者的两边都不是可配置的策略，
     * 而是「工具该不该响应 Esc」这个有唯一答案的问题。
     *
     * @param maxVisitedFiles 文件数上限，必须为正
     */
    GrepFilesTool(int maxVisitedFiles) {
        this.maxVisitedFiles = maxVisitedFiles;
    }

    @Override
    public PathAccess pathAccess() {
        return PathAccess.READ;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Pattern pattern = compile(arguments.requireString("pattern"));
        Path root = ToolPaths.resolve(arguments.optionalString("path", "."));
        int maxResults = arguments.optionalInt("max_results", DEFAULT_MAX_RESULTS);
        int maxLineChars = arguments.optionalInt("max_line_chars", DEFAULT_MAX_LINE_CHARS);
        int maxBytes = arguments.optionalInt("max_bytes", DEFAULT_MAX_BYTES);
        if (maxResults < 1) {
            throw new JellyfishException("max_results 必须大于 0: " + maxResults);
        }
        if (maxLineChars < 1) {
            throw new JellyfishException("max_line_chars 必须大于 0: " + maxLineChars);
        }
        if (maxBytes < 1) {
            throw new JellyfishException("max_bytes 必须大于 0: " + maxBytes);
        }
        if (!Files.exists(root)) {
            throw new JellyfishException("路径不存在: " + ToolPaths.display(root));
        }
        Searcher searcher = new Searcher(pattern, maxResults, maxLineChars, maxBytes,
                request.getCancellationToken(), maxVisitedFiles);
        try {
            searcher.search(root);
        } catch (IOException e) {
            throw new JellyfishException("搜索失败: " + ToolPaths.display(root) + " (" + e.getMessage() + ')', e);
        }
        return new ToolCallResult(name(), render(searcher), ToolSummaries.of(summaryOf(pattern.pattern(), root, searcher)));
    }

    /**
     * 组装展示摘要：一行说清「在哪儿、搜什么、命中多少」。
     * <p>
     * 它和回灌文本受众不同：回灌文本要把匹配行给模型看，摘要只回答「刚才那一行在干什么」。
     * 因此即使因为没有匹配而回灌了长长一句说明，摘要仍然是短的。
     *
     * @param pattern  模型给的原始正则
     * @param root     搜索起点
     * @param searcher 已完成搜索的搜索器
     * @return 摘要文本，保证非 {@code null}
     */
    private static String summaryOf(String pattern, Path root, Searcher searcher) {
        String where = pattern + " @ " + ToolPaths.display(root);
        if (searcher.matches.isEmpty()) {
            return where + searcher.stopNote() + " · 无匹配";
        }
        return where + " · " + searcher.matches.size() + " 处" + searcher.stopNote();
    }

    /**
     * 编译正则表达式。
     *
     * @param pattern 正则原文
     * @return 已编译的模式
     * @throws JellyfishException 表达式非法时抛出
     */
    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new JellyfishException("正则表达式非法: " + e.getDescription(), e);
        }
    }

    /**
     * 渲染搜索结果。
     * <p>
     * <b>「为什么停」比「找到几处」更重要</b>：收满结果、树没扫完、被取消这三件事在结果文本里
     * 都只表现为「就这么几行」，而模型会据此下「没有更多了」的结论。因此每一种停法都有一句
     * 明确说明，且「一处都没有」的三种成因也各说各的话——把「没扫完」说成「没有匹配到」是最误导的一种回答。
     *
     * @param searcher 已完成搜索的搜索器
     * @return 结果文本
     */
    private static String render(Searcher searcher) {
        String body = joinMatches(searcher);
        switch (searcher.stop) {
            case CANCELLED:
                return body.isEmpty()
                        ? "[已取消：搜索被中止，还没找到匹配；这不代表真的没有]"
                        : body + "\n[已取消：搜索被中止，以上是已找到的 " + searcher.matches.size()
                                + " 处匹配，可能还有遗漏]";
            case FILE_LIMIT:
                return body.isEmpty()
                        ? "[已扫描 " + searcher.maxVisitedFiles + " 个文件后停止（未扫完整棵树），没有匹配到内容；"
                                + "这不代表真的没有，可收窄 path 再搜]"
                        : body + "\n[已截断：只扫描了前 " + searcher.maxVisitedFiles + " 个文件（未扫完整棵树），"
                                + "共显示 " + searcher.matches.size() + " 处匹配；可收窄 path 再搜]";
            case RESULT_LIMIT:
                if (body.isEmpty()) {
                    // 保留这条分支是为了不把「有匹配但一处都放不下」说成「没有匹配」——那是最误导的一种回答
                    return "[已截断：结果超过 max_bytes，没有可显示的匹配；请收窄 path 或调小匹配范围]";
                }
                return body + "\n[已截断：只显示前 " + searcher.matches.size()
                        + " 处匹配，可收窄 path 或调大 max_results / max_bytes]";
            default:
                return body.isEmpty() ? "没有匹配到任何内容。" : body;
        }
    }

    /**
     * 把已收集的匹配行拼成文本。
     *
     * @param searcher 已完成搜索的搜索器
     * @return 每行一处匹配的文本；没有匹配时为空串
     */
    private static String joinMatches(Searcher searcher) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < searcher.matches.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(searcher.matches.get(i));
        }
        return text.toString();
    }

    /**
     * 搜索为什么停下来。
     * <p>
     * 三种提前停各有各的话要说（见 {@link #render}），因此它是一等状态而不是一个布尔量——
     * 「截断」与「没扫完」对模型而言含义完全不同。
     */
    private enum StopReason {

        /** 搜完了。 */
        COMPLETED,

        /** 结果收满（匹配数或字节数）。 */
        RESULT_LIMIT,

        /** 访问文件数到顶，树没扫完。 */
        FILE_LIMIT,

        /** 被取消。 */
        CANCELLED
    }

    /**
     * 目录树搜索器：以「文件访问者」的形式承载匹配状态，避免把可变状态散在方法参数里。
     */
    private static final class Searcher extends SimpleFileVisitor<Path> {

        /** 已编译的匹配模式。 */
        private final Pattern pattern;

        /** 最大匹配数。 */
        private final int maxResults;

        /** 单行最大显示字符数。 */
        private final int maxLineChars;

        /** 结果总字节上限。 */
        private final int maxBytes;

        /** 本次调用的取消令牌。 */
        private final CancellationToken token;

        /** 本次搜索的文件数上限。 */
        private final int maxVisitedFiles;

        /** 已收集的匹配行文本。 */
        private final List<String> matches = new ArrayList<String>();

        /** 已收集结果的 UTF-8 字节数。 */
        private long usedBytes;

        /** 已访问的文件数。 */
        private int visitedFiles;

        /** 已扫描的行数，用于按固定间隔检查取消。 */
        private long scannedLines;

        /** 为什么停。 */
        private StopReason stop = StopReason.COMPLETED;

        /**
         * 构造搜索器。
         *
         * @param pattern      已编译的匹配模式
         * @param maxResults   最大匹配数
         * @param maxLineChars 单行最大显示字符数
         * @param maxBytes     结果总字节上限
         * @param token        取消令牌，不可为 {@code null}
         * @param maxVisitedFiles 文件数上限
         */
        Searcher(Pattern pattern, int maxResults, int maxLineChars, int maxBytes, CancellationToken token,
                 int maxVisitedFiles) {
            this.pattern = pattern;
            this.maxResults = maxResults;
            this.maxLineChars = maxLineChars;
            this.maxBytes = maxBytes;
            this.token = token;
            this.maxVisitedFiles = maxVisitedFiles;
        }

        /**
         * 摘要里那句「为什么停」。
         *
         * @return 后缀文本；正常搜完时为空串
         */
        String stopNote() {
            switch (stop) {
                case CANCELLED:
                    return "，已取消";
                case FILE_LIMIT:
                    return "，未扫完";
                case RESULT_LIMIT:
                    return "，已截断";
                default:
                    return "";
            }
        }

        /**
         * 执行搜索：起点是文件时只搜这一个文件，是目录时遍历整棵树。
         *
         * @param root 搜索起点
         * @throws IOException 遍历失败时抛出
         */
        private void search(Path root) throws IOException {
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, this);
                return;
            }
            searchFile(root);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (stopped()) {
                return FileVisitResult.TERMINATE;
            }
            Path name = directory.getFileName();
            if (name != null && SKIPPED_DIRECTORIES.contains(name.toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            searchFile(file);
            return stopped() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        /**
         * 是否该停止遍历。
         * <p>
         * 两种停止在这里合流：被取消，以及访问文件数到顶。前者用户按了 Esc，后者是这次搜索给自己的
         * 时间预算——两者都只「停」，把已经找到的东西带回去。
         *
         * @return 该停止返回 {@code true}
         */
        private boolean stopped() {
            if (stop != StopReason.COMPLETED) {
                return true;
            }
            if (token.isCancelled()) {
                stop = StopReason.CANCELLED;
                return true;
            }
            if (visitedFiles >= maxVisitedFiles) {
                stop = StopReason.FILE_LIMIT;
                return true;
            }
            return false;
        }

        /**
         * 搜索单个文件。
         * <p>
         * 读不了的文件（权限、竞态删除）直接跳过：一个不可读文件不该让整次搜索失败。
         *
         * @param file 文件路径
         * @throws IOException 判断文件类型失败时抛出
         */
        private void searchFile(Path file) throws IOException {
            if (stopped()) {
                return;
            }
            visitedFiles++;
            if (!Files.isRegularFile(file) || isBinary(file)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                int lineNumber = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (cancelledPeriodically()) {
                        return;
                    }
                    if (!pattern.matcher(line).find()) {
                        continue;
                    }
                    if (matches.size() >= maxResults) {
                        // 已经收满，再多看一眼只为确认「还有更多」
                        stop = StopReason.RESULT_LIMIT;
                        return;
                    }
                    String match = ToolPaths.display(file) + ':' + lineNumber + ':' + truncate(line, maxLineChars);
                    long cost = utf8Length(match) + 1L;
                    // 字节上限同样属于「收满」：已经收了一整套结果，再添一处就超预算；
                    // 但至少放行第一处——一处都不给，模型就完全看不到为什么截断了
                    if (!matches.isEmpty() && usedBytes + cost > maxBytes) {
                        stop = StopReason.RESULT_LIMIT;
                        return;
                    }
                    matches.add(match);
                    usedBytes += cost;
                }
            } catch (IOException e) {
                // 单个文件读不了（权限 / 非法 UTF-8 / 中途被删）不影响其余文件
                return;
            }
        }

        /**
         * 按固定间隔检查取消。
         * <p>
         * 不能只在文件边界查：一个几百 MB 的日志文件就足够让 Esc 等上几十秒。
         *
         * @return 已取消返回 {@code true}
         */
        private boolean cancelledPeriodically() {
            scannedLines++;
            if (scannedLines % CANCEL_CHECK_LINES != 0L) {
                return false;
            }
            if (!token.isCancelled()) {
                return false;
            }
            stop = StopReason.CANCELLED;
            return true;
        }

        /**
         * 按字符上限截断匹配行，切口落在码点边界上。
         *
         * @param line     匹配行原文
         * @param maxChars 字符上限
         * @return 原行；超长时返回截尾结果
         */
        private static String truncate(String line, int maxChars) {
            if (line.length() <= maxChars) {
                return line;
            }
            int end = maxChars;
            if (Character.isHighSurrogate(line.charAt(end - 1))) {
                end--;
            }
            return line.substring(0, end) + "…";
        }

        /**
         * 计算文本的 UTF-8 字节数。
         *
         * @param text 文本
         * @return 字节数
         */
        private static long utf8Length(String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }

        /**
         * 判断文件是否为二进制：开头出现 {@code NUL} 就认定不是文本。
         *
         * @param file 文件路径
         * @return 二进制返回 {@code true}
         * @throws IOException 读取失败时抛出
         */
        private static boolean isBinary(Path file) throws IOException {
            byte[] probe = new byte[BINARY_PROBE_BYTES];
            int read;
            try (InputStream input = Files.newInputStream(file)) {
                read = input.read(probe);
            }
            for (int i = 0; i < read; i++) {
                if (probe[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
