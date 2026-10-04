package zcd.jellyfish.plugin.pet;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板贡献处理器：把这只宠物画成一块常驻面板。
 * <p>
 * 版面是「一张脸 + 两行身体 + 一行形态」：
 * <pre>
 * 宠物
 *  ▄▄▄▄▄▄▄▄
 * █ ●    ● █
 * █   ▽▽   █
 *  ▀▀▀▀▀▀▀▀
 * 疲惫 ▓▓▓░░ 2h14m
 * 肥胖 ▓▓▓▓▓ 1.2M
 * 形态 夜行 · 警惕
 * </pre>
 * 「宠物」是面板标题（由外壳画在边框上），因此内容行恰好 7 行——外壳的内容行上限是 8，
 * 留一行余量：这一版之后若想加一行，不必先把别的东西砍掉。
 * <p>
 * <b>每行都带绝对值</b>：条只表达幅度，{@code ▓▓▓░░} 读得出「一半」读不出「1h47m」。
 * 因此条与数字必须同时在场，这不是可以省的装饰。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：本处理器<b>完全合规</b>——只从内存台账取一只宠物、
 * 拼几行文本，不碰文件、不起进程、不发失效事件（那会形成「失效 → 收集 → 失效」的死循环；
 * 失效由事件回调在宠物变化后发）。台账的读路径也不养新的宠物。
 * <p>
 * <b>没有经历时返回空贡献</b>：空贡献等于「不占区域、不进 {@code /ui} 候选」，
 * 因此刚开的会话不会凭空多出一块面板，也不会把右栏从别的观测面板手里抢走。
 * <p>
 * 无状态（只持有台账），可安全跨线程调用。
 *
 * @author zcd
 */
final class PetPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    static final String TITLE = "宠物";

    /** 疲惫行的标签。 */
    static final String LABEL_FATIGUE = "疲惫";

    /** 肥胖行的标签。 */
    static final String LABEL_OBESITY = "肥胖";

    /** 形态行的标签。 */
    static final String LABEL_POSTURE = "形态";

    /** 夜行形态名。 */
    static final String NIGHT_WALKER = "夜行";

    /** 白天形态名。 */
    static final String DAY_WALKER = "昼行";

    /** 标签与值之间的分隔。 */
    private static final String SEPARATOR = " ";

    /** 形态行里各形态之间的分隔。 */
    private static final String JOINT = " · ";

    /** 面板建议落位。 */
    private static final UiRegion REGION = UiRegion.RIGHT;

    /** 采样台账。 */
    private final PetStore store;

    /**
     * 构造处理器。
     *
     * @param store 采样台账，不可为 {@code null}
     */
    PetPanel(PetStore store) {
        this.store = store;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        SessionPet pet = store.existing(request.getSessionId());
        if (pet == null) {
            return PanelContribution.empty();
        }
        Posture posture = pet.posture();
        List<UiLine> lines = new ArrayList<UiLine>(7);
        for (String row : posture.sprite()) {
            lines.add(UiLine.of(UiSegment.of(row, spriteEmphasis(posture))));
        }
        lines.add(barLine(LABEL_FATIGUE, pet.fatigueRatio(), PetRender.duration(pet.fatigueMillis()),
                pet.isTired()));
        // 肥胖是个事实而不是警报：它只说明烧了多少，该不该心疼由人自己判断
        lines.add(barLine(LABEL_OBESITY, pet.obesityRatio(), PetRender.tokens(pet.totalTokens()), false));
        lines.add(postureLine(pet, posture));
        return PanelContribution.of(TITLE, lines, REGION);
    }

    /**
     * 精灵的强调档位：受伤与警惕用强调档（它需要被看见），疲惫用次要档（它需要被察觉但不打扰），
     * 寻常用常规档。
     *
     * @param posture 姿态
     * @return 强调档位，保证非 {@code null}
     */
    private static UiEmphasis spriteEmphasis(Posture posture) {
        switch (posture) {
            case WOUNDED:
            case WARY:
                return UiEmphasis.WARN;
            case TIRED:
                return UiEmphasis.DIM;
            default:
                return UiEmphasis.NORMAL;
        }
    }

    /**
     * 拼一行「标签 + 条 + 数值」。
     * <p>
     * 值在「到了该注意的是」转警示档：{@code ▓▓▓▓░} 与 {@code ▓▓▓▓▓} 的差别需要旁边的数字和档位
     * 一起说清。条本身固定次要档——它是背景刻度，抢眼的是那个数字。
     *
     * @param label   标签
     * @param ratio   比例
     * @param value   行尾数值文本
     * @param warning 是否用警示档
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine barLine(String label, double ratio, String value, boolean warning) {
        return UiLine.of(UiSegment.of(label + SEPARATOR),
                UiSegment.of(PetRender.bar(ratio), UiEmphasis.DIM),
                UiSegment.of(SEPARATOR + value, warning ? UiEmphasis.WARN : UiEmphasis.NORMAL));
    }

    /**
     * 拼形态行：昼夜 + 姿态。
     * <p>
     * 昼夜总是写出来（{@code 昼行} 也是一个事实），因此这一行永远有话可说；姿态只在不是寻常时才追加。
     * 只写姿态不写昼夜的话，「寻常」与「这一天还没开始」在屏幕上会长得一样。
     *
     * @param pet     宠物
     * @param posture 当前姿态
     * @return 界面行，保证非 {@code null}
     */
    private static UiLine postureLine(SessionPet pet, Posture posture) {
        StringBuilder text = new StringBuilder(pet.isNight() ? NIGHT_WALKER : DAY_WALKER);
        if (!posture.isOrdinary()) {
            text.append(JOINT).append(posture.label());
        }
        boolean attention = !posture.isOrdinary();
        return UiLine.of(UiSegment.of(LABEL_POSTURE + SEPARATOR),
                UiSegment.of(text.toString(), attention ? UiEmphasis.WARN : UiEmphasis.DIM));
    }
}
