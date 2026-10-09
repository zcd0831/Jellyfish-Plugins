package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 脚本周期任务宿主的单元测试。
 * <p>
 * 钉住的是四条容易在改动中丢掉的性质：<b>没有声明就不创建任何线程</b>、
 * <b>调用成功才代发界面失效</b>、<b>一次失败不会让后续触发停掉</b>（{@code scheduleWithFixedDelay}
 * 的任务体抛异常会静默停止，那是这类代码最经典的坑）、以及<b>配置覆写越界回落</b>。
 * <p>
 * 触发逻辑大部分用包私有的 {@link ScriptScheduler#tickNow} 直接驱动（确定性、毫秒级），
 * 只用一条真定时器的用例来证明「排定本身是通的」——否则每个用例都要等一秒，
 * 而它们要验的并不是定时精度。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("脚本周期任务宿主")
class ScriptSchedulerTest {

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("没有周期任务声明时不创建任何调度器，也不调用脚本")
    void start_should_scheduleNothing_when_noScheduleDeclared() {
        RecordingCaller caller = new RecordingCaller();

        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("plain", null)), Collections.<String, Map<String, Object>>emptyMap());

        assertEquals(0, scheduler.taskCount());
        assertTrue(caller.names.isEmpty());
        scheduler.close();
    }

    @Test
    @DisplayName("调用成功时应代发一次界面失效，并把任务名作为载荷发下去")
    void tickNow_should_emitInvalidated_when_callSucceeds() {
        RecordingCaller caller = new RecordingCaller();
        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\"}]")),
                Collections.<String, Map<String, Object>>emptyMap());

        scheduler.tickNow(plugin("watch", "[{\"name\":\"refresh\"}]"), "refresh");

        assertEquals(1, caller.names.size());
        assertEquals("periodic:refresh", caller.names.get(0));
        ArgumentCaptor<JellyfishEvent> emitted = ArgumentCaptor.forClass(JellyfishEvent.class);
        verify(context, times(1)).emit(emitted.capture());
        // 脚本发布不了这个事件（脚本白名单只有通知与告警），因此必须由桥接层代发
        assertInstanceOf(UiInvalidatedEvent.class, emitted.getValue());
        scheduler.close();
    }

    @Test
    @DisplayName("调用失败时只记日志，不发界面失效：数据没变，重画面板只是白跑")
    void tickNow_should_notEmitInvalidated_when_callFails() {
        RecordingCaller caller = new RecordingCaller();
        caller.alwaysFail = true;
        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\"}]")),
                Collections.<String, Map<String, Object>>emptyMap());

        scheduler.tickNow(plugin("watch", "[{\"name\":\"refresh\"}]"), "refresh");

        verify(context, never()).emit(any());
        scheduler.close();
    }

    @Test
    @DisplayName("真定时器：上一次调用失败也不会让后续触发停掉，恢复后照常发失效")
    void start_should_keepFiring_when_callFails() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        // 第一次失败、之后成功：既要证明「失败不停」，也要证明「恢复后照常发失效」
        caller.failuresBeforeSuccess = 1;
        Map<String, Object> scriptConfig = new LinkedHashMap<String, Object>();
        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\",\"intervalSeconds\":1}]")),
                Collections.<String, Map<String, Object>>emptyMap());

        // 第一次触发在 1 秒后（初始延迟＝间隔）并失败，第二次在 2 秒后成功：
        // 能等到成功，就说明一次失败没有把后续触发停掉
        assertTrue(caller.oneSuccess.await(6L, TimeUnit.SECONDS), "失败之后周期任务没有继续触发");
        assertTrue(caller.names.size() >= 1, "实际成功次数 " + caller.names.size());
        // 失效广播发生在调度线程上（紧跟在脚本调用之后），因此要等它，而不是立即断言
        verify(context, org.mockito.Mockito.timeout(3000).atLeastOnce()).emit(any());
        scheduler.close();
    }

    @Test
    @DisplayName("代发失效事件失败也不该把定时任务带走（关闭竞态最容易在这一步抛）")
    void tickNow_should_survive_when_emitFails() {
        // 关闭那一刻上下文是 fail-closed 的：emit 会抛。任务体若把异常抛给 scheduleWithFixedDelay，
        // 后续触发会静默停止——那正是本用例要钉住的东西
        org.mockito.Mockito.doThrow(new JellyfishException("上下文已关闭")).when(context).emit(any());
        ScriptScheduler scheduler = ScriptScheduler.start(context, new RecordingCaller(),
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\"}]")),
                Collections.<String, Map<String, Object>>emptyMap());

        // 不抛出去就是全部要求：调用本身成功，只是「重画面板」这一步失败了
        scheduler.tickNow(plugin("watch", "[{\"name\":\"refresh\"}]"), "refresh");

        scheduler.close();
    }

    @Test
    @DisplayName("关闭之后不再触发（在途的一次按调试级吞掉，不再发失效）")
    void close_should_stopFiring_when_called() throws Exception {
        RecordingCaller caller = new RecordingCaller();
        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\",\"intervalSeconds\":1}]")),
                Collections.<String, Map<String, Object>>emptyMap());

        scheduler.close();
        int afterClose = caller.names.size();
        Thread.sleep(1500L);

        assertEquals(afterClose, caller.names.size(), "关闭后不该再有触发");
        scheduler.close();
    }

    @Test
    @DisplayName("配置覆写只认整数：小数、字符串与缺失都当作没配")
    void configuredInterval_should_readOnlyIntegralValues() {
        Map<String, Object> schedules = new HashMap<String, Object>();
        Map<String, Object> entry = new HashMap<String, Object>();
        entry.put("intervalSeconds", 10);
        schedules.put("refresh", entry);
        Map<String, Object> scriptConfig = new HashMap<String, Object>();
        scriptConfig.put("schedules", schedules);

        assertEquals(Integer.valueOf(10), ScriptScheduler.configuredInterval(scriptConfig, "refresh"));
        assertNull(ScriptScheduler.configuredInterval(scriptConfig, "other"));
        assertNull(ScriptScheduler.configuredInterval(null, "refresh"));
        assertNull(ScriptScheduler.configuredInterval(Collections.<String, Object>emptyMap(), "refresh"));

        entry.put("intervalSeconds", 10.0);
        assertEquals(Integer.valueOf(10), ScriptScheduler.configuredInterval(scriptConfig, "refresh"));
        // 小数秒没有意义：宁可当作没配，也不要悄悄截断
        entry.put("intervalSeconds", 10.5);
        assertNull(ScriptScheduler.configuredInterval(scriptConfig, "refresh"));
        entry.put("intervalSeconds", "10");
        assertNull(ScriptScheduler.configuredInterval(scriptConfig, "refresh"));
    }

    @Test
    @DisplayName("覆写有效时用它，越界时回落声明值并发一条配置告警")
    void effectiveInterval_should_fallBackAndWarn_when_overrideIsBelowFloor() {
        RecordingCaller caller = new RecordingCaller();
        ScriptScheduler scheduler = ScriptScheduler.start(context, caller,
                Collections.singletonList(plugin("watch", "[{\"name\":\"refresh\",\"intervalSeconds\":60}]")),
                Collections.<String, Map<String, Object>>emptyMap());
        ScriptPlugin plugin = plugin("watch", "[{\"name\":\"refresh\",\"intervalSeconds\":60}]");
        ScriptManifest.Schedule schedule = plugin.manifest().schedules().get(0);

        Map<String, Object> schedules = new HashMap<String, Object>();
        Map<String, Object> entry = new HashMap<String, Object>();
        entry.put("intervalSeconds", 5);
        schedules.put("refresh", entry);
        Map<String, Object> scriptConfig = new HashMap<String, Object>();
        scriptConfig.put("schedules", schedules);
        assertEquals(5, scheduler.effectiveInterval(plugin, schedule, scriptConfig));

        entry.put("intervalSeconds", 0);
        assertEquals(60, scheduler.effectiveInterval(plugin, schedule, scriptConfig));

        // 越界回落必须喊一声：用户明明配了却没生效，是最难归因的一类现场
        ArgumentCaptor<JellyfishEvent> emitted = ArgumentCaptor.forClass(JellyfishEvent.class);
        verify(context, times(1)).emit(emitted.capture());
        assertInstanceOf(zcd.jellyfish.api.event.notification.ConfigWarningEvent.class, emitted.getValue());
        scheduler.close();
    }

    /**
     * 造一个脚本：清单里只有入口与可选的周期任务声明。
     *
     * @param id            脚本标识
     * @param schedulesJson {@code schedules} 段的 JSON 正文，可为 {@code null}
     * @return 脚本
     */
    private static ScriptPlugin plugin(String id, String schedulesJson) {
        String json = "{\"entry\":\"main.py\""
                + (schedulesJson == null ? "" : ",\"schedules\":" + schedulesJson) + "}";
        ScriptManifest manifest = ScriptManifest.parse(json, id, ExtensionCodecs.DEFAULTS);
        return new ScriptPlugin(id, Paths.get("/tmp", id), manifest);
    }

    /**
     * 记录调用的假调用入口。
     * <p>
     * 用假的而不是真网关：这些用例要验的是「触发时机与失效广播」，与进程、协议无关；
     * 真网关会把用例变成「测环境里有没有解释器」。
     */
    private static final class RecordingCaller implements ScriptCaller {

        /** 已发生的调用，形如 {@code periodic:refresh}。 */
        private final List<String> names = Collections.synchronizedList(new ArrayList<String>());

        /** 成功一次后放行，供真定时器用例等待（只有成功才放行：断言时数据一定已经写好）。 */
        private final CountDownLatch oneSuccess = new CountDownLatch(1);

        /** 是否一直失败。 */
        private boolean alwaysFail;

        /** 前几次调用故意失败，之后转为成功；0 表示一直成功。 */
        private int failuresBeforeSuccess;

        /** 已发生的调用次数（含失败的那些）。 */
        private int attempts;

        @Override
        public JsonNode call(ScriptPlugin plugin, String typeName, JsonNode request) {
            attempts++;
            if (alwaysFail) {
                throw new JellyfishException("行情源不可用");
            }
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--;
                throw new JellyfishException("第一次失败");
            }
            names.add(typeName + ":" + request.path("name").asText());
            oneSuccess.countDown();
            return null;
        }
    }
}
