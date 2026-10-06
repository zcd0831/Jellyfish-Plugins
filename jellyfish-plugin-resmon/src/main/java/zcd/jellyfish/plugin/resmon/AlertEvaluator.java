package zcd.jellyfish.plugin.resmon;

import java.util.ArrayList;
import java.util.List;

/**
 * 阈值判定：把一份采样结果翻成「此刻有哪些项越过了阈值」。
 * <p>
 * <b>它是无状态的，这是上一版的简化结果</b>。上一版告警走外壳通知（一次推送一条），
 * 因此必须做上升沿触发——否则采样每 2 秒一次，一条「堆用了 92%」在几分钟内会变成上百条通知，
 * 而外壳对同一插件来源只保留最近几条，真正重要的那条会被自己刷掉。
 * 现在告警整块显示在面板里（<b>当前状态</b>而不是一次事件），「持续越界只显示一次」由
 * 「同一时刻只渲染一份状态」天然成立，于是那套状态机连同它的收尾逻辑一起消失了。
 * <p>
 * <b>四个判据，各自的阈值理由不同</b>：
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

    /** 堆占用告警标识。 */
    static final String KEY_HEAP = "heap";

    /** 分区占用告警标识。 */
    static final String KEY_DISK = "disk";

    /** 文件描述符告警标识。 */
    static final String KEY_FD = "fd";

    /** 死锁告警标识。 */
    static final String KEY_DEADLOCK = "deadlock";

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
     *
     * @param jvm  JVM 快照，可为 {@code null}（还没有采到）
     * @param disk 磁盘报告，可为 {@code null}
     * @return 越阈值的告警列表，顺序固定（堆 → 死锁 → 文件描述符 → 分区）；没有则空列表
     */
    List<Alert> evaluate(JvmStats jvm, DiskReport disk) {
        List<Alert> alerts = new ArrayList<Alert>(4);
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
    private void addHeap(JvmStats jvm, List<Alert> alerts) {
        double percent = jvm.heapPercent();
        if (percent < 0.0 || percent < heapPercent) {
            return;
        }
        alerts.add(new Alert(KEY_HEAP,
                "堆 " + ResmonFormat.percent(percent) + " 超阈值 " + ResmonFormat.percent(heapPercent),
                "堆已用 " + ResmonFormat.percent(percent) + "（" + ResmonFormat.bytes(jvm.heapUsedBytes())
                        + " / " + ResmonFormat.bytes(jvm.heapMaxBytes()) + "），超过阈值 "
                        + ResmonFormat.percent(heapPercent)));
    }

    /**
     * 判定死锁。
     * <p>
     * 排在堆之后、文件描述符之前：它是四项里唯一「不会自己恢复」的（堆会随 GC 回落、分区会随清理回落），
     * 因此让它在面板上更靠前。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addDeadlock(JvmStats jvm, List<Alert> alerts) {
        long deadlocked = jvm.deadlockedThreadCount();
        if (deadlocked <= 0L) {
            return;
        }
        alerts.add(new Alert(KEY_DEADLOCK,
                "死锁 " + deadlocked + " 个线程",
                "检测到 " + deadlocked + " 个死锁线程——它们不会再前进，只能重启进程"));
    }

    /**
     * 判定文件描述符占用。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addFileDescriptors(JvmStats jvm, List<Alert> alerts) {
        double percent = jvm.fdPercent();
        if (percent < 0.0 || percent < fdPercent) {
            return;
        }
        alerts.add(new Alert(KEY_FD,
                "FD " + ResmonFormat.percent(percent) + " 超阈值 " + ResmonFormat.percent(fdPercent),
                "文件描述符已用 " + ResmonFormat.percent(percent) + "（" + jvm.openFileDescriptorCount()
                        + " / " + jvm.maxFileDescriptorCount() + "），超过阈值 "
                        + ResmonFormat.percent(fdPercent)));
    }

    /**
     * 判定分区占用。
     *
     * @param disk   磁盘报告，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void addDisk(DiskReport disk, List<Alert> alerts) {
        double percent = disk.partitionUsedPercent();
        if (percent < 0.0 || percent < diskPercent) {
            return;
        }
        alerts.add(new Alert(KEY_DISK,
                "磁盘 " + ResmonFormat.percent(percent) + " 超阈值 " + ResmonFormat.percent(diskPercent),
                "磁盘分区已用 " + ResmonFormat.percent(percent) + "，剩 "
                        + ResmonFormat.bytes(disk.partitionUsableBytes())
                        + "，超过阈值 " + ResmonFormat.percent(diskPercent)));
    }
}
