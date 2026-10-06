package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputReferenceDescriptor;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolsPlugin} 与 {@link PluginTool} 注册语义的单元测试。
 * <p>
 * 重点锁住「名片里的名字就是路由键」：注册时名字写两遍是最容易出错的地方，
 * 一处笔误就会让工具在模型侧可见、实际却调不到。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolsPlugin 工具注册")
class ToolsPluginTest {

    /** 插件上下文，只验证注册调用。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("启动时应注册全部六个工具，且都挂在 ToolCallRequest 上")
    void start_should_registerEveryTool() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolDescriptor> descriptors = ArgumentCaptor.forClass(ToolDescriptor.class);

        new ToolsPlugin().start(context);

        verify(context, times(6)).handle(eq(ToolCallRequest.class), names.capture(), descriptors.capture(),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        List<String> registered = new ArrayList<String>(names.getAllValues());
        assertTrue(registered.containsAll(Arrays.asList(
                "read_file", "write_file", "edit_file", "list_dir", "grep_files", "ask_user")),
                registered.toString());
    }

    @Test
    @DisplayName("注册用的路由键必须与名片里的名字一致")
    void start_should_useDescriptorNameAsRouteKey() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolDescriptor> descriptors = ArgumentCaptor.forClass(ToolDescriptor.class);

        new ToolsPlugin().start(context);

        verify(context, times(6)).handle(eq(ToolCallRequest.class), names.capture(), descriptors.capture(),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        for (int i = 0; i < names.getAllValues().size(); i++) {
            assertEquals(descriptors.getAllValues().get(i).getName(), names.getAllValues().get(i));
        }
    }

    @Test
    @DisplayName("工具清单里的名字不得重复：同名工具注册会互相覆盖")
    void start_should_registerDistinctNames() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);

        new ToolsPlugin().start(context);

        verify(context, times(6)).handle(eq(ToolCallRequest.class), names.capture(), any(ToolDescriptor.class),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        assertEquals(6, names.getAllValues().stream().distinct().count());
    }

    @Test
    @DisplayName("提问工具拿的是上下文给出的端口，而不是能力缺失的占位")
    @SuppressWarnings("unchecked")
    void start_should_giveTheAskToolThePortFromContext() throws Exception {
        // Given：一个可辨认的端口实现
        AtomicReference<AskRequest> captured = new AtomicReference<AskRequest>();
        when(context.askUser()).thenReturn(request -> {
            captured.set(request);
            return AskAnswer.answered("a");
        });
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ExtensionHandler<ToolCallRequest, ToolCallResult>> handlers =
                ArgumentCaptor.forClass(ExtensionHandler.class);

        // When
        new ToolsPlugin().start(context);

        // Then：ask_user 那一条的处理器调下去，必须走到宿主给的端口上
        verify(context, times(6)).handle(eq(ToolCallRequest.class), names.capture(), any(ToolDescriptor.class),
                handlers.capture());
        int index = names.getAllValues().indexOf("ask_user");
        assertTrue(index >= 0, names.getAllValues().toString());
        handlers.getAllValues().get(index).handle(new ToolCallRequest("ask_user", askArguments()));
        assertNotNull(captured.get(), "提问没有走到上下文给出的端口上");
        assertEquals("选哪个？", captured.get().getQuestion());
    }

    /**
     * 构造一次提问调用的最小参数。
     *
     * @return 参数映射
     */
    private static Map<String, Object> askArguments() {
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        for (String label : Arrays.asList("甲", "乙")) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", label);
            options.add(item);
        }
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("question", "选哪个？");
        arguments.put("options", options);
        return arguments;
    }

    @Test
    @DisplayName("单个工具注册时应把自身实例作为处理器交出")
    void register_should_bindToolInstance() {
        ReadFileTool tool = new ReadFileTool();

        tool.register(context);

        verify(context).handle(eq(ToolCallRequest.class), eq("read_file"), eq(tool.descriptor()), eq(tool));
    }

    @Test
    @DisplayName("启动时应注册 @ 引用补全与约定贡献")
    void start_should_registerReferenceCompletionAndConvention() {
        new ToolsPlugin().start(context);

        verify(context).handle(eq(InputReferenceRequest.class), eq(FileReferenceCompletion.MARKER),
                any(InputReferenceDescriptor.class),
                any(ExtensionHandler.class));
        verify(context).contribute(eq(PromptContributionRequest.class),
                any(FileReferencePromptContribution.class));
    }
}
