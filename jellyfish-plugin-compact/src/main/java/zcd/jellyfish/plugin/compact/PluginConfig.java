package zcd.jellyfish.plugin.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-plugin-compact} 段解析成值对象。
 * <p>
 * <b>两项都是可选的</b>：不填就返回 {@code null}，让内核用 {@code react} 段的缺省值——「本插件不表态」
 * 与「本插件表态为某个具体数字」是两件不同的事，插件不该用一个自己编的默认值把内核的配置压掉。
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的是最终值。
 * <p>
 * <b>值非法直接抛</b>：类型不对或超出范围属配置错误，启动期就该让人看见，而不是压到运行时变成
 * 「保留条数怎么不生效」。<b>但 {@code keepRecentMessages = 0} 是合法值</b>：契约里它的含义是
 * 「一条原文都不留」，与「没配」（缺省回落内核配置）是两件事。
 * <p>
 * <b>为什么要钳制</b>：内核还会再钳一次（那是它对自己保命机制的把关），这里只做「是不是个合理的数字」
 * 这一层，两边都做不是重复而是分工——本插件拒绝明显荒谬的值，内核拒绝任何越界值。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginConfig.class);

    /** 保留条数配置键。 */
    static final String KEY_KEEP_RECENT_MESSAGES = "keepRecentMessages";

    /** 摘要长度上限配置键。 */
    static final String KEY_MAX_SUMMARY_CHARS = "maxSummaryChars";

    /** 本插件允许的保留条数上限：再大等于「一条都不压」，用户应该改配置而不是把值调这么大。 */
    private static final int MAX_KEEP_RECENT_MESSAGES = 1000;

    /** 本插件允许的摘要长度上限。 */
    private static final int MAX_SUMMARY_CHARS_LIMIT = 20000;

    /** 保留最近多少条原文；{@code null} 表示不表态。 */
    private final Integer keepRecentMessages;

    /** 摘要长度上限；{@code null} 表示不表态。 */
    private final Integer maxSummaryChars;

    /**
     * 构造配置。
     *
     * @param keepRecentMessages 保留最近多少条原文，可为 {@code null}
     * @param maxSummaryChars    摘要长度上限，可为 {@code null}
     */
    private PluginConfig(Integer keepRecentMessages, Integer maxSummaryChars) {
        this.keepRecentMessages = keepRecentMessages;
        this.maxSummaryChars = maxSummaryChars;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置值的类型或取值非法时抛出
     */
    static PluginConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null ? Collections.<String, Object>emptyMap()
                : configuration;
        Integer keepRecent = nonNegativeInt(values.get(KEY_KEEP_RECENT_MESSAGES),
                KEY_KEEP_RECENT_MESSAGES, MAX_KEEP_RECENT_MESSAGES);
        Integer maxSummary = positiveInt(values.get(KEY_MAX_SUMMARY_CHARS),
                KEY_MAX_SUMMARY_CHARS, MAX_SUMMARY_CHARS_LIMIT);
        LOG.debug("压缩插件配置: keepRecentMessages={} maxSummaryChars={}", keepRecent, maxSummary);
        return new PluginConfig(keepRecent, maxSummary);
    }

    /**
     * 获取保留最近多少条原文。
     *
     * @return 条数；本插件未配置时为 {@code null}
     */
    Integer keepRecentMessages() {
        return keepRecentMessages;
    }

    /**
     * 获取摘要长度上限。
     *
     * @return 字符数；本插件未配置时为 {@code null}
     */
    Integer maxSummaryChars() {
        return maxSummaryChars;
    }

    /**
     * 解析一个正整数配置项：缺省（{@code null}）返回 {@code null}，其余必须是 {@code [1, max]} 内的整数。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于错误信息
     * @param max 允许的最大值（含）
     * @return 解析结果，未配置时为 {@code null}
     * @throws JellyfishException 值不是整数或超出范围时抛出
     */
    private static Integer positiveInt(Object raw, String key, int max) {
        Integer value = integer(raw, key);
        if (value != null && (value.intValue() <= 0 || value.intValue() > max)) {
            throw new JellyfishException(key + " 必须在 1 到 " + max + " 之间，实际为 " + value);
        }
        return value;
    }

    /**
     * 解析一个非负整数配置项：缺省（{@code null}）返回 {@code null}，其余必须是 {@code [0, max]} 内的整数。
     * <p>
     * 「保留 0 条」是合法取值而不是写错：契约里 {@code keepRecentMessages = 0} 的含义是
     * 「一条原文都不留，全部交给摘要」，内核照此执行（它只在缺省时才回落到自己的配置）。
     * 把它当成非法值会让用户没法表达这个意思。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于错误信息
     * @param max 允许的最大值（含）
     * @return 解析结果，未配置时为 {@code null}
     * @throws JellyfishException 值不是整数或超出范围时抛出
     */
    private static Integer nonNegativeInt(Object raw, String key, int max) {
        Integer value = integer(raw, key);
        if (value != null && (value.intValue() < 0 || value.intValue() > max)) {
            throw new JellyfishException(key + " 必须在 0 到 " + max + " 之间，实际为 " + value);
        }
        return value;
    }

    /**
     * 解析一个整数配置项。
     * <p>
     * <b>{@code Number} 只接受能精确表示的整数</b>：{@code intValue()} 对小数与超范围的值都是静默截断
     * （{@code 3.7} 变 3、{@code 5000000000} 变一个负数），而配置文件的作者看不出发生过什么。
     * 判据与「字符串必须是整数」一致，只是错误提前到了类型检查这一步。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于错误信息
     * @return 解析结果，未配置时为 {@code null}
     * @throws JellyfishException 值不是整数时抛出
     */
    private static Integer integer(Object raw, String key) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            double asDouble = ((Number) raw).doubleValue();
            if (asDouble != Math.floor(asDouble) || asDouble < Integer.MIN_VALUE
                    || asDouble > Integer.MAX_VALUE) {
                throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
            }
            return Integer.valueOf(((Number) raw).intValue());
        }
        if (raw instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException e) {
                throw new JellyfishException(key + " 必须是整数，实际为 " + raw, e);
            }
        }
        throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
    }
}
