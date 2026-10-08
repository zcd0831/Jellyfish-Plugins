package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellEnvironment} 的单元测试。
 * <p>
 * 关注点：剔除敏感变量是否默认生效、防挂死变量是否注入、以及用户显式配置能不能盖住默认值。
 *
 * @author zcd
 */
@DisplayName("ShellEnvironment")
class ShellEnvironmentTest {

    @Test
    @DisplayName("内置剔除表应恰好是这一份：它是「只看名单不看值」的，少一条就是漏一个族")
    void defaultSensitivePatterns_should_matchExactly() {
        // Then：等值断言（含顺序）挡住「把 *_PAT* 手滑写成 *PAT*」这类改动——
        // 那种改动不会让任何一条功能用例变红，却会让 PATH 被剥掉
        assertEquals(Arrays.asList("*KEY*", "*TOKEN*", "*SECRET*", "*PASSWORD*", "*CREDENTIAL*",
                "*_PAT*", "*AUTH*", "*NETRC*", "*PROXY*", "*PASSPHRASE*", "*_PWD"),
                PluginConfig.DEFAULT_SENSITIVE_PATTERNS);
    }

    @Test
    @DisplayName("默认剔除名字里带凭据关键词的环境变量")
    void build_should_drop_sensitive_variables() {
        Map<String, String> parent = new HashMap<String, String>();
        parent.put("PATH", "/usr/bin");
        parent.put("AWS_SECRET_ACCESS_KEY", "泄漏");
        parent.put("GITHUB_TOKEN", "泄漏");
        parent.put("DB_PASSWORD", "泄漏");
        parent.put("MY_CREDENTIAL", "泄漏");
        parent.put("SOME_KEY", "泄漏");

        Map<String, String> environment = ShellEnvironment.build(parent,
                ShellEnvironment.sensitivePatterns(null), null);

        assertEquals("/usr/bin", environment.get("PATH"));
        for (String name : Arrays.asList("AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "DB_PASSWORD",
                "MY_CREDENTIAL", "SOME_KEY")) {
            assertNull(environment.get(name), name + " 不该被传给子进程");
        }
    }

    @Test
    @DisplayName("易漏的几族凭据也要剔除：PAT、AUTH、NETRC、代理、口令短语、数据库口令")
    void build_should_drop_lessObviousCredentialFamilies() {
        // Given：这些都不含 KEY/TOKEN/SECRET/PASSWORD/CREDENTIAL，旧表全放过
        Map<String, String> parent = new HashMap<String, String>();
        parent.put("GITHUB_PAT", "泄漏");
        parent.put("SSH_AUTH_SOCK", "/tmp/agent.sock");
        parent.put("NETRC", "/home/u/.netrc");
        parent.put("HTTP_PROXY", "http://user:pass@proxy:8080");
        parent.put("HTTPS_PROXY", "http://user:pass@proxy:8080");
        parent.put("GPG_PASSPHRASE", "泄漏");
        parent.put("MYSQL_PWD", "泄漏");

        Map<String, String> environment = ShellEnvironment.build(parent,
                ShellEnvironment.sensitivePatterns(null), null);

        for (String name : Arrays.asList("GITHUB_PAT", "SSH_AUTH_SOCK", "NETRC", "HTTP_PROXY",
                "HTTPS_PROXY", "GPG_PASSPHRASE", "MYSQL_PWD")) {
            assertNull(environment.get(name), name + " 不该被传给子进程");
        }
    }

    @Test
    @DisplayName("名字里带关键词但不是凭据的变量必须留住——把 PATH 剔掉等于几乎每条命令都 command not found")
    void build_should_keepLookalikeNamesThatCarryNoSecret() {
        // Given：*PAT* 会命中 PATH、*PWD* 会命中 shell 自带的 PWD，因此通配模式只能写成 *_PAT* / *_PWD
        Map<String, String> parent = new HashMap<String, String>();
        parent.put("PATH", "/usr/bin:/bin");
        parent.put("PWD", "/home/u");
        parent.put("OLDPWD", "/home");
        parent.put("CLASSPATH", "/tmp/classes");
        parent.put("NPM_CONFIG_PREFIX", "/usr/local");

        Map<String, String> environment = ShellEnvironment.build(parent,
                ShellEnvironment.sensitivePatterns(null), null);

        assertEquals("/usr/bin:/bin", environment.get("PATH"));
        assertEquals("/home/u", environment.get("PWD"));
        assertEquals("/home", environment.get("OLDPWD"));
        assertEquals("/tmp/classes", environment.get("CLASSPATH"));
        assertEquals("/usr/local", environment.get("NPM_CONFIG_PREFIX"));
    }

    @Test
    @DisplayName("默认注入防挂死变量——否则 git log 会停在分页器里等按键")
    void build_should_inject_anti_hang_variables() {
        Map<String, String> environment = ShellEnvironment.build(null, null, null);

        assertEquals("cat", environment.get("PAGER"));
        assertEquals("cat", environment.get("GIT_PAGER"));
        assertEquals("0", environment.get("GIT_TERMINAL_PROMPT"));
        assertEquals("dumb", environment.get("TERM"));
    }

    @Test
    @DisplayName("用户配置盖住默认值——「可配置」的含义就是我说了算")
    void build_should_let_user_overrides_win() {
        Map<String, String> overrides = new HashMap<String, String>();
        overrides.put("TERM", "xterm-256color");
        overrides.put("CUSTOM", "1");

        Map<String, String> environment = ShellEnvironment.build(null, null, overrides);

        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("1", environment.get("CUSTOM"));
        assertEquals("cat", environment.get("PAGER"));
    }

    @Test
    @DisplayName("用户追加的剔除模式叠加在内置表之上")
    void sensitivePatterns_should_extend_defaults() {
        List<String> patterns = ShellEnvironment.sensitivePatterns(Arrays.asList("*INTERNAL*"));

        Map<String, String> parent = new HashMap<String, String>();
        parent.put("INTERNAL_HOST", "x");
        parent.put("API_KEY", "y");

        Map<String, String> environment = ShellEnvironment.build(parent, patterns, null);

        assertNull(environment.get("INTERNAL_HOST"));
        assertNull(environment.get("API_KEY"));
    }

    @ParameterizedTest
    @DisplayName("通配匹配大小写不敏感，且支持 ? ")
    @ValueSource(strings = {"*KEY*", "*key*", "AWS_*", "A?S_KEY"})
    void matches_should_be_caseInsensitive(String pattern) {
        assertTrue(ShellEnvironment.matches(pattern, "AWS_KEY"));
    }

    @Test
    @DisplayName("通配模式不匹配无关名字")
    void matches_should_not_match_unrelated() {
        assertFalse(ShellEnvironment.matches("*TOKEN*", "PATH"));
        assertFalse(ShellEnvironment.matches("API_KEY", "API_KEY_SUFFIX_MISMATCH_X"));
    }

    @Test
    @DisplayName("空环境也能构造，不抛异常")
    void build_should_tolerate_empty_input() {
        assertFalse(ShellEnvironment.build(null, null, null).isEmpty());
        assertFalse(ShellEnvironment.build(new HashMap<String, String>(),
                ShellEnvironment.sensitivePatterns(null), null).isEmpty());
    }
}
