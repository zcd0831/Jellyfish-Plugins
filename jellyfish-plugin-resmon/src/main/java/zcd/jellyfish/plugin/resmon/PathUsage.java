package zcd.jellyfish.plugin.resmon;

import java.nio.file.Path;

/**
 * 一个被统计路径的占用结果：体积、文件数与是否真的存在。
 * <p>
 * <b>为什么把「不存在」也当成一个正常结果</b>：这些目录大半由别的插件按需创建——没有待办就没有
 * {@code ~/.jellyfish/todos}，非 TUI 模式没有日志文件。若把「不存在」表达成 {@code null} 或异常，
 * 上游就得为每一种组合写分支；表达成 {@code present=false, bytes=0} 之后，呈现层只需把它显示成
 * {@link ResmonFormat#UNKNOWN}，而「目录不存在」与「目录有 0 字节」在界面上本来就该长得不一样。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PathUsage {

    /** 占用项键名（见 {@link UsageKeys}）。 */
    private final String key;

    /** 被统计的路径。 */
    private final Path path;

    /** 体积（字节）。 */
    private final long bytes;

    /** 普通文件数（不含目录；单文件统计时为 1）。 */
    private final long files;

    /** 路径是否存在（文件时表示是普通文件）。 */
    private final boolean present;

    /**
     * 构造占用结果。
     *
     * @param key     占用项键名，不可为 {@code null}
     * @param path    被统计路径，不可为 {@code null}
     * @param bytes   体积，不可为负
     * @param files   文件数，不可为负
     * @param present 路径是否存在
     */
    PathUsage(String key, Path path, long bytes, long files, boolean present) {
        this.key = key;
        this.path = path;
        this.bytes = bytes;
        this.files = files;
        this.present = present;
    }

    /**
     * 构造「路径不存在」的结果。
     *
     * @param key  占用项键名，不可为 {@code null}
     * @param path 被统计路径，不可为 {@code null}
     * @return 体积与文件数均为 0、{@code present} 为 {@code false} 的结果
     */
    static PathUsage missing(String key, Path path) {
        return new PathUsage(key, path, 0L, 0L, false);
    }

    /**
     * 获取占用项键名。
     *
     * @return 键名
     */
    String key() {
        return key;
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
     * @return 字节数
     */
    long bytes() {
        return bytes;
    }

    /**
     * 获取文件数。
     *
     * @return 文件数
     */
    long files() {
        return files;
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
        return "PathUsage{" + key + "=" + bytes + "B/" + files + "f, present=" + present + '}';
    }
}
