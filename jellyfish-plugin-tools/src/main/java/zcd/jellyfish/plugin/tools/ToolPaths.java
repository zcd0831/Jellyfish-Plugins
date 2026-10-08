package zcd.jellyfish.plugin.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具的文件路径约定：统一解析与展示口径。
 * <p>
 * 两个刻意的决定：
 * <ul>
 *     <li><b>相对路径按进程工作目录解析</b>：{@code ToolCallRequest} 不带工作目录，
 *     会话里也没有 cwd 这个概念，因此基准只能是进程启动目录。把它集中在这里，
 *     将来补上会话级 cwd 时只改这一处；</li>
 *     <li><b>展示用相对路径</b>：输出里的绝对路径又长又难读，还会让相邻两次调用的输出无法比对；
 *     落在工作目录内的路径统一转成相对形式，目录外才退回绝对路径。</li>
 * </ul>
 * <p>
 * 这里<b>不做</b>越界拦截：能否访问由权限层判定（agent 授权、插件按模式收窄的白名单，
 * 以及本插件自己的 {@link PathPolicy}），工具自己再收一道窄口子只会让「读工作目录外的文件」
 * 这种正常需求无法完成。**闸门与工具必须用同一套解析规则**，否则两边对「同一个路径落在哪」的判断
 * 会分叉——分叉的方向恰恰是闸门被绕过，这也是本类把解析收在一处的原因。
 * <p>
 * <b>认 {@code ~}</b>：工具结果的落盘路径以 {@code ~} 形式回灌给模型（内核侧
 * {@code HomePaths.abbreviate} 缩写，以免把真实用户名写进上下文），模型照抄那条路径回查时
 * 必须打得开——否则信封里「完整内容已落盘」的指引就是一句空话。展开规则与内核侧逐字一致，
 * 与 {@code @} 引用补全共用同一份（{@link #expandHome}）。
 *
 * @author zcd
 */
final class ToolPaths {

    /** 进程工作目录，相对路径的解析基准。 */
    private static final Path WORKING_DIRECTORY = Paths.get("").toAbsolutePath().normalize();

    /** 用户主目录占位前缀，与内核侧 {@code HomePaths} 同名同义。 */
    private static final String HOME_PREFIX = "~";

    /**
     * 工具类，禁止实例化。
     */
    private ToolPaths() {
    }

    /**
     * 取进程工作目录。
     * <p>
     * 供需要枚举目录的场景使用（输入框的 {@code @} 引用补全），遗循「相对路径按进程工作目录解析」
     * 这一条口径——补全与工具看到的是同一个基准。
     *
     * @return 进程工作目录，保证非 {@code null}
     */
    static Path workingDirectory() {
        return WORKING_DIRECTORY;
    }

    /**
     * 把模型给的路径解析成规范化的绝对路径。
     *
     * @param raw 原始路径，不可为空白
     * @return 规范化绝对路径
     */
    static Path resolve(String raw) {
        return Paths.get(expandHome(raw)).toAbsolutePath().normalize();
    }

    /**
     * 展开路径行首的 {@code ~} 为用户主目录。
     * <p>
     * 只认 {@code ~} 与 {@code ~/}（或 {@code ~\}）两种形式：{@code ~other/x} 需要解析其他用户的
     * 主目录，那是 shell 的能力，插件不猜——原样交给 {@link java.nio.file.Paths} 当相对路径处理。
     * {@code user.home} 缺失（极端受限的运行环境）时同样原样返回，不把路径改坏。
     *
     * @param raw 原始路径，可为 {@code null}
     * @return 展开后的路径；无需展开时原样返回
     */
    static String expandHome(String raw) {
        if (raw == null || !raw.startsWith(HOME_PREFIX)) {
            return raw;
        }
        if (raw.length() > 1) {
            char next = raw.charAt(1);
            if (next != '/' && next != '\\') {
                return raw;
            }
        }
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            return raw;
        }
        return raw.length() == 1 ? home : home + raw.substring(1);
    }

    /**
     * 判断路径是否落在任一给定目录之内。
     * <p>
     * <b>必须按「真实位置」比，不能只比规范化的字面路径</b>：工作目录里放一个指向 {@code /etc} 的
     * 符号链接，字面路径就在允许范围内，而工具顺着链接读写的却是外面——闸门等于不存在。
     * 因此这里先用 {@link #realPath(Path)} 解掉链接再比。
     * <p>
     * 判据是「等于该目录、或在其下」：{@link Path#startsWith(Path)} 按路径<b>段</b>比较，
     * 因此 {@code /work/other} 不会被 {@code /work/o} 误判为在内。
     *
     * @param target 目标路径
     * @param roots  允许的目录列表，不可为 {@code null}
     * @return 落在任一项之内返回 {@code true}
     */
    static boolean insideAny(Path target, List<Path> roots) {
        Path real = realPath(target);
        for (Path root : roots) {
            if (real.startsWith(realPath(root))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把路径解析到真实位置：解开符号链接，尚不存在的尾段原样保留。
     * <p>
     * <b>为什么不能直接用 {@code toRealPath}</b>：它对不存在的路径直接抛异常，而闸门恰恰要判
     * 「模型准备新建的那个文件在哪」——那些路径本来就还不存在。做法是往上找到最近一个已存在的祖先、
     * 解开它的链接，再把缺掉的那几段按原样接回去。
     * <p>
     * <b>解不出来时退回规范化路径</b>（不可读、链接成环）：闸门随后按字面判断，宁可判「在内」也不
     * 凭空拒绝——工具在真正读写时会把系统错误报出来，那比一句「权限不允许」更好排查。
     *
     * @param path 路径
     * @return 真实位置的绝对路径：链接已解开、尾段保留、已规范化
     */
    static Path realPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        List<Path> missing = new ArrayList<Path>();
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name == null) {
                return absolute;
            }
            missing.add(name);
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return absolute;
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            real = real.resolve(missing.get(i));
        }
        return real.normalize();
    }

    /**
     * 把路径转成适合放进工具输出的文本。
     *
     * @param path 路径
     * @return 工作目录内为相对路径，目录外为绝对路径
     */
    static String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(WORKING_DIRECTORY)) {
            return absolute.toString();
        }
        String relative = WORKING_DIRECTORY.relativize(absolute).toString();
        return relative.isEmpty() ? "." : relative;
    }
}
