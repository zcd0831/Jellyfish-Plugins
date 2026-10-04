package zcd.jellyfish.plugin.sparkline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SparklinePanel} 的单元测试：无数据不占区域、三行的内容与强调档位、落位建议。
 *
 * @author zcd
 */
@DisplayName("趋势面板")
class SparklinePanelTest {

    /** 图宽，取小值便于断言。 */
    private static final int WIDTH = 4;

    /** 采样台账。 */
    private SparklineStore store;

    /** 被测面板。 */
    private SparklinePanel panel;

    @BeforeEach
    void setUp() {
        store = new SparklineStore(WIDTH, 8);
        panel = new SparklinePanel(store, WIDTH);
    }

    @Test
    @DisplayName("没有采样点时不占区域，也不因为缺会话上下文而贴别人的图")
    void handle_should_beEmptyWithoutSamples() {
        assertTrue(panel.handle(new PanelContributionRequest("s1")).isEmpty());
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());

        record("s1");
        assertTrue(panel.handle(new PanelContributionRequest("s2")).isEmpty());
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("三行分别是缓存、失败、输入，且每行都带当前值与单位")
    void handle_should_renderThreeLinesWithValues() {
        record("s1");

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        assertEquals(SparklinePanel.TITLE, contribution.getTitle());
        assertEquals(UiRegion.RIGHT, contribution.getPreferredRegion());
        List<String> texts = textsOf(contribution);
        assertEquals(3, texts.size());
        assertTrue(texts.get(0).startsWith(SparklinePanel.LABEL_CACHE), texts.get(0));
        assertTrue(texts.get(1).startsWith(SparklinePanel.LABEL_FAILURE), texts.get(1));
        assertTrue(texts.get(2).startsWith(SparklinePanel.LABEL_INPUT), texts.get(2));
        // 只表达形状是不够用的：行尾必须能读出值与单位
        assertTrue(texts.get(0).endsWith("50%"), texts.get(0));
        assertTrue(texts.get(2).endsWith("1.0k"), texts.get(2));
    }

    @Test
    @DisplayName("失败率为 0 时用次要档，非 0 时转警示档")
    void handle_should_warnOnlyWhenFailuresExist() {
        record("s1");
        assertEquals(UiEmphasis.DIM, failureEmphasis());

        store.onLlmCallFailed(new LlmCallFailedEvent("s1", "p", "m", 400, "rejected"));

        assertEquals(UiEmphasis.WARN, failureEmphasis());
    }

    @Test
    @DisplayName("每行的图部分是定长块字符段")
    void handle_should_renderFixedWidthGraph() {
        record("s1");

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        // 第二段是图，长度固定为图宽
        assertEquals(WIDTH, contribution.getLines().get(0).getSegments().get(1).getText().length());
    }

    /**
     * 取失败线的数值强调档位。
     *
     * @return 强调档位
     */
    private UiEmphasis failureEmphasis() {
        UiLine line = panel.handle(new PanelContributionRequest("s1")).getLines().get(1);
        return line.getSegments().get(2).getEmphasis();
    }

    /**
     * 记一次模型调用，使会话有图可画。
     *
     * @param sessionId 会话标识
     */
    private void record(String sessionId) {
        store.onLlmCallCompleted(new LlmCallCompletedEvent(sessionId, "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 1010, 500, 0)));
    }

    /**
     * 取面板内容行的文本。
     *
     * @param contribution 面板贡献
     * @return 文本列表
     */
    private static List<String> textsOf(PanelContribution contribution) {
        List<String> texts = new ArrayList<String>();
        for (UiLine line : contribution.getLines()) {
            texts.add(line.text());
        }
        return texts;
    }
}
