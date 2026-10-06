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
import java.util.EnumSet;

/**
 * 目录 / 文件体积统计：把一个路径下的普通文件大小求和，并数出文件个数。
 * <p>
 * <b>刻意不引入任何第三方库</b>：整个过程只要 {@code java.nio} 的深度优先遍历，Apache Commons IO 之类
 * 带来的只是「插件要 shade 一个 300KB 的依赖」。
 * <p>
 * <b>四条边界，每一条都是为了「统计本身不能成为故障源」</b>：
 * <ul>
 *     <li><b>深度上限</b>：{@code ~/.jellyfish/sessions} 这类目录理论上没有上界，无限递归会在一次采样里
 *     走完用户全部历史。超过深度的部分不计入，{@link PluginConfig#walkMaxDepth()} 可调；</li>
 *     <li><b>不跟随符号链接</b>：{@code walkFileTree} 不指定 {@link FileVisitOption#FOLLOW_LINKS} 时
 *     符号链接不会被展开，因此「软链到根目录」不会把整块磁盘算进来；</li>
 *     <li><b>访问失败只跳过</b>：权限不足、坏链接、并发删除都会让单个条目失败，
 *     {@code visitFileFailed} 一律 {@code CONTINUE}——统计一个目录不该因为其中一个子目录读不到而全盘失败；</li>
 *     <li><b>可被打断</b>：遍历每到一个文件就看一次中断标志，因此插件停止时的
 *     {@code shutdownNow()} 不会等一次全盘遍历走完。</li>
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
     * 统计一个目录下的全部普通文件。
     * <p>
     * 目录不存在、或存在但不是目录（例如被同名文件占住）时返回「不存在」结果，不抛异常。
     *
     * @param key  占用项键名，不可为 {@code null}
     * @param path 目录路径，不可为 {@code null}
     * @return 统计结果，保证非 {@code null}
     */
    PathUsage directory(String key, Path path) {
        if (!Files.isDirectory(path)) {
            return PathUsage.missing(key, path);
        }
        Collector collector = new Collector();
        try {
            Files.walkFileTree(path, EnumSet.noneOf(FileVisitOption.class), maxDepth, collector);
        } catch (IOException | RuntimeException e) {
            // 遍历中途失败（起始目录被删、文件系统错误……）：把已经数出来的部分如实报出去，
            // 比报一个 0 更好——0 会让「正在膨胀的目录」看起来像被清空了。
            LOG.warn("统计目录 {} 时中断，返回已扫到的部分：{}", path, e.getMessage());
        }
        return new PathUsage(key, path, collector.bytes, collector.files, true);
    }

    /**
     * 统计一个文件<b>连同它的轮换历史</b>：同目录下所有以该文件名开头的普通文件之和。
     * <p>
     * <b>为什么不能只统计那一个文件</b>：这个入口是给 TUI 日志用的，而日志现在是滚动的——
     * 磁盘上真正占地方的是 {@code jellyfish-tui.log} 加上 {@code .1} 到 {@code .5}，
     * 只数当前那一个会漏掉五分之四（按缺省预算最多漏掉约 50 MB）。
     * 一个「越用越少」的观测结果比没有观测更糟：它会让人以为日志已经被控制住了。
     * <p>
     * <b>为什么不需要单独的「只统计一个文件」入口</b>：没有历史文件时本方法的结果就是那一个文件的
     * 体积与 1 个文件，因此「单文件」是它的特例而不是另一种情况。
     * <p>
     * <b>匹配规则是「同名，或同名前跟一个点」而不是纯前缀</b>：轮换档名可能是 {@code .1}、{@code .2024-01-01}、
     * {@code .1.gz}（取决于配置怎么改），但它们<b>都</b>是「点 + 后缀」这个形状；而纯前缀会把
     * {@code jellyfish-tui.logx-backup} 这类同前缀的无关文件也算进来——那是别人的文件，
     * 把它记到日志头上只会让人去查一个不存在的增长。
     *
     * @param key     占用项键名，不可为 {@code null}
     * @param logFile 当前日志文件路径，不可为 {@code null}
     * @return 统计结果；父目录不存在、或没有任何匹配文件时返回「不存在」结果
     */
    PathUsage withSiblings(String key, Path logFile) {
        Path directory = logFile.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return PathUsage.missing(key, logFile);
        }
        String base = logFile.getFileName().toString();
        long bytes = 0L;
        long files = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isLogRotatedFrom(candidate.getFileName().toString(), base) && Files.isRegularFile(candidate)) {
                    bytes += Files.size(candidate);
                    files++;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("统计 {} 的轮换历史失败：{}", logFile, e.getMessage());
        }
        if (files == 0L) {
            return PathUsage.missing(key, logFile);
        }
        return new PathUsage(key, logFile, bytes, files, true);
    }

    /**
     * 判断一个目录项名是否属于某个日志文件（含它的轮换档）。
     *
     * @param name 目录项名，不可为 {@code null}
     * @param base 当前日志文件名，不可为 {@code null}
     * @return 同名、或以「{@code 基名.}」开头时返回 {@code true}
     */
    private static boolean isLogRotatedFrom(String name, String base) {
        return name.equals(base) || name.startsWith(base + ".");
    }

    /**
     * 遍历器：累加普通文件大小与个数。
     * <p>
     * 目录本身不计入体积（目录项大小因文件系统而异），只计普通文件的 {@code size}。
     *
     * @author zcd
     */
    private static final class Collector extends SimpleFileVisitor<Path> {

        /** 累计字节数。 */
        private long bytes;

        /** 累计文件数。 */
        private long files;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (Thread.currentThread().isInterrupted()) {
                // 插件停止时的 shutdownNow 会打这个标志：一次全盘遍历不该拖住 stop()
                return FileVisitResult.TERMINATE;
            }
            if (attributes.isRegularFile()) {
                bytes += attributes.size();
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
