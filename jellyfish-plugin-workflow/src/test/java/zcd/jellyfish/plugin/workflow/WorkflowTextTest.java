package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WorkflowText} 的单元测试：显示宽度、按宽度截断、耗时文案。
 *
 * @author zcd
 */
@DisplayName("面板文本工具")
class WorkflowTextTest {

    @Test
    @DisplayName("宽度按显示列算：中日韩与全角标点占两列，框线字符占一列")
    void displayWidth_shouldCountColumns() {
        assertEquals(0, WorkflowText.displayWidth(null));
        assertEquals(0, WorkflowText.displayWidth(""));
        assertEquals(4, WorkflowText.displayWidth("abcd"));
        assertEquals(4, WorkflowText.displayWidth("调研"));
        // 框线字符落在 U+2500–U+257F，与外壳的 DisplayWidth 一样按一列算——连线能不能对齐全看这条
        assertEquals(1, WorkflowText.displayWidth("\u2500"));
        assertEquals(1, WorkflowText.displayWidth("\u252c"));
        assertEquals(5, WorkflowText.displayWidth("\u252c\u2500\u2500\u2500>"));
        // 全角括号与中文一样占两列
        assertEquals(4, WorkflowText.displayWidth("\uff08\uff09"));
    }

    @Test
    @DisplayName("代理对按一个字符两列算，且零宽字符不占列")
    void displayWidth_shouldHandleSurrogatePairAndZeroWidth() {
        assertEquals(2, WorkflowText.displayWidth("\uD83D\uDE00"));
        assertEquals(0, WorkflowText.displayWidth("\u200B"));
        assertEquals(2, WorkflowText.displayWidth("\uFEFF\u4E2D"));
    }

    @Test
    @DisplayName("截断不超预算，且切口不落在代理对中间")
    void clip_shouldNotExceedBudget() {
        assertEquals("abc", WorkflowText.clip("abc", 10));
        // 预算 6：留一列给省略号，正好放得下两个汉字
        assertEquals("调研" + WorkflowText.ELLIPSIS, WorkflowText.clip("调研并写方案", 6));
        String clipped = WorkflowText.clip("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 4);
        assertEquals("\uD83D\uDE00" + WorkflowText.ELLIPSIS, clipped);
        // emoji 占两列、省略号占一列：预算 4 只放得下一个
        assertEquals(3, WorkflowText.displayWidth(clipped));
    }

    @Test
    @DisplayName("耗时按三档写：秒、分秒、时分")
    void elapsed_shouldFormatThreeScales() {
        assertEquals("0s", WorkflowText.elapsed(-1L));
        assertEquals("38s", WorkflowText.elapsed(38_000L));
        assertEquals("1m02s", WorkflowText.elapsed(62_000L));
        assertEquals("2h05m", WorkflowText.elapsed(7_500_000L));
    }
}
