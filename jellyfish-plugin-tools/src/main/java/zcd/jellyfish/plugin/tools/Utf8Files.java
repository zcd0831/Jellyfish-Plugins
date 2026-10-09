package zcd.jellyfish.plugin.tools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * UTF-8 文本读写的共用小工具：严格解码与原子替换。
 * <p>
 * <b>为什么要「严格」</b>：默认的解码器会把非法字节悄悄换成替换字符（U+FFFD），于是
 * 「这个文件根本不是 UTF-8」这件事在界面上长得像「文件里有一堆问号」。对只读的场合那是误导，
 * 对<b>要写回文件</b>的场合更是灾难：整份读出、整份写回，一次替换就把一个 GBK 文件变成了
 * 一坨乱码。因此读的时候必须能说出「它不是 UTF-8」，而不是假装读到了。
 * <p>
 * <b>为什么要「原子替换」</b>：{@code Files.write} 是先截断再写。写到一半失败（磁盘满、进程被杀）
 * 留下的是一份残缺的原文件——而它原本是好的。同目录临时文件 + {@code ATOMIC_MOVE} 让
 * 「替换」变成一个瞬间动作：要么旧的，要么新的，没有第三种。
 * <p>
 * 工具类，禁止实例化。
 *
 * @author zcd
 */
final class Utf8Files {

    /**
     * 工具类，禁止实例化。
     */
    private Utf8Files() {
    }

    /**
     * 造一个严格 UTF-8 解码器：遇到非法字节抛 {@link CharacterCodingException}。
     *
     * @return 解码器
     */
    static CharsetDecoder strictDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * 造一个严格解码的按行读取器。
     *
     * @param file 文件路径
     * @return 读取器
     * @throws IOException 打开失败时抛出
     */
    static BufferedReader strictReader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), strictDecoder()));
    }

    /**
     * 整份读成文本，非法字节即报错。
     *
     * @param file 文件路径
     * @return 文件正文
     * @throws IOException              读取失败时抛出
     * @throws CharacterCodingException 内容不是合法 UTF-8 时抛出
     */
    static String readStrict(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return strictDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 原子替换文件内容。
     * <p>
     * <b>权限尽量保留</b>：临时文件是按「只有自己可读写」创建的，直接换上去会把原文件的权限改掉
     * （对别人共享的脚本、可执行文件尤其刺眼），因此换之前先照着原文件复制一份权限。
     * 非 POSIX 文件系统（Windows）没有这套权限，忽略即可。
     *
     * @param file    目标文件
     * @param content 新内容
     * @throws IOException 写入失败时抛出
     */
    static void writeAtomic(Path file, byte[] content) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING);
            copyPermissions(file, temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 少数网络盘不支持原子移动：退回普通替换。内容已经完整写在临时文件里，
                // 因此这里丢掉的只是「替换那一瞬间的原子性」
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 把源文件的权限复制给目标文件。
     *
     * @param source 源文件
     * @param target 目标文件
     */
    private static void copyPermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (IOException e) {
            // 权限复制失败不该让写入失败：内容才是这次操作的目的
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统没有这套权限模型
        }
    }
}
