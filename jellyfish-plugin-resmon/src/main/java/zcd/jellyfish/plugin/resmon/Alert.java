package zcd.jellyfish.plugin.resmon;

/**
 * 一条阈值告警：稳定的合并键 + 给人看的一行文本。
 * <p>
 * <b>为什么键与文本要分开</b>：键是「同一条告警」的身份，外壳按它把后到的通知原地覆盖先到的
 * （否则堆占用越界每 2 秒推一条，通知区立刻被同一条消息刷满，而每条都占一个位置）；
 * 文本是那一轮的读数与阈值，每次都不同。合并键若也用文本，就永远合并不上。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class Alert {

    /** 告警键。 */
    private final String key;

    /** 告警文本，单行。 */
    private final String text;

    /**
     * 构造告警。
     *
     * @param key  稳定的合并键，不可为空白
     * @param text 单行文本，不可为空白
     */
    Alert(String key, String text) {
        this.key = key;
        this.text = text;
    }

    /**
     * 获取合并键。
     *
     * @return 合并键
     */
    String key() {
        return key;
    }

    /**
     * 获取告警文本。
     *
     * @return 单行文本
     */
    String text() {
        return text;
    }

    @Override
    public String toString() {
        return "Alert{" + key + ": " + text + '}';
    }
}
