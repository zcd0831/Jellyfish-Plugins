package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.ui.UiLine;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SparklinePanel} 的行宽约束测试：默认配置下每一行都必须放得进最小侧栏。
 * <p>
 * <b>为什么这是一条值得守的不变量</b>：面板建议落纵向栏，而外壳给侧栏的最小宽度是 20 列（含边框），
 * 也就是内容区只剩 <b>18 列</b>。超出的那一行会被外壳折行——本来三行就有六行，而面板的行数预算
 * 是有限的。折行不会报错、也不会崩，只会让面板变得难读，因此这里用断言把它钉住：
 * 谁把默认图宽调大、或往行里加一段文本，都会在这里当场失败。
 * <p>
 * <b>用极长值取最坏情形</b>：{@code 100%}（四列）与 {@code 1.2M}（四列）是行尾数值的最宽形态，
 * 拿日常值测不出问题。
 *
 * @author zcd
 */
@DisplayName("趋势面板行宽")
class SparklinePanelWidthTest {

    /** 最小侧栏宽（含边框）为 20 列，减掉两侧边框即内容宽。 */
    private static final int MIN_CONTENT_WIDTH = 18;

    /** 汉字前缀起始码点，用于宽度估算。 */
    private static final int CJK_START = 0x2E80;

    /** 块字符区间起点。 */
    private static final int BLOCK_START = 0x2581;

    /** 块字符区间终点。 */
    private static final int BLOCK_END = 0x2588;

    @Test
    @DisplayName("缓存全命中、上下文到百万量级时，三行仍放得进最小侧栏")
    void lines_should_fitMinimumSidebarWidth_when_valuesAreWidest() {
        SparklineStore store = store();
        // 最宽的行尾数值：100% 与 1.2M
        store.onLlmCallCompleted(call("s", 1200000, 1200000));
        store.onLlmCallCompleted(call("s", 1200000, 1200000));

        assertLinesFit(store);
    }

    @Test
    @DisplayName("没有缓存命中且全是失败时，三行仍放得进最小侧栏")
    void lines_should_fitMinimumSidebarWidth_whenEverythingFails() {
        SparklineStore store = store();
        store.onLlmCallCompleted(call("s", 999, 0));
        store.onLlmCallFailed(new LlmCallFailedEvent("s", "deepseek", "chat", 400, "rejected"));
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", false, 12L, "exit 1", "s"));

        assertLinesFit(store);
    }

    /**
     * 断言面板每一行的估算列数都不超过最小侧栏的内容宽。
     *
     * @param store 已采样的台账
     */
    private static void assertLinesFit(SparklineStore store) {
        SparklinePanel panel = new SparklinePanel(store, SparklineConfig.DEFAULT_GRAPH_WIDTH);
        PanelContribution contribution = panel.handle(new PanelContributionRequest("s"));
        assertTrue(!contribution.isEmpty(), "本用例的前提是面板有内容");

        for (UiLine line : contribution.getLines()) {
            int columns = columnsOf(line.text());
            assertTrue(columns <= MIN_CONTENT_WIDTH,
                    "行超出最小侧栏内容宽：" + line.text() + " = " + columns + " 列");
        }
    }

    /**
     * 造一个用缺省配置的台账。
     *
     * @return 台账
     */
    private static SparklineStore store() {
        return new SparklineStore(SparklineConfig.DEFAULT_GRAPH_WIDTH, SparklineConfig.DEFAULT_FAILURE_WINDOW);
    }

    /**
     * 造一次模型调用完成事件。
     *
     * @param sessionId 会话标识
     * @param prompt    输入 token 总数
     * @param cacheRead 命中缓存的输入 token
     * @return 事件
     */
    private static LlmCallCompletedEvent call(String sessionId, int prompt, int cacheRead) {
        return new LlmCallCompletedEvent(sessionId, "deepseek", "chat",
                new TokenUsageSnapshot(prompt, 200, prompt + 200, cacheRead, 0));
    }

    /**
     * 估算一行占用的终端列数：块字符与 ASCII 算 1 列，其余（汉字）算 2 列。
     * <p>
     * 这是内核 {@code DisplayWidth} 的同口径近似，只用于本类的宽度约束断言——
     * 插件测试不引入外壳模块，因为插件在运行期也不认识任何外壳类型。
     *
     * @param text 文本
     * @return 估算列数
     */
    private static int columnsOf(String text) {
        int total = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            boolean block = codePoint >= BLOCK_START && codePoint <= BLOCK_END;
            total += block || codePoint < CJK_START ? 1 : 2;
            i += Character.charCount(codePoint);
        }
        return total;
    }
}
