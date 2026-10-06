package zcd.jellyfish.plugin.resmon;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

/**
 * 基于 JMX（{@code java.lang.management}）的 JVM 采样实现。
 * <p>
 * <b>为什么用 {@code com.sun.management} 的两个扩展接口</b>：CPU 使用率与文件描述符计数只在这里。
 * 前者是「系统到底忙不忙」的唯一来源；后者是这套指标里最容易被忽略、又最能解释「越跑越卡」的一项
 * ——内核对每个会话都可能起子进程（shell / mcp / 脚本桥接），fd 泄漏的表现是运行几小时后
 * 连打开文件都失败，而在此之前所有别的指标都还很健康。取不到时统一返回「不可用」，
 * 界面显示 {@link ResmonFormat#UNKNOWN}，而不是假装成 0。
 * <p>
 * <b>两处刻意的降频，都是为了「采样不能反过来影响被采样的进程」</b>：
 * <ul>
 *     <li><b>死锁检测每 {@value #DEADLOCK_CHECK_INTERVAL} 次采样做一次</b>：
 *     {@code findDeadlockedThreads()} 在 HotSpot 上要触发一次全线程 dump，代价与「每 2 秒一次」
 *     完全不匹配；而死锁一旦发生就不会自己消失，30 秒的发现延迟没有任何实际损失；</li>
 *     <li><b>GC 读数只在两次采样之间做差</b>：累计值随进程运行单调增长，
 *     把它直接放进快照会让面板显示一个只会变大的数，看不出当下是否在频繁 GC。</li>
 * </ul>
 * <p>
 * <b>{@link #probe()} 是同步的</b>：它要读写「上一次读数」。采样器只从自己那一个后台线程调用它，
 * 这把锁因此不会有竞争，存在只是为了让「万一被两个线程同时调用」不至于给出错乱的增量。
 *
 * @author zcd
 */
final class ManagementJvmProbe implements JvmProbe {

    /** 死锁检测的采样间隔倍数。 */
    static final int DEADLOCK_CHECK_INTERVAL = 15;

    /** 元空间内存池名（HotSpot 自 JDK 8 起稳定）。 */
    private static final String POOL_METASPACE = "Metaspace";

    /** 压缩类空间内存池名。 */
    private static final String POOL_COMPRESSED_CLASS_SPACE = "Compressed Class Space";

    /** 代码缓存内存池名。 */
    private static final String POOL_CODE_CACHE = "Code Cache";

    /**
     * 判定「这是老年代收集器」的名字片段。
     * <p>
     * JDK 没有给出 GC 类型的标准 API，因此只能按名字判断。这几条片段覆盖了 HotSpot 上全部常见组合：
     * {@code G1 Old Generation}、{@code PS MarkSweep}、{@code MarkSweepCompact}、
     * {@code ConcurrentMarkSweep}。判断不出来的一律算年轻代——把老年代误算成年轻代只会让
     * 明细少一行，反过来则会让面板报出并不存在的 Full GC。
     */
    private static final String[] OLD_GENERATION_MARKERS =
            {"old", "marksweep", "mark sweep", "concurrent", "global"};

    /** 内存用量。 */
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    /** 线程用量与死锁检测。 */
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    /** 类加载计数。 */
    private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();

    /** 运行时长。 */
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

    /** 垃圾收集器列表。 */
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();

    /** 内存池列表。 */
    private final List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    /** 操作系统信息。 */
    private final OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();

    /** 上一次采样的时刻；负数表示还没采过。 */
    private long lastProbeMillis = -1L;

    /** 上一次年轻代 GC 次数。 */
    private long lastYoungCount;

    /** 上一次年轻代 GC 耗时。 */
    private long lastYoungTime;

    /** 上一次老年代 GC 次数。 */
    private long lastOldCount;

    /** 上一次老年代 GC 耗时。 */
    private long lastOldTime;

    /** 距离下一次死锁检测还剩几次采样。 */
    private int samplesUntilDeadlockCheck;

    /** 最近一次死锁检测的结果。 */
    private long lastDeadlocked;

    @Override
    public synchronized JvmStats probe() {
        long now = System.currentTimeMillis();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        GcDelta delta = gcDelta(now);

        JvmStats.Builder builder = JvmStats.builder()
                .capturedAt(now)
                .uptime(runtime.getUptime())
                .heap(heap.getUsed(), heap.getCommitted(), heap.getMax())
                .nonHeap(nonHeap.getUsed())
                .memoryPools(poolUsage(POOL_METASPACE), poolUsage(POOL_COMPRESSED_CLASS_SPACE),
                        poolUsage(POOL_CODE_CACHE))
                .gc(delta.youngCount, delta.youngTime, delta.oldCount, delta.oldTime, delta.elapsedMillis)
                .threads(threads.getThreadCount(), threads.getPeakThreadCount(), threads.getDaemonThreadCount())
                .deadlocked(deadlockedThreads())
                .loadedClasses(classes.getLoadedClassCount())
                .availableProcessors(operatingSystem.getAvailableProcessors());
        readOperatingSystem(builder);
        return builder.build();
    }

    /**
     * 计算本次采样相对上一次的 GC 增量，并把当前读数记成下一次的基线。
     *
     * @param now 本次采样时刻
     * @return 增量，保证非 {@code null}
     */
    private GcDelta gcDelta(long now) {
        long youngCount = 0L;
        long youngTime = 0L;
        long oldCount = 0L;
        long oldTime = 0L;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = Math.max(collector.getCollectionCount(), 0L);
            long time = Math.max(collector.getCollectionTime(), 0L);
            if (isOldGeneration(collector.getName())) {
                oldCount += count;
                oldTime += time;
            } else {
                youngCount += count;
                youngTime += time;
            }
        }
        GcDelta delta = new GcDelta(delta(youngCount, lastYoungCount), delta(youngTime, lastYoungTime),
                delta(oldCount, lastOldCount), delta(oldTime, lastOldTime),
                lastProbeMillis < 0L ? 0L : now - lastProbeMillis);
        lastProbeMillis = now;
        lastYoungCount = youngCount;
        lastYoungTime = youngTime;
        lastOldCount = oldCount;
        lastOldTime = oldTime;
        return delta;
    }

    /**
     * 求一次增量的差值。
     *
     * @param current 本次读数
     * @param last    上次读数
     * @return 差值；首次采样（上次读数为 0 且本次也还没建立基线）或计数器回绕时为 0
     */
    private long delta(long current, long last) {
        if (lastProbeMillis < 0L) {
            return 0L;
        }
        // 计数器理论上不回绕，但被重置（例如 JMX 重连）时会出现负数，报 0 比报一个负的 GC 次数好
        return Math.max(current - last, 0L);
    }

    /**
     * 判断某个收集器是否属于老年代。
     *
     * @param name 收集器名字，可为 {@code null}
     * @return 属于老年代返回 {@code true}
     */
    private static boolean isOldGeneration(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String marker : OLD_GENERATION_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取某个内存池的已用量。
     *
     * @param poolName 内存池名
     * @return 已用字节数；池不存在或取不到用量时为 {@link JvmStats#UNKNOWN}
     */
    private long poolUsage(String poolName) {
        for (MemoryPoolMXBean pool : pools) {
            if (poolName.equals(pool.getName())) {
                MemoryUsage usage = pool.getUsage();
                return usage == null ? JvmStats.UNKNOWN : usage.getUsed();
            }
        }
        return JvmStats.UNKNOWN;
    }

    /**
     * 按降频节奏做一次死锁检测。
     *
     * @return 死锁线程数
     */
    private long deadlockedThreads() {
        if (samplesUntilDeadlockCheck > 0) {
            samplesUntilDeadlockCheck--;
            return lastDeadlocked;
        }
        samplesUntilDeadlockCheck = DEADLOCK_CHECK_INTERVAL - 1;
        try {
            long[] ids = threads.findDeadlockedThreads();
            lastDeadlocked = ids == null ? 0L : ids.length;
        } catch (RuntimeException e) {
            // 某些 JVM 不支持同步器死锁检测：报「没有死锁」比让整次采样失败更合适
            lastDeadlocked = 0L;
        }
        return lastDeadlocked;
    }

    /**
     * 读取 CPU 与文件描述符用量。
     * <p>
     * 扩展接口不存在（非 HotSpot 系 JVM）时什么都不设，各字段保持「不可用」。
     *
     * @param builder 建造者，不可为 {@code null}
     */
    private void readOperatingSystem(JvmStats.Builder builder) {
        if (!(operatingSystem instanceof com.sun.management.OperatingSystemMXBean)) {
            return;
        }
        com.sun.management.OperatingSystemMXBean extended =
                (com.sun.management.OperatingSystemMXBean) operatingSystem;
        builder.cpu(percent(extended.getProcessCpuLoad()), percent(extended.getSystemCpuLoad()));
        if (extended instanceof com.sun.management.UnixOperatingSystemMXBean) {
            com.sun.management.UnixOperatingSystemMXBean unix =
                    (com.sun.management.UnixOperatingSystemMXBean) extended;
            builder.fileDescriptors(unix.getOpenFileDescriptorCount(), unix.getMaxFileDescriptorCount());
        }
    }

    /**
     * 把 JMX 的 0..1 负载值换算成百分比。
     * <p>
     * <b>NaN 必须并进「不可用」</b>：{@code getSystemCpuLoad()} 在还没有两个采样点可比较时返回
     * {@code NaN}，而 {@code NaN < 0} 与 {@code NaN >= 0} 同时为假——漏掉这一条，
     * 面板上会出现 {@code NaN%}（或者更糟：被 {@code Math.round} 悄悄变成 {@code 0%}，
     * 让一台满载的机器看起来空闲）。
     *
     * @param load 负载值
     * @return 0..100 的百分比；负值与 NaN（都表示不可用）一律返回 {@link JvmStats#UNKNOWN_PERCENT}
     */
    private static double percent(double load) {
        if (Double.isNaN(load) || load < 0.0) {
            return JvmStats.UNKNOWN_PERCENT;
        }
        return load * 100.0;
    }

    /**
     * 一次 GC 增量的四个读数与采样间隔。
     *
     * @author zcd
     */
    private static final class GcDelta {

        /** 年轻代次数增量。 */
        private final long youngCount;

        /** 年轻代耗时增量。 */
        private final long youngTime;

        /** 老年代次数增量。 */
        private final long oldCount;

        /** 老年代耗时增量。 */
        private final long oldTime;

        /** 采样间隔。 */
        private final long elapsedMillis;

        /**
         * 构造增量。
         *
         * @param youngCount    年轻代次数增量
         * @param youngTime     年轻代耗时增量
         * @param oldCount      老年代次数增量
         * @param oldTime       老年代耗时增量
         * @param elapsedMillis 采样间隔
         */
        GcDelta(long youngCount, long youngTime, long oldCount, long oldTime, long elapsedMillis) {
            this.youngCount = youngCount;
            this.youngTime = youngTime;
            this.oldCount = oldCount;
            this.oldTime = oldTime;
            this.elapsedMillis = elapsedMillis;
        }
    }
}
