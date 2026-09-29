package zcd.jellyfish.plugin.todo;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 待办状态：未开始 / 进行中 / 已完成。
 * <p>
 * <b>为什么必须有「进行中」这一态</b>：模型表达「这一项正在做」时用的词是 {@code in_progress}，
 * 两态装不下它——只能写 {@code pending}（看不出在做哪件事）或 {@code completed}（谎报完成）。
 * 而此前这个取值被当成非法参数<b>整批拒掉</b>：整表覆盖是一次原子写入，同一批里已经标成
 * {@code completed} 的项跟着一起丢，界面于是停在旧状态上，表现就是「待办状态不会及时更新」。
 * 放宽到三态正是为了让模型能如实说话。
 * <p>
 * <b>线名与标记定义在同一处</b>：{@code wireName} 是模型与文件里写的取值，{@code mark} 是清单里
 * 那一对方括号。两者与状态一一对应，放在一起才不会出现「模型写 in_progress、界面画 {@code [ ]}」
 * 这种漂移（{@link TodoText} 里的渲染与 {@link TodoWriteTool} 的解析因此共用这份定义）。
 * <p>
 * <b>读时归一化、写时规范</b>：读的时候容忍大小写与连字符（{@code In-Progress} / {@code in-progress}
 * 都认），写出去的一律是小写下划线。模型手上打成什么样，不该决定文件里存的是什么。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
enum TodoStatus {

    /** 未开始：已经列进计划但还没轮到它。 */
    PENDING("pending", "[ ] "),

    /** 进行中：此刻正在做的就是这一项。 */
    IN_PROGRESS("in_progress", "[~] "),

    /** 已完成。 */
    COMPLETED("completed", "[x] ");

    /** 模型与待办文件里使用的取值。 */
    private final String wireName;

    /** 清单里的前置标记（含尾随空格，直接拼在内容前面）。 */
    private final String mark;

    /**
     * 构造状态。
     *
     * @param wireName 对外的取值
     * @param mark     清单标记
     */
    TodoStatus(String wireName, String mark) {
        this.wireName = wireName;
        this.mark = mark;
    }

    /**
     * 取对外取值，同时作为 Jackson 的序列化形态。
     * <p>
     * 不标注的话枚举会按名字序列化成 {@code IN_PROGRESS}，与模型读写用的词不一致；
     * 文件是要被人读、被 diff 的，它必须与模型看到的是同一个词。
     *
     * @return 取值文本，形如 {@code in_progress}
     */
    @JsonValue
    String wireName() {
        return wireName;
    }

    /**
     * 取清单标记。
     *
     * @return 标记文本，形如 {@code "[x] "}
     */
    String mark() {
        return mark;
    }

    /**
     * 按取值解析状态，忽略大小写与连字符。
     *
     * @param text 取值原值，可为 {@code null}（按未开始处理）
     * @return 解析出的状态；无法识别时返回 {@code null}（由调用点决定怎么报错）
     */
    static TodoStatus ofWireName(String text) {
        if (text == null) {
            return PENDING;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (TodoStatus status : values()) {
            if (status.wireName.equals(normalized)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 拼出全部允许取值，供报错消息使用。
     * <p>
     * 从枚举自身拼而不是写死一句文案：将来加状态时不会漏改消息，模型看到的允许集合与代码永远一致。
     *
     * @return 形如 {@code pending、in_progress 或 completed} 的文本
     */
    static String allowedNames() {
        TodoStatus[] statuses = values();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) {
                text.append(i == statuses.length - 1 ? " 或 " : "、");
            }
            text.append(statuses[i].wireName);
        }
        return text.toString();
    }
}
