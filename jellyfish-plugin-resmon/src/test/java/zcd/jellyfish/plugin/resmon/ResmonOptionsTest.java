package zcd.jellyfish.plugin.resmon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResmonOptions} 的单元测试：候选清单完整，且「当前值」标记跟随真实开关。
 * <p>
 * 候选查询是一条只读路径，所以这里还要验证它<b>不产生副作用</b>——外壳会在用户还没决定参数时
 * 就调它一次，若它顺手改了状态，「弹一下候选」就会把面板关掉。
 *
 * @author zcd
 */
@DisplayName("resmon 候选查询")
class ResmonOptionsTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path root;

    /** 采样器。 */
    private ResmonSampler sampler;

    /** 被测处理器。 */
    private ResmonOptions options;

    @BeforeEach
    void setUp() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_BASE_DIR, root.toString());
        sampler = new ResmonSampler(PluginConfig.from(values), Mockito.mock(JvmProbe.class),
                new DirSizer(2), Mockito.mock(PluginContext.class));
        options = new ResmonOptions(sampler);
    }

    @Test
    @DisplayName("给出五个候选：jvm / disk / auto on / auto off / help")
    void handle_should_offer_all_subcommands() {
        CommandOptions result = options.handle(new CommandOptionRequest(ResmonCommand.NAME, "s-1"));

        assertEquals(5, result.getChoices().size());
        assertTrue(has(result, ResmonCommand.ARG_JVM));
        assertTrue(has(result, ResmonCommand.ARG_DISK));
        assertTrue(has(result, ResmonCommand.VALUE_AUTO_ON));
        assertTrue(has(result, ResmonCommand.VALUE_AUTO_OFF));
        assertTrue(has(result, ResmonCommand.ARG_HELP));
    }

    @Test
    @DisplayName("「当前值」标记落在与真实开关一致的那一个上")
    void handle_should_mark_current_auto_state() {
        assertTrue(current(options.handle(new CommandOptionRequest(ResmonCommand.NAME, "s-1")),
                ResmonCommand.VALUE_AUTO_ON));

        sampler.autoRefresh(false);

        assertTrue(current(options.handle(new CommandOptionRequest(ResmonCommand.NAME, "s-1")),
                ResmonCommand.VALUE_AUTO_OFF));
    }

    @Test
    @DisplayName("候选查询是只读的：问一次不会改开关")
    void handle_should_not_change_state() {
        boolean before = sampler.autoRefresh();

        options.handle(new CommandOptionRequest(ResmonCommand.NAME, "s-1"));

        assertEquals(before, sampler.autoRefresh());
    }

    @Test
    @DisplayName("候选里没有清理类动作：本插件只读")
    void handle_should_not_offer_destructive_actions() {
        String values = text(options.handle(new CommandOptionRequest(ResmonCommand.NAME, "s-1")));

        assertFalse(values.contains("clean"), values);
        assertFalse(values.contains("delete"), values);
        assertFalse(values.contains("清理"), values);
    }

    /**
     * 判断候选清单里是否有某个取值。
     *
     * @param result 候选结果，不可为 {@code null}
     * @param value  取值
     * @return 存在返回 {@code true}
     */
    private static boolean has(CommandOptions result, String value) {
        for (CommandChoice choice : result.getChoices()) {
            if (value.equals(choice.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断某个取值是否被标成当前值。
     *
     * @param result 候选结果，不可为 {@code null}
     * @param value  取值
     * @return 是当前值返回 {@code true}
     */
    private static boolean current(CommandOptions result, String value) {
        for (CommandChoice choice : result.getChoices()) {
            if (value.equals(choice.getValue())) {
                return choice.isCurrent();
            }
        }
        return false;
    }

    /**
     * 把候选清单拼成文本，供「不含破坏性动作」这类断言使用。
     *
     * @param result 候选结果，不可为 {@code null}
     * @return 文本
     */
    private static String text(CommandOptions result) {
        StringBuilder out = new StringBuilder();
        for (CommandChoice choice : result.getChoices()) {
            out.append(choice.getValue()).append(' ').append(choice.getLabel()).append(' ')
                    .append(choice.getDescription()).append('\n');
        }
        return out.toString();
    }
}
