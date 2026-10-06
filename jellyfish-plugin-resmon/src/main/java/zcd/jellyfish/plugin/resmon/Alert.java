package zcd.jellyfish.plugin.resmon;

/**
 * 一条阈值告警：稳定标识 + 两个粒度的文本。
 * <p>
 * <b>为什么两个文本而不是一个</b>：同一件事在两个面上要的长度不一样——面板落在侧栏里，
 * 一行只有二十来列，只能写「堆 92% 超阈值 85%」；而 {@code /resmon} 的明细没有宽度限制，
 * 写「堆已用 92%，超过阈值 85%」更清楚，还能带上剩余量这类上下文。
 * 若只留一份文本，要么面板被折行吃掉两行，要么明细里读到一句压缩到看不懂的话。
 * <p>
 * <b>为什么没有合并键了</b>：合并键是给外壳通知用的（同 key 原地覆盖，避免同一条告警刷屏）。
 * 告警现在整块显示在面板里，是「当前状态」而不是「一次推送」，因此不再需要这个身份。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class Alert {

    /** 告警标识：面板按它排序与去重，取值见 {@link AlertEvaluator} 的常量。 */
    private final String key;

    /** 面板上用的短文本，必须能塞进一行。 */
    private final String panelText;

    /** 命令明细里用的完整文本。 */
    private final String detailText;

    /**
     * 构造告警。
     *
     * @param key        告警标识，不可为空白
     * @param panelText  面板短文本，不可为空白
     * @param detailText 明细完整文本，不可为空白
     */
    Alert(String key, String panelText, String detailText) {
        this.key = key;
        this.panelText = panelText;
        this.detailText = detailText;
    }

    /**
     * 获取告警标识。
     *
     * @return 标识
     */
    String key() {
        return key;
    }

    /**
     * 获取面板短文本。
     *
     * @return 单行短文本
     */
    String panelText() {
        return panelText;
    }

    /**
     * 获取明细完整文本。
     *
     * @return 完整文本
     */
    String detailText() {
        return detailText;
    }

    @Override
    public String toString() {
        return "Alert{" + key + ": " + panelText + '}';
    }
}
