package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PetPanel} 的单元测试：无经历不占区域、版面行数与内容、姿态与强调档位、落位建议。
 *
 * @author zcd
 */
@DisplayName("宠物面板")
class PetPanelTest {

    /** 疲惫满格：两小时。 */
    private static final long FATIGUE_FULL = 2L * 3600L * 1000L;

    /** 假钟。 */
    private final AtomicLong now = new AtomicLong(0L);

    /** 宠物台账。 */
    private PetStore store;

    /** 被测面板。 */
    private PetPanel panel;

    @BeforeEach
    void setUp() {
        store = new PetStore(now::get, ZoneId.systemDefault(), FATIGUE_FULL, 1000000L, 23);
        panel = new PetPanel(store);
    }

    @Test
    @DisplayName("还没有经历时不占区域，也不因为缺会话上下文而贴别人的宠物")
    void handle_should_beEmptyWithoutPet() {
        assertTrue(panel.handle(new PanelContributionRequest("s1")).isEmpty());
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());

        activity("s1");
        assertTrue(panel.handle(new PanelContributionRequest("s2")).isEmpty());
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("版面是四行精灵 + 疲惫 + 肥胖 + 形态，共七行（面板上限八行）")
    void handle_should_renderSevenLines() {
        activity("s1");
        advance(30L * 60000L);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        assertEquals(PetPanel.TITLE, contribution.getTitle());
        assertEquals(UiRegion.RIGHT, contribution.getPreferredRegion());
        List<String> texts = textsOf(contribution);
        assertEquals(7, texts.size());
        assertEquals(10, texts.get(0).length());
        assertTrue(texts.get(4).startsWith(PetPanel.LABEL_FATIGUE + " "), texts.get(4));
        assertTrue(texts.get(5).startsWith(PetPanel.LABEL_OBESITY + " "), texts.get(5));
        assertTrue(texts.get(6).startsWith(PetPanel.LABEL_POSTURE + " "), texts.get(6));
    }

    @Test
    @DisplayName("疲惫行带条与时长：条表达幅度，数字给刻度")
    void fatigueLine_should_carryBarAndDuration() {
        activity("s1");
        work("s1", 30L);

        String line = textsOf(panel.handle(new PanelContributionRequest("s1"))).get(4);

        assertEquals(PetPanel.LABEL_FATIGUE + " " + PetRender.bar(0.25) + " 30m", line);
    }

    @Test
    @DisplayName("肥胖行带条与累计 token")
    void obesityLine_should_carryBarAndTokens() {
        store.onLlmCallCompleted(new LlmCallCompletedEvent("s1", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 500000, 0, 0)));

        String line = textsOf(panel.handle(new PanelContributionRequest("s1"))).get(5);

        assertEquals(PetPanel.LABEL_OBESITY + " " + PetRender.bar(0.5) + " 500k", line);
    }

    @Test
    @DisplayName("形态行总是写出昼夜，寻常时不追加姿态")
    void postureLine_should_alwaysStateDayOrNight() {
        activity("s1");

        String line = textsOf(panel.handle(new PanelContributionRequest("s1"))).get(6);

        assertEquals(PetPanel.LABEL_POSTURE + " " + PetPanel.DAY_WALKER, line);
    }

    @Test
    @DisplayName("有姿态时追加在形态行上，并转警示档")
    void postureLine_shouldAppendPostureAndWarn() {
        activity("s1");
        store.onTurnCancelled(new TurnCancelledEvent("s1", "t-1"));
        store.onTurnCancelled(new TurnCancelledEvent("s1", "t-2"));

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));
        UiLine line = contribution.getLines().get(6);

        assertEquals(PetPanel.LABEL_POSTURE + " " + PetPanel.DAY_WALKER + " · 警惕", line.text());
        assertEquals(UiEmphasis.WARN, line.getSegments().get(1).getEmphasis());
    }

    @Test
    @DisplayName("疲惫时精灵退到次要档：该被察觉，但不该打扰")
    void sprite_should_dimWhenTired() {
        activity("s1");
        work("s1", 100L);

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        assertEquals(UiEmphasis.DIM, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("受伤或警惕时精灵用警示档：它需要被看见")
    void sprite_should_warnWhenWounded() {
        activity("s1");
        for (int i = 0; i < SessionPet.WOUND_STREAK; i++) {
            store.onToolCallCompleted(new ToolCallCompletedEvent("c" + i, "shell", false, 1L, "exit 1", "s1"));
        }

        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        assertEquals(UiEmphasis.WARN, contribution.getLines().get(0).getSegments().get(0).getEmphasis());
        assertTrue(contribution.getLines().get(6).text().endsWith("伤痕"));
    }

    /**
     * 记一次活动，让会话有一只宠物。
     *
     * @param sessionId 会话标识
     */
    private void activity(String sessionId) {
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, sessionId));
    }

    /**
     * 模拟一段连续工作：每隔一分钟来一次活动，直到累计给定分钟数。
     * <p>
     * 不能只推进时钟——空闲超过 {@link SessionPet#IDLE_RESET_MILLIS} 就会被判成「人已经离开」，
     * 而那正是它的用途。连续工作必须由持续的事件造出来。
     *
     * @param sessionId 会话标识
     * @param minutes   累计工作分钟数
     */
    private void work(String sessionId, long minutes) {
        for (long i = 0; i < minutes; i++) {
            advance(60000L);
            activity(sessionId);
        }
    }

    /**
     * 推进假钟。
     *
     * @param millis 毫秒
     */
    private void advance(long millis) {
        now.addAndGet(millis);
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
