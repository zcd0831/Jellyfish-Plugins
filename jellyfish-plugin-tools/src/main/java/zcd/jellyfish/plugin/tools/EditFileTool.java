package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 工具 {@code edit_file}：按原文精确匹配替换，只改文件的一处（或若干处）。
 * <p>
 * <b>为什么要校验匹配数量</b>：模型写出的「原文」常常是文件里很常见的一小段（比如一个变量名），
 * 直接替换会把不相关的地方一起改掉。匹配到多处而调用方没显式声明 {@code replace_all} 时报错，
 * 逼它把上下文补足——这条错误信息比一次错误改写便宜得多。
 * <p>
 * <b>为什么用字面量匹配而不是正则</b>：被改的是代码，里面的 {@code .}、{@code (}、{@code $}
 * 全是普通字符；一旦按正则解释，模型必须自己转义，错一次就改坏文件。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class EditFileTool implements PluginTool {

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "edit_file",
            "在文件中精确替换一段原文（字面量匹配，按 UTF-8 读写）。原文匹配到多处时必须显式声明 replace_all。",
            ToolSchema.properties(
                    "path", ToolSchema.string("文件路径，相对路径按进程工作目录解析"),
                    "old_text", ToolSchema.string("要被替换的原文，必须与文件中完全一致（含缩进）"),
                    "new_text", ToolSchema.string("替换后的新文本，可以是空串（等于删除）"),
                    "replace_all", ToolSchema.bool("原文匹配到多处时是否全部替换，缺省 false")),
            Arrays.asList("path", "old_text", "new_text"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public PathAccess pathAccess() {
        return PathAccess.WRITE;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path file = ToolPaths.resolve(arguments.requireString("path"));
        String oldText = arguments.requireText("old_text");
        String newText = arguments.requireText("new_text");
        boolean replaceAll = arguments.optionalBoolean("replace_all", false);
        if (oldText.isEmpty()) {
            throw new JellyfishException("old_text 不能为空；要整文件重写请用 write_file");
        }
        if (oldText.equals(newText)) {
            throw new JellyfishException("old_text 与 new_text 相同，无需修改");
        }
        Stamp before = Stamp.of(file);
        String content = readText(file);
        int matches = countMatches(content, oldText);
        if (matches == 0) {
            throw new JellyfishException("文件中未找到 old_text 匹配内容，请确认原文（缩进与换行必须完全一致）: "
                    + ToolPaths.display(file));
        }
        if (matches > 1 && !replaceAll) {
            throw new JellyfishException("old_text 在文件中匹配到 " + matches
                    + " 处，请补足上下文使其唯一，或设置 replace_all=true 全部替换");
        }
        String replaced = replaceAll ? content.replace(oldText, newText) : replaceOnce(content, oldText, newText);
        // 读之后、写之前再看一眼：这两步之间文件可能被别人（另一个代理、用户自己的编辑器）改过，
        // 而我们是整份覆盖——不查就会把他的改动静默抹掉
        requireUnchanged(file, before);
        if (!Files.isWritable(file)) {
            // 原子替换只受目录权限约束，会绕过文件自身的只读位——那与「不可写就别写」的直觉相反，
            // 因此这里显式挡一道，保持与普通写入一致的语义
            throw new JellyfishException("文件不可写: " + ToolPaths.display(file));
        }
        try {
            // 原子替换：写到一半失败时留下的仍是原来那份完整内容，而不是半截文件
            Utf8Files.writeAtomic(file, replaced.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JellyfishException("写入文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
        return new ToolCallResult(name(), "已在 " + ToolPaths.display(file) + " 替换 " + matches + " 处",
                ToolSummaries.of(ToolPaths.display(file) + " · 替换 " + matches + " 处"));
    }

    /**
     * 读取文件全部文本。
     * <p>
     * <b>不是 UTF-8 就明确拒绝</b>：整份读出、整份写回，而读出时非法字节已经被换成替换字符——
     * 于是「替换一处」会把一个 GBK 文件整体变成乱码。那是不可逆的数据损毁，宁可在这里失败。
     *
     * @param file 文件路径
     * @return 文件内容
     * @throws JellyfishException 文件不存在、是目录、不是 UTF-8 或读取失败时抛出
     */
    private static String readText(Path file) {
        if (!Files.exists(file)) {
            throw new JellyfishException("文件不存在: " + ToolPaths.display(file));
        }
        if (Files.isDirectory(file)) {
            throw new JellyfishException("这是一个目录，无法编辑: " + ToolPaths.display(file));
        }
        try {
            return Utf8Files.readStrict(file);
        } catch (CharacterCodingException e) {
            throw new JellyfishException("这不是 UTF-8 文本（存在非法字节），edit_file 只改 UTF-8 文件："
                    + "请先转码，或用 write_file 整份重写: " + ToolPaths.display(file));
        } catch (IOException e) {
            throw new JellyfishException("读取文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 校验文件自「读之前记录的身份」以来没被改动过。
     * <p>
     * <b>包私有接缝</b>：这条判据的窗口落在两次系统调用之间（先记身份、再读内容、再比对），
     * 用例无法确定性地把它摆出来，因此把「判定」本身做成一个可以直接喂参数进去的函数——
     * 与 {@code ScriptScheduler.tickNow} 同一手法。
     *
     * @param file   文件路径
     * @param before 读之前记录的身份
     * @throws JellyfishException 文件已被改动时抛出
     */
    void requireUnchanged(Path file, Stamp before) {
        Stamp now = Stamp.of(file);
        if (!before.equals(now)) {
            throw new JellyfishException("文件在读取之后被改动过，为免覆盖别人的改动，请重新读取后再改: "
                    + ToolPaths.display(file));
        }
    }

    /**
     * 文件的「身份」：大小与修改时间。
     * <p>
     * 用来回答「读完之后它有没有被改过」。判据取这两样而不是内容比对：内容是刚读到的，
     * 再读一遍要付一次 IO，而这两样在一次改写里几乎不可能同时不变。
     */
    static final class Stamp {

        /** 改动时间（毫秒）。 */
        private final long modifiedAt;

        /** 文件大小。 */
        private final long size;

        /**
         * 构造身份。
         *
         * @param modifiedAt 改动时间（毫秒）
         * @param size       文件大小
         */
        private Stamp(long modifiedAt, long size) {
            this.modifiedAt = modifiedAt;
            this.size = size;
        }

        /**
         * 取一个文件的身份。
         *
         * @param file 文件路径
         * @return 身份
         * @throws JellyfishException 文件不存在或取属性失败时抛出
         */
        static Stamp of(Path file) {
            try {
                return new Stamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            } catch (NoSuchFileException e) {
                // 与 readText 同一句文案：调用方（模型）看到的是「文件不存在」而不是「取属性失败」
                throw new JellyfishException("文件不存在: " + ToolPaths.display(file));
            } catch (IOException e) {
                throw new JellyfishException("读取文件属性失败: " + ToolPaths.display(file)
                        + " (" + e.getMessage() + ')', e);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Stamp)) {
                return false;
            }
            Stamp that = (Stamp) other;
            return modifiedAt == that.modifiedAt && size == that.size;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(modifiedAt).hashCode() * 31 + Long.valueOf(size).hashCode();
        }
    }

    /**
     * 统计字面量在文本中出现的次数，不重复计数重叠部分。
     *
     * @param content 文本
     * @param needle  待查找的字面量，保证非空
     * @return 出现次数
     */
    private static int countMatches(String content, String needle) {
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * 只替换第一次出现的字面量。
     *
     * @param content 文本
     * @param oldText 原文
     * @param newText 新文本
     * @return 替换后的文本
     */
    private static String replaceOnce(String content, String oldText, String newText) {
        int index = content.indexOf(oldText);
        return content.substring(0, index) + newText + content.substring(index + oldText.length());
    }
}
