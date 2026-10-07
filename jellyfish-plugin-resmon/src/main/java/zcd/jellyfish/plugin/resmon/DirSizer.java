package zcd.jellyfish.plugin.resmon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 占用统计：把一个一级子项（目录或文件）的体积与文件数算出来。
 * <p>
 * <b>刻意不引入任何第三方库</b>：整个过程只要 {@code java.nio} 的深度优先遍历。
 * <p>
 * <b>体积与文件数口径不同，这是本类唯一需要解释的地方</b>：版本库内部（{@code .git} 等）
 * 的字节<b>计入体积</b>、它的文件<b>不计入文件数</b>。原因是两个数字回答不同的问题：
 * 体积回答「磁盘被占了多久」，少算 git 历史会让它与分区的已用空间对不上；文件数回答
 * 「这里有多少个条目」，而 git 的对象文件是历史元数据——把它们算进来，会话目录会报出
 * 「769 个文件」而用户只聊过 37 次天。单独把版本库内部那部分记在
 * {@link PathUsage#vcsBytes()} 里，用来解释「为什么这个目录比看上去大」。
 * <p>
 * <b>四条边界，每一条都是为了「统计本身不能成为故障源」</b>：
 * <ul>
 *     <li><b>深度上限</b>：{@code sessions} 这类目录理论上没有上界，无限递归会在一次采样里走完
 *     用户全部历史。超过深度的部分不计入，{@link PluginConfig#walkMaxDepth()} 可调；</li>
 *     <li><b>不跟随符号链接</b>：{@code walkFileTree} 不指定 {@link FileVisitOption#FOLLOW_LINKS} 时
 *     符号链接不会被展开，因此「软链到根目录」不会把整块磁盘算进来；</li>
 *     <li><b>访问失败只跳过</b>：权限不足、坏链接、并发删除都会让单个条目失败，
 *     {@code visitFileFailed} 一律 {@code CONTINUE}——统计一个目录不该因为其中一个子目录读不到而全盘失败；</li>
 *     <li><b>可被打断</b>：遍历每到一个文件就看一次中断标志，因此插件停止时的 {@code shutdownNow()}
 *     不会等一次全盘遍历走完。</li>
 * </ul>
 * <p>
 * 无状态（除不可变的深度上限），可安全跨线程使用。
 *
 * @author zcd
 */
final class DirSizer {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(DirSizer.class);

    /** 深度上限的缺省值：覆盖得住内核与官方插件的目录布局，又不至于走完整棵家目录。 */
    static final int DEFAULT_MAX_DEPTH = 6;

    /** 深度上限的合法下界：0 只统计起始目录自身，那没有任何意义。 */
    static final int MIN_MAX_DEPTH = 1;

    /**
     * 版本库内部目录名：进入它们之后，文件不再计入「文件数」（但字节照算）。
     * <p>
     * 目前只有 git 会真的出现在 {@code ~/.jellyfish} 下（会话持久化插件每次落盘留一次提交），
     * 另外两个是顺手兜住的——它们是同一个「历史元数据」形态，规则一样就不该分三套代码。
     */
    private static final Set<String> VCS_DIR_NAMES =
            new HashSet<String>(Arrays.asList(".git", ".hg", ".svn"));

    /** 遍历深度上限。 */
    private final int maxDepth;

    /**
     * 构造统计器。
     *
     * @param maxDepth 遍历深度上限，小于 {@link #MIN_MAX_DEPTH} 时按 {@link #DEFAULT_MAX_DEPTH} 处理
     */
    DirSizer(int maxDepth) {
        this.maxDepth = maxDepth < MIN_MAX_DEPTH ? DEFAULT_MAX_DEPTH : maxDepth;
    }

    /**
     * 获取实际生效的深度上限。
     *
     * @return 深度上限
     */
    int maxDepth() {
        return maxDepth;
    }

    /**
     * 统计一个一级子项：目录递归求和，普通文件连它的轮换档一起算。
     * <p>
     * 两条分支合在一个入口里，是因为调用方（采样器）面对的是「{@code baseDir} 下的一个子项」，
     * 它自己并不知道也不必关心那是个目录还是个文件。
     *
     * @param name 条目名，不可为 {@code null}
     * @param path 路径，不可为 {@code null}
     * @return 统计结果，保证非 {@code null}
     */
    PathUsage child(String name, Path path) {
        if (Files.isDirectory(path)) {
            return directory(name, path);
        }
        if (Files.isRegularFile(path)) {
            return fileWithSiblings(name, path);
        }
        // 符号链接指向不存在的位置、被并发删掉、是 socket…… 都按「不存在」处理
        return PathUsage.missing(name, path);
    }

    /**
     * 统计一个目录下的全部普通文件。
     * <p>
     * 目录本身不计体积（目录项大小因文件系统而异），只计普通文件的 {@code size}。
     *
     * @param name 条目名，不可为 {@code null}
     * @param path 目录路径，不可为 {@code null}
     * @return 统计结果；不是目录时返回「不存在」结果
     */
    PathUsage directory(String name, Path path) {
        if (!Files.isDirectory(path)) {
            return PathUsage.missing(name, path);
        }
        Collector collector = new Collector();
        try {
            Files.walkFileTree(path, EnumSet.noneOf(FileVisitOption.class), maxDepth, collector);
        } catch (IOException | RuntimeException e) {
            // 遍历中途失败（起始目录被删、文件系统错误……）：把已经数出来的部分如实报出去，
            // 比报一个 0 更好——0 会让「正在膨胀的目录」看起来像被清空了。
            LOG.warn("统计目录 {} 时中断，返回已扫到的部分：{}", path, e.getMessage());
        }
        return new PathUsage(name, path, collector.bytes, collector.files, collector.vcsBytes, true);
    }

    /**
     * 统计一个文件<b>连同它的轮换历史</b>：同目录下所有以「该文件名 + 点」开头的普通文件之和。
     * <p>
     * 滚动日志是这条分支存在的理由：磁盘上真正占地方的是 {@code jellyfish-tui.log} 加上
     * {@code .1} 到 {@code .5}，只数当前那一个会漏掉绝大部分——而一个「越用越少」的观测结果
     * 比没有观测更糟，它会让人以为日志已经被控制住了。
     * <p>
     * <b>匹配是「同名，或同名前跟一个点」而不是纯前缀</b>：{@code .1} 与 {@code .1.gz}
     * 都是「点 + 后缀」这个形状，而纯前缀会把 {@code jellyfish-tui.logx-backup} 这类无关文件
     * 也算进来——那是别人的文件，记到日志头上只会让人去查一个不存在的增长。
     *
     * @param name 条目名，不可为 {@code null}
     * @param path 当前文件路径，不可为 {@code null}
     * @return 统计结果；父目录不存在、或没有任何匹配文件时返回「不存在」结果
     */
    PathUsage fileWithSiblings(String name, Path path) {
        Path directory = path.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return PathUsage.missing(name, path);
        }
        String base = path.getFileName().toString();
        long bytes = 0L;
        long files = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isRotatedFrom(candidate.getFileName().toString(), base) && Files.isRegularFile(candidate)) {
                    bytes += Files.size(candidate);
                    files++;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("统计 {} 的轮换历史失败：{}", path, e.getMessage());
        }
        if (files == 0L) {
            return PathUsage.missing(name, path);
        }
        return new PathUsage(name, path, bytes, files, 0L, true);
    }

    /**
     * 判断一个目录项名是否属于某个文件（含它的轮换档）。
     *
     * @param candidate 目录项名，不可为 {@code null}
     * @param base      当前文件名，不可为 {@code null}
     * @return 同名、或以「{@code 基名.}」开头时返回 {@code true}
     */
    private static boolean isRotatedFrom(String candidate, String base) {
        return candidate.equals(base) || candidate.startsWith(base + ".");
    }

    /**
     * 遍历器：累加体积与文件数，并把版本库内部的部分单独记账。
     * <p>
     * {@code visitFile} 与 {@code preVisitDirectory} 的调用是配对的深度优先序，因此用一条
     * 「当前是否在版本库里」的栈就能判断每个文件属于哪一侧——比在每个文件上回溯它的祖先路径便宜得多。
     *
     * @author zcd
     */
    private static final class Collector extends SimpleFileVisitor<Path> {

        /** 累计字节数（含版本库内部）。 */
        private long bytes;

        /** 累计文件数（不含版本库内部）。 */
        private long files;

        /** 版本库内部占用的字节数。 */
        private long vcsBytes;

        /** 进入每个目录时记下它是不是版本库目录，离开时按序弹出。 */
        private final Deque<Boolean> vcsStack = new ArrayDeque<Boolean>();

        /** 当前所处的版本库目录层数：用计数而不是每次遍历整条栈来回答同一个问题。 */
        private int vcsDepth;

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            Path name = directory.getFileName();
            boolean vcs = name != null && VCS_DIR_NAMES.contains(name.toString());
            vcsStack.push(vcs);
            if (vcs) {
                vcsDepth++;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
            if (!vcsStack.isEmpty() && Boolean.TRUE.equals(vcsStack.pop())) {
                vcsDepth--;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (Thread.currentThread().isInterrupted()) {
                // 插件停止时的 shutdownNow 会打这个标志：一次全盘遍历不该拖住 stop()
                return FileVisitResult.TERMINATE;
            }
            if (!attributes.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            long size = attributes.size();
            bytes += size;
            if (vcsDepth > 0) {
                vcsBytes += size;
            } else {
                files++;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            return FileVisitResult.CONTINUE;
        }
    }
}
