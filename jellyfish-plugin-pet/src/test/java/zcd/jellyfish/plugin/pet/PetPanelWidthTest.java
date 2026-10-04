package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.ui.UiLine;

import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PetPanel} 的行宽约束测试：默认刻度下每一行都必须放得进最小侧栏。
 * <p>
 * <b>为什么这是一条值得守的不变量</b>：面板建议落纵向栏，而外壳给侧栏的最小宽度是 20 列（含边框），
 * 也就是内容区只剩 <b>18 列</b>。超出的那一行会被外壳折行——精灵一折行就再也看不出是只宠物了。
 * 折行不会报错、也不会崩，只会让面板变得不可读，因此这里用断言把它钉住：谁往行里加一段文本、
 * 或把时长格式改长，都会在这里当场失败。
 * <p>
 * <b>用最坏情形取上界</b>：{@code 24h} 级别的时长（{@code 12h34m} 是五列）与 {@code 999.9M} 级别的
 * token、以及「夜行 + 伤痕」这种最长的形态行，都是实际可达的最宽形态。
 *
 * @author zcd
 */
@DisplayName("宠物面板行宽")
class PetPanelWidthTest {

    /** 最小侧栏宽（含边框）为 20 列，减掉两侧边框即内容宽。 */
    private static final int MIN_CONTENT_WIDTH = 18;

    /** 汉字前缀起始码点，用于宽度估算。 */
    private static final int CJK_START = 0x2E80;

    /** 块字符区间起点（U+2580 起是方块元素，含精灵与条用到的全部字符）。 */
    private static final int BLOCK_START = 0x2580;

    /** 块字符区间终点。 */
    private static final int BLOCK_END = 0x259F;

    @Test
    @DisplayName("寻常姿态下所有行放得进最小侧栏")
    void lines_should_fitMinimumSidebarWidth() {
        AtomicLong now = new AtomicLong(0L);
        PetStore store = store(now);
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s"));
        now.addAndGet(3600000L + 47L * 60000L);
        store.onLlmCallCompleted(new LlmCallCompletedEvent("s", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 123400, 0, 0)));

        assertLinesFit(store);
    }

    @Test
    @DisplayName("最长的形态行（夜行 + 伤痕）仍放得进最小侧栏")
    void postureLine_should_fitMinimumSidebarWidth() {
        AtomicLong now = new AtomicLong(millisAt23());
        PetStore store = store(now);
        store.onToolCallCompleted(new ToolCallCompletedEvent("c1", "shell", true, 1L, null, "s"));
        for (int i = 0; i < SessionPet.WOUND_STREAK; i++) {
            store.onToolCallCompleted(new ToolCallCompletedEvent("cf", "shell", false, 1L, "exit 1", "s"));
        }
        // 长时间工作 + 巨量 token：把两条数值行也推到最宽
        now.addAndGet(3600L * 1000L * 12L + 34L * 60000L);
        store.onLlmCallCompleted(new LlmCallCompletedEvent("s", "deepseek", "chat",
                new TokenUsageSnapshot(1000, 10, 999900000, 0, 0)));

        PetPanel panel = assertLinesFit(store);
        String postureLine = panel.handle(new PanelContributionRequest("s")).getLines().get(6).text();
        assertTrue(postureLine.contains(Posture.WOUNDED.label()), postureLine);
    }

    /**
     * 断言面板每一行的估算列数都不超过最小侧栏的内容宽。
     *
     * @param store 已积累经历的台账
     * @return 被测面板
     */
    private static PetPanel assertLinesFit(PetStore store) {
        PetPanel panel = new PetPanel(store);
        PanelContribution contribution = panel.handle(new PanelContributionRequest("s"));
        assertTrue(!contribution.isEmpty(), "本用例的前提是面板有内容");

        for (UiLine line : contribution.getLines()) {
            int columns = columnsOf(line.text());
            assertTrue(columns <= MIN_CONTENT_WIDTH,
                    "行超出最小侧栏内容宽：" + line.text() + " = " + columns + " 列");
        }
        return panel;
    }

    /**
     * 造一个台账。
     *
     * @param now 假钟
     * @return 台账
     */
    private static PetStore store(AtomicLong now) {
        return new PetStore(now::get, ZoneId.systemDefault(),
                PetConfig.DEFAULT_FATIGUE_FULL_MINUTES * 60000L, PetConfig.DEFAULT_OBESITY_FULL_TOKENS,
                PetConfig.DEFAULT_NIGHT_HOUR);
    }

    /**
     * 估算一行占用的终端列数：方块元素与 ASCII 算 1 列，其余（汉字）算 2 列。
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

    /**
     * 取「今天 23:30」对应的 epoch 毫秒，用于造出夜行形态。
     *
     * @return epoch 毫秒
     */
    private static long millisAt23() {
        return java.time.LocalDateTime.of(java.time.LocalDate.now(), java.time.LocalTime.of(23, 30))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
