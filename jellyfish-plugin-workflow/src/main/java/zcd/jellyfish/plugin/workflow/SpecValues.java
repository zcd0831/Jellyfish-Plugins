package zcd.jellyfish.plugin.workflow;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * spec 字段的类型取值工具：把「模型传进来的不可信结构」变成校验过的值。
 * <p>
 * <b>类型不符一律抛 {@link JellyfishException}，且报错必须回显实际收到的东西</b>：模型看不到这份实现，
 * 只说「类型不对」它改不动（它以为自己传的就是对的），会原样重试；带上实际值它才能改对。
 * <p>
 * <b>空字符串按「没写」处理</b>：模型常把可选字段写成 {@code ""}，把它当成缺省比报错更符合意图。
 *
 * @author zcd
 */
final class SpecValues {

    /** 报错里回显字符串值时的长度上限。 */
    private static final int MAX_DESCRIBE_CHARS = 40;

    /**
     * 工具类，禁止实例化。
     */
    private SpecValues() {
    }

    /**
     * 取可选文本。
     *
     * @param value 取到的值，可为 {@code null}
     * @param where 字段路径，用于报错定位
     * @return 去空白后的文本；缺省或空白时返回 {@code null}
     * @throws JellyfishException 值不是字符串时抛出
     */
    static String optionalText(Object value, String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new JellyfishException(where + " 必须是字符串，收到：" + describe(value));
        }
        String text = ((String) value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 取必填文本。
     *
     * @param value 取到的值，可为 {@code null}
     * @param where 字段路径，用于报错定位
     * @return 去空白后的文本，保证非空白
     * @throws JellyfishException 缺失、空白或类型不符时抛出
     */
    static String requiredText(Object value, String where) {
        String text = optionalText(value, where);
        if (text == null) {
            throw new JellyfishException(where + " 不能为空");
        }
        return text;
    }

    /**
     * 取字符串数组。
     *
     * @param value 取到的值，可为 {@code null}
     * @param where 字段路径，用于报错定位
     * @return 去空白后的文本列表，保证非 {@code null}
     * @throws JellyfishException 类型不符或某一项为空白时抛出
     */
    static List<String> textList(Object value, String where) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new JellyfishException(where + " 必须是数组，收到：" + describe(value));
        }
        List<?> raw = (List<?>) value;
        List<String> result = new ArrayList<String>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            result.add(requiredText(raw.get(i), where + "[" + i + "]"));
        }
        return result;
    }

    /**
     * 取子对象。
     *
     * @param value 取到的值，可为 {@code null}
     * @param where 字段路径，用于报错定位
     * @return 子对象；缺失时返回 {@code null}
     * @throws JellyfishException 值不是对象时抛出
     */
    static Map<?, ?> optionalMap(Object value, String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            throw new JellyfishException(where + " 必须是对象，收到：" + describe(value));
        }
        return (Map<?, ?>) value;
    }

    /**
     * 描述一个值的类型，用于报错回显。
     *
     * @param value 值，可为 {@code null}
     * @return 一行可读描述
     */
    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            String text = ((String) value).replaceAll("\\s+", " ");
            return "\"" + (text.length() > MAX_DESCRIBE_CHARS
                    ? text.substring(0, MAX_DESCRIBE_CHARS) + "…" : text) + "\"";
        }
        if (value instanceof Map) {
            return "一个对象";
        }
        if (value instanceof List) {
            return "一个数组（" + ((List<?>) value).size() + " 项）";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return value.getClass().getSimpleName();
    }
}
