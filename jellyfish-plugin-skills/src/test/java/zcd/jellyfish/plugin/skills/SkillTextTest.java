package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SkillText} 的单元测试：体积格式化与单行截断。
 * <p>
 * 截断看似只是「取前 N 个字符」，但它有两处会出错的地方，都值得各钉一条用例：
 * 切口落在代理对中间（半个字符进了 system prompt），以及不折叠空白（清单排版被打乱）。
 *
 * @author zcd
 */
@DisplayName("skill 文本工具")
class SkillTextTest {

    @Test
    @DisplayName("短文本原样返回，不受折叠影响")
    void singleLine_should_keepShortText() {
        assertEquals("简短描述", SkillText.singleLine("简短描述", 64));
    }

    @Test
    @DisplayName("换行与连续空白应被折叠成单个空格")
    void singleLine_should_collapseWhitespace() {
        assertEquals("第一行 第二行", SkillText.singleLine("第一行\n\n  第二行  ", 64));
    }

    @Test
    @DisplayName("截断应加省略号，且长度符合上限")
    void singleLine_should_truncateWithEllipsis() {
        assertEquals("abcd…", SkillText.singleLine("abcdefgh", 4));
    }

    @Test
    @DisplayName("切口落在代理对中间时应退一格，而不是留下半个字符")
    void singleLine_should_notSplitSurrogatePair() {
        // 「🎯」占两个 UTF-16 码元：上限正好切在它中间（19 个半角字符 + 目标的第一个码元）
        String text = "0123456789012345678🎯后面还有内容";

        String cut = SkillText.singleLine(text, 20);

        assertEquals("0123456789012345678…", cut);
        // 半个字符的典型表现就是「末尾多一个孤立的高代理项」
        assertEquals(-1, cut.indexOf('\uD83C'), cut);
    }

    @Test
    @DisplayName("字符为 null 时返回空串")
    void singleLine_should_returnEmpty_when_null() {
        assertEquals("", SkillText.singleLine(null, 10));
    }
}
