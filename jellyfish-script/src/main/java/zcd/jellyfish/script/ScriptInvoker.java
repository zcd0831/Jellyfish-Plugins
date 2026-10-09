package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.CancellationToken;

/**
 * 一次脚本调用的通道：把「扩展点类型名 + 请求载荷 + 取消令牌」送出去，拿回结果载荷。
 * <p>
 * <b>为什么是接口而不是直接把调用方传进来</b>：{@code codec} 只该关心「请求怎么变成 JSON、
 * 结果 JSON 怎么变回值对象」，不该知道脚本标识、进程、超时与熔断这些运行时概念。
 * 让调用方在注册时把这个窄接口闭包进 {@code codec}，编解码器就能保持纯粹的纯函数，
 * 而运行时概念的演化也不会波及它们。
 * <p>
 * <b>取消令牌为什么在这里</b>：它是唯一一个「只在工具调用上有」的调用期设施，而把
 * {@code ToolCallRequest} 整份交给运行时又会让 codec 与请求类型耦合。传一个令牌是两者之间
 * 最小的共识：不关心它的 codec 一律传 {@link CancellationToken#NONE}。
 * <p>
 * 实现方<b>不返回 {@code null}</b> 是一个软约定而非硬保证：{@link ExtensionCodec} 的解码方法
 * 必须接受 {@code null}（协议层可能确实没有结果载荷），因此实现方返回 {@code null}
 * 一律按「空结果」处理。
 * <p>
 * <b>「不表态」请抛 {@link ScriptNotHandledException}</b>，不要借用 {@code null}：
 * 两者在调用点会走成同一条路（处理器返回 {@code null}），但对熔断器来说含义完全不同——
 * {@code null} 是「这次拿到了空结果」（算成功），而 {@code ScriptNotHandledException} 是
 * 「这次没去办」（不算成功也不算失败）。把它们混成一个值，坏脚本会被判成好的。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ScriptInvoker {

    /**
     * 调用一次脚本扩展点。
     *
     * @param typeName 扩展点类型名（{@code codec} 自己给出，例如 {@code tool}），不可为空白
     * @param request  请求载荷，可为 {@code null}（表示该扩展点的请求没有载荷）
     * @param token    调用级取消令牌，不可为 {@code null}（不关心取消的扩展点传 {@link CancellationToken#NONE}）
     * @return 结果载荷；脚本没有结果载荷时为 {@code null}
     */
    JsonNode invoke(String typeName, JsonNode request, CancellationToken token);
}
