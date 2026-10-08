package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 工具 {@code list_dir}：列举一个目录的直接子项。
 * <p>
 * <b>只列一层、不递归</b>：递归列举既容易因为目录树太大把输出撑爆，也让模型失去「逐层探索」的控制权。
 * 需要跨目录找东西时用 {@code grep_files}。
 * <p>
 * <b>默认分页</b>：大目录用 {@code offset} / {@code limit} 分页，命中上限时给出续页提示。
 * 一个有几万项的目录一次性列出来，只会把上下文占满却什么也说明不了。
 * <p>
 * 输出把目录排在文件之前、并给目录名带上 {@code /} 后缀：模型据此一眼看出该继续往下看
 * 还是可以直接读。<b>不做任何过滤</b>（{@code target}、{@code node_modules} 照常列出）——
 * 「目录里到底有什么」是事实，替模型裁剪只会让它对项目结构形成错误印象。
 * <p>
 * <b>但「事实」也要有个边界</b>：分页约束的只是<b>显示</b>，枚举本身此前无上限——一个几十万项的目录
 * （日志堆积、误指向共享盘或 {@code /proc}）会被整份读进内存再排序。因此枚举本身有上限
 * （{@value #MAX_ENTRIES}），到顶即停并如实说「只统计了前 N 项」。这与「不做过滤」不矛盾：
 * 过滤是<b>替模型判断哪些项不重要</b>，上限是<b>明确告诉它这次没看全</b>。
 * <p>
 * 枚举循环里还会查一次取消令牌（{@link ToolCallRequest#getCancellationToken()}）：网络挂载上
 * 一次目录列举可能卡很久，用户按 Esc 应当能中止。取消时把已经列到的部分照常返回，并说明被中止。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class ListDirTool implements PluginTool {

    /** 缺省每页最多返回的条目数。 */
    private static final int DEFAULT_LIMIT = 200;

    /**
     * 一次列举最多收集多少个条目。
     * <p>
     * 取值远大于 {@link #DEFAULT_LIMIT}：这个上限不是给人看的，而是给内存与耗时兜底——
     * 正常目录都在几千项以内，真撞上它是「这个目录不正常」的信号，此时如实说没看全比整份读进来更安全。
     * 不配置化，与 {@code grep_files} 的两个上限同一个理由。
     */
    private static final int MAX_ENTRIES = 50000;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "list_dir",
            "列举一个目录的直接子项（不递归）。目录名以 / 结尾，文件带字节数。大目录可用 offset 与 limit 分页。",
            ToolSchema.properties(
                    "path", ToolSchema.string("目录路径，相对路径按进程工作目录解析；缺省为当前工作目录"),
                    "offset", ToolSchema.integer("起始条目序号，从 1 开始；缺省从第一项开始"),
                    "limit", ToolSchema.integer("每页最多返回多少项，缺省 " + DEFAULT_LIMIT)),
            Arrays.<String>asList());

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** 本次实例的条目数上限。 */
    private final int maxEntries;

    /**
     * 构造工具，使用缺省上限。
     */
    public ListDirTool() {
        this(MAX_ENTRIES);
    }

    /**
     * 构造工具，并指定条目数上限（测试用：上限是五万，真造那么多文件既慢又无谓）。
     *
     * @param maxEntries 条目数上限，必须为正
     */
    ListDirTool(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    @Override
    public PathAccess pathAccess() {
        return PathAccess.READ;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path directory = ToolPaths.resolve(arguments.optionalString("path", "."));
        int offset = arguments.optionalInt("offset", 1);
        int limit = arguments.optionalInt("limit", DEFAULT_LIMIT);
        if (offset < 1) {
            throw new JellyfishException("offset 必须从 1 开始: " + offset);
        }
        if (limit < 1) {
            throw new JellyfishException("limit 必须大于 0: " + limit);
        }
        if (!Files.exists(directory)) {
            throw new JellyfishException("目录不存在: " + ToolPaths.display(directory));
        }
        if (!Files.isDirectory(directory)) {
            throw new JellyfishException("不是目录，请改用 read_file: " + ToolPaths.display(directory));
        }
        Listing listing = listEntries(directory, request.getCancellationToken(), maxEntries);
        List<Path> entries = listing.entries;
        if (entries.isEmpty()) {
            if (listing.interrupted()) {
                return new ToolCallResult(name(), cancelledText(directory, listing),
                        ToolSummaries.of(ToolPaths.display(directory) + "（已取消）"));
            }
            return new ToolCallResult(name(), "目录 " + ToolPaths.display(directory) + " 是空目录",
                    ToolSummaries.of(ToolPaths.display(directory) + "（空目录）"));
        }
        // 目录优先、同类按名字排序：让「该往下走」这件事在输出里一眼可见
        entries.sort(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(path -> fileName(path), String.CASE_INSENSITIVE_ORDER));
        if (offset > entries.size()) {
            throw new JellyfishException("offset 超出目录项数: offset=" + offset
                    + "，目录共 " + entries.size() + " 项");
        }
        int from = offset - 1;
        int to = (int) Math.min((long) from + limit, entries.size());
        StringBuilder text = new StringBuilder("目录 ").append(ToolPaths.display(directory))
                .append("（共 ").append(entries.size()).append(" 项，显示第 ")
                .append(from + 1).append('-').append(to).append(" 项）：");
        for (int index = from; index < to; index++) {
            text.append('\n').append(render(entries.get(index)));
        }
        if (listing.truncated) {
            // 先报「没看全」，再报「没显示完」——前者改变的是「这个数是不是总数」，
            // 后者只是分页；顺序反了会让人以为条目总数是确定的
            text.append("\n[已截断：只统计了前 ").append(listing.limit)
                    .append(" 项（目录可能还有更多），因此上面的总数不是全量]");
        }
        if (to < entries.size()) {
            text.append("\n[已截断：还有 ").append(entries.size() - to)
                    .append(" 项未显示，可用 offset=").append(to + 1).append(" 继续（limit=").append(limit)
                    .append("）]");
        }
        if (listing.cancelled) {
            text.append("\n[已取消：列举被中止，以上不是这个目录的完整内容]");
        }
        return new ToolCallResult(name(), text.toString(), ToolSummaries.of(ToolPaths.display(directory)
                + " · 第 " + (from + 1) + '-' + to + " 项，共 " + entries.size() + " 项"
                + (listing.truncated ? "（未列全）" : "")));
    }

    /**
     * 被取消时那句回灌文本。
     * <p>
     * 与 {@code grep_files} 同一口径：把已经拿到的部分照常给出，同时说清它是残缺的——
     * 含糊其辞的代价是模型据此下「这个目录就这些」的结论。
     *
     * @param directory 目录路径
     * @param listing   列举结果
     * @return 文本
     */
    private static String cancelledText(Path directory, Listing listing) {
        if (listing.entries.isEmpty()) {
            return "目录 " + ToolPaths.display(directory) + " 的列举已被取消，一个条目都没拿到。";
        }
        return "目录 " + ToolPaths.display(directory) + " 的列举已被取消，以下是已拿到的 "
                + listing.entries.size() + " 项（不是完整内容）。";
    }

    /**
     * 读取目录的直接子项。
     * <p>
     * 枚举与取消检查在同一个循环里：{@code DirectoryStream} 的迭代可能阻塞在文件系统上，
     * 每拿到一项就顺势看一眼令牌，用户按 Esc 才不必等这一整轮列举走完。
     *
     * @param directory  目录路径
     * @param token      取消令牌，不可为 {@code null}
     * @param maxEntries 条目数上限
     * @return 列举结果
     * @throws JellyfishException 读取失败时抛出
     */
    private static Listing listEntries(Path directory, CancellationToken token, int maxEntries) {
        Listing listing = new Listing();
        listing.limit = maxEntries;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (token.isCancelled()) {
                    listing.cancelled = true;
                    return listing;
                }
                if (listing.entries.size() >= maxEntries) {
                    listing.truncated = true;
                    return listing;
                }
                listing.entries.add(entry);
            }
        } catch (IOException e) {
            throw new JellyfishException("列举目录失败: " + ToolPaths.display(directory)
                    + " (" + e.getMessage() + ')', e);
        }
        return listing;
    }

    /**
     * 一次列举的结果：条目、以及「是不是全部拿到了」。
     * <p>
     * 两个标志分开不是为了区分「谁先发生」，而是因为它们对用户说的话不同：
     * 截断是「这个目录太大了」，取消是「这次列举没做完」。
     */
    private static final class Listing {

        /** 已收集的子项。 */
        private final List<Path> entries = new ArrayList<Path>();

        /** 是否因为条目数到顶而停。 */
        private boolean truncated;

        /** 是否因为被取消而停。 */
        private boolean cancelled;

        /** 本次列举的条目数上限，用于在提示里给出真实数字。 */
        private int limit;

        /**
         * 是否提前停了（截断或被取消）。
         *
         * @return 提前停了返回 {@code true}
         */
        boolean interrupted() {
            return truncated || cancelled;
        }
    }

    /**
     * 渲染单个子项。
     *
     * @param entry 子项路径
     * @return 一行文本
     */
    private static String render(Path entry) {
        String fileName = fileName(entry);
        if (Files.isDirectory(entry)) {
            return "d  " + fileName + '/';
        }
        long size;
        try {
            size = Files.size(entry);
        } catch (IOException e) {
            // 大小读不出来不影响「这里有个文件」这个事实，退化成不显示大小
            return "f  " + fileName;
        }
        return "f  " + fileName + "  (" + size + " 字节)";
    }

    /**
     * 取文件名，根路径退化为其自身文本。
     *
     * @param path 路径
     * @return 文件名
     */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}
