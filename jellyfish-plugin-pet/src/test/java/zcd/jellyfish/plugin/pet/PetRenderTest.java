package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PetRender} 的单元测试：条的格数、时长与 token 的格式化。
 *
 * @author zcd
 */
@DisplayName("宠物字符画")
class PetRenderTest {

    @Test
    @DisplayName("条固定五格，比例决定填满几格")
    void bar_should_haveFiveCells() {
        assertEquals(5, PetRender.bar(0.0).length());
        assertEquals("\u2591\u2591\u2591\u2591\u2591", PetRender.bar(0.0));
        assertEquals("\u2593\u2593\u2593\u2593\u2593", PetRender.bar(1.0));
        assertEquals(5, PetRender.bar(0.5).length());
    }

    @Test
    @DisplayName("比例越界时被夹住：负值不欠格、超过 1 不溢出")
    void bar_should_clampRatio() {
        assertEquals("\u2591\u2591\u2591\u2591\u2591", PetRender.bar(-0.5));
        assertEquals("\u2593\u2593\u2593\u2593\u2593", PetRender.bar(3.0));
    }

    @Test
    @DisplayName("刚有一点点也至少填一格：否则「刚开始累积」会被读成「没发生」")
    void bar_should_fillAtLeastOneCellForTinyRatio() {
        assertEquals("\u2593\u2591\u2591\u2591\u2591", PetRender.bar(0.01));
    }

    @Test
    @DisplayName("时长按量级换单位，只给一个单位")
    void duration_should_pickSingleUnit() {
        assertEquals("30s", PetRender.duration(30000L));
        assertEquals("45m", PetRender.duration(45L * 60000L));
        assertEquals("1h", PetRender.duration(3600000L));
        assertEquals("2h14m", PetRender.duration(2L * 3600000L + 14L * 60000L));
        assertEquals("0s", PetRender.duration(-5L));
    }

    @Test
    @DisplayName("token 按量级换单位，宽度不超过四列")
    void tokens_should_switchUnitByMagnitude() {
        assertEquals("872", PetRender.tokens(872L));
        assertEquals("9.8k", PetRender.tokens(9800L));
        assertEquals("120k", PetRender.tokens(120000L));
        assertEquals("1.2M", PetRender.tokens(1200000L));
    }

    @Test
    @DisplayName("每种姿态的精灵都是四行、每行十列")
    void posture_should_haveFixedSpriteShape() {
        for (Posture posture : Posture.values()) {
            String[] sprite = posture.sprite();
            assertEquals(4, sprite.length, posture.label());
            for (String row : sprite) {
                assertEquals(10, row.length(), posture.label() + ": [" + row + "]");
                assertTrue(row.codePointAt(0) <= 0xFFFF, posture.label());
            }
        }
    }

    @Test
    @DisplayName("精灵返回副本：改它不会污染姿态本身")
    void posture_should_returnSpriteCopy() {
        String[] first = Posture.ORDINARY.sprite();
        first[0] = "XXXX";

        assertEquals(10, Posture.ORDINARY.sprite()[0].length());
    }
}
