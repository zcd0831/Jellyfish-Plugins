package zcd.jellyfish.plugin.pet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 本插件的配置：{@code plugins.configurations.jellyfish-pet} 段。
 * <p>
 * 三项都是「什么时候算过头」的刻度，而不是开关——宠物的形态本身不提供关闭选项，
 * 不需要它的人不装这个插件即可（与内核「不装 plan 插件就没有模式概念」同一口径）。
 * <ul>
 *     <li>{@code fatigueFullMinutes}：连续工作多久算「疲惫满格」；</li>
 *     <li>{@code obesityFullTokens}：本会话累计烧掉多少 token 算「肥胖满格」；</li>
 *     <li>{@code nightHour}：从几点起算深夜。它决定「夜行」形态何时出现，
 *     而「深夜」在不同人眼里是不同的钟点——写死 23 点会把作息偏晚的人一律说成夜行。</li>
 * </ul>
 * <p>
 * <b>配置可疑只告警、不中断启动</b>：不是数字、超出合理区间都回落缺省值并发一条
 * {@link ConfigWarningEvent}，与内核「配置好坏不阻断启动」的既有口径一致。这三项都是观感刻度，
 * 一个写错的数字不该让宠物整只消失。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PetConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PetConfig.class);

    /** 疲惫满格时长配置键。 */
    static final String KEY_FATIGUE_FULL_MINUTES = "fatigueFullMinutes";

    /** 肥胖满格 token 配置键。 */
    static final String KEY_OBESITY_FULL_TOKENS = "obesityFullTokens";

    /** 深夜起点配置键。 */
    static final String KEY_NIGHT_HOUR = "nightHour";

    /** 疲惫满格时长缺省值：两小时。 */
    static final int DEFAULT_FATIGUE_FULL_MINUTES = 120;

    /** 肥胖满格 token 缺省值：两百万。 */
    static final long DEFAULT_OBESITY_FULL_TOKENS = 2000000L;

    /** 深夜起点缺省值：23 点。 */
    static final int DEFAULT_NIGHT_HOUR = 23;

    /** 疲惫满格时长下限（分钟）。 */
    static final int MIN_FATIGUE_MINUTES = 1;

    /** 疲惫满格时长上限（分钟）：一天。 */
    static final int MAX_FATIGUE_MINUTES = 1440;

    /** 肥胖满格下限（token）。 */
    static final long MIN_OBESITY_TOKENS = 1000L;

    /** 肥胖满格上限（token）。 */
    static final long MAX_OBESITY_TOKENS = 1000000000L;

    /** 生效的疲惫满格时长（毫秒）。 */
    private final long fatigueFullMillis;

    /** 生效的肥胖满格 token 数。 */
    private final long obesityFullTokens;

    /** 生效的深夜起点（小时）。 */
    private final int nightHour;

    /**
     * 构造配置。
     *
     * @param fatigueFullMillis 疲惫满格时长（毫秒）
     * @param obesityFullTokens 肥胖满格 token 数
     * @param nightHour         深夜起点（小时）
     */
    private PetConfig(long fatigueFullMillis, long obesityFullTokens, int nightHour) {
        this.fatigueFullMillis = fatigueFullMillis;
        this.obesityFullTokens = obesityFullTokens;
        this.nightHour = nightHour;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @param source        告警来源标识，通常是插件标识
     * @param warner        告警发布入口，可为 {@code null}（不告警）
     * @return 配置值对象，保证非 {@code null}
     */
    static PetConfig from(Map<String, Object> configuration, String source, Consumer<JellyfishEvent> warner) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap() : configuration;
        int minutes = intOf(values.get(KEY_FATIGUE_FULL_MINUTES), DEFAULT_FATIGUE_FULL_MINUTES,
                MIN_FATIGUE_MINUTES, MAX_FATIGUE_MINUTES, KEY_FATIGUE_FULL_MINUTES, source, warner);
        long tokens = longOf(values.get(KEY_OBESITY_FULL_TOKENS), DEFAULT_OBESITY_FULL_TOKENS,
                MIN_OBESITY_TOKENS, MAX_OBESITY_TOKENS, KEY_OBESITY_FULL_TOKENS, source, warner);
        int hour = intOf(values.get(KEY_NIGHT_HOUR), DEFAULT_NIGHT_HOUR, 0, 23, KEY_NIGHT_HOUR, source, warner);
        LOG.info("宠物插件已装配: fatigueFullMinutes={} obesityFullTokens={} nightHour={}",
                minutes, tokens, hour);
        return new PetConfig(minutes * 60000L, tokens, hour);
    }

    /**
     * 取疲惫满格时长。
     *
     * @return 时长（毫秒），保证不小于 1
     */
    long fatigueFullMillis() {
        return fatigueFullMillis;
    }

    /**
     * 取肥胖满格 token 数。
     *
     * @return token 数，保证不小于 1
     */
    long obesityFullTokens() {
        return obesityFullTokens;
    }

    /**
     * 取深夜起点。
     *
     * @return 小时，保证在 {@code [0, 23]} 内
     */
    int nightHour() {
        return nightHour;
    }

    /**
     * 解析一个带区间的整数配置项，非法或越界时回落缺省值并告警。
     *
     * @param raw      配置原值，可为 {@code null}
     * @param fallback 缺省值
     * @param min      下限（含）
     * @param max      上限（含）
     * @param key      配置键，进告警文案便于定位
     * @param source   告警来源标识
     * @param warner   告警发布入口，可为 {@code null}
     * @return 生效值
     */
    private static int intOf(Object raw, int fallback, int min, int max, String key, String source,
                             Consumer<JellyfishEvent> warner) {
        Long value = number(raw, key, source, warner);
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            warn(source, warner, key + " 应在 " + min + " 到 " + max + " 之间，实际为 " + value
                    + "；已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value.intValue();
    }

    /**
     * 解析一个带区间的长整数配置项，非法或越界时回落缺省值并告警。
     *
     * @param raw      配置原值，可为 {@code null}
     * @param fallback 缺省值
     * @param min      下限（含）
     * @param max      上限（含）
     * @param key      配置键，进告警文案便于定位
     * @param source   告警来源标识
     * @param warner   告警发布入口，可为 {@code null}
     * @return 生效值
     */
    private static long longOf(Object raw, long fallback, long min, long max, String key, String source,
                               Consumer<JellyfishEvent> warner) {
        Long value = number(raw, key, source, warner);
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            warn(source, warner, key + " 应在 " + min + " 到 " + max + " 之间，实际为 " + value
                    + "；已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 取数字形式的配置值。
     *
     * @param raw    配置原值，可为 {@code null}
     * @param key    配置键，进告警文案便于定位
     * @param source 告警来源标识
     * @param warner 告警发布入口，可为 {@code null}
     * @return 数值；未配置或不是数字时返回 {@code null}（不是数字时会先告警）
     */
    private static Long number(Object raw, String key, String source, Consumer<JellyfishEvent> warner) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number)) {
            warn(source, warner, key + " 应为数字，实际为 " + raw.getClass().getName() + "；已按缺省值处理");
            return null;
        }
        return ((Number) raw).longValue();
    }

    /**
     * 发布一条配置告警。
     *
     * @param source  告警来源标识
     * @param warner  告警发布入口，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(String source, Consumer<JellyfishEvent> warner, String message) {
        if (warner == null) {
            return;
        }
        try {
            warner.accept(new ConfigWarningEvent(source, message));
        } catch (RuntimeException e) {
            // 告警失败不该让插件起不来：可观测性不是启动路径上的必要条件
            LOG.warn("配置告警发布失败: {}", message, e);
        }
    }
}
