package zcd.jellyfish.plugin.workflow;

/**
 * 一个步骤此刻处于什么状态：面板据此决定标记与强调档位。
 * <p>
 * <b>标记用 ASCII 而不是符号</b>：与 {@code jellyfish-plugin-todo} 的清单同一口径——终端字体与
 * East Asian Width 的差异会让 {@code ✓} / {@code ▶} 在不同环境里占 1 格或 2 格，
 * 面板的列宽由外壳算，插件给宽字符会让它算错。
 *
 * @author zcd
 */
enum StepState {

    /** 还没轮到它。 */
    PENDING("[ ] "),

    /** 正在跑。 */
    RUNNING("[~] "),

    /** 成功了。 */
    DONE("[x] "),

    /** 跑过但失败了。 */
    FAILED("[!] "),

    /** 静态条件不满足，没跑（不是失败）。 */
    SKIPPED("[-] ");

    /** 面板里的行首标记。 */
    private final String mark;

    /**
     * 构造状态。
     *
     * @param mark 行首标记
     */
    StepState(String mark) {
        this.mark = mark;
    }

    /**
     * 获取行首标记。
     *
     * @return 标记，保证非空白
     */
    String mark() {
        return mark;
    }
}
