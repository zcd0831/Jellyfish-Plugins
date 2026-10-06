package zcd.jellyfish.plugin.resmon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阈值判定：把一份采样结果翻成「这次要不要提醒用户」，并且只在<b>越过阈值的那一刻</b>提醒一次。
 * <p>
 * <b>为什么必须做边沿触发</b>：采样每 2 秒一次，若「越界就报」会让一条「堆用了 92%」在几分钟内
 * 变成上百条通知。外壳对同一插件来源只保留最近几条通知，于是真正重要的那条会被自己刷掉；
 * 而如果靠外壳去重，用户就又要在每个外壳里实现一次同样的判断。所以在源头只报上升沿。
 * <p>
 * <b>为什么不报「恢复了」</b>：本插件的通知按约定只推 {@code WARN} 一级；而「恢复正常」是中性信息，
 * 用它去占一条 WARN 位置会让告警本身贬值。恢复与否在面板与 {@code /resmon} 上都看得见——
 * 那才是持续状态的正确去处。
 * <p>
 * <b>回落要清掉标记</b>：否则「越界 → 恢复 → 再次越界」只有第一次会响，而第二次往往才是真的
 * 需要人处理（例如第一次是压测、第二次是内存泄漏）。这也是本类唯一的状态。
 * <p>
 * 实现假定它只被采样线程一次调用一次地使用（不并发）。
 *
 * @author zcd
 */
final class AlertEvaluator {

    /** 堆占用告警键。 */
    static final String KEY_HEAP = "resmon.heap";

    /** 分区占用告警键。 */
    static final String KEY_DISK = "resmon.disk";

    /** 文件描述符告警键。 */
    static final String KEY_FD = "resmon.fd";

    /** 死锁告警键。 */
    static final String KEY_DEADLOCK = "resmon.deadlock";

    /** 堆占用阈值。 */
    private final double heapPercent;

    /** 分区占用阈值。 */
    private final double diskPercent;

    /** 文件描述符占用阈值。 */
    private final double fdPercent;

    /** 当前处于「已越界」状态的告警键。 */
    private final Set<String> firing = new HashSet<String>();

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
     * 判定本轮采样需要新发出的告警。
     *
     * @param jvm  JVM 快照，可为 {@code null}（还没有采到）
     * @param disk 磁盘报告，可为 {@code null}
     * @return 新越过阈值的告警列表，没有则空列表；保证非 {@code null}
     */
    List<Alert> evaluate(JvmStats jvm, DiskReport disk) {
        List<Alert> alerts = new ArrayList<Alert>();
        if (jvm != null) {
            evaluateHeap(jvm, alerts);
            evaluateFileDescriptors(jvm, alerts);
            evaluateDeadlock(jvm, alerts);
        }
        if (disk != null && disk.scanned()) {
            evaluateDisk(disk, alerts);
        }
        return alerts;
    }

    /**
     * 判定堆占用。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void evaluateHeap(JvmStats jvm, List<Alert> alerts) {
        double percent = jvm.heapPercent();
        if (fire(KEY_HEAP, percent >= 0.0 && percent >= heapPercent)) {
            alerts.add(new Alert(KEY_HEAP, "堆已用 " + ResmonFormat.percent(percent)
                    + "，超过阈值 " + ResmonFormat.percent(heapPercent)));
        }
    }

    /**
     * 判定文件描述符占用。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void evaluateFileDescriptors(JvmStats jvm, List<Alert> alerts) {
        double percent = jvm.fdPercent();
        if (fire(KEY_FD, percent >= 0.0 && percent >= fdPercent)) {
            alerts.add(new Alert(KEY_FD, "文件描述符已用 " + ResmonFormat.percent(percent)
                    + "（" + jvm.openFileDescriptorCount() + "/" + jvm.maxFileDescriptorCount()
                    + "），超过阈值 " + ResmonFormat.percent(fdPercent)));
        }
    }

    /**
     * 判定死锁。
     * <p>
     * 阈值固定为「大于 0」：死锁不是「多与少」的问题，出现一个就代表有线程再也不会前进。
     *
     * @param jvm    快照，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void evaluateDeadlock(JvmStats jvm, List<Alert> alerts) {
        long deadlocked = jvm.deadlockedThreadCount();
        if (fire(KEY_DEADLOCK, deadlocked > 0L)) {
            alerts.add(new Alert(KEY_DEADLOCK, "检测到 " + deadlocked + " 个死锁线程"));
        }
    }

    /**
     * 判定分区占用。
     *
     * @param disk   磁盘报告，不可为 {@code null}
     * @param alerts 结果收集列表，不可为 {@code null}
     */
    private void evaluateDisk(DiskReport disk, List<Alert> alerts) {
        double percent = disk.partitionUsedPercent();
        if (fire(KEY_DISK, percent >= 0.0 && percent >= diskPercent)) {
            alerts.add(new Alert(KEY_DISK, "磁盘分区已用 " + ResmonFormat.percent(percent)
                    + "，剩 " + ResmonFormat.bytes(disk.partitionUsableBytes())
                    + "，超过阈值 " + ResmonFormat.percent(diskPercent)));
        }
    }

    /**
     * 记录一次判定，并回答「这次是否要发出去」。
     *
     * @param key      告警键，不可为 {@code null}
     * @param exceeded 本轮是否越界
     * @return 本次是上升沿返回 {@code true}
     */
    private boolean fire(String key, boolean exceeded) {
        if (!exceeded) {
            firing.remove(key);
            return false;
        }
        return firing.add(key);
    }
}
