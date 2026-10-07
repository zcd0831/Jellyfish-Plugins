package zcd.jellyfish.plugin.resmon;

import java.util.ArrayList;
import java.util.List;

/**
 * 阈值判定：把一份采样结果翻成「此刻有哪些项越过了阈值」，每项一句给人看的话。
 * <p>
 * <b>它产出的是文本而不是某种告警对象</b>：产物只有两个去处——面板把越阈值的那一行转成警示档位
 * （那由面板自己按同一批阈值判定，不需要这里给对象），以及 {@code /resmon} 的告警段逐行列出来。
 * 既然没有人需要「告警的标识」，就不该有一个只为承载标识而存在的类型。
 * <p>
 * <b>它是无状态的</b>：告警是「当前状态」而不是一次事件，因此不需要「只报一次」的记账
 * ——同一时刻只渲染一份状态天然成立，回落之后再越界也自然重新出现。
 * <p>
 * <b>四项判据，各自的阈值理由不同</b>：
 * <ul>
 *     <li><b>堆</b>与<b>分区</b>：比例阈值可配——不同机器对「还剩多少算危险」的容忍度差别很大
 *     （系统盘只剩 5 G 与数据盘只剩 5 G 完全是两件事）；</li>
 *     <li><b>文件描述符</b>：阈值可配，因为它跟 {@code ulimit} 的关系比跟「用了多少」更紧；</li>
 *     <li><b>死锁</b>：固定判据「大于 0」。死锁不是多与少的问题——出现一个就代表有线程再也不会前进，
 *     没有需要用户自己调的空间。</li>
 * </ul>
 * <p>
 * 不可变，可安全跨线程传递（无状态）。
 *
 * @author zcd
 */
final class AlertEvaluator {

    /** 堆占用阈值。 */
    private final double heapPercent;

    /** 分区占用阈值。 */
    private final double diskPercent;

    /** 文件描述符占用阈值。 */
    private final double fdPercent;

    /**
     * 按配置构造判定器。
     *
     * @param config 插件配置，不可为 {@code null}
     */
    AlertEvaluator(PluginConfig config) {
        this.heapPercent = config.alertHeapPercent();
        this.diskPercent = config.alertDiskPercent();
        this.fdPercent = config.alertFdPercent();
    }

    /**
     * 判定此刻所有越阈值的项。
     * <p>
     * 顺序固定为堆 → 死锁 → 文件描述符 → 分区：死锁排在最前是因为它是四项里唯一
     * 「不会自己恢复」的（堆会随 GC 回落、分区会随清理回落、文件描述符会随连接关闭回落），
     * 因此它更该被先看到。
     *
     * @param jvm  JVM 快照，可为 {@code null}（还没有采到）
     * @param disk 磁盘报告，可为 {@code null}
     * @return 越阈值的告警文本列表，没有则空列表；保证非 {@code null}
     */
    List<String> evaluate(JvmStats jvm, DiskReport disk) {
        List<String> alerts = new ArrayList<String>(4);
        if (jvm != null) {
            addHeap(jvm, alerts);
            addDeadlock(jvm, alerts);
            addFileDescriptors(jvm, alerts);
        }
        if (disk != null && disk.scanned()) {
            addDisk(disk, alerts);
        }
        return alerts;
    }

    /**
     * 判定堆占用。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addHeap(JvmStats jvm, List<String> alerts) {
        double percent = jvm.heapPercent();
        if (percent < 0.0 || percent < heapPercent) {
            return;
        }
        alerts.add("堆已用 " + ResmonFormat.percent(percent) + "（" + ResmonFormat.bytes(jvm.heapUsedBytes())
                + " / " + ResmonFormat.bytes(jvm.heapMaxBytes()) + "），超过阈值 "
                + ResmonFormat.percent(heapPercent));
    }

    /**
     * 判定死锁。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addDeadlock(JvmStats jvm, List<String> alerts) {
        long deadlocked = jvm.deadlockedThreadCount();
        if (deadlocked <= 0L) {
            return;
        }
        alerts.add("检测到 " + deadlocked + " 个死锁线程——它们不会再前进，只能重启进程");
    }

    /**
     * 判定文件描述符占用。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addFileDescriptors(JvmStats jvm, List<String> alerts) {
        double percent = jvm.fdPercent();
        if (percent < 0.0 || percent < fdPercent) {
            return;
        }
        alerts.add("文件描述符已用 " + ResmonFormat.percent(percent) + "（" + jvm.openFileDescriptorCount()
                + " / " + jvm.maxFileDescriptorCount() + "），超过阈值 " + ResmonFormat.percent(fdPercent));
    }

    /**
     * 判定分区占用。
     *
     * @param disk   磁盘报告，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addDisk(DiskReport disk, List<String> alerts) {
        double percent = disk.partitionUsedPercent();
        if (percent < 0.0 || percent < diskPercent) {
            return;
        }
        alerts.add("磁盘分区已用 " + ResmonFormat.percent(percent) + "，剩 "
                + ResmonFormat.bytes(disk.partitionUsableBytes())
                + "，超过阈值 " + ResmonFormat.percent(diskPercent));
    }
}
