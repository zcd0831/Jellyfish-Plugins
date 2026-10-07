package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Collections;
import java.util.Map;

/**
 * 五个文件工具共用的元数据构造入口。
 * <p>
 * <b>为什么汇总到一处</b>：它们都只需要一个 {@code summary}（内核把它接在工具名后面，回答
 * 「刚才那一行到底是什么事」）。键名只在 {@link ToolMetadata} 里写一次，这里再收一次是为了不让
 * 「键名写错」在五个类里各发生一次。
 * <p>
 * <b>为什么在这里压成单行</b>：摘要会被外壳当成一行轨迹渲染，而工具的输入里可能带换行
 * （例如 {@code grep_files} 的正则、路径里的空白）。把「摘要必须是一行」这条契约的落实点放在
 * 构造入口，比让五个工具各自记得替换一遍可靠。
 *
 * @author zcd
 */
final class ToolSummaries {

    /**
     * 工具类，禁止实例化。
     */
    private ToolSummaries() {
    }

    /**
     * 构造只带单行摘要的元数据。
     *
     * @param summary 摘要文本，不可为 {@code null}
     * @return 不可变元数据，保证非 {@code null}
     */
    static Map<String, Object> of(String summary) {
        return Collections.<String, Object>singletonMap(ToolMetadata.KEY_SUMMARY, singleLine(summary));
    }

    /**
     * 把摘要压成单行：连续的空白（含换行）折成一个空格，并去掉首尾空白。
     *
     * @param text 文本，不可为 {@code null}
     * @return 单行文本，保证非 {@code null}
     */
    private static String singleLine(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && sb.length() > 0) {
                sb.append(' ');
            }
            pendingSpace = false;
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }
}
