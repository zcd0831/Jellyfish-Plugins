package zcd.jellyfish.plugin.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanConfig} 的单元测试：钉住「用户配置是唯一来源」「非法项只告警不中断」与拒绝文案。
 *
 * @author zcd
 */
@DisplayName("plan 白名单配置")
class PlanConfigTest {

    /** 收集告警，验证「只告警、不抛错」。 */
    private final List<JellyfishEvent> warnings = new ArrayList<JellyfishEvent>();

    @Test
    @DisplayName("未声明白名单即空集合：plan 下所有工具都会被拒")
    void from_should_returnEmptySet_when_absent() {
        PlanConfig config = PlanConfig.from(null, "jellyfish-plan", warnings::add);

        assertTrue(config.isEmpty());
        assertFalse(config.allows("read_file"));
        assertTrue(config.denialReason("write_file").contains("readOnlyTools"), config.denialReason("write_file"));
    }

    @Test
    @DisplayName("用户写了哪些工具名，plan 下就只有哪些可用")
    void allows_should_followUserDeclaration() {
        PlanConfig config = config(Arrays.<Object>asList("read_file", "list_dir"));

        assertTrue(config.allows("read_file"));
        assertTrue(config.allows("list_dir"));
        assertFalse(config.allows("write_file"));
        assertFalse(config.allows(null));
        assertFalse(config.isEmpty());
    }

    @Test
    @DisplayName("拒绝文案里带上白名单与「去哪儿声明」：只写「仅允许只读工具」用户不知道该改哪里")
    void denialReason_should_listWhitelistAndWhereToDeclare() {
        PlanConfig config = config(Arrays.<Object>asList("read_file", "list_dir"));

        String reason = config.denialReason("write_file");

        assertTrue(reason.contains("read_file, list_dir"), reason);
        assertTrue(reason.contains("plugins.configurations.jellyfish-plan.readOnlyTools"), reason);
    }

    @Test
    @DisplayName("白名单不是数组时按空处理并发一条告警，不中断启动")
    void from_should_warnAndIgnore_when_notArray() {
        PlanConfig config = config("read_file");

        assertTrue(config.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(((ConfigWarningEvent) warnings.get(0)).getMessage().contains("应为工具名数组"),
                ((ConfigWarningEvent) warnings.get(0)).getMessage());
    }

    @Test
    @DisplayName("含非字符串项时跳过该项、保留其余，并告警")
    void from_should_skipIllegalItems_andKeepOthers() {
        PlanConfig config = config(Arrays.<Object>asList("read_file", Integer.valueOf(3), "  "));

        assertTrue(config.allows("read_file"));
        assertFalse(config.allows("3"));
        assertEquals(2, warnings.size());
    }

    @Test
    @DisplayName("白名单为空时告警只喊一次：每次判定都喊会把这个提示变成刷屏噪音")
    void warnIfWhitelistIsEmpty_should_warnOnlyOnce() {
        PlanConfig config = PlanConfig.from(Collections.<String, Object>emptyMap(), "jellyfish-plan",
                warnings::add);

        config.warnIfWhitelistIsEmpty("write_file");
        config.warnIfWhitelistIsEmpty("edit_file");

        assertEquals(1, warnings.size());
        assertTrue(((ConfigWarningEvent) warnings.get(0)).getMessage().contains("readOnlyTools"),
                ((ConfigWarningEvent) warnings.get(0)).getMessage());
    }

    @Test
    @DisplayName("白名单非空时不发告警：那不是配置问题")
    void warnIfWhitelistIsEmpty_shouldStaySilent_whenWhitelistPresent() {
        PlanConfig config = config(Arrays.<Object>asList("read_file"));

        config.warnIfWhitelistIsEmpty("write_file");

        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("给模型看的白名单文本在为空时给出可读说明，而不是一段空白")
    void whitelistText_should_explainEmptyCase() {
        assertEquals("read_file", config(Arrays.<Object>asList("read_file")).whitelistText());
        assertTrue(PlanConfig.from(null, "jellyfish-plan", null).whitelistText().contains("未声明"));
    }

    @Test
    @DisplayName("没有告警入口时也不抛错：告警是可选的可观测性，不该成为判定链的失败点")
    void from_should_tolerateMissingWarner() {
        PlanConfig config = PlanConfig.from(null, "jellyfish-plan", null);

        config.warnIfWhitelistIsEmpty("write_file");
        PlanConfig illegal = PlanConfig.from(configuration("read_file"), "jellyfish-plan", null);

        assertTrue(illegal.isEmpty());
        assertFalse(config.allows("write_file"));
    }

    /**
     * 构造只含 {@code readOnlyTools} 的配置段。
     *
     * @param declared 白名单原值
     * @return 配置段
     */
    private static Map<String, Object> configuration(Object declared) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(PlanConfig.KEY_READ_ONLY_TOOLS, declared);
        return values;
    }

    /**
     * 用给定白名单构造配置。
     *
     * @param declared 白名单原值
     * @return 配置
     */
    private PlanConfig config(Object declared) {
        return PlanConfig.from(configuration(declared), "jellyfish-plan", warnings::add);
    }
}
