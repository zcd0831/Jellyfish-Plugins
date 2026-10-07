package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.JellyfishException;

/**
 * 一个步骤的执行条件：按它<b>直接依赖</b>的成败决定要不要跑。
 * <p>
 * <b>只有三个取值，且不做布尔组合</b>：声明式 spec 一旦允许条件 + 循环 + 变量，就会在无人察觉时
 * 变成一门语言，而维护一门语言（语法、版本兼容、错误语义）的成本远高于它带来的编排收益。
 * 需要更复杂的判断时，让模型<b>重新发一份 spec</b>，或把判断拆成更多步骤。
 *
 * @author zcd
 */
enum StepCondition {

    /** 无条件执行（缺省）。 */
    ALWAYS("always"),

    /** 直接依赖全部成功才执行。 */
    ON_SUCCESS("on_success"),

    /** 直接依赖里有任何一个失败才执行。 */
    ON_FAILURE("on_failure");

    /** spec 里书写的取值。 */
    private final String wireName;

    /**
     * 构造条件。
     *
     * @param wireName spec 里的取值
     */
    StepCondition(String wireName) {
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
     * <p>
     * <b>非法取值当场拒绝并回显实际收到的内容</b>：模型看不到这份实现，只说「取值非法」它改不动，
     * 会原样重试。
     *
     * @param value 取值，可为 {@code null}（按 {@link #ALWAYS} 处理）
     * @param where 出错时用于定位的字段路径
     * @return 条件，保证非 {@code null}
     * @throws JellyfishException 取值不在枚举内时抛出
     */
    static StepCondition fromWire(Object value, String where) {
        String text = SpecValues.optionalText(value, where);
        if (text == null) {
            return ALWAYS;
        }
        for (StepCondition condition : values()) {
            if (condition.wireName.equals(text)) {
                return condition;
            }
        }
        throw new JellyfishException(where + " 只能是 always / on_success / on_failure，收到：" + text);
    }
}
