package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Payloads#integer} 的取值口径：**只收能精确表示的整数**。
 * <p>
 * 这条口径对应的是 G-33 里「同一类静默」的另一处落点：配置文件那边（压缩插件的 {@code PluginConfig}）
 * 早已不收小数与超范围的值，而脚本返回的数值此前仍走 {@code asInt()}——{@code 3.7} 被静默截成 3、
 * {@code 5000000000} 被截成一个负数，脚本作者无从发现。因此这里钉住「截断不再发生」。
 * <p>
 * 另外一条同样重要：**0 必须照收**。这些字段在协议里 0 是明确的表态（「保留 0 条」「老化百分比 0」），
 * 与「没表态（{@code null}）」是两件事——把 0 归一成 {@code null} 会把语义反过来。
 *
 * @author zcd
 */
@DisplayName("协议整数取值")
class PayloadsTest {

    /** 字段名，与真实 codec 用的那些同名即可。 */
    private static final String FIELD = "keepRecentMessages";

    @Test
    @DisplayName("能精确表示的整数照收，0 是有效值")
    void integer_shouldReadExactValuesAndKeepZero() {
        assertEquals(Integer.valueOf(0), Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":0}"), FIELD),
                "0 是「一条都不留」的明确表态，不能被当成没表态");
        assertEquals(Integer.valueOf(3), Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":3}"), FIELD));
        assertEquals(Integer.valueOf(-1), Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":-1}"), FIELD));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE),
                Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":2147483647}"), FIELD));
    }

    @Test
    @DisplayName("小数不再被截断，而是按「不表态」处理")
    void integer_shouldNotTruncateFraction() {
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":3.7}"), FIELD),
                "3.7 被截成 3 就是一次无声的改值");
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":3.0}"), FIELD),
                "3.0 也是浮点写法，无法判断脚本的本意，按不表态处理更诚实");
    }

    @Test
    @DisplayName("超出 int 范围不再被截断成另一个数")
    void integer_shouldNotTruncateOutOfRange() {
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":5000000000}"), FIELD),
                "超范围被截成负数是最坏的一种静默");
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":-5000000000}"), FIELD));
    }

    @Test
    @DisplayName("缺失、非数字、非对象一律按「不表态」处理")
    void integer_shouldReturnNullForNonNumeric() {
        assertNull(Payloads.integer(ScriptJson.tree("{}"), FIELD));
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":\"3\"}"), FIELD));
        assertNull(Payloads.integer(ScriptJson.tree("{\"" + FIELD + "\":null}"), FIELD));
        assertNull(Payloads.integer(ScriptJson.tree("[]"), FIELD));
        assertNull(Payloads.integer(null, FIELD));
    }
}
