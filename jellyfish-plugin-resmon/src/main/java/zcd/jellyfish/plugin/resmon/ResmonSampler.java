package zcd.jellyfish.plugin.resmon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.plugin.PluginContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采样器：在后台线程上按两个不同的节奏采集 JVM 与磁盘，并把结果留在内存里供界面读取。
 * <p>
 * <b>为什么所有慢活都必须在后台线程上</b>：面板处理器跑在 TUI 的渲染线程里，契约要求它纯只读、
 * 无 I/O、必须快。一次目录递归遍历要几十毫秒到几秒，放在处理器里就是「面板一动，整个界面就卡住」。
 * 因此这里的分工是硬性的：本类负责全部 I/O 与计算，处理器只把内存里那份不可变快照拼成几行文本。
 * <p>
 * <b>为什么是两个节奏而不是一个</b>：读 JMX 是微秒级的纯内存操作，值得 2 秒一次；
 * 递归遍历一个装了几百个会话文件的目录可能上百毫秒，60 秒一次都嫌频繁。
 * 用一个节奏只能二选一——要么磁盘拖慢 JVM 的刷新，要么 JVM 跟着磁盘一起变慢。
 * 两个 {@code scheduleWithFixedDelay} 之间也互不阻塞（线程池给两个线程）。
 * <p>
 * <b>磁盘这一侧扫的是「{@code baseDir} 的一级子项」而不是一份写死的目录清单</b>：
 * 写死清单意味着本插件要知道每个别的插件把数据放在哪儿，而它既拿不到工作目录、也不允许自行读
 * {@code jellyfish.json}，无从核对。改成扫描之后，新装的插件、新出现的目录都会被自动看见，
 * 而配置里只剩一个根目录——那是最稳定的一个约定。
 * <p>
 * <b>三条与生命周期有关的纪律，每一条都对应一次真实的故障形态</b>：
 * <ul>
 *     <li><b>任务体自己 try/catch</b>：{@code scheduleWithFixedDelay} 的任务体一旦抛异常，
 *     之后的触发会<b>静默停止</b>——插件看起来还在，面板却再也不更新，且没有任何报错；</li>
 *     <li><b>{@code stopping} 之前不许碰上下文</b>：插件停止后任何注册与投递都当场抛
 *     {@link JellyfishException}（fail-closed），因此任务体第一件事就是检查停止标记，
 *     而不是先算完再发现推不出去；</li>
 *     <li><b>{@code close()} 要等到线程真的结束</b>：插件框架只保证「{@code stop()} 返回之后上下文失效」，
 *     不等在途任务；如果 {@code close()} 只是发起 {@code shutdownNow} 就返回，
 *     就存在「stop 已返回、采样线程还在推失效」的窗口，那正是噪声日志的来源。</li>
 * </ul>
 * <p>
 * 可安全跨线程使用：快照字段是 {@code volatile} 的不可变对象，写只有采样线程一个。
 *
 * @author zcd
 */
final class ResmonSampler implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ResmonSampler.class);

    /** 采样线程数：JVM 与磁盘各一个，互不阻塞。 */
    private static final int THREADS = 2;

    /** 关闭时等待在途采样结束的秒数。 */
    private static final long CLOSE_WAIT_SECONDS = 3L;

    /** 插件配置。 */
    private final PluginConfig config;

    /** JVM 采样端口。 */
    private final JvmProbe probe;

    /** 目录统计器。 */
    private final DirSizer sizer;

    /** 插件上下文，用于往外壳推失效。 */
    private final PluginContext context;

    /** 阈值判定器：无状态，算出「此刻哪些项越阈值」。 */
    private final AlertEvaluator evaluator;

    /** 最近一次 JVM 快照；还没采到时为 {@code null}。 */
    private volatile JvmStats jvm;

    /** 最近一次磁盘报告；还没扫到时是空报告。 */
    private volatile DiskReport disk = DiskReport.empty();

    /** 此刻越阈值的告警文本；无告警时为空列表。 */
    private volatile List<String> alerts = Collections.emptyList();

    /** 是否主动推送界面失效。 */
    private volatile boolean autoRefresh;

    /** 是否正在停止。 */
    private volatile boolean stopping;

    /** 采样线程池；未启动时为 {@code null}。 */
    private ScheduledExecutorService executor;

    /** 上一次扫描的体积合计，用于算增长率。 */
    private long lastScannedBytes = -1L;

    /** 上一次扫描的时刻。 */
    private long lastScannedAtMillis = -1L;

    /**
     * 构造采样器。
     *
     * @param config  插件配置，不可为 {@code null}
     * @param probe   JVM 采样端口，不可为 {@code null}
     * @param sizer   目录统计器，不可为 {@code null}
     * @param context 插件上下文，不可为 {@code null}
     */
    ResmonSampler(PluginConfig config, JvmProbe probe, DirSizer sizer, PluginContext context) {
        this.config = config;
        this.probe = probe;
        this.sizer = sizer;
        this.context = context;
        this.evaluator = new AlertEvaluator(config);
        this.autoRefresh = config.autoRefresh();
    }

    /**
     * 启动两个采样节奏，各自先立即跑一次。
     * <p>
     * 首次延迟为 0：面板的第一帧就该有内容，否则用户在最需要它的那两秒里看到的是一块空面板。
     *
     * @throws JellyfishException 已经启动过时抛出
     */
    void start() {
        if (executor != null) {
            throw new JellyfishException("资源采样器已经启动");
        }
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(THREADS,
                daemonThreads(context.pluginId() + "-resmon-"));
        executor = pool;
        pool.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sampleJvm();
            }
        }, 0L, config.sampleIntervalMillis(), TimeUnit.MILLISECONDS);
        pool.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sampleDisk();
            }
        }, 0L, config.diskScanIntervalMillis(), TimeUnit.MILLISECONDS);
        LOG.info("资源监控已启动: baseDir={}, jvmInterval={}ms, diskInterval={}ms, autoRefresh={}",
                config.baseDir(), config.sampleIntervalMillis(), config.diskScanIntervalMillis(), autoRefresh);
    }

    /**
     * 停止采样并等待在途任务结束，幂等。
     * <p>
     * 返回之后本类不再以内核上下文做任何事，因此插件的 {@code stop()} 可以安全返回。
     */
    @Override
    public void close() {
        stopping = true;
        ScheduledExecutorService pool = executor;
        executor = null;
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("资源采样线程未在 {} 秒内结束，可能有目录遍历正在收尾", CLOSE_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取最近一次 JVM 快照。
     *
     * @return 快照；还没有采到时为 {@code null}
     */
    JvmStats jvm() {
        return jvm;
    }

    /**
     * 获取最近一次磁盘报告。
     *
     * @return 报告，保证非 {@code null}；还没有扫到时 {@link DiskReport#scanned()} 为 {@code false}
     */
    DiskReport disk() {
        return disk;
    }

    /**
     * 获取此刻越阈值的告警文本。
     * <p>
     * <b>它是一份状态快照，不是事件流</b>：面板每次刷新都重新读一遍，因此同一项持续越界只会显示一份，
     * 回落之后再越界也自然重新出现——不需要任何「只报一次」的记账。
     *
     * @return 不可变列表，保证非 {@code null}；无告警时为空列表
     */
    List<String> alerts() {
        return alerts;
    }

    /**
     * 判断是否在采样后主动推送界面失效。
     *
     * @return 推送返回 {@code true}
     */
    boolean autoRefresh() {
        return autoRefresh;
    }

    /**
     * 设置是否主动推送界面失效。
     * <p>
     * 这是运行期开关，只活在内存里：它控制的是「面板会不会自己动」，与任何会话、任何落盘数据无关，
     * 因此重启回到配置段里的缺省值才是正确的行为（写进文件反而会留下一个用户忘了的隐式状态）。
     *
     * @param value 是否推送
     */
    void autoRefresh(boolean value) {
        this.autoRefresh = value;
    }

    /**
     * 采一次 JVM 并推送界面失效。
     */
    private void sampleJvm() {
        if (stopping) {
            return;
        }
        try {
            jvm = probe.probe();
            refreshAlerts();
            publishInvalidated("jvm");
        } catch (RuntimeException e) {
            LOG.warn("JVM 采样失败：{}", e.getMessage());
        }
    }

    /**
     * 扫一次磁盘并推送界面失效。
     */
    private void sampleDisk() {
        if (stopping) {
            return;
        }
        try {
            disk = scanDisk();
            refreshAlerts();
            publishInvalidated("disk");
        } catch (RuntimeException e) {
            LOG.warn("磁盘统计失败：{}", e.getMessage());
        }
    }

    /**
     * 在调用线程上同步采一次（JVM + 磁盘），不推任何东西，也不检查停止标记。
     * <p>
     * <b>它存在是为了让「采样结果 → 呈现」这一段可以脱离线程池被测</b>：采样节奏（多久一次、
     * 什么时候推失效）是另一条被测路径，混在一起就只能靠 sleep 去等，那既慢又不稳。
     * <p>
     * 生产路径上它不被调用——后台线程走的是上面两个方法。因此这里刻意<b>不</b>往外壳推任何东西：
     * 在调用方线程上投递会改变「谁在什么时候推」这条语义。
     */
    void sampleNow() {
        jvm = probe.probe();
        disk = scanDisk();
        refreshAlerts();
    }

    /**
     * 重算此刻越阈值的告警。
     * <p>
     * 用当前两份快照一起判定：内存与磁盘由两个线程分别刷新，因此这里读到的可能是
     * 「新内存 + 旧磁盘」——这对告警没有影响（两者互不相干），却省掉了一份跨线程的联合快照。
     * <p>
     * 关掉 {@code alerts} 配置时置空而不是不更新：用户关掉它的意图是「别再标出」，
     * 因此已算出来的那份也要立刻消失，而不是留在面板上直到下次重启。
     */
    private void refreshAlerts() {
        if (stopping || !config.alerts()) {
            alerts = Collections.emptyList();
            return;
        }
        alerts = Collections.unmodifiableList(new ArrayList<String>(evaluator.evaluate(jvm, disk)));
    }

    /**
     * 实际扫描一轮磁盘。
     * <p>
     * 结果按体积从大到小排序，同体积按名字——「谁的占地方」是这块面板存在的理由，
     * 让最大的那条永远在第一行比按文件系统的返回顺序稳定得多。
     * <p>
     * <b>轮换/备份文件不单独成项</b>：{@code jellyfish-tui.log.1} 与
     * {@code jellyfish.json.bak-before-stock} 这类文件会被 {@link DirSizer#child} 算进它们的主文件里
     * （那是让滚动日志不被漏报的同一个机制），因此这里必须把它们从列表里剔掉——否则同一批字节
     * 会被算两遍，而「合计」也就不再可信。判据是「是不是某个同级子项的点号后缀」，
     * 不猜具体后缀长什么样，因此换成别的命名约定也照样成立。
     *
     * @return 报告，保证非 {@code null}
     */
    private DiskReport scanDisk() {
        long now = System.currentTimeMillis();
        List<PathUsage> usages = new ArrayList<PathUsage>();
        Path base = config.baseDir();
        if (Files.isDirectory(base)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                List<Path> children = new ArrayList<Path>();
                for (Path child : stream) {
                    children.add(child);
                }
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (isSiblingOfAnother(children, name, Files.isRegularFile(child))) {
                        continue;
                    }
                    usages.add(sizer.child(name, child));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("列不出 {} 的一级子项：{}", base, e.getMessage());
            }
        }
        Collections.sort(usages, bySizeDescending());
        long total = 0L;
        for (PathUsage usage : usages) {
            total += usage.bytes();
        }
        PartitionSpace space = partitionSpace();
        DiskReport report = DiskReport.of(now, usages, space.totalBytes, space.usableBytes,
                lastScannedBytes, lastScannedAtMillis);
        lastScannedBytes = total;
        lastScannedAtMillis = now;
        return report;
    }

    /**
     * 判断一个条目是不是「另一个同级子项的点号后缀」，因而会被那个子项折进去。
     * <p>
     * <b>只有「同名同级项是普通文件」时才会真的折进去</b>：把轮换档算进主文件的是
     * {@link DirSizer#fileWithSiblings}，它只对普通文件生效（{@code isRegularFile}）。
     * 因此这里必须收窄到同一种形状——否则 {@code stock.old} 在主项 {@code stock} 是个
     * <b>目录</b>时既不成项、也没人算它，合计就少了一块；反向那一半（主项是文件、自己是目录）
     * 同理：目录不会被 {@code fileWithSiblings} 求和。
     * <p>
     * <b>「跳过了多少」不值得单独报出来</b>：这些条目本来就该归在主项名下，而它们在磁盘上
     * 属于同一件事——报一句「另有 3 个轮换档已并入」只会给面板加一行没人看的字。
     *
     * @param children 同级的全部子项路径，不可为 {@code null}
     * @param name     待判断的名字，不可为 {@code null}
     * @param isFile   待判断的条目是不是普通文件
     * @return 会被某个同级子项折进去时返回 {@code true}
     */
    private static boolean isSiblingOfAnother(List<Path> children, String name, boolean isFile) {
        if (!isFile) {
            return false;
        }
        for (Path other : children) {
            String base = other.getFileName().toString();
            if (base.equals(name) || !name.startsWith(base + ".")) {
                continue;
            }
            if (Files.isRegularFile(other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造「体积从大到小、同体积按名字」的比较器。
     *
     * @return 比较器，保证非 {@code null}
     */
    private static Comparator<PathUsage> bySizeDescending() {
        return new Comparator<PathUsage>() {
            @Override
            public int compare(PathUsage left, PathUsage right) {
                int bySize = Long.compare(right.bytes(), left.bytes());
                return bySize != 0 ? bySize : left.name().compareTo(right.name());
            }
        };
    }

    /**
     * 往外壳推一条「我的面板内容脏了」。
     * <p>
     * <b>这是本插件往外壳推的唯一一条东西</b>。告警不走推送：它是「当前状态」而不是一次事件，
     * 而状态该由面板每次刷新时重新读一遍——推的话，同一项会在每次采样时重发一条，
     * 而外壳的通知区在没有会话的首页上也会把它显示出来，那正是它最没用的时候。
     * <p>
     * 这是面板能自己动起来的唯一机制：外壳只在缓存失效时才重新问插件要内容，
     * 而空闲时（没有回合在跑）没有任何东西会替我们失效。投递是尽力而为的，
     * 队列满时外壳丢最新一条并在指标里记账——丢一条的后果只是这一帧的内容旧 2 秒，
     * 因此不重试、不补偿。
     *
     * @param what 失效定位线索（哪一块脏了），不可为空白
     */
    private void publishInvalidated(String what) {
        if (stopping || !autoRefresh) {
            return;
        }
        try {
            context.present(ShellContribution.invalidated(ShellContribution.Scope.SHELL, null, what));
        } catch (RuntimeException e) {
            LOG.debug("界面失效推送失败：{}", e.getMessage());
        }
    }

    /**
     * 读取统计根目录所在分区的容量。
     * <p>
     * 根目录不存在时退到用户主目录；两者都取不到就报「不可用」——分区容量在整个界面上只占一格，
     * 不值得为它引入失败路径。
     *
     * @return 容量；不可用时两个字段都是负数
     */
    private PartitionSpace partitionSpace() {
        FileStore store = fileStoreOf(config.baseDir());
        if (store == null) {
            String home = System.getProperty("user.home");
            store = home == null ? null : fileStoreOf(Paths.get(home));
        }
        if (store == null) {
            return new PartitionSpace(JvmStats.UNKNOWN, JvmStats.UNKNOWN);
        }
        try {
            return new PartitionSpace(store.getTotalSpace(), store.getUsableSpace());
        } catch (IOException | RuntimeException e) {
            LOG.debug("读取分区容量失败：{}", e.getMessage());
            return new PartitionSpace(JvmStats.UNKNOWN, JvmStats.UNKNOWN);
        }
    }

    /**
     * 取某个路径所在的文件系统。
     *
     * @param path 路径，不可为 {@code null}
     * @return 文件系统；路径不存在或读取失败时为 {@code null}
     */
    private static FileStore fileStoreOf(Path path) {
        try {
            return Files.exists(path) ? Files.getFileStore(path) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 创建守护线程工厂。
     * <p>
     * 守护线程保证采样线程不会拖住 JVM 退出：即使某个外壳忘了调用 {@code stop()}，
     * 进程也该能正常结束。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂，保证非 {@code null}
     */
    private static ThreadFactory daemonThreads(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /**
     * 分区容量的一次读数。
     *
     * @author zcd
     */
    private static final class PartitionSpace {

        /** 总容量，负数表示不可用。 */
        private final long totalBytes;

        /** 可用容量，负数表示不可用。 */
        private final long usableBytes;

        /**
         * 构造读数。
         *
         * @param totalBytes  总容量
         * @param usableBytes 可用容量
         */
        PartitionSpace(long totalBytes, long usableBytes) {
            this.totalBytes = totalBytes;
            this.usableBytes = usableBytes;
        }
    }
}
