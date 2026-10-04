package zcd.jellyfish.plugin.pet;

import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.TurnCancelledEvent;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 宠物台账：每个会话一只，由内核事件累积而成。
 * <p>
 * <b>为什么只在事件回调里建桶、读路径绝不建</b>：面板处理器挂在渲染线程上，契约要求它<b>纯只读</b>。
 * 若让它「查不到就顺手养一只」，渲染线程就在写共享状态了——而那只宠物永远不会有经历
 * （有经历必然先有事件），于是它只是一次没意义的写入。读路径因此只 {@code get}。
 * <p>
 * <b>它认识的全是内核事件，不认识任何别的插件</b>：宠物不读待办、不读编排、不解析别家工具的参数。
 * 这不只是洁癖——跨插件的私有数据是别人的实现细节，读它等于把两个插件的发版绑在一起；
 * 而内核的通知是<b>对外承诺</b>，只在该改契约时才会改。
 * <p>
 * <b>按会话分桶，子代理天然分离</b>：事件基类自带 {@code sessionId}，子代理的工具调用记在子代理会话上。
 * 因此一个会话的宠物反映的是<b>你自己</b>在这个会话里的作息与手气——那正是镜子该照的东西。
 * <p>
 * <b>不持久化</b>：会话关闭即清桶。不清的话，长进程里每开一个会话就多留一只没人看的宠物，
 * 而它再也不会被读到。
 * <p>
 * 线程安全：桶表用 {@link ConcurrentHashMap}，单只宠物的读写由 {@link SessionPet} 自己守。
 *
 * @author zcd
 */
final class PetStore {

    /** 会话标识 → 它的宠物。 */
    private final ConcurrentHashMap<String, SessionPet> pets = new ConcurrentHashMap<String, SessionPet>();

    /** 当前时间来源（epoch 毫秒）。 */
    private final LongSupplier clock;

    /** 本地时区。 */
    private final ZoneId zone;

    /** 疲惫满格时长（毫秒）。 */
    private final long fatigueFullMillis;

    /** 肥胖满格 token 数。 */
    private final long obesityFullTokens;

    /** 深夜起点（小时）。 */
    private final int nightHour;

    /**
     * 构造台账。
     *
     * @param clock             当前时间来源（epoch 毫秒），不可为 {@code null}
     * @param zone              本地时区，不可为 {@code null}
     * @param fatigueFullMillis 疲惫满格时长（毫秒）
     * @param obesityFullTokens 肥胖满格 token 数
     * @param nightHour         深夜起点（小时）
     */
    PetStore(LongSupplier clock, ZoneId zone, long fatigueFullMillis, long obesityFullTokens, int nightHour) {
        this.clock = clock;
        this.zone = zone;
        this.fatigueFullMillis = fatigueFullMillis;
        this.obesityFullTokens = obesityFullTokens;
        this.nightHour = nightHour;
    }

    /**
     * 记一次模型调用：它同时是活动、也是这个会话烧掉的那些 token。
     *
     * @param event 事件，可为 {@code null}
     */
    void onLlmCallCompleted(LlmCallCompletedEvent event) {
        SessionPet pet = petOf(event == null ? null : event.getSessionId());
        if (pet == null) {
            return;
        }
        TokenUsageSnapshot usage = event.getUsage();
        pet.onLlmCall(usage == null ? null : usage.getTotalTokens());
    }

    /**
     * 记一次失败的模型调用。
     * <p>
     * 只算活动、不累加 token（失败的调用没有用量可言），也<b>不影响伤痕</b>——
     * 伤痕记的是「工具反复失败」这个环境问题，与模型调用是两条不同的信号。
     *
     * @param event 事件，可为 {@code null}
     */
    void onLlmCallFailed(LlmCallFailedEvent event) {
        SessionPet pet = petOf(event == null ? null : event.getSessionId());
        if (pet != null) {
            pet.onLlmCall(null);
        }
    }

    /**
     * 记一次工具调用：成功会清掉连续失败，失败会累积成伤痕。
     *
     * @param event 事件，可为 {@code null}
     */
    void onToolCallCompleted(ToolCallCompletedEvent event) {
        SessionPet pet = petOf(event == null ? null : event.getSessionId());
        if (pet != null) {
            pet.onToolCall(event.isSuccess());
        }
    }

    /**
     * 记一次回合被打断。
     *
     * @param event 事件，可为 {@code null}
     */
    void onTurnCancelled(TurnCancelledEvent event) {
        SessionPet pet = petOf(event == null ? null : event.getSessionId());
        if (pet != null) {
            pet.onTurnCancelled();
        }
    }

    /**
     * 会话关闭时丢掉它养出来的那只。
     *
     * @param event 事件，可为 {@code null}
     */
    void onSessionClosed(SessionClosedEvent event) {
        if (event == null || event.getSessionId() == null) {
            return;
        }
        pets.remove(event.getSessionId());
    }

    /**
     * 取某个会话已有的宠物，不养新的。
     * <p>
     * 供渲染线程调用，因此刻意不带任何写操作。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 该会话的宠物；还没有经历过任何事时返回 {@code null}
     */
    SessionPet existing(String sessionId) {
        return sessionId == null ? null : pets.get(sessionId);
    }

    /**
     * 取某个会话的宠物，没有就养一只。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 宠物；会话标识为 {@code null} 时返回 {@code null}
     */
    private SessionPet petOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionPet existingPet = pets.get(sessionId);
        if (existingPet != null) {
            return existingPet;
        }
        // computeIfAbsent：并发事件可能同时给同一个会话养一只，而两次 put 会让先到的经历凭空消失
        return pets.computeIfAbsent(sessionId, ignored ->
                new SessionPet(clock, zone, fatigueFullMillis, obesityFullTokens, nightHour));
    }

    /**
     * 丢掉全部宠物，供插件停止时调用。
     */
    void clear() {
        pets.clear();
    }
}
