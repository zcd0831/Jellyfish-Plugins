package zcd.jellyfish.plugin.resmon;

import java.nio.file.Path;

/**
 * 一个被统计条目（{@code baseDir} 下的一个一级子项）的占用结果。
 * <p>
 * <b>三个数字各有各的口径，这里把差异写清，免得读的人自己推断</b>：
 * <ul>
 *     <li>{@link #bytes()}：<b>包含版本库内部</b>。它是这个目录在磁盘上真实占掉的字节数，
 *     少算它就会与分区的已用空间对不上（会话目录里 git 历史往往比会话文件本身还大）；</li>
 *     <li>{@link #files()}：<b>不含版本库内部</b>的文件数。git 的对象文件是历史元数据，
 *     把它们算进来会让「会话目录有 769 个文件」这种读数出现——而用户只有 37 次会话；
 *     体积与文件数口径不同是刻意的，两者的用途不同（一个看磁盘，一个看条目数）；</li>
 *     <li>{@link #vcsBytes()}：版本库内部占了多少，用来解释「为什么这个目录比看上去大」。</li>
 * </ul>
 * <p>
 * <b>为什么「不存在」是正常结果</b>：{@code baseDir} 下的子项本来就会被增删（插件卸载后目录还在、
 * 用户手工清掉某个目录、脚本还没跑过）。把「不存在」表达成 {@code null} 或异常，上游就得为每种组合
 * 写分支；表达成 {@code present=false} 之后，呈现层只需把它显示成
 * {@link ResmonFormat#UNKNOWN}，而「目录不存在」与「目录有 0 字节」在界面上本来就该长得不一样。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PathUsage {

    /** 条目名（{@code baseDir} 下的一级子项名）。 */
    private final String name;

    /** 被统计的路径。 */
    private final Path path;

    /** 体积（字节），含版本库内部。 */
    private final long bytes;

    /** 文件数，不含版本库内部。 */
    private final long files;

    /** 版本库内部占用的字节数。 */
    private final long vcsBytes;

    /** 路径是否存在。 */
    private final boolean present;

    /**
     * 构造占用结果。
     *
     * @param name     条目名，不可为 {@code null}
     * @param path     被统计路径，不可为 {@code null}
     * @param bytes    体积，不可为负
     * @param files    文件数，不可为负
     * @param vcsBytes 版本库内部字节数，不可为负
     * @param present  路径是否存在
     */
    PathUsage(String name, Path path, long bytes, long files, long vcsBytes, boolean present) {
        this.name = name;
        this.path = path;
        this.bytes = bytes;
        this.files = files;
        this.vcsBytes = vcsBytes;
        this.present = present;
    }

    /**
     * 构造「路径不存在」的结果。
     *
     * @param name 条目名，不可为 {@code null}
     * @param path 被统计路径，不可为 {@code null}
     * @return 各数值均为 0、{@code present} 为 {@code false} 的结果
     */
    static PathUsage missing(String name, Path path) {
        return new PathUsage(name, path, 0L, 0L, 0L, false);
    }

    /**
     * 获取条目名。
     *
     * @return 条目名
     */
    String name() {
        return name;
    }

    /**
     * 获取被统计路径。
     *
     * @return 路径
     */
    Path path() {
        return path;
    }

    /**
     * 获取体积。
     *
     * @return 字节数，含版本库内部
     */
    long bytes() {
        return bytes;
    }

    /**
     * 获取文件数。
     *
     * @return 文件数，不含版本库内部
     */
    long files() {
        return files;
    }

    /**
     * 获取版本库内部占用的字节数。
     *
     * @return 字节数
     */
    long vcsBytes() {
        return vcsBytes;
    }

    /**
     * 判断路径是否存在。
     *
     * @return 存在返回 {@code true}
     */
    boolean present() {
        return present;
    }

    @Override
    public String toString() {
        return "PathUsage{" + name + "=" + bytes + "B/" + files + "f, vcs=" + vcsBytes + ", present=" + present + '}';
    }
}
