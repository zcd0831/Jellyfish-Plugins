package zcd.jellyfish.plugin.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.SubAgentPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowTool} 的单元测试：工具名片、参数校验的转交、回灌文本与元数据。
 *
 * @author zcd
 */
@DisplayName("workflow 工具")
class WorkflowToolTest {

    @Test
    @DisplayName("名片把 spec 标成必填，并给出步骤与聚合的字段")
    void descriptor_shouldDescribeSpec() {
        ToolDescriptor descriptor = WorkflowTool.descriptor();

        assertEquals(WorkflowTool.NAME, descriptor.getName());
        assertEquals(Arrays.asList("spec"), descriptor.getRequired());
        Map<String, Object> spec = (Map<String, Object>) descriptor.getParameters().get("spec");
        assertEquals("object", spec.get("type"));
        assertEquals(Arrays.asList("steps"), spec.get("required"));
        Map<String, Object> properties = (Map<String, Object>) spec.get("properties");
        assertTrue(properties.containsKey("steps"));
        assertTrue(properties.containsKey("aggregate"));
        assertTrue(descriptor.getDescription().contains("task"), descriptor.getDescription());
    }

    @Test
    @DisplayName("没有会话上下文时拒绝：子代理结果要归到某个会话上")
    void handle_shouldRejectWithoutSession() {
        WorkflowTool tool = new WorkflowTool(new WorkflowEngine(SubAgentPort.unavailable(), tracker()));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(WorkflowTool.NAME, arguments())));

        assertTrue(error.getMessage().contains("会话"), error.getMessage());
    }

    @Test
    @DisplayName("spec 非法时把校验消息原样抛出（模型据此改参数）")
    void handle_shouldSurfaceSpecValidationErrors() {
        WorkflowTool tool = new WorkflowTool(new WorkflowEngine(SubAgentPort.unavailable(), tracker()));

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> tool.handle(request(new LinkedHashMap<String, Object>())));

        assertTrue(error.getMessage().contains("spec"), error.getMessage());
    }

    @Test
    @DisplayName("正常跑完：首行是结论、随后是聚合正文、元数据里没有终态标记")
    void handle_shouldRenderSuccess() {
        WorkflowTool tool = new WorkflowTool(new WorkflowEngine(
                fixedPort(DelegationResult.completed("run-1", "调研结论", 3, 120L)), tracker()));

        ToolCallResult result = tool.handle(request(arguments()));

        String text = String.valueOf(result.getOutput());
        assertTrue(text.startsWith("[workflow 小任务 完成 · 1 步 · 3 轮 · 120 tok]"), text);
        assertTrue(text.contains("## probe（scout）"), text);
        assertTrue(text.contains("调研结论"), text);
        assertNull(result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertEquals(1, result.getMetadata().get("workflowSteps"));
        assertEquals(120L, result.getMetadata().get("workflowTokens"));
        assertTrue(String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)).contains("workflow 小任务"));
    }

    @Test
    @DisplayName("有步骤没跑成：首行点明是哪一个，末尾给出原因，并打上终态标记")
    void handle_shouldRenderFailure() {
        WorkflowTool tool = new WorkflowTool(new WorkflowEngine(
                fixedPort(DelegationResult.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）")),
                tracker()));

        ToolCallResult result = tool.handle(request(arguments()));

        String text = String.valueOf(result.getOutput());
        assertTrue(text.contains("probe 失败"), text);
        assertTrue(text.contains("失败：probe（scout）"), text);
        assertTrue(text.contains("已被禁用"), text);
        assertEquals("WORKFLOW_INCOMPLETE", result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertFalse(WorkflowSpecParser.isSuccess(DelegationResult.rejected("x")));
    }

    /**
     * 构造一个不带回调的台账：本测试只关心渲染结果，不观察面板状态。
     *
     * @return 台账
     */
    private static WorkflowTracker tracker() {
        return new WorkflowTracker(() -> {
            // 本测试不观察状态变化通知
        });
    }

    /**
     * 构造一个「每次都给出同一个结果」的假端口。
     *
     * @param result 固定结果
     * @return 端口
     */
    private static SubAgentPort fixedPort(final DelegationResult result) {
        return new SubAgentPort() {
            @Override
            public DelegationHandle spawn(DelegationRequest request) {
                return DelegationHandle.settled(result);
            }
        };
    }

    /**
     * 构造一次工具调用请求。
     *
     * @param arguments 参数
     * @return 请求
     */
    private static ToolCallRequest request(Map<String, Object> arguments) {
        return new ToolCallRequest(WorkflowTool.NAME, arguments, "s-1");
    }

    /**
     * 构造一份单步 spec 的参数。
     *
     * @return 参数
     */
    private static Map<String, Object> arguments() {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("id", "probe");
        step.put("agent", "scout");
        step.put("prompt", "查一下");
        java.util.List<Object> steps = new ArrayList<Object>();
        steps.add(step);
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("name", "小任务");
        spec.put("steps", steps);
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("spec", spec);
        return arguments;
    }
}
