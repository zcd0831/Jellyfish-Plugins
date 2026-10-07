# 变更日志

本文件记录 Jellyfish 官方插件仓库所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
