package zcd.jellyfish.plugin.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginConfigScope;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-plugin-skills} 段解析成值对象。
 * <p>
 * <b>根目录是列表而不是单个目录</b>：主流用法就是「用户级 + 项目级」两个根叠加，而且顺序有意义
 * ——前面的根优先，同名的 skill 只保留先出现的那一个（项目级想覆盖用户级，把它写在前面即可）。
 * <p>
 * <b>两个文件名约定都是「目录 / SKILL.md」</b>：名字固定，因此没有「这个目录到底算不算 skill」
 * 的判别问题——不含 {@code SKILL.md} 的子目录直接跳过。
 * <p>
 * <b>默认根目录落在内核的约定目录里</b>（全局 {@code ~/.jellyfish/skills}、项目
 * {@code ./.jellyfish/skills}）：skills 是用户数据，和配置、会话、插件平级，不该另起一套目录约定。
 * <p>
 * <b>项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换由内核完成</b>，这里拿到的就是最终值；
 * 但 {@code ~} <b>没有</b>被展开（内核只在配置文件的路径段上做这件事），因此这里自己展开一次。
 * <p>
 * <b>类型不对就报错，不退回默认值</b>：写错的配置静默走默认值，是「配置不生效」这类
 * 最难排查问题的标准成因。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SkillsConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SkillsConfig.class);

    /** 根目录列表配置键。 */
    static final String KEY_ROOTS = "roots";

    /** 启用开关配置键。 */
    static final String KEY_ENABLED = "enabled";

    /** 清单条数上限配置键。 */
    static final String KEY_MAX_SKILLS = "maxSkills";

    /** 单条 description 展示上限配置键。 */
    static final String KEY_MAX_DESCRIPTION_CHARS = "maxDescriptionChars";

    /** 单次加载正文的字节上限配置键。 */
    static final String KEY_MAX_BODY_BYTES = "maxBodyBytes";

    /** 缺省根目录：用户级在前、项目级在后，与内核的约定目录（{@code ~/.jellyfish/}）一致。 */
    static final List<String> DEFAULT_ROOTS =
            Collections.unmodifiableList(Arrays.asList("~/.jellyfish/skills", "./.jellyfish/skills"));

    /** 缺省启用。 */
    static final boolean DEFAULT_ENABLED = true;

    /** 缺省清单条数上限：足够覆盖真实用法，同时把每轮 system prompt 的固定开销封住。 */
    static final int DEFAULT_MAX_SKILLS = 50;

    /** 缺省 description 展示长度。 */
    static final int DEFAULT_MAX_DESCRIPTION_CHARS = 200;

    /** 缺省正文读取上限：64 KiB，与 tools 插件的 {@code read_file} 同口径。 */
    static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    /** 清单条数上限允许的最大值。 */
    static final int MAX_SKILLS_LIMIT = 500;

    /** description 展示长度允许的最大值。 */
    static final int MAX_DESCRIPTION_CHARS_LIMIT = 2000;

    /** 正文读取上限允许的最大值：4 MiB。 */
    static final int MAX_BODY_BYTES_LIMIT = 4 * 1024 * 1024;

    /** 是否启用工具与清单贡献。 */
    private final boolean enabled;

    /** 根目录，按优先级顺序排列。 */
    private final List<Path> roots;

    /** 清单条数上限。 */
    private final int maxSkills;

    /** description 展示长度上限。 */
    private final int maxDescriptionChars;

    /** 正文读取字节上限。 */
    private final int maxBodyBytes;

    /**
     * 构造配置。
     *
     * @param enabled             是否启用
     * @param roots               根目录，按优先级顺序
     * @param maxSkills           清单条数上限
     * @param maxDescriptionChars description 展示长度上限
     * @param maxBodyBytes        正文读取字节上限
     */
    private SkillsConfig(boolean enabled, List<Path> roots, int maxSkills, int maxDescriptionChars,
                         int maxBodyBytes) {
        this.enabled = enabled;
        this.roots = roots;
        this.maxSkills = maxSkills;
        this.maxDescriptionChars = maxDescriptionChars;
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * 从插件上下文解析配置。
     * <p>
     * <b>{@code roots} 来自项目级时必须留在项目目录内</b>：「去哪读 SKILL.md」决定了哪些文本会以
     * 系统指令的姿态进上下文。项目级配置随仓库走，若它能把自己的加载目录指到 {@code /} 或 {@code ~}，
     * 一个 {@code git clone} 下来的目录就能把机器上任意位置的文件拉进来。
     * 用户自己那台机器上的全局级配置不受此限——那本来就是用户的地盘。
     * <p>
     * 其余键（清单条数、展示长度、正文上限）都只是「收紧」，两边都能调，不必区分来源。
     * <p>
     * 名字用 {@code of} 而不是重载 {@code from(Map)}：两者在传 {@code null} 时会有歧义。
     *
     * @param context 插件上下文，不可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置值的类型或取值非法时抛出
     */
    static SkillsConfig of(PluginContext context) {
        return build(context.configuration(),
                context.configScope() == PluginConfigScope.PROJECT);
    }

    /**
     * 从插件配置段解析配置。
     * <p>
     * 调用方须保证这一份「不是项目级给的」——生产路径统一走 {@link #of(PluginContext)}，
     * 本方法留给测试与「只有一份配置」的装配场景。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置值的类型或取值非法时抛出
     */
    static SkillsConfig from(Map<String, Object> configuration) {
        return build(configuration, false);
    }

    /**
     * 解析配置的公共实现。
     *
     * @param configuration      插件配置段，可为 {@code null}
     * @param restrictRootsToProject 是否要求 roots 留在项目目录内
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置值的类型或取值非法时抛出
     */
    private static SkillsConfig build(Map<String, Object> configuration, boolean restrictRootsToProject) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap()
                : configuration;
        SkillsConfig config = new SkillsConfig(
                bool(values.get(KEY_ENABLED), KEY_ENABLED, DEFAULT_ENABLED),
                roots(values.get(KEY_ROOTS), restrictRootsToProject),
                boundedInt(values.get(KEY_MAX_SKILLS), KEY_MAX_SKILLS, DEFAULT_MAX_SKILLS, 1,
                        MAX_SKILLS_LIMIT),
                boundedInt(values.get(KEY_MAX_DESCRIPTION_CHARS), KEY_MAX_DESCRIPTION_CHARS,
                        DEFAULT_MAX_DESCRIPTION_CHARS, 20, MAX_DESCRIPTION_CHARS_LIMIT),
                boundedInt(values.get(KEY_MAX_BODY_BYTES), KEY_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES,
                        1024, MAX_BODY_BYTES_LIMIT));
        LOG.debug("skills 插件配置: {}", config);
        return config;
    }

    /**
     * 判断是否启用工具与清单贡献。
     *
     * @return 启用返回 {@code true}
     */
    boolean enabled() {
        return enabled;
    }

    /**
     * 获取根目录。
     *
     * @return 不可变根目录列表，按优先级顺序，保证非 {@code null}
     */
    List<Path> roots() {
        return roots;
    }

    /**
     * 获取清单条数上限。
     *
     * @return 上限条数
     */
    int maxSkills() {
        return maxSkills;
    }

    /**
     * 获取 description 展示长度上限。
     *
     * @return 字符数上限
     */
    int maxDescriptionChars() {
        return maxDescriptionChars;
    }

    /**
     * 获取正文读取字节上限。
     *
     * @return 字节数上限
     */
    int maxBodyBytes() {
        return maxBodyBytes;
    }

    @Override
    public String toString() {
        return "SkillsConfig{enabled=" + enabled + ", roots=" + roots + ", maxSkills=" + maxSkills
                + ", maxDescriptionChars=" + maxDescriptionChars + ", maxBodyBytes=" + maxBodyBytes + '}';
    }

    /**
     * 解析根目录列表：缺省走 {@link #DEFAULT_ROOTS}，显式空数组表示「一个根都不要」。
     * <p>
     * 与内核的「字段缺失 ≠ 显式空数组」同口径：缺失是不限制，{@code []} 是明确清空。
     * <p>
     * <b>缺省值不受「限定项目目录」约束</b>：缺省里那两条（{@code ~/.jellyfish/skills} 与
     * {@code ./.jellyfish/skills}）是构件自带的、解释器自己写下的值，不是仓库内容能改的东西。
     * 约束只落在「项目级配置显式写了 roots」这一种情形上，见 {@link #requireInsideProject}。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param restrictToProject 是否要求每一项留在项目目录内
     * @return 不可变根目录列表，保证非 {@code null}
     * @throws JellyfishException 结构或取值非法时抛出
     */
    private static List<Path> roots(Object raw, boolean restrictToProject) {
        List<Object> entries = new ArrayList<Object>();
        boolean explicit = raw != null;
        if (raw == null) {
            entries.addAll(DEFAULT_ROOTS);
        } else {
            if (!(raw instanceof List)) {
                throw new JellyfishException(KEY_ROOTS + " 必须是字符串数组，实际为 " + raw);
            }
            entries.addAll((List<?>) raw);
        }
        List<Path> resolved = new ArrayList<Path>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String) || ((String) entry).trim().isEmpty()) {
                throw new JellyfishException(KEY_ROOTS + " 只能包含非空字符串，实际为 " + entry);
            }
            String text = ((String) entry).trim();
            if (restrictToProject && explicit) {
                requireInsideProject(text);
            }
            Path path = normalizePath(text);
            if (!resolved.contains(path)) {
                resolved.add(path);
            }
        }
        return Collections.unmodifiableList(resolved);
    }

    /**
     * 校验项目级配置给的根目录留在项目目录内。
     * <p>
     * <b>判据是「相对路径 + 不向上逃逸」</b>，而不是「解析后落在某个绝对目录下」：插件拿不到工作目录
     * （{@code PluginContext} 刻意不开放它），比较绝对路径就得自己猜一个，猜错的方向恰恰是放行。
     * 相对路径的语义由内核按进程工作目录解析，因此「相对且不逃逸」正好等价于「在项目目录内」，
     * 而且不需要知道那个目录到底在哪。
     *
     * @param text 用户写的路径原文
     * @throws JellyfishException 指向项目目录之外时抛出
     */
    private static void requireInsideProject(String text) {
        if (text.startsWith("~")) {
            throw new JellyfishException(KEY_ROOTS + " 由项目级配置给出时不能指向主目录：" + text
                    + "（项目级配置随仓库走，只能指向项目目录内的相对路径）");
        }
        if (Paths.get(text).isAbsolute()) {
            throw new JellyfishException(KEY_ROOTS + " 由项目级配置给出时不能是绝对路径：" + text
                    + "（项目级配置随仓库走，只能指向项目目录内的相对路径）");
        }
        // 只看向上逃逸：./.jellyfish/skills 这样的正常写法规范化后不以 .. 开头
        for (Path segment : Paths.get(text)) {
            if ("..".equals(segment.toString())) {
                throw new JellyfishException(KEY_ROOTS + " 由项目级配置给出时不能跳出项目目录：" + text);
            }
        }
    }

    /**
     * 解析布尔配置。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 布尔值
     * @throws JellyfishException 值不是布尔时抛出
     */
    private static boolean bool(Object raw, String key, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean)) {
            throw new JellyfishException(key + " 必须是布尔值，实际为 " + raw);
        }
        return ((Boolean) raw).booleanValue();
    }

    /**
     * 解析带上下界的整数配置：缺省返回缺省值，越界或类型不对当场报错。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @param min          允许的最小值（含）
     * @param max          允许的最大值（含）
     * @return 解析结果
     * @throws JellyfishException 值不是整数或越界时抛出
     */
    private static int boundedInt(Object raw, String key, int defaultValue, int min, int max) {
        if (raw == null) {
            return defaultValue;
        }
        long value;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String) {
            try {
                value = Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw new JellyfishException(key + " 必须是整数，实际为 " + raw, e);
            }
        } else {
            throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
        }
        if (value < min || value > max) {
            throw new JellyfishException(key + " 必须在 " + min + " 到 " + max + " 之间，实际为 " + value);
        }
        return (int) value;
    }

    /**
     * 展开用户主目录前缀并规范化路径。
     *
     * @param raw 原始路径文本
     * @return 规范化绝对路径（相对路径按进程工作目录解析）
     * @throws JellyfishException 无法确定用户主目录时抛出
     */
    private static Path normalizePath(String raw) {
        String text = raw;
        if (text.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                throw new JellyfishException("无法展开 ~ ：未取到 user.home");
            }
            text = home + text.substring(1);
        }
        return Paths.get(text).toAbsolutePath().normalize();
    }
}
