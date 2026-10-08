package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TodoScope} 的单元测试：协作键就是「归属会话」。
 * <p>
 * 这里只验「插件有没有把归属交给内核去算」：走链规则本身（穿多层临时会话、分支会话归自己）
 * 住在 {@code SessionManager}，由内核自己的用例守着。两侧各管一半，避免同一条规则被验两遍、
 * 又各自漂移。
 *
 * @author zcd
 */
@DisplayName("待办协作键")
class TodoScopeTest {

    @Test
    @DisplayName("工具那条路径：请求带的是子代理会话，键是内核给的归属会话")
    void collaborationKeyOf_should_useOwnerSessionOfRequest() {
        // Given：子代理会话 child 归 root
        TodoScope scope = TodoTestScope.of(Collections.singletonMap("child", "root"));

        // When
        String key = scope.collaborationKeyOf(new ToolCallRequest(TodoWriteTool.NAME,
                Collections.<String, Object>emptyMap(), "child", null, null, "root", "run-1", null));

        // Then：不看请求里的父会话字段，而是问内核「这个会话归谁」
        assertEquals("root", key);
    }

    @Test
    @DisplayName("回合上下文那条路径：用请求里的会话标识算出同一个键")
    void collaborationKeyOf_should_resolveSessionIdDirectly() {
        // Given：验证「两条路径算出同一个键」——否则子代理写一份、读另一份
        TodoScope scope = TodoTestScope.of(Collections.singletonMap("grand-child", "root"));

        // When / Then
        assertEquals("root", scope.collaborationKeyOf("grand-child"));
        assertEquals("root", scope.collaborationKeyOf(new ToolCallRequest(TodoWriteTool.NAME,
                Collections.<String, Object>emptyMap(), "grand-child", null, null, "child", "run-2", null)));
    }

    @Test
    @DisplayName("没有会话上下文时返回 null：没有会话就没有清单可归属")
    void collaborationKeyOf_should_returnNull_when_noSession() {
        TodoScope scope = TodoTestScope.self();

        // Then
        assertNull(scope.collaborationKeyOf((String) null));
        assertNull(scope.collaborationKeyOf("  "));
    }

    @Test
    @DisplayName("内核答不上来时返回 null，不去猜：猜错就会写到别人的清单里")
    void collaborationKeyOf_should_returnNull_when_ownerUnknown() {
        // Given：内核返回空（算不出归属）
        PluginContext context = mock(PluginContext.class);
        when(context.ownerSessionId("child")).thenReturn(null);
        when(context.ownerSessionId("blank-owner")).thenReturn("  ");

        // When / Then
        TodoScope scope = new TodoScope(context);
        assertNull(scope.collaborationKeyOf("child"));
        assertNull(scope.collaborationKeyOf("blank-owner"));
    }

    @Test
    @DisplayName("分支会话归自己：内核这么说，插件就照它读写")
    void collaborationKeyOf_should_followKernel_when_forked() {
        // Given：分支会话 fork-1 的归属就是它自己（内核的规则：它也是用户会话）
        Map<String, String> owners = new HashMap<String, String>();
        owners.put("fork-1", "fork-1");
        TodoScope scope = TodoTestScope.of(owners);

        // When / Then：请求里带着父会话（源会话）也不影响——归属由内核说了算
        assertEquals("fork-1", scope.collaborationKeyOf(new ToolCallRequest(TodoWriteTool.NAME,
                Collections.<String, Object>emptyMap(), "fork-1", null, null, "source", "run-3", null)));
    }
}
