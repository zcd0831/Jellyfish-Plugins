package zcd.jellyfish.plugin.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态分层：给每个步骤算一个层号，同层 = 彼此无依赖 = 可以并行。
 * <p>
 * <b>为什么面板需要它</b>：引擎是<b>运行期</b>才逐层推进的（{@link WorkflowEngine} 每次挑出「依赖都已决」
 * 的那一批），而面板要在一开始就把「哪几步会一起跑、谁在等谁」画出来——那需要的是<b>静态</b>的层号，
 * 而不是此刻已经跑到哪一层。
 * <p>
 * <b>它与引擎的分层口径一致</b>：层号取「最长路径」定义（{@code layer(step) = 1 + max(layer(needs))}），
 * 于是一个步骤只可能等它的依赖，而同层的两个步骤之间不可能有边。这正是「同一层 = 可以并行」
 * 这句话的依据，也是面板把它们画成同一批的前提。
 * <p>
 * <b>为什么不用抛异常来对付环</b>：解析期已经拒了环（{@link WorkflowSpecParser}），真走到这里说明
 * spec 来自别的路径。而一块面板不该因为一份怪 spec 就整块消失——算不动就停在能算出的最大层，
 * 画出来仍然可用。同理，松弛轮数上界取步骤数：即使真有环，也不会在这里转不出来。
 * <p>
 * 无状态，可安全跨线程调用。
 *
 * @author zcd
 */
final class WorkflowLayers {

    /**
     * 工具类，禁止实例化。
     */
    private WorkflowLayers() {
    }

    /**
     * 计算每个步骤的层号。
     * <p>
     * 做法是反复松弛：初始全为 1，每条 {@code needs} 边要求「下游 ≥ 上游 + 1」，推到不动为止。
     * 与按拓扑序一次算完相比，它多花几轮，但不需要先排拓扑序，也就不必再处理一次「排序失败」。
     *
     * @param steps 步骤列表（声明顺序），不可为 {@code null}
     * @return 步骤标识 → 层号（从 1 起），保证非 {@code null}
     */
    static Map<String, Integer> of(List<WorkflowStep> steps) {
        Map<String, Integer> layers = new LinkedHashMap<String, Integer>();
        for (WorkflowStep step : steps) {
            layers.put(step.getId(), Integer.valueOf(1));
        }
        // 上界取步骤数：最长链也不过这么多层，真有环时也保证有限轮内停下来
        int ceiling = Math.max(1, steps.size());
        for (int round = 0; round < steps.size(); round++) {
            boolean changed = false;
            for (WorkflowStep step : steps) {
                int wanted = 1;
                for (String need : step.getNeeds()) {
                    Integer upstream = layers.get(need);
                    if (upstream != null) {
                        // 层号夹在步骤数以内：真有一份带环的 spec 时，数字也不会跑到几百层去
                        wanted = Math.min(ceiling, Math.max(wanted, upstream.intValue() + 1));
                    }
                }
                if (layers.get(step.getId()).intValue() < wanted) {
                    layers.put(step.getId(), Integer.valueOf(wanted));
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return layers;
    }
}
