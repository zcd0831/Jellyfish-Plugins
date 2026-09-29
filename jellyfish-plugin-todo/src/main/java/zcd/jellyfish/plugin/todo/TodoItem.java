package zcd.jellyfish.plugin.todo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import zcd.jellyfish.api.JellyfishException;

/**
 * 一条待办：内容 + 状态。
 * <p>
 * <b>刻意只有两个字段</b>：待办是「本会话要给模型看的计划」，编号、创建时间这类元信息对模型没有意义，
 * 而对人可见的编号由渲染时按位置生成，不必落盘。字段越少，越不容易在插件与模型之间产生两套语义。
 * <p>
 * <b>状态是一枚 {@link TodoStatus} 而不是 {@code boolean done}</b>：两态装不下「进行中」，
 * 而模型表达它用的词是 {@code in_progress}——形态必须与模型的语言对齐。
 * <p>
 * <b>落盘字段从 {@code done} 换成 {@code status}</b>：读的时候两个字段都认（旧文件里
 * {@code done:true} 就是已完成、{@code done:false} 就是未开始），因此升级后旧文件照样能读；
 * 写出去的一律是新字段 {@code status}。两者同时出现时以 {@code status} 为准——它是当前格式。
 * <p>
 * 用「全字段构造器 + 无 setter」的不可变形态：它同时是落盘 DTO，Jackson 用显式的
 * {@code @JsonCreator} / {@code @JsonProperty} 反序列化，因此不依赖编译期的 {@code -parameters}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoItem {

    /** 待办内容。 */
    private final String content;

    /** 待办状态。 */
    private final TodoStatus status;

    /**
     * 构造待办。
     *
     * @param content 待办内容，不可为空白
     * @param status  待办状态，可为 {@code null}（按未开始处理）
     * @throws JellyfishException 内容为空白时抛出
     */
    TodoItem(String content, TodoStatus status) {
        if (content == null || content.trim().isEmpty()) {
            throw new JellyfishException("todo content must not be blank");
        }
        this.content = content;
        this.status = status == null ? TodoStatus.PENDING : status;
    }

    /**
     * 反序列化构造器：认新字段 {@code status}，也认旧字段 {@code done}。
     * <p>
     * 旧文件是升级前写下的，丢掉它们等于把用户的待办清空；而写路径只产出新字段，
     * 因此兼容的代价只有这里几行。
     *
     * @param content 待办内容，不可为空白
     * @param status  新格式的状态取值，可为 {@code null}
     * @param done    旧格式的完成标记，可为 {@code null}
     * @throws JellyfishException 内容为空白，或状态取值无法识别时抛出
     */
    @JsonCreator
    TodoItem(@JsonProperty("content") String content,
             @JsonProperty("status") String status,
             @JsonProperty("done") Boolean done) {
        this(content, resolveStatus(status, done));
    }

    /**
     * 解析落盘的状态：新字段优先，其次旧字段，都缺则按未开始。
     *
     * @param status 新格式的状态取值，可为 {@code null}
     * @param done   旧格式的完成标记，可为 {@code null}
     * @return 待办状态，保证非 {@code null}
     * @throws JellyfishException 状态取值无法识别时抛出；不静默退回 {@code done}，
     *                            否则一个写错的取值会被旧字段悄悄盖掉
     */
    private static TodoStatus resolveStatus(String status, Boolean done) {
        if (status != null) {
            TodoStatus parsed = TodoStatus.ofWireName(status);
            if (parsed == null) {
                throw new JellyfishException("待办文件的 status 只能是 " + TodoStatus.allowedNames()
                        + "，实际为 \"" + status + "\"");
            }
            return parsed;
        }
        if (done == null) {
            // 状态整个缺失：按未开始处理，与工具侧「少写状态按未完成」同一口径
            return TodoStatus.PENDING;
        }
        return done ? TodoStatus.COMPLETED : TodoStatus.PENDING;
    }

    /**
     * 获取待办内容。
     *
     * @return 待办内容
     */
    @JsonProperty("content")
    String content() {
        return content;
    }

    /**
     * 获取待办状态。
     *
     * @return 待办状态，保证非 {@code null}
     */
    @JsonProperty("status")
    TodoStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "TodoItem{status=" + status.wireName() + ", content=" + content + '}';
    }
}
