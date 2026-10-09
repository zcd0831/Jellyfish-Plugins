package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 脚本的周期任务宿主：按清单里的 {@code schedules} 声明，定时调用脚本的 {@code periodic} 处理器。
 * <p>
 * <b>为什么必须有它（脚本为什么不能自建定时器）</b>：脚本的 {@code main.py} 没有自己的主循环——
 * worker 是「收请求 → 跑处理器 → 收事件」的单线程循环，进程只在被调用时活着，空闲还会自毁
 * （{@code workerIdleSeconds}）。因此「到点做一件事」这个发起方只能落在桥接层：
 * 它是唯一常驻、且手里有调用通路（{@link ScriptCaller}）的一侧。
 * <p>
 * <b>为什么不能反过来（让内核提供定时器）</b>：Java 插件本来就能自建定时器（{@code JellyfishPlugin}
 * 的生命周期契约明说「先停下会产生注册的线程与定时器，再返回」是插件自己的责任），因此内核侧
 * 没有这条缺口，不需要为它新增公共设施；缺口只存在于脚本这条线。这也解释了本类的归属：
 * 它是<b>跨语言桥接的机制层</b>，Python 与 Node 两个桥接插件各自 shade 一份，所以「所有脚本语言」
 * 都吃到同一个机制，而不是只服务 Python。
 * <p>
 * <b>线程模型：一个脚本一条线</b>。声明了周期任务的脚本各得一个单线程调度器，理由有两条：
 * 一是脚本的 worker 本来同时只处理一个在途请求，所以「同一脚本的任务串行」不引入任何额外约束；
 * 二是「脚本 A 的任务跑得久」不该延后「脚本 B 的节拍」——共享一条线会让这件事发生。
 * 线程一律是守护线程：进程退出不该被它拖住。
 * <p>
 * <b>间隔语义是「上一次跑完 + 间隔」</b>：用 {@code scheduleWithFixedDelay}，它本身就是
 * single-flight 的。这一点是硬要求——取数一次可能十几秒（见 {@code stock} 的实测），
 * 用固定频率会把「还没返回就又发一次」变成常态，对脚本 worker 与外部接口都是压力。
 * <p>
 * <b>成功之后代发一次 {@code UiInvalidatedEvent}</b>：周期任务的语义是「后台更新了我贡献的内容」，
 * 而脚本发布不了这个事件（{@code ScriptEventFactory.EMITTABLE} 只有通知与告警两个），
 * 因此由桥接层在调用成功后代发。这是本机制存在的核心价值——没有它，脚本定时抓到的新数据
 * 写进了缓存文件，而屏上那块面板永远不会自己重画（外壳只在回合进行中每秒补失效）。
 * <p>
 * <b>失败只记日志，绝不让定时器停</b>：{@code scheduleWithFixedDelay} 的任务体抛异常会让后续触发
 * <b>静默停止</b>（同一个坑在 {@code IdleSessionReaper} 那里也有注释）。因此任务体自己 try/catch，
 * 且失败<b>不</b>发失效事件（数据没变，发了只是让所有面板白跑一遍）。
 * <p>
 * {@link #close()} 幂等，关闭后不再有任何触发。
 *
 * @author zcd
 */
public final class ScriptScheduler implements AutoCloseable {

    /** 周期任务在协议里的类型名：与两份 SDK 的 {@code _DISPATCH["periodic"]} 必须逐字一致。 */
    public static final String PERIODIC_TYPE = "periodic";

    /** 逐脚本配置段里承载周期任务覆写的键。 */
    public static final String KEY_SCHEDULES = "schedules";

    /** 覆写条目里承载间隔的键。 */
    public static final String KEY_INTERVAL = "intervalSeconds";

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptScheduler.class);

    /** 能力上下文，用于代发 UI 失效事件与配置告警。 */
    private final PluginContext context;

    /** 脚本调用入口（带熔断）。 */
    private final ScriptCaller caller;

    /** 已启动的调度器：一个声明了周期任务的脚本一个。 */
    private final List<ScheduledExecutorService> executors = new ArrayList<ScheduledExecutorService>();

    /** 已排定的任务数，用于诊断与测试。 */
    private int taskCount;

    /** 是否已关闭：关闭后的失败按调试级记录，不刷告警。 */
    private volatile boolean closed;

    /**
     * 构造调度器（不排任务，见 {@link #start}）。
     *
     * @param context 能力上下文，不可为 {@code null}
     * @param caller  脚本调用入口，不可为 {@code null}
     */
    private ScriptScheduler(PluginContext context, ScriptCaller caller) {
        this.context = context;
        this.caller = caller;
    }

    /**
     * 按清单声明启动全部周期任务。
     * <p>
     * <b>初始延迟等于间隔</b>：不在启动瞬间打一发。启动期发请求会把「外部接口慢」变成
     * 「内核启动慢」，而它本来只需要在下一个整间隔上开始。
     * <p>
     * <b>没有声明就不创建任何线程</b>：绝大多数脚本没有周期任务，因此这条路径必须是零成本。
     * <p>
     * <b>覆写越界只回落并告警</b>：{@code intervalSeconds} 写错（负数、比下限小、类型不对）
     * 不该让整只脚本起不来——那是「改一个观感参数把功能弄消失了」，最难归因的一类现场。
     * 回落时发一条配置告警，因为用户明明配了却没生效。
     *
     * @param context              能力上下文，不可为 {@code null}
     * @param caller               脚本调用入口，不可为 {@code null}
     * @param plugins              本次扫描到的脚本，不可为 {@code null}
     * @param scriptConfigurations 逐脚本配置段，不可为 {@code null}
     * @return 调度器，保证非 {@code null}
     */
    public static ScriptScheduler start(PluginContext context, ScriptCaller caller,
                                        List<ScriptPlugin> plugins,
                                        Map<String, Map<String, Object>> scriptConfigurations) {
        ScriptScheduler scheduler = new ScriptScheduler(context, caller);
        scheduler.scheduleAll(plugins, scriptConfigurations);
        return scheduler;
    }

    /**
     * 逐脚本排定周期任务。
     *
     * @param plugins              本次扫描到的脚本
     * @param scriptConfigurations 逐脚本配置段
     */
    private void scheduleAll(List<ScriptPlugin> plugins,
                             Map<String, Map<String, Object>> scriptConfigurations) {
        for (ScriptPlugin plugin : plugins) {
            List<ScriptManifest.Schedule> schedules = plugin.manifest().schedules();
            if (schedules.isEmpty()) {
                continue;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    daemonFactory(context.pluginId() + "-timer-" + plugin.id()));
            executors.add(executor);
            Map<String, Object> scriptConfig = scriptConfigurations.get(plugin.id());
            for (ScriptManifest.Schedule schedule : schedules) {
                int interval = effectiveInterval(plugin, schedule, scriptConfig);
                executor.scheduleWithFixedDelay(new Tick(this, plugin, schedule.name()),
                        interval, interval, TimeUnit.SECONDS);
                taskCount++;
            }
            LOG.info("脚本周期任务已排定: script={} tasks={} nextIn={}s",
                    plugin.id(), Integer.valueOf(schedules.size()),
                    Integer.valueOf(effectiveInterval(plugin, schedules.get(0), scriptConfig)));
        }
    }

    /**
     * 取某个周期任务生效的间隔秒数（含配置覆写）。
     * <p>
     * 包私有以便单测直接验证「覆写怎么解析、越界怎么回落」，不必等真定时器跳一次。
     *
     * @param plugin       目标脚本
     * @param schedule     清单声明
     * @param scriptConfig 该脚本的配置段，可为 {@code null}
     * @return 生效的间隔秒数
     */
    int effectiveInterval(ScriptPlugin plugin, ScriptManifest.Schedule schedule,
                          Map<String, Object> scriptConfig) {
        Integer override = configuredInterval(scriptConfig, schedule.name());
        if (override == null) {
            return schedule.intervalSeconds();
        }
        if (override.intValue() < ScriptManifest.MIN_INTERVAL_SECONDS) {
            warn("脚本 " + plugin.id() + " 的周期任务 " + schedule.name()
                    + " 覆写间隔为 " + override + " 秒，低于下限 " + ScriptManifest.MIN_INTERVAL_SECONDS
                    + " 秒，已回落清单声明的 " + schedule.intervalSeconds() + " 秒");
            return schedule.intervalSeconds();
        }
        return override.intValue();
    }

    /**
     * 从逐脚本配置段里取覆写的间隔。
     * <p>
     * 取值形状：{@code scripts.<脚本 id>.schedules.<任务名>.intervalSeconds}。任何一层缺失、
     * 类型不对（例如写成了字符串或小数）都当作「没配」处理——配置段的其它部分由各脚本自己解释，
     * 因此这里对不认识的东西一律不表态，只在自己的树上做严格判断。
     *
     * @param scriptConfig 该脚本的配置段，可为 {@code null}
     * @param name         任务名
     * @return 覆写的秒数；未配置或类型不对时返回 {@code null}
     */
    static Integer configuredInterval(Map<String, Object> scriptConfig, String name) {
        if (scriptConfig == null) {
            return null;
        }
        Object schedules = scriptConfig.get(KEY_SCHEDULES);
        if (!(schedules instanceof Map)) {
            return null;
        }
        Object entry = ((Map<?, ?>) schedules).get(name);
        if (!(entry instanceof Map)) {
            return null;
        }
        Object value = ((Map<?, ?>) entry).get(KEY_INTERVAL);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            double asDouble = ((Number) value).doubleValue();
            if (asDouble == Math.floor(asDouble)) {
                return Integer.valueOf(((Number) value).intValue());
            }
        }
        return null;
    }

    /**
     * 发一条配置告警。
     * <p>
     * 用事件而不是只写日志：用户在 {@code jellyfish.json} 里配了东西却没生效，
     * 是「我配了啊」这类现场的标准成因，而它必须能被界面看见——与桥接插件对
     * 「配置了不存在的脚本」的处理同一条口径。
     *
     * @param message 告警文本
     */
    private void warn(String message) {
        LOG.warn(message);
        context.emit(new ConfigWarningEvent("plugins.configurations." + context.pluginId(), message));
    }

    /**
     * 构造守护线程工厂。
     *
     * @param name 线程名
     * @return 线程工厂
     */
    private static ThreadFactory daemonFactory(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 取周期任务数量，供诊断与测试断言。
     *
     * @return 已排定的任务数
     */
    public int taskCount() {
        return taskCount;
    }

    /**
     * 关闭全部调度器，幂等。
     * <p>
     * <b>必须在网关关闭之前调用</b>：任务体会走一次脚本调用，若网关已经关了，
     * 每一次触发都会以「调用失败」的形式刷日志，而那是纯噪声。
     * <p>
     * 用 {@code shutdownNow} 与内核其它组件一致（立刻停、不等）；在途的那一次调用会被打断，
     * 其失败按调试级记录，不算告警。
     */
    @Override
    public void close() {
        closed = true;
        for (ScheduledExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    /**
     * 执行一次周期触发（包私有，供单测直接驱动，不必等真定时器跳）。
     * <p>
     * 任务体自己吞掉全部异常：{@code scheduleWithFixedDelay} 的任务一旦抛错，后续触发
     * 会<b>静默停止</b>——表现是「跑了几次就不动了」，而日志里什么都没有。
     * 关闭过程中的失败只按调试级记录（那是预期内的打断，不是故障）。
     *
     * @param plugin 目标脚本
     * @param name   任务名
     */
    void tickNow(ScriptPlugin plugin, String name) {
        if (closed) {
            // 关闭之后连调用都不发起：网关可能已经关了，发出去只会得到一条「调用失败」的噪声
            return;
        }
        ObjectNode request = ScriptJson.objectNode();
        request.put("name", name);
        try {
            caller.call(plugin, PERIODIC_TYPE, request, CancellationToken.NONE);
        } catch (RuntimeException e) {
            if (closed) {
                LOG.debug("周期任务在关闭过程中被打断: script={} task={}", plugin.id(), name);
            } else {
                // 熔断/超时/脚本自身报错都到这里。不发失效事件：数据没变，
                // 发了只会让全部面板白跑一遍（见类注释）
                LOG.warn("周期任务调用失败: script={} task={} 原因={}", plugin.id(), name, e.getMessage());
            }
            return;
        }
        emitInvalidation(plugin, name);
    }

    /**
     * 代发一次 UI 失效事件。
     * <p>
     * <b>它自己吞掉异常，这是本方法存在的理由</b>：任务体抛异常会让
     * {@code scheduleWithFixedDelay} 的后续触发<b>静默停止</b>，而「代发失效」恰好是关闭那一刻
     * 最容易抛的一步——刚查过 {@code closed} 之后上下文才关（fail-closed）就会抛。
     * 那个竞争窗口本身无害（最多多发一次没人看的失效），真正要防的是它把定时器带走。
     *
     * @param plugin 目标脚本
     * @param name   任务名
     */
    private void emitInvalidation(ScriptPlugin plugin, String name) {
        try {
            context.emit(new UiInvalidatedEvent());
        } catch (RuntimeException e) {
            if (closed) {
                LOG.debug("关闭过程中代发失效事件失败: script={} task={}", plugin.id(), name);
            } else {
                LOG.warn("代发 UI 失效事件失败: script={} task={} 原因={}", plugin.id(), name, e.getMessage());
            }
        }
    }

    /**
     * 一次周期触发。
     */
    private static final class Tick implements Runnable {

        /** 宿主，用来取上下文、调用入口与关闭标志。 */
        private final ScriptScheduler owner;

        /** 目标脚本。 */
        private final ScriptPlugin plugin;

        /** 任务名（同时是路由键）。 */
        private final String name;

        /**
         * 构造一次触发。
         *
         * @param owner  宿主
         * @param plugin 目标脚本
         * @param name   任务名
         */
        private Tick(ScriptScheduler owner, ScriptPlugin plugin, String name) {
            this.owner = owner;
            this.plugin = plugin;
            this.name = name;
        }

        @Override
        public void run() {
            owner.tickNow(plugin, name);
        }

        @Override
        public String toString() {
            return "Tick{script=" + plugin.id() + ", task=" + name + '}';
        }
    }
}
