package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.LifecycleVerdict;

/**
 * {@link LifecycleVerdict} 的协议读写：会话关闭前与分支前两个钩子共用同一份形状。
 * <p>
 * 协议形状：
 * <pre>
 *   result : {"cancel":true,"reason":"工作区还有未提交的改动"}
 * </pre>
 * <b>为什么单独抽出来</b>：两个钩子的请求载荷不同、结果类型完全相同。各写一份解码，迟早会漂移成
 * 「关会话能拦、分叉拦不住」这种只在特定路径上复现的毛病——而它们的语义本就应该一致。
 * <p>
 * <b>宽容的简写</b>：{@code true} 等价「拦下（无理由）」、字符串等价「带理由拦下」、
 * {@code null} / {@code false} / 空对象等价「放行」。与权限拦截那边的写法同源，作者不必记两套。
 * <p>
 * 工具类，禁止实例化。
 *
 * @author zcd
 */
final class LifecycleVerdicts {

    /** 结果字段：是否拦下。 */
    private static final String FIELD_CANCEL = "cancel";

    /** 结果字段：拦下理由。 */
    private static final String FIELD_REASON = "reason";

    /**
     * 工具类，禁止实例化。
     */
    private LifecycleVerdicts() {
    }

    /**
     * 把脚本返回值解码成裁定。
     *
     * @param result 脚本返回的载荷，可为 {@code null}
     * @return 裁定，保证非 {@code null}
     */
    static LifecycleVerdict decode(JsonNode result) {
        if (result == null || result.isNull()) {
            return LifecycleVerdict.proceed();
        }
        if (result.isBoolean()) {
            return result.asBoolean() ? LifecycleVerdict.cancel(null) : LifecycleVerdict.proceed();
        }
        if (result.isTextual()) {
            return LifecycleVerdict.cancel(result.asText());
        }
        if (!Payloads.bool(result, FIELD_CANCEL, false)) {
            return LifecycleVerdict.proceed();
        }
        return LifecycleVerdict.cancel(Payloads.text(result, FIELD_REASON));
    }
}
