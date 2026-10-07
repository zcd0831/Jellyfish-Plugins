package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InitCommand} 的单元测试：钉住「什么时候接力、什么时候拒绝」这条分流，以及两种模板的取舍。
 * <p>
 * 测试用真实临时目录跑真实的探测环节（{@link ConventionFiles} 只有文件系统这一个依赖），
 * 因此顺带覆盖了「空文件算不算已存在」这类口径问题。命令名与模板正文不在断言范围内——
 * 前者是注册路由键（由 {@code ProjectPluginTest} 与加载链路测试守着），后者是措辞。
 *
 * @author zcd
 */
@DisplayName("项目约定初始化命令")
class InitCommandTest {

    /** 约定文件名，与实现保持一致。 */
    private static final String CONVENTION_FILE = "AGENTS.md";

    /** 临时工作目录：模拟「用户在仓库根敲 /init」。 */
    @TempDir
    Path workingDirectory;

    @Test
    @DisplayName("没有约定文件时应接力：把指令交给模型，命令自己不写盘")
    void handle_should_handoff_when_fileMissing() {
        CommandResult result = command().handle(request());

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.hasHandoff());
        assertNull(result.getOutput());
        assertTrue(result.getHandoffText().contains(CONVENTION_FILE), result.getHandoffText());
    }

    @Test
    @DisplayName("已有约定文件且没带 --force 时应拒绝，并指出逃生门")
    void handle_should_reject_when_fileExistsWithoutForce() throws IOException {
        write(CONVENTION_FILE, "# AGENTS.md\n用户手写的约定\n");

        CommandResult result = command().handle(request());

        assertTrue(result.isError());
        assertFalse(result.hasHandoff());
        assertTrue(result.getOutput().contains(InitCommand.VALUE_FORCE), result.getOutput());
        // 文件一字未动
        assertEquals("# AGENTS.md\n用户手写的约定\n",
                new String(Files.readAllBytes(workingDirectory.resolve(CONVENTION_FILE)),
                        StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("带 --force 时应接力，并在基础指令之后追加「先读再增量改」的约束")
    void handle_should_handoffWithUpdateClause_when_forceGiven() throws IOException {
        Path withFile = workingDirectory.resolve("with-file");
        Files.createDirectories(withFile);
        Files.write(withFile.resolve(CONVENTION_FILE), "# AGENTS.md\n".getBytes(StandardCharsets.UTF_8));

        // 空目录上的基准指令：用它来确认「追加」而不是「替换」
        CommandResult plain = new InitCommand(new ConventionFiles(workingDirectory)).handle(request());
        CommandResult forced = new InitCommand(new ConventionFiles(withFile))
                .handle(request(InitCommand.VALUE_FORCE));

        assertTrue(plain.hasHandoff());
        assertTrue(forced.hasHandoff());
        assertTrue(forced.getHandoffText().startsWith(plain.getHandoffText()),
                "更新约束应追加在基础指令之后");
        assertTrue(forced.getHandoffText().contains("增量更新"), forced.getHandoffText());
    }

    @Test
    @DisplayName("空文件按「不存在」处理，与本插件探测环节同一口径")
    void handle_should_treatEmptyFileAsMissing() throws IOException {
        write(CONVENTION_FILE, "");

        CommandResult forced = command().handle(request(InitCommand.VALUE_FORCE));
        CommandResult plain = command().handle(request());

        assertTrue(plain.hasHandoff());
        // 没有既有内容要保护，因此不该出现「增量更新」那段
        assertEquals(forced.getHandoffText(), plain.getHandoffText());
        assertFalse(plain.getHandoffText().contains("增量更新"));
    }

    @Test
    @DisplayName("参数不认识时报错并给出用法")
    void handle_should_reject_unknownArgument() {
        CommandResult result = command().handle(request("yes"));

        assertTrue(result.isError());
        assertTrue(result.getOutput().startsWith("用法：/init"), result.getOutput());
    }

    @Test
    @DisplayName("参数多于一个时报错，不忽略多余参数")
    void handle_should_reject_tooManyArguments() {
        CommandResult result = command().handle(request(InitCommand.VALUE_FORCE, InitCommand.VALUE_FORCE));

        assertTrue(result.isError());
        assertTrue(result.getOutput().startsWith("用法：/init"), result.getOutput());
    }

    @Test
    @DisplayName("接力文本非空白：空文本会被内核当场拒绝，命令不该产出它")
    void handle_should_produceNonBlankHandoff() {
        CommandResult result = command().handle(request());

        assertNotNull(result.getHandoffText());
        assertFalse(result.getHandoffText().trim().isEmpty());
    }

    /**
     * 构造被测命令：基准目录是本次测试的临时工作目录。
     *
     * @return 命令处理器
     */
    private InitCommand command() {
        return new InitCommand(new ConventionFiles(workingDirectory));
    }

    /**
     * 构造一条参数为空的命令请求。
     *
     * @param tokens 参数
     * @return 命令请求
     */
    private static CommandRequest request(String... tokens) {
        List<String> list = tokens.length == 0
                ? Collections.<String>emptyList()
                : Arrays.asList(tokens);
        return new CommandRequest(InitCommand.COMMAND_NAME,
                new CommandArguments(list, String.join(" ", list)), "s-1");
    }

    /**
     * 在临时工作目录里写一个文件。
     *
     * @param name    文件名
     * @param content 内容
     * @throws IOException 写入失败时抛出
     */
    private void write(String name, String content) throws IOException {
        Files.write(workingDirectory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }
}
