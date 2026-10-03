package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.JellyfishException;

/**
 * 结果的聚合方式。
 * <p>
 * <b>只有两种</b>：把各步骤的正文按顺序收集起来，或者再派一个子代理把它们汇成一段结论。
 * 「合并」这类更聪明的策略不在这里做——需要它的时候，那本身就是<b>一个步骤</b>，
 * 由模型自己声明出来（它可以有一个 agent 与一段 prompt）。
 *
 * @author zcd
 */
enum AggregateMode {

    /** 按步骤顺序拼接各步正文（缺省）。 */
    COLLECT("collect"),

    /** 再派生一个子代理，把各步正文汇成一段结论。 */
    SUMMARIZE("summarize");

    /** spec 里书写的取值。 */
    private final String wireName;

    /**
     * 构造聚合方式。
     *
     * @param wireName spec 里的取值
     */
    AggregateMode(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 获取 spec 里的取值。
     *
     * @return 取值
     */
    String wireName() {
        return wireName;
    }

    /**
     * 解析 spec 里的取值。
     *
     * @param value 取值，可为 {@code null}（按 {@link #COLLECT} 处理）
     * @param where 出错时用于定位的字段路径
     * @return 聚合方式，保证非 {@code null}
     * @throws JellyfishException 取值不在枚举内时抛出
     */
    static AggregateMode fromWire(Object value, String where) {
        String text = SpecValues.optionalText(value, where);
        if (text == null) {
            return COLLECT;
        }
        for (AggregateMode mode : values()) {
            if (mode.wireName.equals(text)) {
                return mode;
            }
        }
        throw new JellyfishException(
                where + " 只能是 collect / summarize，收到：" + text);
    }
}
