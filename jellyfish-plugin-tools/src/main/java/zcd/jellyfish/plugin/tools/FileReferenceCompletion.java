package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.api.extension.InputReferenceEscapes;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.InputReferenceResult;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 输入引用 {@code @}：在输入框里补全工作目录下的文件与子目录。
 * <p>
 * <b>只做补全，不读文件</b>：{@code @路径} 的含义是「引用了这个文件」，读取由模型调用
 * {@code read_file} 完成（配套的 {@link FileReferencePromptContribution} 负责把这条约定告诉模型）。
 * 因此这里拿不到、也不需要文件内容，自然没有内联与治理问题。
 * <p>
 * <b>为什么归本插件</b>：路径基准与「哪些路径能读」都是文件工具的知识（{@link ToolPaths}），
 * 放在外壳里就等于把同一份知识写两遍。卸载本插件后 {@code @} 不再被认领，
 * 用户敲它只是一段普通文本——这正是「能力来自插件」的应有形态。
 * <p>
 * <b>约束</b>：本处理器在渲染线程上同步执行（输入框每帧可能问一次），因此必须快；
 * 目录列举加了 {@link #MAX_CHOICES} 上限，并且不递归。
 * <p>
 * <b>片段里的空白是转义过的</b>：含空格的路径由 {@link InputReferenceEscapes} 负责转义（插入时）
 * 与还原（比较之前），规则与外壳切片段时用的是同一处定义——三处各写一份的话，
 * 迟早出现「能补全但读不对」或「能读但补全断了」这种只有一半成立的现场。
 * <p>
 * <b>不做过滤</b>：隐藏文件照常列出——「目录里到底有什么」是事实，替用户裁剪只会让他以为文件不存在。
 *
 * @author zcd
 */
final class FileReferenceCompletion implements ExtensionHandler<InputReferenceRequest, InputReferenceResult> {

    /** 行内标记字符，同时是注册时的路由键。 */
    static final String MARKER = "@";

    /** 一次最多返回的候选数：足够覆盖实际使用，又不会让面板与目录扫描无界膨胀。 */
    private static final int MAX_CHOICES = 200;

    @Override
    public InputReferenceResult handle(InputReferenceRequest request) {
        String token = request.getToken();
        // 片段里的空白是**转义过**的（外壳按同一份规则切片段，见 InputReferenceEscapes），
        // 因此比较之前先还原：不还原的话，「my\ f」永远匹配不上「my file.txt」
        String plain = InputReferenceEscapes.unescape(token);
        int slash = plain.lastIndexOf('/');
        String dirPart = slash < 0 ? "" : plain.substring(0, slash + 1);
        String namePrefix = slash < 0 ? plain : plain.substring(slash + 1);
        Path directory = resolveDirectory(dirPart);
        if (directory == null) {
            return InputReferenceResult.empty();
        }
        List<Path> entries = list(directory);
        String lowerPrefix = namePrefix.toLowerCase(Locale.ROOT);
        List<Path> matched = new ArrayList<Path>();
        for (Path entry : entries) {
            String name = fileName(entry);
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matched.add(entry);
            }
        }
        if (matched.isEmpty()) {
            return InputReferenceResult.empty();
        }
        // 目录优先、同类按名字排序：与 list_dir 同口径，让「该往下走」一眼可见
        matched.sort(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(FileReferenceCompletion::fileName, String.CASE_INSENSITIVE_ORDER));
        List<InputReferenceChoice> choices = new ArrayList<InputReferenceChoice>();
        int limit = Math.min(MAX_CHOICES, matched.size());
        for (int i = 0; i < limit; i++) {
            choices.add(choice(dirPart, matched.get(i)));
        }
        return InputReferenceResult.of(choices);
    }

    /**
     * 把目录片段解析成存在的目录。
     *
     * @param dirPart 目录片段（含结尾 {@code /}），可为空串
     * @return 目录路径；无法解析或不是目录时返回 {@code null}
     */
    private static Path resolveDirectory(String dirPart) {
        Path directory = dirPart.isEmpty()
                ? ToolPaths.workingDirectory()
                : Paths.get(ToolPaths.expandHome(dirPart));
        Path normalized = directory.toAbsolutePath().normalize();
        return Files.isDirectory(normalized) ? normalized : null;
    }

    /**
     * 读取目录的直接子项，读取失败时退化为空列表。
     *
     * @param directory 目录路径
     * @return 子项列表，保证非 {@code null}
     */
    private static List<Path> list(Path directory) {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException e) {
            // 补全失败不该升级成界面故障：面板显示「无匹配」即可，用户还可以手敲路径
            return Collections.emptyList();
        }
        return entries;
    }

    /**
     * 构造一条候选。
     * <p>
     * <b>插入文本是转义过的</b>：片段按空白切，因此含空格的路径必须转义成 {@code my\ file.txt}
     * 才不会被切成两段（规则与外壳共用 {@link InputReferenceEscapes}）。
     * 转义的是「目录 + 文件名」整段：目录名里有空格时，那一层也得转义。
     *
     * @param dirPart 目录片段（含结尾 {@code /}，未转义的原文），可为空串
     * @param entry   子项路径
     * @return 候选
     */
    private static InputReferenceChoice choice(String dirPart, Path entry) {
        boolean directory = Files.isDirectory(entry);
        String name = fileName(entry);
        String suffix = directory ? "/" : "";
        String label = name + suffix;
        String insertText = InputReferenceEscapes.escape(dirPart + name) + suffix;
        String detail = directory ? "目录" : sizeDetail(entry);
        return new InputReferenceChoice(label, insertText, detail);
    }

    /**
     * 渲染文件大小说明。
     *
     * @param entry 文件路径
     * @return 说明文本
     */
    private static String sizeDetail(Path entry) {
        try {
            return Files.size(entry) + " 字节";
        } catch (IOException e) {
            // 大小读不出来不影响「这里有个文件」这个事实
            return "文件";
        }
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
