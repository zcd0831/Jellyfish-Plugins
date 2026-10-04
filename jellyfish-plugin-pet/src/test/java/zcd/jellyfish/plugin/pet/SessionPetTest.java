package zcd.jellyfish.plugin.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionPet} 的单元测试：疲惫的连续段与空闲重置、肥胖累积、伤痕的连续性、警惕门槛、夜行。
 * <p>
 * 时间用可推进的假钟：疲惫随墙钟走，而让测试真的等两小时显然不行。
 *
 * @author zcd
 */
@DisplayName("宠物的经历")
class SessionPetTest {

    /** 疲惫满格：两小时。 */
    private static final long FATIGUE_FULL = 2L * 3600L * 1000L;

    /** 肥胖满格：一百万 token。 */
    private static final long OBESITY_FULL = 1000000L;

    /** 假钟。 */
    private final AtomicLong now = new AtomicLong(hourAt(10));

    /**
     * 构造一只宠物。
     *
     * @return 宠物
     */
    private SessionPet pet() {
        LongSupplier clock = now::get;
        return new SessionPet(clock, ZoneId.systemDefault(), FATIGUE_FULL, OBESITY_FULL, 23);
    }

    @Test
    @DisplayName("没有任何活动时疲惫与肥胖都是零")
    void freshPet_should_beIdleAndLean() {
        SessionPet pet = pet();

        assertEquals(0.0, pet.fatigueRatio(), 1e-9);
        assertEquals(0.0, pet.obesityRatio(), 1e-9);
        assertEquals(Posture.ORDINARY, pet.posture());
        assertFalse(pet.isNight());
    }

    @Test
    @DisplayName("疲惫随墙钟增长：不必等事件到齐")
    void fatigue_should_growWithClock() {
        SessionPet pet = pet();
        pet.onToolCall(true);

        work(pet, 30L);

        // 连续工作半小时：疲惫是两小时的四分之一
        assertEquals(0.25, pet.fatigueRatio(), 1e-9);
        assertEquals(30L * 60000L, pet.fatigueMillis());
    }

    @Test
    @DisplayName("空闲超过重置间隔就重新开始：去开个会回来，宠物不该还在替你喊累")
    void fatigue_should_resetAfterIdleGap() {
        SessionPet pet = pet();
        pet.onToolCall(true);
        advance(30L * 60000L);

        // 空闲超过 5 分钟：此刻没有「连续工作」这回事
        advance(SessionPet.IDLE_RESET_MILLIS + 1000L);
        assertEquals(0L, pet.fatigueMillis());

        // 再干一会儿，从这一刻重新计时
        pet.onToolCall(true);
        advance(60000L);

        assertEquals(60000L, pet.fatigueMillis());
    }

    @Test
    @DisplayName("短暂停顿不切断连续段：一轮工具加一次调用之间不会超过五分钟")
    void fatigue_should_bridgeShortGaps() {
        SessionPet pet = pet();
        pet.onToolCall(true);

        advance(60000L);
        pet.onLlmCall(100);
        advance(60000L);

        assertEquals(120000L, pet.fatigueMillis());
    }

    @Test
    @DisplayName("肥胖累积模型调用报告的 token，只增不减")
    void obesity_should_accumulateTokens() {
        SessionPet pet = pet();

        pet.onLlmCall(400000);
        pet.onLlmCall(600000);
        pet.onToolCall(true);

        assertEquals(1000000L, pet.totalTokens());
        assertEquals(1.0, pet.obesityRatio(), 1e-9);
    }

    @Test
    @DisplayName("厂商没上报用量时不当成 0 计入：那是「未知」")
    void obesity_should_ignoreUnknownUsage() {
        SessionPet pet = pet();

        pet.onLlmCall(null);
        pet.onLlmCall(0);

        assertEquals(0L, pet.totalTokens());
    }

    @Test
    @DisplayName("连续失败三次形成伤痕，一次成功就清零")
    void wound_should_requireConsecutiveFailures() {
        SessionPet pet = pet();

        pet.onToolCall(false);
        pet.onToolCall(false);
        assertFalse(pet.isWounded());

        pet.onToolCall(true);
        pet.onToolCall(false);
        pet.onToolCall(false);
        assertFalse(pet.isWounded(), "失败被成功打断过，不该攒出伤痕");

        pet.onToolCall(false);
        assertTrue(pet.isWounded());
    }

    @Test
    @DisplayName("伤痕一旦形成就留下：它是既成事实，不是瞬时状态")
    void wound_should_bePermanentWithinSession() {
        SessionPet pet = pet();
        hurt(pet);

        pet.onToolCall(true);
        pet.onToolCall(true);

        assertTrue(pet.isWounded());
        assertEquals(Posture.WOUNDED, pet.posture());
    }

    @Test
    @DisplayName("模型调用失败不算工具的连续失败：两条信号不能混")
    void llmFailure_should_notWound() {
        SessionPet pet = pet();

        pet.onLlmCall(null);
        pet.onLlmCall(null);
        pet.onLlmCall(null);

        assertFalse(pet.isWounded());
    }

    @Test
    @DisplayName("被打断两次才变得警惕：一次往往只是试错")
    void wary_should_requireTwoInterruptions() {
        SessionPet pet = pet();

        pet.onTurnCancelled();
        assertFalse(pet.isWary());

        pet.onTurnCancelled();
        assertTrue(pet.isWary());
    }

    @Test
    @DisplayName("深夜有过活动就是夜行")
    void night_should_beMarkedByLateActivity() {
        SessionPet pet = pet();
        pet.onToolCall(true);
        assertFalse(pet.isNight());

        now.set(hourAt(23));
        pet.onToolCall(true);

        assertTrue(pet.isNight());
    }

    @Test
    @DisplayName("疲惫到四分之三才换姿态：条是幅度，脸是门槛")
    void posture_should_becomeTiredAtThreshold() {
        SessionPet pet = pet();
        pet.onToolCall(true);

        work(pet, 60L);
        assertEquals(Posture.ORDINARY, pet.posture());

        work(pet, 30L);
        assertEquals(Posture.TIRED, pet.posture());
    }

    @Test
    @DisplayName("姿态优先级：伤 > 警惕 > 疲惫")
    void posture_should_preferTheMostUrgent() {
        SessionPet pet = pet();
        hurt(pet);
        pet.onTurnCancelled();
        pet.onTurnCancelled();
        pet.onToolCall(true);
        work(pet, 110L);

        assertTrue(pet.isWounded() && pet.isWary() && pet.isTired());
        assertEquals(Posture.WOUNDED, pet.posture());
    }

    /**
     * 模拟一段连续工作：每隔一分钟来一次活动，直到累计给定分钟数。
     * <p>
     * 不能只推进时钟——{@link SessionPet#IDLE_RESET_MILLIS} 会把「没人干活的那段」判成空闲，
     * 而那正是它的用途。因此「连续工作」必须由持续的事件造出来。
     *
     * @param pet     宠物
     * @param minutes 累计工作分钟数
     */
    private void work(SessionPet pet, long minutes) {
        for (long i = 0; i < minutes; i++) {
            advance(60000L);
            pet.onToolCall(true);
        }
    }

    /**
     * 让宠物连续失败三次，形成伤痕。
     *
     * @param pet 宠物
     */
    private static void hurt(SessionPet pet) {
        for (int i = 0; i < SessionPet.WOUND_STREAK; i++) {
            pet.onToolCall(false);
        }
    }

    /**
     * 推进假钟。
     *
     * @param millis 推进的毫秒数
     */
    private void advance(long millis) {
        now.addAndGet(millis);
    }

    /**
     * 取本地某点整对应的 epoch 毫秒。
     * <p>
     * 用今天这个日期而不是固定日期：DST 规则年年变，而「今天某点」在用户时区里恒有效。
     *
     * @param hour 小时（0..23）
     * @return epoch 毫秒
     */
    private static long hourAt(int hour) {
        return LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, 0))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
