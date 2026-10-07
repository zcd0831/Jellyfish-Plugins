package zcd.jellyfish.plugin.resmon;

/**
 * 一次 JVM 资源采样的不可变快照。
 * <p>
 * <b>为什么 GC 存的是「增量」而不是累计值</b>：累计 GC 次数与耗时从进程启动算起，只会单调变大，
 * 看它看不出「现在是不是在频繁 GC」；而「这段采样窗口里 GC 占了多少时间」才是能直接下判断的数——
 * 它也正是面板上那一格要回答的问题。因此采样器保留上一次的读数，这里只放差值。
 * <p>
 * <b>为什么用建造者而不是全字段构造器</b>：字段有二十来个，而真实的调用点只有两处
 * （采样器给全套、单测只给关心的那两三个）。全字段构造器会让「只想造一个堆占用越界的快照」
 * 变成写二十多个参数，于是测试会开始复制粘贴、并在加了新字段之后悄悄失去意义。
 * <p>
 * <b>不可用一律用负数表达</b>：{@code heapMax} 未设上限、非 Unix 平台没有文件描述符计数、
 * CPU 负载首次采样前拿不到值——这些都不是 0，用 0 会让「没有这项数据」看起来像「这项数据是零」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class JvmStats {

    /** 数值不可用的标记。 */
    static final long UNKNOWN = -1L;

    /** 比例不可用的标记。 */
    static final double UNKNOWN_PERCENT = -1.0;

    /** 采样时刻。 */
    private final long capturedAtMillis;

    /** JVM 运行时长。 */
    private final long uptimeMillis;

    /** 堆已用。 */
    private final long heapUsedBytes;

    /** 堆已提交。 */
    private final long heapCommittedBytes;

    /** 堆上限；未设上限时为 {@link #UNKNOWN}。 */
    private final long heapMaxBytes;

    /** 非堆已用。 */
    private final long nonHeapUsedBytes;

    /** 元空间已用；取不到时为 {@link #UNKNOWN}。 */
    private final long metaspaceUsedBytes;

    /** 压缩类空间已用；取不到时为 {@link #UNKNOWN}。 */
    private final long compressedClassSpaceUsedBytes;

    /** 代码缓存已用；取不到时为 {@link #UNKNOWN}。 */
    private final long codeCacheUsedBytes;

    /** 采样窗口内年轻代 GC 次数。 */
    private final long youngGcCount;

    /** 采样窗口内年轻代 GC 耗时。 */
    private final long youngGcTimeMillis;

    /** 采样窗口内老年代 GC 次数。 */
    private final long oldGcCount;

    /** 采样窗口内老年代 GC 耗时。 */
    private final long oldGcTimeMillis;

    /** 本次采样与上一次之间的时间间隔；0 表示这是第一次采样。 */
    private final long gcElapsedMillis;

    /** 当前线程数。 */
    private final long threadCount;

    /** 峰值线程数。 */
    private final long peakThreadCount;

    /** 守护线程数。 */
    private final long daemonThreadCount;

    /** 死锁线程数（最近一次检测的结果）。 */
    private final long deadlockedThreadCount;

    /** 已加载类数。 */
    private final long loadedClassCount;

    /** 打开的文件描述符数；非 Unix 或取不到时为 {@link #UNKNOWN}。 */
    private final long openFileDescriptorCount;

    /** 文件描述符上限；非 Unix 或取不到时为 {@link #UNKNOWN}。 */
    private final long maxFileDescriptorCount;

    /** 进程 CPU 使用率（0..100）；取不到时为 {@link #UNKNOWN_PERCENT}。 */
    private final double processCpuPercent;

    /** 系统 CPU 使用率（0..100）；取不到时为 {@link #UNKNOWN_PERCENT}。 */
    private final double systemCpuPercent;

    /** 可用处理器数。 */
    private final int availableProcessors;

    /**
     * 由建造者构造快照。
     *
     * @param builder 建造者
     */
    private JvmStats(Builder builder) {
        this.capturedAtMillis = builder.capturedAtMillis;
        this.uptimeMillis = builder.uptimeMillis;
        this.heapUsedBytes = builder.heapUsedBytes;
        this.heapCommittedBytes = builder.heapCommittedBytes;
        this.heapMaxBytes = builder.heapMaxBytes;
        this.nonHeapUsedBytes = builder.nonHeapUsedBytes;
        this.metaspaceUsedBytes = builder.metaspaceUsedBytes;
        this.compressedClassSpaceUsedBytes = builder.compressedClassSpaceUsedBytes;
        this.codeCacheUsedBytes = builder.codeCacheUsedBytes;
        this.youngGcCount = builder.youngGcCount;
        this.youngGcTimeMillis = builder.youngGcTimeMillis;
        this.oldGcCount = builder.oldGcCount;
        this.oldGcTimeMillis = builder.oldGcTimeMillis;
        this.gcElapsedMillis = builder.gcElapsedMillis;
        this.threadCount = builder.threadCount;
        this.peakThreadCount = builder.peakThreadCount;
        this.daemonThreadCount = builder.daemonThreadCount;
        this.deadlockedThreadCount = builder.deadlockedThreadCount;
        this.loadedClassCount = builder.loadedClassCount;
        this.openFileDescriptorCount = builder.openFileDescriptorCount;
        this.maxFileDescriptorCount = builder.maxFileDescriptorCount;
        this.processCpuPercent = builder.processCpuPercent;
        this.systemCpuPercent = builder.systemCpuPercent;
        this.availableProcessors = builder.availableProcessors;
    }

    /**
     * 创建建造者，各数值字段的初值是「不可用」。
     *
     * @return 建造者，保证非 {@code null}
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * 获取采样时刻。
     *
     * @return 毫秒时间戳
     */
    long capturedAtMillis() {
        return capturedAtMillis;
    }

    /**
     * 获取 JVM 运行时长。
     *
     * @return 毫秒数
     */
    long uptimeMillis() {
        return uptimeMillis;
    }

    /**
     * 获取堆已用。
     *
     * @return 字节数
     */
    long heapUsedBytes() {
        return heapUsedBytes;
    }

    /**
     * 获取堆已提交。
     *
     * @return 字节数
     */
    long heapCommittedBytes() {
        return heapCommittedBytes;
    }

    /**
     * 获取堆上限。
     *
     * @return 字节数；未设上限时为 {@link #UNKNOWN}
     */
    long heapMaxBytes() {
        return heapMaxBytes;
    }

    /**
     * 获取非堆已用。
     *
     * @return 字节数
     */
    long nonHeapUsedBytes() {
        return nonHeapUsedBytes;
    }

    /**
     * 获取元空间已用。
     *
     * @return 字节数；取不到时为 {@link #UNKNOWN}
     */
    long metaspaceUsedBytes() {
        return metaspaceUsedBytes;
    }

    /**
     * 获取压缩类空间已用。
     *
     * @return 字节数；取不到时为 {@link #UNKNOWN}
     */
    long compressedClassSpaceUsedBytes() {
        return compressedClassSpaceUsedBytes;
    }

    /**
     * 获取代码缓存已用。
     *
     * @return 字节数；取不到时为 {@link #UNKNOWN}
     */
    long codeCacheUsedBytes() {
        return codeCacheUsedBytes;
    }

    /**
     * 获取采样窗口内的 GC 总次数。
     *
     * @return 次数
     */
    long gcCount() {
        return youngGcCount + oldGcCount;
    }

    /**
     * 获取采样窗口内的 GC 总耗时。
     *
     * @return 毫秒数
     */
    long gcTimeMillis() {
        return youngGcTimeMillis + oldGcTimeMillis;
    }

    /**
     * 获取采样窗口内年轻代 GC 次数。
     *
     * @return 次数
     */
    long youngGcCount() {
        return youngGcCount;
    }

    /**
     * 获取采样窗口内年轻代 GC 耗时。
     *
     * @return 毫秒数
     */
    long youngGcTimeMillis() {
        return youngGcTimeMillis;
    }

    /**
     * 获取采样窗口内老年代 GC 次数。
     * <p>
     * 单独报出来是因为它的含义与年轻代完全不同：年轻代 GC 频繁只是「分配快」，
     * 老年代 GC（Full GC）意味着进程被停下来，是用户能感觉到的卡顿。
     *
     * @return 次数
     */
    long oldGcCount() {
        return oldGcCount;
    }

    /**
     * 获取采样窗口内老年代 GC 耗时。
     *
     * @return 毫秒数
     */
    long oldGcTimeMillis() {
        return oldGcTimeMillis;
    }

    /**
     * 获取本次采样与上一次之间的时间间隔。
     *
     * @return 毫秒数；首次采样时为 0
     */
    long gcElapsedMillis() {
        return gcElapsedMillis;
    }

    /**
     * 获取 GC 时间占比。
     *
     * @return 0..100 的百分比；首次采样或间隔非正时为 {@link #UNKNOWN_PERCENT}
     */
    double gcPercent() {
        if (gcElapsedMillis <= 0L) {
            return UNKNOWN_PERCENT;
        }
        return gcTimeMillis() * 100.0 / gcElapsedMillis;
    }

    /**
     * 获取当前线程数。
     *
     * @return 线程数
     */
    long threadCount() {
        return threadCount;
    }

    /**
     * 获取峰值线程数。
     *
     * @return 线程数
     */
    long peakThreadCount() {
        return peakThreadCount;
    }

    /**
     * 获取守护线程数。
     *
     * @return 线程数
     */
    long daemonThreadCount() {
        return daemonThreadCount;
    }

    /**
     * 获取死锁线程数。
     *
     * @return 线程数；最近一次检测的结果，每若干次采样才更新一次
     */
    long deadlockedThreadCount() {
        return deadlockedThreadCount;
    }

    /**
     * 获取已加载类数。
     *
     * @return 类数
     */
    long loadedClassCount() {
        return loadedClassCount;
    }

    /**
     * 获取打开的文件描述符数。
     *
     * @return 个数；取不到时为 {@link #UNKNOWN}
     */
    long openFileDescriptorCount() {
        return openFileDescriptorCount;
    }

    /**
     * 获取文件描述符上限。
     *
     * @return 个数；取不到时为 {@link #UNKNOWN}
     */
    long maxFileDescriptorCount() {
        return maxFileDescriptorCount;
    }

    /**
     * 获取文件描述符使用比例。
     *
     * @return 0..100 的百分比；上限不可用时为 {@link #UNKNOWN_PERCENT}
     */
    double fdPercent() {
        if (maxFileDescriptorCount <= 0L || openFileDescriptorCount < 0L) {
            return UNKNOWN_PERCENT;
        }
        return openFileDescriptorCount * 100.0 / maxFileDescriptorCount;
    }

    /**
     * 获取进程 CPU 使用率。
     *
     * @return 0..100 的百分比；取不到时为 {@link #UNKNOWN_PERCENT}
     */
    double processCpuPercent() {
        return processCpuPercent;
    }

    /**
     * 获取系统 CPU 使用率。
     *
     * @return 0..100 的百分比；取不到时为 {@link #UNKNOWN_PERCENT}
     */
    double systemCpuPercent() {
        return systemCpuPercent;
    }

    /**
     * 获取可用处理器数。
     *
     * @return 处理器数
     */
    int availableProcessors() {
        return availableProcessors;
    }

    /**
     * 获取堆使用比例。
     *
     * @return 0..100 的百分比；堆上限不可用时为 {@link #UNKNOWN_PERCENT}
     */
    double heapPercent() {
        if (heapMaxBytes <= 0L) {
            return UNKNOWN_PERCENT;
        }
        return heapUsedBytes * 100.0 / heapMaxBytes;
    }

    @Override
    public String toString() {
        return "JvmStats{heap=" + heapUsedBytes + "/" + heapMaxBytes + ", threads=" + threadCount
                + ", deadlocked=" + deadlockedThreadCount + '}';
    }

    /**
     * {@link JvmStats} 的建造者。
     * <p>
     * 各字段的初值是「不可用」而不是 0：漏设一个字段时，界面上会显示成
     * {@link ResmonFormat#UNKNOWN}（明显是缺数据），而不是一个看起来正常的 0。
     *
     * @author zcd
     */
    static final class Builder {

        /** 采样时刻。 */
        private long capturedAtMillis;

        /** JVM 运行时长。 */
        private long uptimeMillis = UNKNOWN;

        /** 堆已用。 */
        private long heapUsedBytes = UNKNOWN;

        /** 堆已提交。 */
        private long heapCommittedBytes = UNKNOWN;

        /** 堆上限。 */
        private long heapMaxBytes = UNKNOWN;

        /** 非堆已用。 */
        private long nonHeapUsedBytes = UNKNOWN;

        /** 元空间已用。 */
        private long metaspaceUsedBytes = UNKNOWN;

        /** 压缩类空间已用。 */
        private long compressedClassSpaceUsedBytes = UNKNOWN;

        /** 代码缓存已用。 */
        private long codeCacheUsedBytes = UNKNOWN;

        /** 采样窗口内年轻代 GC 次数。 */
        private long youngGcCount;

        /** 采样窗口内年轻代 GC 耗时。 */
        private long youngGcTimeMillis;

        /** 采样窗口内老年代 GC 次数。 */
        private long oldGcCount;

        /** 采样窗口内老年代 GC 耗时。 */
        private long oldGcTimeMillis;

        /** 采样间隔。 */
        private long gcElapsedMillis;

        /** 当前线程数。 */
        private long threadCount = UNKNOWN;

        /** 峰值线程数。 */
        private long peakThreadCount = UNKNOWN;

        /** 守护线程数。 */
        private long daemonThreadCount = UNKNOWN;

        /** 死锁线程数。 */
        private long deadlockedThreadCount;

        /** 已加载类数。 */
        private long loadedClassCount = UNKNOWN;

        /** 打开的文件描述符数。 */
        private long openFileDescriptorCount = UNKNOWN;

        /** 文件描述符上限。 */
        private long maxFileDescriptorCount = UNKNOWN;

        /** 进程 CPU 使用率。 */
        private double processCpuPercent = UNKNOWN_PERCENT;

        /** 系统 CPU 使用率。 */
        private double systemCpuPercent = UNKNOWN_PERCENT;

        /** 可用处理器数。 */
        private int availableProcessors;

        /** 私有构造器：只允许经 {@link JvmStats#builder()} 创建。 */
        private Builder() {
        }

        /**
         * 设置采样时刻。
         *
         * @param value 毫秒时间戳
         * @return 本建造者
         */
        Builder capturedAt(long value) {
            this.capturedAtMillis = value;
            return this;
        }

        /**
         * 设置 JVM 运行时长。
         *
         * @param value 毫秒数
         * @return 本建造者
         */
        Builder uptime(long value) {
            this.uptimeMillis = value;
            return this;
        }

        /**
         * 设置堆用量。
         *
         * @param used      已用字节数
         * @param committed 已提交字节数
         * @param max       上限字节数，{@link JvmStats#UNKNOWN} 表示未设上限
         * @return 本建造者
         */
        Builder heap(long used, long committed, long max) {
            this.heapUsedBytes = used;
            this.heapCommittedBytes = committed;
            this.heapMaxBytes = max;
            return this;
        }

        /**
         * 设置非堆已用。
         *
         * @param value 字节数
         * @return 本建造者
         */
        Builder nonHeap(long value) {
            this.nonHeapUsedBytes = value;
            return this;
        }

        /**
         * 设置内存池用量。
         *
         * @param metaspace    元空间已用字节数
         * @param classSpace   压缩类空间已用字节数
         * @param codeCache    代码缓存已用字节数
         * @return 本建造者
         */
        Builder memoryPools(long metaspace, long classSpace, long codeCache) {
            this.metaspaceUsedBytes = metaspace;
            this.compressedClassSpaceUsedBytes = classSpace;
            this.codeCacheUsedBytes = codeCache;
            return this;
        }

        /**
         * 设置采样窗口内的 GC 增量。
         *
         * @param youngCount 年轻代次数
         * @param youngTime  年轻代耗时毫秒数
         * @param oldCount   老年代次数
         * @param oldTime    老年代耗时毫秒数
         * @param elapsed    本次与上次采样的间隔毫秒数，0 表示首次采样
         * @return 本建造者
         */
        Builder gc(long youngCount, long youngTime, long oldCount, long oldTime, long elapsed) {
            this.youngGcCount = youngCount;
            this.youngGcTimeMillis = youngTime;
            this.oldGcCount = oldCount;
            this.oldGcTimeMillis = oldTime;
            this.gcElapsedMillis = elapsed;
            return this;
        }

        /**
         * 设置线程用量。
         *
         * @param current 当前线程数
         * @param peak    峰值线程数
         * @param daemon  守护线程数
         * @return 本建造者
         */
        Builder threads(long current, long peak, long daemon) {
            this.threadCount = current;
            this.peakThreadCount = peak;
            this.daemonThreadCount = daemon;
            return this;
        }

        /**
         * 设置死锁线程数。
         *
         * @param value 线程数
         * @return 本建造者
         */
        Builder deadlocked(long value) {
            this.deadlockedThreadCount = value;
            return this;
        }

        /**
         * 设置已加载类数。
         *
         * @param value 类数
         * @return 本建造者
         */
        Builder loadedClasses(long value) {
            this.loadedClassCount = value;
            return this;
        }

        /**
         * 设置文件描述符用量。
         *
         * @param open 已打开个数
         * @param max  上限个数
         * @return 本建造者
         */
        Builder fileDescriptors(long open, long max) {
            this.openFileDescriptorCount = open;
            this.maxFileDescriptorCount = max;
            return this;
        }

        /**
         * 设置 CPU 使用率。
         *
         * @param process 进程使用率（0..100）
         * @param system  系统使用率（0..100）
         * @return 本建造者
         */
        Builder cpu(double process, double system) {
            this.processCpuPercent = process;
            this.systemCpuPercent = system;
            return this;
        }

        /**
         * 设置可用处理器数。
         *
         * @param value 处理器数
         * @return 本建造者
         */
        Builder availableProcessors(int value) {
            this.availableProcessors = value;
            return this;
        }

        /**
         * 构造快照。
         *
         * @return 不可变快照，保证非 {@code null}
         */
        JvmStats build() {
            return new JvmStats(this);
        }
    }
}
