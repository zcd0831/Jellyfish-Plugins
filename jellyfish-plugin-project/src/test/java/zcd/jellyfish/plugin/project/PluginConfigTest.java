package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginConfigScope;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PluginConfig} 的单元测试：缺省、显式取值与非法值的失败口径。
 *
 * @author zcd
 */
@DisplayName("项目约定插件配置")
class PluginConfigTest {

    @Test
    @DisplayName("内联上限只认全局级那份：项目级把它调到 1 MiB 也不生效")
    void of_should_ignoreProjectLevelMaxInlineBytes() {
        // Given：全局级设了 4 KiB，项目级把同一个键调到 1 MiB（整段替换会顶掉全局级那份）
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.configuration()).thenReturn(configOf(PluginConfig.MAX_INLINE_BYTES_LIMIT));
        Mockito.when(context.globalConfiguration()).thenReturn(configOf(4096));
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.PROJECT);

        // Then：读到的是用户自己设的 4 KiB，项目级的调宽被忽略
        assertEquals(4096, PluginConfig.of(context).maxInlineBytes());
    }

    @Test
    @DisplayName("项目级只加了个无关的键时，全局级设的上限不能被一起丢掉")
    void of_should_keepGlobalValue_when_projectLevelHasOtherKeys() {
        // Given：这正是「来源是项目级就整段忽略」那种写法会出错的地方
        PluginContext context = Mockito.mock(PluginContext.class);
        Map<String, Object> projectLevel = new java.util.LinkedHashMap<String, Object>();
        projectLevel.put("somethingElse", "x");
        Mockito.when(context.configuration()).thenReturn(projectLevel);
        Mockito.when(context.globalConfiguration()).thenReturn(configOf(2048));
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.PROJECT);

        // Then
        assertEquals(2048, PluginConfig.of(context).maxInlineBytes());
    }

    @Test
    @DisplayName("来源未知时退回合并值：旧容器上的行为与改造前一致")
    void of_should_fallBackToMergedValue_when_scopeUnknown() {
        // Given：不区分来源的容器（globalConfiguration 的默认实现返回 configuration）
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.globalConfiguration()).thenReturn(configOf(8192));
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.UNKNOWN);

        assertEquals(8192, PluginConfig.of(context).maxInlineBytes());
    }

    @Test
    @DisplayName("配置段缺失时用缺省内联上限")
    void from_should_useDefault_when_configurationAbsent() {
        assertEquals(PluginConfig.DEFAULT_MAX_INLINE_BYTES, PluginConfig.from(null).maxInlineBytes());
    }

    @Test
    @DisplayName("配置键缺失时用缺省内联上限")
    void from_should_useDefault_when_keyAbsent() {
        PluginConfig config = PluginConfig.from(Collections.<String, Object>emptyMap());

        assertEquals(PluginConfig.DEFAULT_MAX_INLINE_BYTES, config.maxInlineBytes());
    }

    @Test
    @DisplayName("显式数字应按原值生效")
    void from_should_honourConfiguredNumber() {
        assertEquals(4096, PluginConfig.from(configOf(4096)).maxInlineBytes());
    }

    @Test
    @DisplayName("字符串形式的数字也应生效：JSON 之外的配置来源给的就是字符串")
    void from_should_honourConfiguredString() {
        assertEquals(512, PluginConfig.from(configOf("512")).maxInlineBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, PluginConfig.MAX_INLINE_BYTES_LIMIT})
    @DisplayName("边界值 0 与上限都应被接受：0 表示从不内联")
    void from_should_acceptBoundaryValues(int configured) {
        assertEquals(configured, PluginConfig.from(configOf(configured)).maxInlineBytes());
    }

    @Test
    @DisplayName("负数应直接抛：它既不是「关掉」也不是任何有意义的取值")
    void from_should_throw_when_negative() {
        Map<String, Object> configuration = configOf(-1);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(configuration));
    }

    @Test
    @DisplayName("超过上限应直接抛：那是往每轮 system prompt 里塞文本的笔误")
    void from_should_throw_when_aboveLimit() {
        Map<String, Object> configuration = configOf(PluginConfig.MAX_INLINE_BYTES_LIMIT + 1);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(configuration));
    }

    @Test
    @DisplayName("非数字字符串应直接抛")
    void from_should_throw_when_notANumber() {
        Map<String, Object> configuration = configOf("大一点");

        assertThrows(JellyfishException.class, () -> PluginConfig.from(configuration));
    }

    @Test
    @DisplayName("类型不对应直接抛")
    void from_should_throw_when_typeIsWrong() {
        Map<String, Object> configuration = configOf(Boolean.TRUE);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(configuration));
    }

    /**
     * 构造只含内联上限一项的配置段。
     *
     * @param value 配置原值
     * @return 配置段
     */
    private static Map<String, Object> configOf(Object value) {
        return Collections.<String, Object>singletonMap(PluginConfig.KEY_MAX_INLINE_BYTES, value);
    }
}
