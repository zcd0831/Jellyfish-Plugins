package zcd.jellyfish.plugin.todo;

/**
 * 一次待办动作的结果：认领或完成。
 * <p>
 * <b>为什么两个操作用一枚结果码</b>：它们都是「对某一条待办做一次动作」，失败的理由也就那几种
 * （没有这一条、这一条是别人的、没有可认领的）。分开造两套结果类型只会让两个工具各自翻译一遍同样的分支，
 * 而消息文本仍留在各自的工具里——那里才知道该说「已认领」还是「已完成」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoActionResult {

    /**
     * 结果码。
     */
    enum Code {

        /** 成功：认领成功（含「你之前就已认领」）或已完成。 */
        OK,

        /** 指名的那一条待办不存在。 */
        NOT_FOUND,

        /** 那一条已被别的 run 认领。 */
        TAKEN,

        /** 没有可认领的待办（只剩认领会产生这个结果）。 */
        NONE_PENDING
    }

    /** 结果码。 */
    private final Code code;

    /** 动作作用到的那一条；失败时为 {@code null}。 */
    private final TodoItem item;

    /**
     * 构造结果。
     *
     * @param code 结果码，不可为 {@code null}
     * @param item 动作作用到的那一条，可为 {@code null}
     */
    private TodoActionResult(Code code, TodoItem item) {
        this.code = code;
        this.item = item;
    }

    /**
     * 构造成功结果。
     *
     * @param item 动作作用到的那一条，不可为 {@code null}
     * @return 结果，保证非 {@code null}
     */
    static TodoActionResult ok(TodoItem item) {
        return new TodoActionResult(Code.OK, item);
    }

    /**
     * 构造失败结果。
     *
     * @param code 结果码，不可为 {@code null} 且不可为 {@link Code#OK}
     * @return 结果，保证非 {@code null}
     */
    static TodoActionResult failed(Code code) {
        return new TodoActionResult(code, null);
    }

    /**
     * 获取结果码。
     *
     * @return 结果码，保证非 {@code null}
     */
    Code getCode() {
        return code;
    }

    /**
     * 获取动作作用到的那一条待办。
     *
     * @return 待办；失败时为 {@code null}
     */
    TodoItem getItem() {
        return item;
    }

    /**
     * 判断是否成功。
     *
     * @return 成功返回 {@code true}
     */
    boolean isOk() {
        return code == Code.OK;
    }
}
