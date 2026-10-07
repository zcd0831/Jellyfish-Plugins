package zcd.jellyfish.plugin.resmon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次磁盘统计的完整结果：各占用项的体积、所在分区的余量，以及相对上一次扫描的增长。
 * <p>
 * <b>为什么把「上一次扫描」的两个字段也带在这里</b>：增长率是这套数据里最有价值的一项——
 * 一个 1.4G 的会话目录本身不说明问题，一小时涨 12M 才是「它迟早会撑满磁盘」。
 * 而算增长率需要上一次的总量与时刻，把它们连同本次结果一起装进同一个不可变对象，
 * 呈现层就不必回头去问采样器「上一份快照是什么」，也就不存在「读到半新半旧的两份数据」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class DiskReport {

    /** 每小时对应的毫秒数，用于把增量换算成「每小时多少字节」。 */
    private static final long MILLIS_PER_HOUR = 3600000L;

    /** 空报告：还没扫过磁盘时用它，各派生值一律为「不可用」。 */
    private static final DiskReport EMPTY = new DiskReport(0L, Collections.<PathUsage>emptyList(),
            0L, -1L, -1L, -1L, -1L);

    /** 本次扫描时刻。 */
    private final long capturedAtMillis;

    /** 各占用项的统计结果，顺序即呈现顺序。 */
    private final List<PathUsage> usages;

    /** 各占用项体积合计。 */
    private final long totalBytes;

    /** 所在分区总容量（字节），负数表示不可用。 */
    private final long partitionTotalBytes;

    /** 所在分区可用容量（字节），负数表示不可用。 */
    private final long partitionUsableBytes;

    /** 上一次扫描的体积合计，负数表示没有上一次。 */
    private final long previousTotalBytes;

    /** 上一次扫描的时刻，非正数表示没有上一次。 */
    private final long previousCapturedAtMillis;

    /**
     * 构造磁盘报告。
     *
     * @param capturedAtMillis        本次扫描时刻
     * @param usages                  各占用项统计结果
     * @param totalBytes              体积合计
     * @param partitionTotalBytes     分区总容量，负数表示不可用
     * @param partitionUsableBytes    分区可用容量，负数表示不可用
     * @param previousTotalBytes      上一次体积合计，负数表示没有上一次
     * @param previousCapturedAtMillis 上一次扫描时刻，非正数表示没有上一次
     */
    private DiskReport(long capturedAtMillis, List<PathUsage> usages, long totalBytes,
                       long partitionTotalBytes, long partitionUsableBytes,
                       long previousTotalBytes, long previousCapturedAtMillis) {
        this.capturedAtMillis = capturedAtMillis;
        this.usages = Collections.unmodifiableList(new ArrayList<PathUsage>(usages));
        this.totalBytes = totalBytes;
        this.partitionTotalBytes = partitionTotalBytes;
        this.partitionUsableBytes = partitionUsableBytes;
        this.previousTotalBytes = previousTotalBytes;
        this.previousCapturedAtMillis = previousCapturedAtMillis;
    }

    /**
     * 取空报告。
     *
     * @return 还没有任何一次扫描时的报告
     */
    static DiskReport empty() {
        return EMPTY;
    }

    /**
     * 构造一次扫描结果，体积合计由各占用项自行相加。
     *
     * @param capturedAtMillis         本次扫描时刻
     * @param usages                   各占用项统计结果，可为 {@code null}
     * @param partitionTotalBytes      分区总容量，负数表示不可用
     * @param partitionUsableBytes     分区可用容量，负数表示不可用
     * @param previousTotalBytes       上一次体积合计，负数表示没有上一次
     * @param previousCapturedAtMillis 上一次扫描时刻，非正数表示没有上一次
     * @return 报告，保证非 {@code null}
     */
    static DiskReport of(long capturedAtMillis, List<PathUsage> usages,
                         long partitionTotalBytes, long partitionUsableBytes,
                         long previousTotalBytes, long previousCapturedAtMillis) {
        List<PathUsage> copy = usages == null ? Collections.<PathUsage>emptyList() : usages;
        long total = 0L;
        for (PathUsage usage : copy) {
            total += usage.bytes();
        }
        return new DiskReport(capturedAtMillis, copy, total, partitionTotalBytes, partitionUsableBytes,
                previousTotalBytes, previousCapturedAtMillis);
    }

    /**
     * 判断是否已经扫过一次盘。
     *
     * @return 扫过返回 {@code true}
     */
    boolean scanned() {
        return capturedAtMillis > 0L;
    }

    /**
     * 获取本次扫描时刻。
     *
     * @return 毫秒时间戳；未扫过时为 0
     */
    long capturedAtMillis() {
        return capturedAtMillis;
    }

    /**
     * 获取各占用项的统计结果。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<PathUsage> usages() {
        return usages;
    }

    /**
     * 获取体积合计。
     *
     * @return 字节数
     */
    long totalBytes() {
        return totalBytes;
    }

    /**
     * 获取分区已用比例。
     *
     * @return 0..100 的百分比；分区容量不可用时返回负数
     */
    double partitionUsedPercent() {
        if (partitionTotalBytes <= 0L || partitionUsableBytes < 0L) {
            return -1.0;
        }
        return (partitionTotalBytes - partitionUsableBytes) * 100.0 / partitionTotalBytes;
    }

    /**
     * 获取分区剩余容量。
     *
     * @return 字节数；不可用时返回负数
     */
    long partitionUsableBytes() {
        return partitionUsableBytes;
    }

    /**
     * 获取分区总容量。
     *
     * @return 字节数；不可用时返回负数
     */
    long partitionTotalBytes() {
        return partitionTotalBytes;
    }

    /**
     * 按上一次扫描折算的增长速度。
     *
     * @return 每小时字节数（可为负，表示变小了）；没有上一次或时间间隔非正时返回负数
     */
    long growthBytesPerHour() {
        if (previousTotalBytes < 0L || previousCapturedAtMillis <= 0L) {
            return -1L;
        }
        long elapsed = capturedAtMillis - previousCapturedAtMillis;
        if (elapsed <= 0L) {
            return -1L;
        }
        return Math.round((totalBytes - previousTotalBytes) * (double) MILLIS_PER_HOUR / elapsed);
    }

    /**
     * 取某个一级子项的统计结果。
     *
     * @param name 子项名
     * @return 统计结果；该项不存在时返回 {@code null}
     */
    PathUsage usageOf(String name) {
        for (PathUsage usage : usages) {
            if (usage.name().equals(name)) {
                return usage;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "DiskReport{at=" + capturedAtMillis + ", total=" + totalBytes + ", items=" + usages.size() + '}';
    }
}
