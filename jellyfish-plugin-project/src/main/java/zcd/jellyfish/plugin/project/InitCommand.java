package zcd.jellyfish.plugin.project;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code /init [--force]} 命令：把「读一遍这个仓库、写出 {@code AGENTS.md}」交给模型去做。
 * <p>
 * <b>它自己不写文件，一个字都不写</b>：命令处理器碰不到模型，也起不了回合，因此它的全部工作就是
 * 返回 {@link CommandResult#handoff(String)}——一段给模型的指令。内核会把这段文本<b>当成用户这一次
 * 敲下的输入</b>接着走（输入改写 → 指令 → 建会话 → 起回合），于是模型据此派出读取与写入工具调用。
 * <p>
 * <b>为什么让模型写、而不是插件直接写盘</b>：内容得先看过仓库才知道，插件只会写模板；
 * 而让模型走工具写，写入就<b>照旧过权限与审批链</b>。插件直接落盘等于绕过内核唯一的权限收口，
 * 这笔账不能这么算。
 * <p>
 * <b>已存在时不静默覆盖</b>：没有 {@code --force} 就只回一条错误，把「要不要动用户手写的文件」
 * 这个决定权留给用户；带了 {@code --force} 才接力，并在指令后面追加一段「先读再增量改、不要重写」的约束。
 * <p>
 * <b>指令模板在插件 jar 里</b>（{@code init-prompt.md} / {@code init-prompt-force.md}），
 * 与压缩插件的 {@code summary-prompt.md} 同一范式：改措辞要重新打包。
 * <b>读不到就在启动期抛</b>——那是打包缺陷，压到运行时只会变成「{@code /init} 怎么没反应」。
 * <p>
 * 无状态（指令模板在构造期读定），可安全跨线程传递。
 *
 * @author zcd
 */
final class InitCommand implements ExtensionHandler<CommandRequest, CommandResult> {

    /** 命令名，同时是注册路由键。 */
    static final String COMMAND_NAME = "init";

    /** 覆盖取向参数：带上它才允许在已有文件的基础上更新。 */
    static final String VALUE_FORCE = "--force";

    /** 指令模板资源路径。 */
    private static final String PROMPT_RESOURCE = "/init-prompt.md";

    /** 「已存在，要更新」时追加的约束资源路径。 */
    private static final String FORCE_RESOURCE = "/init-prompt-force.md";

    /** 用法文案：参数非法与参数过多时共用。 */
    private static final String USAGE = "用法：/init [--force]";

    /** 约定文件探测器：用来判断「已经有一份了」。 */
    private final ConventionFiles files;

    /** 首次初始化的指令正文。 */
    private final String prompt;

    /** 已有文件时追加的更新约束。 */
    private final String forcePrompt;

    /**
     * 构造命令处理器。
     *
     * @param files 约定文件探测器，不可为 {@code null}
     * @throws JellyfishException 指令模板资源缺失或读不出时抛出
     */
    InitCommand(ConventionFiles files) {
        this.files = files;
        this.prompt = readResource(PROMPT_RESOURCE);
        this.forcePrompt = readResource(FORCE_RESOURCE);
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        List<String> tokens = request.getArguments().getTokens();
        if (tokens.size() > 1) {
            return CommandResult.error(USAGE);
        }
        boolean force = false;
        if (!tokens.isEmpty()) {
            if (!VALUE_FORCE.equals(tokens.get(0))) {
                return CommandResult.error(USAGE);
            }
            force = true;
        }
        // 空文件按「不存在」处理，与本插件探测环节的口径一致：没有内容就没有要保护的东西
        ConventionFile existing = files.probe();
        if (existing == null) {
            return CommandResult.handoff(prompt);
        }
        if (!force) {
            return CommandResult.error("工作目录下已有 " + existing.name() + ConventionText.size(existing)
                    + "，未做任何改动。要在此基础上更新请用 /init --force（会先读再改，不是重写）。");
        }
        return CommandResult.handoff(prompt + '\n' + forcePrompt);
    }

    /**
     * 读取插件 jar 里的文本资源。
     *
     * @param resource 资源路径（以 {@code /} 开头）
     * @return 资源正文，保证非空
     * @throws JellyfishException 资源不存在或读取失败时抛出
     */
    private static String readResource(String resource) {
        try (InputStream in = InitCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JellyfishException("插件资源缺失：" + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("插件资源读取失败：" + resource, e);
        }
    }
}
