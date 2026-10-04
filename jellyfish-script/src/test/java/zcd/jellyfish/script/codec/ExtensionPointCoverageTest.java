package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.script.ScriptJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 脚本桥接的扩展点覆盖契约。
 * <p>
 * <b>它解决什么问题</b>：内核每新增一个扩展点，脚本侧就多出一块「还没打通」的空白，而这件事
 * 此前没有任何地方会变红——{@code ExtensionCodecs.DEFAULTS} 只写着自己认识的那几个，
 * 于是覆盖范围会随时间悄悄从 11/28 变成 12/30、13/32，直到某天有人发现某个能力脚本用不了。
 * <p>
 * 本测试枚举 {@code jellyfish-api} 里<b>全部</b> {@link ExtensionRequest} 子类，逐个要求它被
 * {@code extension-points.json} 分类为 {@code in} / {@code planned} / {@code excluded} 之一。
 * 新增扩展点而未分类 = 构建失败，逼迫在这里做一次显式决策（而不是默认遗忘）。
 * <p>
 * <b>为什么三段而不是两段</b>：「还没做」与「明确不做」是两件事，合并会让路线图丢掉，
 * 也会让「excluded 里为什么有它」无从回答。
 * <p>
 * <b>它不是重复检查</b>：{@code in} 段与 {@code ExtensionCodecs.DEFAULTS} 是双向绑定——
 * 改了一处而忘了另一处同样会失败。这正是「同一份事实散在多处」的解毒剂。
 *
 * @author zcd
 */
@DisplayName("脚本桥接扩展点覆盖契约")
class ExtensionPointCoverageTest {

    /** 能力档资源路径（与两份 SDK 读取的是同一份）。 */
    private static final String RESOURCE = "script/extension-points.json";

    /** 扩展点类所在的包。 */
    private static final String PACKAGE_NAME = "zcd.jellyfish.api.extension";

    /** 扩展点类所在的包路径。 */
    private static final String PACKAGE_PATH = "zcd/jellyfish/api/extension/";

    @Test
    @DisplayName("api 里的每一个扩展点都应被分类，且只被分类一次")
    void classification_should_coverEveryExtensionPointExactlyOnce() {
        JsonNode root = readResource();
        Set<String> in = classesOf(root.get("in"));
        Set<String> planned = classesOf(root.get("planned"));
        Set<String> excluded = classesOf(root.get("excluded"));

        Set<String> classified = new LinkedHashSet<String>();
        for (String name : in) {
            assertTrue(classified.add(name), "扩展点被重复分类: " + name);
        }
        for (String name : planned) {
            assertTrue(classified.add(name), "扩展点被重复分类: " + name);
        }
        for (String name : excluded) {
            assertTrue(classified.add(name), "扩展点被重复分类: " + name);
        }

        Set<String> discovered = discoverExtensionPoints();
        assertFalse(discovered.isEmpty(), "没有从 jellyfish-api 里发现任何扩展点，扫描逻辑可能失效了");

        Set<String> unclassified = new LinkedHashSet<String>(discovered);
        unclassified.removeAll(classified);
        assertTrue(unclassified.isEmpty(),
                "内核新增了扩展点但脚本桥接还没做能力档决策（请在 script/extension-points.json 里"
                        + "把它分到 in / planned / excluded）: " + unclassified);

        Set<String> unknown = new LinkedHashSet<String>(classified);
        unknown.removeAll(discovered);
        assertTrue(unknown.isEmpty(),
                "能力档里列了 api 中不存在的扩展点（改名或删除了？）: " + unknown);
    }

    @Test
    @DisplayName("in 段应与 ExtensionCodecs 默认清单双向一致")
    void inList_should_matchCodecsBidirectionally() {
        JsonNode in = readResource().get("in");
        Set<String> declaredTypes = new LinkedHashSet<String>();
        for (JsonNode entry : in) {
            String type = entry.get("type").asText();
            String className = entry.get("class").asText();
            assertTrue(declaredTypes.add(type), "in 段里类型名重复: " + type);

            ExtensionCodec<?, ?> codec = ExtensionCodecs.DEFAULTS.byName(type);
            assertNotNull(codec, "in 段声明了 " + type + " 但 ExtensionCodecs 里没有对应 codec");
            assertEquals(className, codec.requestType().getSimpleName(),
                    "in 段的 class 与 codec 的请求类型不一致: " + type);
            assertEquals(!entry.get("routeKey").asBoolean(), ExtensionCodecs.DEFAULTS.isTypeLevel(type),
                    "in 段的 routeKey 与 codec 的类型级声明不一致: " + type);
        }

        Set<String> codecTypes = new LinkedHashSet<String>(ExtensionCodecs.DEFAULTS.names());
        Set<String> missing = new LinkedHashSet<String>(codecTypes);
        missing.removeAll(declaredTypes);
        assertTrue(missing.isEmpty(), "ExtensionCodecs 里有 codec 但能力档的 in 段没有它: " + missing);
    }

    /**
     * 读取能力档资源。
     *
     * @return JSON 根节点
     */
    private static JsonNode readResource() {
        InputStream stream = ExtensionPointCoverageTest.class.getClassLoader().getResourceAsStream(RESOURCE);
        assertNotNull(stream, "能力档资源不存在: " + RESOURCE);
        try (InputStream input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return ScriptJson.tree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("读取能力档失败: " + RESOURCE, e);
        }
    }

    /**
     * 取分类段里的类简单名集合。
     *
     * @param section 分类段（可为 {@code null}）
     * @return 类简单名集合，保证非 {@code null}
     */
    private static Set<String> classesOf(JsonNode section) {
        Set<String> names = new LinkedHashSet<String>();
        if (section == null || !section.isArray()) {
            return names;
        }
        for (JsonNode entry : section) {
            names.add(entry.get("class").asText());
        }
        return names;
    }

    /**
     * 扫描 {@code jellyfish-api} 里全部具体的 {@link ExtensionRequest} 子类。
     * <p>
     * 直接扫 api 的类来源（jar 或展开目录），而不是在测试里再抄一份名单：
     * 抄一份就等于把「会被漏掉」这件事原样搬进测试。
     *
     * @return 类简单名集合，保证非 {@code null}
     */
    private static Set<String> discoverExtensionPoints() {
        Set<String> candidates = candidateNames();
        Set<String> result = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            String binaryName = PACKAGE_NAME + '.' + candidate;
            try {
                Class<?> type = Class.forName(binaryName, false, ExtensionPointCoverageTest.class.getClassLoader());
                if (!ExtensionRequest.class.isAssignableFrom(type) || type.equals(ExtensionRequest.class)) {
                    continue;
                }
                if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                    continue;
                }
                result.add(candidate);
            } catch (ClassNotFoundException e) {
                fail("扫描到类条目却加载不到: " + binaryName);
            }
        }
        return result;
    }

    /**
     * 列出 api 的扩展点包里的全部顶层类名（不做继承判断）。
     *
     * @return 类简单名集合，保证非 {@code null}
     */
    private static Set<String> candidateNames() {
        Set<String> names = new LinkedHashSet<String>();
        URL location = ExtensionRequest.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location, "取不到 jellyfish-api 的类来源，无法扫描扩展点");
        try {
            Path path = Paths.get(location.toURI());
            if (Files.isDirectory(path)) {
                collectFromDirectory(path.resolve(PACKAGE_PATH), names);
            } else {
                collectFromJar(path, names);
            }
        } catch (Exception e) {
            fail("扫描 jellyfish-api 失败: " + e.getMessage());
        }
        return names;
    }

    /**
     * 从展开目录里收集类名。
     *
     * @param directory 包目录
     * @param target    收集目标
     * @throws IOException 遍历失败时抛出
     */
    private static void collectFromDirectory(Path directory, Set<String> target) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = path.getFileName().toString();
                if (name.endsWith(".class")) {
                    addIfTopLevel(name.substring(0, name.length() - ".class".length()), target);
                }
            }
        }
    }

    /**
     * 从 jar 里收集类名。
     *
     * @param path   jar 路径
     * @param target 收集目标
     * @throws IOException 读取失败时抛出
     */
    private static void collectFromJar(Path path, Set<String> target) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(PACKAGE_PATH) || !name.endsWith(".class")) {
                    continue;
                }
                addIfTopLevel(name.substring(PACKAGE_PATH.length(), name.length() - ".class".length()), target);
            }
        }
    }

    /**
     * 只收顶层类：内部类与匿名类不是扩展点。
     *
     * @param simpleName 类简单名
     * @param target     收集目标
     */
    private static void addIfTopLevel(String simpleName, Set<String> target) {
        if (simpleName.indexOf('$') < 0 && !simpleName.isEmpty()) {
            target.add(simpleName);
        }
    }
}
