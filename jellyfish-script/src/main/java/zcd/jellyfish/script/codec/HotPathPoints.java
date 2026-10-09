package zcd.jellyfish.script.codec;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 热路径扩展点：<b>每次组装请求都会被问到</b>的那些点。
 * <p>
 * <b>它们为什么需要特殊对待</b>：脚本调用是一次进程往返，而网关是懒启动的——首次调用要抽资源、
 * fork 解释器、等全部 worker 就绪，几百毫秒起。那笔开销放在「每个回合一次」「每次用户提交」
 * 上是可接受的，放在「每次发给厂商之前」上是不可接受的：一个长任务跑 20 轮，就是 20 次落在
 * 关键路径上的往返。
 * <p>
 * 因此桥接层对这几个点做两件事（见 {@code ScriptGateway.call}）：
 * <ul>
 *     <li><b>不冷启动</b>：worker 没热着就抛 {@code ScriptNotHandledException}（「不表态」），
 *     调用点走保守缺省。
 *     代价是「只提供热路径贡献、别的一个点都不提供」的脚本不会被拉起——那种脚本本来就不值得
 *     一次冷启动，但这是一个需要写进文档的、真实的行为边界。同一条边界还有一个后果：
 *     这类脚本<b>也不会因此被熔断</b>（「不表态」既不算成功也不算失败），
 *     它的可观测点是 {@code /<语言>} 台账里的「未启动」；</li>
 *     <li><b>更短的截止时间</b>：热着的往返是毫秒级，给 2 秒足够；超时不杀 worker
 *     （网关自己的截止时间会收），但仍上报失败，好让熔断能在某个脚本持续拖慢时把它关掉。</li>
 * </ul>
 * <b>为什么这份名单在 Java 侧而不是读能力档</b>：它是<b>桥接的执行策略</b>（「允许不允许冷启动」），
 * 不是「有哪些扩展点」这件事。与能力档的一致性由测试守着（{@code ExtensionPointCoverageTest}
 * 断言这里的每个名字都是已注册的扩展点、且能力档里标了 {@code hotPath}）。
 * <p>
 * 工具类，禁止实例化。
 *
 * @author zcd
 */
public final class HotPathPoints {

    /** 每次组装请求都会被问到的扩展点。 */
    private static final Set<String> TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    RequestTuningCodec.TYPE_NAME,
                    AgingStrategyCodec.TYPE_NAME)));

    /**
     * 工具类，禁止实例化。
     */
    private HotPathPoints() {
    }

    /**
     * 判断一个扩展点是不是热路径点。
     *
     * @param typeName 扩展点类型名，可为 {@code null}
     * @return 是热路径点返回 {@code true}
     */
    public static boolean contains(String typeName) {
        return typeName != null && TYPES.contains(typeName);
    }

    /**
     * 获取全部热路径扩展点类型名。
     *
     * @return 不可变集合
     */
    public static Set<String> names() {
        return TYPES;
    }
}
