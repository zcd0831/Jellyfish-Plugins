package zcd.jellyfish.plugin.resmon;

/**
 * JVM 资源采样端口。
 * <p>
 * <b>为什么要抽成接口</b>：真实实现读的是 JMX，而 JMX 的返回值随平台、JDK 版本与运行参数变化
 * （非 Unix 没有文件描述符计数、没设 {@code -Xmx} 就没有堆上限、首次采样拿不到 CPU 负载）。
 * 把这些分支留在唯一的实现类里、让采样节奏（何时采、多久采一次、什么时候推失效）走接口，
 * 采样器就能在一个不起进程、不依赖平台的前提下被完整测试。
 * <p>
 * 实现必须是纯读取的：它可能被后台线程按秒级节奏调用。
 *
 * @author zcd
 */
interface JvmProbe {

    /**
     * 采集一次 JVM 资源快照。
     * <p>
     * 实现应在内部维护「上一次读数」，把 GC 次数与耗时折算成两次调用之间的增量。
     *
     * @return 快照，保证非 {@code null}
     */
    JvmStats probe();
}
