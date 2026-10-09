package zcd.jellyfish.plugin.skills;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * skill 目录的缓存：把「扫描一次的代价」与「每轮都要问一次的频率」隔开。
 * <p>
 * <b>为什么必须缓存</b>：清单贡献每轮组装请求时都会被问一次，而扫描要列举目录、读每个
 * {@code SKILL.md}——把这些放进每轮的路径，等于让对话的每一轮都付一次磁盘遍历的钱。
 * <p>
 * <b>为什么缓存又必须会自动失效</b>：{@code /reload} 只会重启「配置段变了」的插件，
 * 而编辑一个 {@code SKILL.md} 并不改配置。不做失效的话，「我改了 skill 却不生效」只能靠重启进程解决。
 * <p>
 * <b>失效判据是「文件系统签名的变化」，不是时间</b>：签名由各根目录、以及各根目录下的每一个
 * 候选目录（含<b>被跳过的</b>）与它的 {@code SKILL.md} 的修改时间拼成，因此新增、删除、改名、编辑、
 * 以及「在已有子目录里补上 {@code SKILL.md}」都会被看见，而没有任何改动时一次扫描都不会发生。
 * 判据只做 O(根目录数 + 候选目录数) 次 {@code stat} 加每个根目录一次浅列举，
 * 不读文件内容——这正是它能被放进每轮路径的原因。
 * <p>
 * <b>为什么不用「多久重扫一次」这种时间判据</b>：它要么太频繁（白扫），要么太稀疏
 * （改了之后一段时间不生效），而「改了却不知道」恰恰是这里唯一要避免的事。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
class SkillCatalog {

    /** 配置：根目录与各项上限。 */
    private final SkillsConfig config;

    /** 扫描器；无状态，可复用。 */
    private final SkillScanner scanner = new SkillScanner();

    /** 最近一次扫描结果。 */
    private volatile SkillScanResult snapshot;

    /** 最近一次扫描之后计算的文件系统签名。 */
    private volatile String signature;

    /**
     * 构造目录缓存。
     *
     * @param config 配置，不可为 {@code null}
     */
    SkillCatalog(SkillsConfig config) {
        this.config = config;
    }

    /**
     * 取当前可用的 skill 清单，必要时先重扫。
     *
     * @return 扫描结果，保证非 {@code null}
     */
    synchronized SkillScanResult current() {
        String observed = filesystemSignature();
        if (snapshot == null || !observed.equals(signature)) {
            snapshot = scanner.scan(config.roots(), config);
            // 重扫之后再算一次签名而不是复用 observed：扫描期间文件可能又变了，
            // 复用会把那次改动记成「已看见」，于是它要等到下一次改动才生效
            signature = filesystemSignature();
        }
        return snapshot;
    }

    /**
     * 计算文件系统签名：各根目录、以及各根目录下的每一个候选目录与它的 {@code SKILL.md} 的修改时间。
     * <p>
     * <b>候选目录算进去，包括被跳过的那些</b>：签名必须能看见「在已有子目录里补一个 {@code SKILL.md}」、
     * 「给缺 description 的条目补上描述」这类事，而它们发生在<b>根目录本身没有变化</b>的时候
     * （改的是子目录里的文件）。因此判据的主体是候选目录而不是「已成功加载的 skill」——
     * 后者恰恰会漏掉「上次没加载成功、这次能加载了」这一整类变化。
     * <p>
     * <b>第一个 {@code SKILL.md} 的 mtime 记的是 {@code -1}</b>（文件不存在）：这样「补上文件」
     * 一定改变签名，而不依赖「往目录里加文件会不会改目录 mtime」这种因文件系统而异的性质。
     * <p>
     * 代价从 O(根目录数) 变成 O(根目录数 + 候选目录数) 次 {@code stat} 加每个根目录一次浅列举：
     * 仍然不读任何文件内容，因此它还能被放进每轮的路径。
     *
     * @return 签名文本
     */
    private String filesystemSignature() {
        StringBuilder signatureBuilder = new StringBuilder();
        for (Path root : config.roots()) {
            signatureBuilder.append(root).append('=').append(lastModified(root)).append(';');
            for (Path candidate : candidates(root)) {
                Path body = candidate.resolve(SkillScanner.SKILL_FILE);
                signatureBuilder.append(candidate).append('=').append(lastModified(candidate))
                        .append(',').append(body).append('=').append(lastModified(body)).append(';');
            }
        }
        return signatureBuilder.toString();
    }

    /**
     * 列出根目录下的候选目录（浅列举，不判断它能不能加载成 skill）。
     *
     * @param root 根目录
     * @return 有序候选目录列表，保证非 {@code null}
     */
    private static List<Path> candidates(Path root) {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            // 列举不出来（不存在、不可读）就当作没有候选：那是扫描器要去记问题的事，
            // 签名这一层只需要一个确定的答案
            return Collections.emptyList();
        }
        // 排序只为让签名稳定：目录列举顺序在文件系统之间并不一致，不排的话同一份内容会算出不同签名
        Collections.sort(entries);
        return entries;
    }

    /**
     * 取路径的修改时间。
     *
     * @param path 路径，可为 {@code null}
     * @return 毫秒时间戳；路径不存在或不可读时返回 {@code -1}
     */
    private static long lastModified(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
