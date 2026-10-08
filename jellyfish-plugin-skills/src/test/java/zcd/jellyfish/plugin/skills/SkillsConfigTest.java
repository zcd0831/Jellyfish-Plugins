package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginConfigScope;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillsConfig} 的单元测试：默认值、根目录解析与越界报错。
 *
 * @author zcd
 */
@DisplayName("skills 插件配置")
class SkillsConfigTest {

    /** 用户主目录。 */
    private static final String HOME = System.getProperty("user.home");

    @Test
    @DisplayName("项目级给的 roots 指向主目录或绝对路径一律拒绝：配置随仓库走，不该决定去哪读系统指令")
    void of_should_rejectProjectRootsOutsideProject() {
        assertProjectRootRejected("~/.jellyfish/skills");
        assertProjectRootRejected("/etc");
        assertProjectRootRejected("../../etc");
    }

    @Test
    @DisplayName("项目级给的 roots 只要是项目内的相对路径就放行")
    void of_should_keepProjectRootsInsideProject() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList("./.jellyfish/skills"));
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.configuration()).thenReturn(values);
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.PROJECT);

        // Then
        assertEquals(Collections.singletonList(
                        Paths.get(".jellyfish", "skills").toAbsolutePath().normalize()),
                SkillsConfig.of(context).roots());
    }

    @Test
    @DisplayName("全局级给的 roots 不受此限：那本来就是用户自己的地盘")
    void of_should_allowGlobalRootsAnywhere() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList("/opt/shared-skills"));
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.configuration()).thenReturn(values);
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.GLOBAL);

        // Then
        assertEquals(Collections.singletonList(Paths.get("/opt/shared-skills").toAbsolutePath().normalize()),
                SkillsConfig.of(context).roots());
    }

    @Test
    @DisplayName("来源未知时不做限制：旧容器上的行为与改造前一致")
    void of_should_notRestrict_when_scopeUnknown() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList("/opt/shared-skills"));
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.configuration()).thenReturn(values);
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.UNKNOWN);

        // Then
        assertEquals(Collections.singletonList(Paths.get("/opt/shared-skills").toAbsolutePath().normalize()),
                SkillsConfig.of(context).roots());
    }

    /**
     * 断言某个路径作为项目级 roots 会被拒。
     *
     * @param root 路径
     */
    private static void assertProjectRootRejected(String root) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root));
        PluginContext context = Mockito.mock(PluginContext.class);
        Mockito.when(context.configuration()).thenReturn(values);
        Mockito.when(context.configScope()).thenReturn(PluginConfigScope.PROJECT);

        assertThrows(JellyfishException.class, () -> SkillsConfig.of(context), root);
    }

    @Test
    @DisplayName("整段缺省时走默认根目录与默认上限")
    void from_should_useDefaults_when_configurationEmpty() {
        // When
        SkillsConfig config = SkillsConfig.from(null);

        // Then
        assertTrue(config.enabled());
        assertEquals(Arrays.asList(
                        Paths.get(HOME, ".jellyfish", "skills").toAbsolutePath().normalize(),
                        Paths.get(".jellyfish", "skills").toAbsolutePath().normalize()),
                config.roots());
        assertEquals(50, config.maxSkills());
        assertEquals(200, config.maxDescriptionChars());
        assertEquals(64 * 1024, config.maxBodyBytes());
    }

    @Test
    @DisplayName("显式空数组表示一个根都不要，而不是退回默认值")
    void from_should_keepEmptyRoots_when_explicitEmptyList() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.emptyList());

        // When
        SkillsConfig config = SkillsConfig.from(values);

        // Then
        assertTrue(config.roots().isEmpty());
    }

    @Test
    @DisplayName("根目录里的 ~ 与相对路径都应解析成规范化绝对路径")
    void from_should_resolveHomeAndRelativeRoots() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Arrays.asList("~/skills", "./local-skills"));

        // When
        SkillsConfig config = SkillsConfig.from(values);

        // Then
        assertEquals(Paths.get(HOME, "skills").toAbsolutePath().normalize(), config.roots().get(0));
        assertEquals(Paths.get("local-skills").toAbsolutePath().normalize(), config.roots().get(1));
    }

    @Test
    @DisplayName("重复的根目录只保留一次：同一个目录扫两遍只会把同名冲突记两遍")
    void from_should_deduplicateRoots() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Arrays.asList("/tmp/skills", "/tmp/skills", "/tmp/other"));

        // When
        SkillsConfig config = SkillsConfig.from(values);

        // Then
        assertEquals(2, config.roots().size());
    }

    @Test
    @DisplayName("整数配置允许写成字符串：配置文件里手写引号是常见笔误")
    void from_should_acceptNumericString_when_valueIsText() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_MAX_SKILLS, "12");

        // When
        SkillsConfig config = SkillsConfig.from(values);

        // Then
        assertEquals(12, config.maxSkills());
    }

    @Test
    @DisplayName("启停开关只接受布尔值")
    void from_should_rejectNonBoolean_when_enabledIsNotBoolean() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ENABLED, "yes");

        // When / Then
        assertThrows(JellyfishException.class, () -> SkillsConfig.from(values));
    }

    @Test
    @DisplayName("越界的数值应报错：静默夹到边界只会让「配置不生效」无从排查")
    void from_should_rejectOutOfRangeValues() {
        // Given
        Map<String, Object> tooMany = new HashMap<String, Object>();
        tooMany.put(SkillsConfig.KEY_MAX_SKILLS, 0);
        Map<String, Object> tooSmallBody = new HashMap<String, Object>();
        tooSmallBody.put(SkillsConfig.KEY_MAX_BODY_BYTES, 1);

        // When / Then
        assertThrows(JellyfishException.class, () -> SkillsConfig.from(tooMany));
        assertThrows(JellyfishException.class, () -> SkillsConfig.from(tooSmallBody));
    }

    @Test
    @DisplayName("根目录条目必须是字符串")
    void from_should_rejectNonStringRoot() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Arrays.asList(42));

        // When / Then
        assertThrows(JellyfishException.class, () -> SkillsConfig.from(values));
    }

    @Test
    @DisplayName("根目录必须是数组而不是单个字符串")
    void from_should_rejectNonListRoots() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, "~/skills");

        // When / Then
        assertThrows(JellyfishException.class, () -> SkillsConfig.from(values));
    }

    @Test
    @DisplayName("禁用开关应被读进来")
    void from_should_readDisabledFlag() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ENABLED, Boolean.FALSE);

        // When
        SkillsConfig config = SkillsConfig.from(values);

        // Then
        assertFalse(config.enabled());
    }
}
