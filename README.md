# Jellyfish 官方插件

> 本文件由内核仓库 `README.md` 中的插件章节抽取而成，供手动迁移到独立的官方插件仓库。
> 迁移时把下面的相对路径视为**插件仓库根目录**下的路径（不再有 `jellyfish-plugins/` 前缀）。

官方插件**一个插件一个子模块**（PF4J 是「一个 jar 一个 `plugin.properties`」）：

| 模块 | plugin.id | 提供什么 |
| --- | --- | --- |
| `jellyfish-plugin-tools` | `jellyfish-tools` | 五个文件工具：`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_files` |
| `jellyfish-plugin-session-file` | `jellyfish-session-file` | 会话持久化：一个会话一个 JSON 文件，并用 git 管理历史 |
| `jellyfish-plugin-todo` | `jellyfish-todo` | 会话待办：模型可写的 `todo_write` 工具 + 只读 `/todo` + 随本轮消息送达的待办块 + 状态栏进度 + 侧栏清单面板 |
| `jellyfish-plugin-project` | `jellyfish-project` | 项目约定：探测工作目录下的 `AGENTS.md`，**小文件内联原文、大文件只给路径**（阈值可配） |
| `jellyfish-plugin-compact` | `jellyfish-compact` | 会话压缩策略：提供摘要指令与保留条数/摘要上限（**不装它就没有压缩**，见下文） |
| `jellyfish-plugin-python` | `jellyfish-plugin-python` | Python 脚本插件运行时：把 `scripts/python/<id>/` 下的脚本目录变成标准插件（控制面网关 + 每脚本一 worker 进程） |
| `jellyfish-plugin-node` | `jellyfish-plugin-node` | Node 脚本插件运行时：与 Python 同构（同一套协议与进程模型），零第三方依赖 |
| `jellyfish-plugin-shell` | `jellyfish-shell` | 命令行：`shell` 工具（`/bin/sh -c` 执行命令原文）+ 命令策略（白名单准入、可信表免审批、只读不打扰、灾难形状拒绝、其余审批）。**没有沙箱**，见下文 |
| `jellyfish-plugin-skills` | `jellyfish-skills` | skills：按目录发现 `SKILL.md`，元信息常驻 system prompt、正文由模型用 `skill` 工具按需加载，见下文 |
| `jellyfish-plugin-mcp` | `jellyfish-mcp` | MCP 客户端：stdio 连外部 MCP server，把它的工具以 `mcp__<server>__<tool>` 接入，见下文 |

`jellyfish-tools` 的五个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit`、`max_bytes` | 按行分片读取，命中 `limit` 或 `max_bytes` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path`、`offset`、`limit` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类；大目录分页 |
| `grep_files` | `pattern`、`path`、`max_results`、`max_line_chars`、`max_bytes` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

其中 `read_file`、`list_dir`、`grep_files` 在描述符里声明为**只读**，PLAN 模式下开箱可用；`write_file` 与 `edit_file` 会改动工作目录，PLAN 模式下会被拒绝。

打包与安装（扫描目录由内核 `config.json` 的 `plugins.roots` 决定，默认是 `~/.jellyfish/plugins/`，该目录不入版本库）：

```bash
mvn -q package -DskipTests
mkdir -p plugins
cp jellyfish-plugin-tools/target/jellyfish-plugin-tools-*.jar plugins/
cp jellyfish-plugin-session-file/target/jellyfish-plugin-session-file-*.jar plugins/
cp jellyfish-plugin-todo/target/jellyfish-plugin-todo-*.jar plugins/
cp jellyfish-plugin-project/target/jellyfish-plugin-project-*.jar plugins/
cp jellyfish-plugin-compact/target/jellyfish-plugin-compact-*.jar plugins/
cp jellyfish-plugin-python/target/jellyfish-plugin-python-*.jar plugins/
cp jellyfish-plugin-node/target/jellyfish-plugin-node-*.jar plugins/
cp jellyfish-plugin-shell/target/jellyfish-plugin-shell-*.jar plugins/
```

插件配置写在 `jellyfish.json` 的 `plugins.configurations.<pluginId>` 段：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-tools": {
        "readOnlyTools": ["read_file", "list_dir", "grep_files"]
      },
      "jellyfish-session-file": {
        "sessionDir": "~/.jellyfish/sessions",
        "gitEnabled": true
      },
      "jellyfish-todo": {
        "todoDir": "~/.jellyfish/todos",
        "readOnlyTools": ["todo_write"]
      },
      "jellyfish-compact": {
        "keepRecentMessages": 20,
        "maxSummaryChars": 4000
      },
      "jellyfish-project": {
        "maxInlineBytes": 32768
      },
      "jellyfish-shell": {
        "timeoutSeconds": 120,
        "maxTimeoutSeconds": 1800,
        "idleTimeoutSeconds": 0,
        "environment": {},
        "allowedCommands": [],
        "commandPolicy": {
          "enabled": true,
          "trustedCommands": [],
          "readOnlyCommands": [],
          "deniedPatterns": []
        }
      }
    }
  }
}
```

- `readOnlyTools` 是**跨插件的约定键**（`jellyfish.json` 里位于插件配置段下），作用是**追加** PLAN 模式的只读白名单。
  - 工具的只读性**默认由工具提供方在 `ToolDescriptor` 里声明**，不需要用户再写一遍：`read_file` / `list_dir` / `grep_files`（`jellyfish-tools`）与 `todo_write`（`jellyfish-todo`）开箱即在 PLAN 白名单里；`write_file` / `edit_file` 不是。
  - 配置里的声明只能**追加**，用于把提供方没标只读的工具自行纳入，不能撤销提供方的声明。
  - 两个来源取**并集**，且不依赖任何缓存：插件热部署（装上 / 卸下 / 重载）后白名单立刻跟着变。
  - PLAN 模式下不在白名单里的工具一律拒绝。
- `sessionDir`（默认 `~/.jellyfish/sessions`）：会话文件目录。会话是跨项目的运行态数据，因此默认放全局级目录。
- `gitEnabled`（默认 `true`）：首次落盘时在 `sessionDir` 里 `git init`，此后**每次内容变化的落盘留一次提交**（内容没变则不写文件、也不提交）。机器上没有 git 时只告警，文件照常落盘。
- `todoDir`（默认 `~/.jellyfish/todos`）：待办文件目录，一个会话一个 JSON 文件，空表会删掉文件。
- `keepRecentMessages` / `maxSummaryChars`（`jellyfish-compact`，**都可省略**）：本插件对压缩参数的覆盖值；省略时用内核 `react` 段的缺省值。省略是「不表态」，不是「用 0」。
- `maxInlineBytes`（`jellyfish-project`，默认 `32768` 即 32 KiB）：约定文件**多大以内可以把原文放进 system prompt**。超过它只给路径指引；写 `0` 表示从不内联（彻底关掉内联的逃生门）。上限 1 MiB，超出或为负数会在启动期直接报错。约定文件名固定为 `AGENTS.md`，查找基准固定为进程工作目录——这两项不可配。
- `timeoutSeconds`（`jellyfish-shell`，默认 `120`）：命令最多允许跑多久；单次调用可以用 `timeout_seconds` 参数覆盖，并被 `maxTimeoutSeconds`（默认 `1800`）钳制。**不支持「不超时」**——保留一个上限，避免配置写错变成无限等待。
- `idleTimeoutSeconds`（`jellyfish-shell`，默认 `0` 即**关闭**）：连续多久没有任何输出就判定卡住。墙钟回答「最多跑多久」，它回答「多久没动静就当死了」：一条持续打印进度的 `mvn test` 跑 20 分钟不该被误杀，而 `docker build` 之类确实可能长时间无输出，所以缺省不开。**模型不能设置它**，它是用户的环境策略。
- `environment`（`jellyfish-shell`）：额外注入或覆盖的环境变量。子进程默认**继承**父进程环境，但名字匹配 `*KEY*` / `*TOKEN*` / `*SECRET*` / `*PASSWORD*` / `*CREDENTIAL*` 的变量**不会**传下去（工具输出会送到远端 LLM），另有一组防挂死默认值（`PAGER=cat`、`GIT_PAGER=cat`、`GIT_TERMINAL_PROMPT=0`、`TERM=dumb`、`NO_COLOR=1`、`DEBIAN_FRONTEND=noninteractive`）。这里写的值可以盖掉默认值。`sensitivePatterns` 用于**追加**剔除模式。
- `allowedCommands`（`jellyfish-shell`，默认 `[]`）：**非空即默认拒绝**的前缀白名单，支持 `git status` 这种两 token 形式（单 token 覆盖该命令的全部子命令）。它服务于 `-cli` / `-server` 这类没有人在场批准的模式，且**不受 `commandPolicy.enabled` 影响**。
- `commandPolicy`（`jellyfish-shell`）：命令策略段，四个键都**可省略**。
  - `enabled`（默认 `true`）：只关**分类器**这个便利机制。`false` 时只读表与「其余命令问人」都不再表态，但**白名单、可信表、拒绝形状照旧生效**——它们不是分类器的一部分。
  - `trustedCommands`（默认 `[]`）：**可信命令表，命中即免审批**（无异议、不弹批准框）。粒度与白名单一致。它是「白名单里的命令为什么还要点批准」的答案：白名单只管「能不能跑」，免审批归这张表。
  - `readOnlyCommands`（默认 `[]`）：**追加**在内置只读表之后，用于把用户认为只读的命令纳入免打扰范围。内置表**不可替换**，只能追加。
  - `deniedPatterns`（默认 `[]`）：**追加**在内置拒绝形状（`rm -rf /`、`mkfs`、`of=/dev/`、`:(){`）之后。同样是追加，内置那几条不可撤销。
- **`allowedCommands` 与 `trustedCommands` 要在两处各写一遍才算「能跑且不打扰」**。这不是冗余失误，是刻意分工：前者回答能不能跑（默认拒绝），后者回答要不要问人（默认问）。也因此，「允许跑但每次都要我批准」这种姿态仍然写得出来——只配白名单、不配可信表即可。

## 待办（jellyfish-todo）

待办是模型的计划草稿，由插件自己持有（内核不再有会话待办字段、也没有 `/todo` 系统命令）：

| 面 | 扩展点 | 说明 |
| --- | --- | --- |
| `todo_write` 工具 | `ToolCallRequest` | 模型写待办的唯一入口。**整表覆盖**：传完整的新列表，上次列过而这次没列出的项视为删除，空数组表示清空；状态取 `pending` / `in_progress` / `completed`，缺省按 `pending` 处理 |
| `/todo` 命令 | `CommandRequest` | 只读地列出当前会话待办（写入只走 `todo_write`，不给同一份状态第二套写入语义） |
| 上下文注入 | `TurnContextRequest` | 每轮把待办块拼在本轮用户消息前面送达，模型始终看得见自己的计划；没有待办时不注入。**不再走 `PromptContributionRequest`**：system prompt 是缓存前缀的第 0 个 token，待办每勾掉一项都会作废整个请求（连同全部历史），而随消息落盘是 append-only 的 |
| 状态栏进度 | `StatusLineContributionRequest` | 状态栏尾部显示 `待办 2/5`，有进行中项时补 `· 进行中 1`，不敲命令也能看到还剩几件事；没有待办时不占位 |
| 侧栏清单 | `PanelContributionRequest` | 在侧栏常驻显示完整清单（已完成项整行变暗、进行中项高亮：`[~]`），建议放右栏；没有待办时不占区域 |

三态的写法与人看到的标记一一对应：`pending` = `[ ]`、`in_progress` = `[~]`、`completed` = `[x]`（清单里有进行中项时，注入的待办块标题会补一句图例）。状态取值读的时候忽略大小写与连字符（`In-Progress` 也认），写出去的一律是小写下划线。

参数非法（`todos` 不是数组、项不是对象、`content` 为空、`status` 不在取值内）会**当场报错**，并作为工具结果回灌给模型让它自己改，而不是静默落盘一份坏数据。报错消息会**带上实际收到的值**（`实际为 "doing"`）：只说「实际为 String」时模型改不动，只会原样重试。待办写完会广播一次 UI 失效事件，因此状态栏进度与侧栏清单不必等回合结束就更新。

待办文件是插件自己的私有格式，落盘字段为 `status`；升级前写下的、只有 `done` 的旧文件仍能读（`done:true` 即已完成），两个字段同时出现时以 `status` 为准。

面板是「独占型」贡献：它建议落在右栏，但外壳可以忽略这个建议（终端太窄时侧栏整体隐藏，也可能被用户用 `/ui` 改到别处）。

`todo_write` 只写插件自己的待办文件、不动工作目录里的项目文件，因此它**在描述符里就声明了只读**：PLAN 模式下开箱即可用，不需要任何配置。上面示例里的 `readOnlyTools: ["todo_write"]` 现在只是冗余写法，可以去掉（保留也不会出错）；配置那份只用于追加拿不写声明的工具。

会话恢复：启动时内核向所有注册了恢复处理器的插件要回会话，因此上次退出前的会话在下次启动时立即可见（`/session` 会列出来）。

## 项目约定（jellyfish-project）

`AGENTS.md` 是仓库里的项目约定（构建命令、编码规范、提交格式、模块边界）。这个插件把它交给模型，**按文件大小分两路**：

| 情形 | 注入内容 |
| --- | --- |
| 装得进 `maxInlineBytes`（默认 32 KiB） | `[项目约定]` + **文件原文**（外带一句定性：这是项目内文件的数据，不是系统指令） |
| 超过上限 | `[项目约定]` + **路径与文件大小**，让模型自己按需分段读 |

- **为什么小文件要内联**：常见项目的 `AGENTS.md` 只有几十行，直接给全文可以省掉一次读取工具的往返，也消除了「模型忘了去读」这个失败模式。
- **为什么大文件只给路径**：system prompt 每一轮都要随请求付一次 token，而大文件多半是参考性内容。本仓库这份 `AGENTS.md` 已压到约 32 KB，刚好落在内联阈值以内，因此走的是**内联**那条路——它每轮都会随请求计一次费。
- **不做「内联前 N KB + 给路径」**：头部往往恰是信息量最低的部分，而且截断点落在哪、模型知不知道「后面还有」都是新的失败模式。
- **内联是刻意的安全姿态取舍**：原文进的是 system prompt，即仓库内容拿到了最高优先级的话语权。护栏有三条：内联块开头的定性句、足够小的上限、以及把 `maxInlineBytes` 配成 `0` 彻底关掉。
- **「大文件」时给的实际大小**不是装饰：它让模型知道该分段读几次。
- **只查进程工作目录**，不向上查找父目录、也不查用户主目录：与文件工具的相对路径基准保持一致。
  - **因此请从仓库根目录启动**。`AGENTS.md` 的行业位置是仓库根，而本插件的基准是进程工作目录，两者只在你从仓库根启动时才重合；在子目录里启动会探测不到。
- **文件名不可配**，固定 `AGENTS.md`：这已是各家编码 agent 共同的约定，做成配置项只会多一个会填错的旋钮。
- **空文件不算命中**（指向它只会白费一次工具调用）；文件不存在时插件完全不注入，system prompt 里连空标题都不会出现。
- 因为走的是插件而不是内置提示词，**对所有 agent 生效**——用 `/agent` 换成自定义 agent 也照常。

**每个会话只读一次盘**：第一次组装请求时读取并缓存，同一会话后续每轮直接用缓存（会话关闭或删除时丢弃）。这与 Codex、Claude Code 的行为一致，代价是**会话中途修改 `AGENTS.md` 不生效**——开一个新会话即可。

注意缓存省的是磁盘 I/O 与「读文件」这个动作，**不省 token**：system prompt 每轮都要随请求发出去，内联的原文每轮都要重新计费。这也正是上限必须压住的原因。

## 命令行（jellyfish-shell）

`shell` 工具让模型在本机执行命令。**它没有沙箱**：命令以本进程的权限运行，能读写你这个用户的任意文件。

| 参数 | 说明 |
| --- | --- |
| `command` | 命令行原文，交给 `/bin/sh -c` 执行——管道、重定向、通配符都按 shell 语义工作 |
| `cwd` | 工作目录，相对路径按进程工作目录解析；缺省为进程工作目录 |
| `timeout_seconds` | 本次调用的超时秒数，被 `maxTimeoutSeconds` 钳制 |

- **每次调用都是一个全新的 shell**，`cd` 不跨调用保留：需要换目录就用 `cd 目录 && 命令` 或传 `cwd`。
- **`stdin` 在启动后立即关闭**：`vi` / `ssh` / `sudo` 这类交互式命令会立刻失败。这不是缺陷——TUI 处于 raw 模式，子进程直接写终端会把界面画烂。
- **stdout 与 stderr 合并**成一条流（到达顺序，像终端一样）。
- **非零退出码是结果而不是错误**：`grep` 没匹配到返回 1，这在输出里如实报告；工具调用本身不算失败。
- **超时与静默**：见上文两个配置项。被终止时输出里会说明是墙钟超时、静默判定还是取消，并附上已经捕获的部分输出。命令的输出是**边产生边捕获**的：内存占用与输出体积无关，超限部分落盘，回灌给模型的是头尾预览加路径。
- **执行期的输出会实时显示**：`-cli` 写到 stderr，`-tui` 在消息区显示「运行中的工具轨迹」块（只保留末尾若干行，工具一返回就被正式结果取代）。
- **环境变量**：继承父进程，但剔除凭据命名的变量并注入防挂死默认值，见上文。

失败的命令在界面上都是能一眼看出的：`-tui` 的工具轨迹后带 `⚠ 退出码 N`（或被终止的原因），`-cli` 的结束行同样补一段后缀，`-server` 的 `tool_done.metadata` 给了同样的字段。判据由内核统一给出（退出码非零，或终止原因不是正常完成），**不解析文本**。

**权限与分类器**：每次调用依次过五道，先拒绝后免打扰、最后才问人。

1. `allowedCommands` 非空且没命中 → **直接拒绝**（默认拒绝的硬门）；
2. 命中拒绝形状（`rm -rf /`、`mkfs`、`dd of=/dev/`）→ **直接拒绝**；
3. 命中 `commandPolicy.trustedCommands` → **免审批**，直接执行；
4. 命中只读表（`ls`、`git status`、`cat`……）→ **免审批**；
5. 其余 → **升级为人工审批**，你会看到一个批准框。

第 2 步排在第 3 步之前是刻意的：把 `rm` 写进可信表，`rm -rf /` 依旧被拒。白名单、可信表、拒绝形状都**不受 `commandPolicy.enabled` 影响**（那个开关关掉的只是第 4、5 步的分类器）。

- **分类器不是安全边界**：`FOO=bar cmd`、`$(...)`、`&&` 链都能绕过它。它的价值是让你不必为每次 `git status` 点一次批准——否则你最终会把 `shell` 从 `askTools` 里整个拿掉，那才是真正的风险。
- **`commandPolicy.trustedCommands` 是免审批表，白名单不解除审批**：这是最容易踩的一处——`allowedCommands` 里的 `mvn test` 每次仍然弹批准框，因为白名单只回答「能不能跑」。要它静默执行，就把同一条前缀也写进 `trustedCommands`（粒度与白名单一致，单 token 覆盖全部子命令）。
- **`find` / `git fetch` / `npm test` 刻意不算只读**：`find -delete` 会删东西，`git fetch` 改 ref，`npm test` 执行仓库里的任意代码。它们免审批的唯一正当路径是可信表：内置表保持保守，你的判断写成一条显式、可审计的配置。
- **默认配置（`shell` 不在 `askTools` 里）就是推荐的姿态**：只读命令静默执行，其余命令由分类器升级为审批，你会看到一次批准框。
- **把 `shell` 写进 `askTools` 则是「每条命令都要批准」**（连 `git status` 也要）：核心策略的 `ASK` 无法被插件的「无异议」降级——插件的裁定只能收紧、不能放宽。想要最强姿态就用它，代价是噪音。
- **`-cli` / `-server` 下没有人在场**：`askTools` 等于禁用，而 **`ASK` 就是拒绝**。于是免审批的放行口只剩两个——被分类器判为只读的命令，以及 `trustedCommands` 里的命令（白名单非空时还得先过白名单）。**想在无人值守下跑 `mvn test` 这类非只读命令，必须把它写进 `trustedCommands`**：只配白名单不够，白名单只解除默认拒绝，后面等着它的仍是「`ASK` 即拒绝」。这也是那半句「分类器可被绕过」必须被认真对待的地方：要跑无人值守的服务，就配上白名单与可信表。
- **不做目录围栏**：它可绕过（`cd /`、绝对路径、`sh -c` 嵌套），与文件工具没有围栏也不自洽，还会挡住合法需求。真正的边界是审批加白名单。
- **`allowedCommands` 白名单**（非空即默认拒绝）是给 `-cli` / `-server` 这类没有人在场的模式准备的安全网；**它只管准入，不管审批**，免审批看 `trustedCommands`。
- **Windows 未验证**：非 POSIX 平台映射成 `cmd.exe /c`，但没有在真机上跑过。
- **进程树只能尽力杀**：`sh -c` 的直接子进程是 shell，杀掉它不一定带走 `npm run dev` 拉起的孙进程。工具会用 `pgrep -P` 递归尽力而为，**杀不干净是已知边界**。

端到端测试会真的起进程再杀掉它们，因此单独一个 profile：`mvn -q -Pshell-it test`。

## skills（jellyfish-skills）

把一个目录里的说明文件变成模型可以按需取用的「技能」。约定很简单：**一个子目录一个 skill，正文写在 `SKILL.md` 里**。

```markdown
---
name: pdf-processing
description: 处理 PDF 时使用：拆分、合并、提取文本
---

# 用法

1. 用 `scripts/merge.py` 合并……
2. 细节见 `references/api.md`
```

- **`description` 必填**：它是模型判断「该不该用这个 skill」的唯一依据，缺了整条会被跳过并在 `/skills` 里说明原因。
- **`name` 可省**：省略时用目录名。头部只认 `---` 围栏里的扁平 `key: value`（支持 `>` / `|` 折行与引号），**不是完整 YAML**。
- **三层渐进披露**：名称 + 描述常驻 system prompt；模型判断相关时调 `skill` 工具把正文取回来；正文里提到的附带文件（`references/`、`scripts/`）用 `read_file` / `shell` 按需读取。
- **`skill` 工具是只读的**，PLAN 模式下同样可用。
- **改了 `SKILL.md` 立即生效**：目录缓存按文件修改时间失效，不需要重启，也不需要 `/reload`。

配置（`jellyfish.json`）：

```jsonc
{
  "plugins": {
    "configurations": {
      "jellyfish-skills": {
        "roots": ["~/.jellyfish/skills", "./.jellyfish/skills"],
        "maxSkills": 50,
        "maxDescriptionChars": 200,
        "maxBodyBytes": 65536
      }
    }
  }
}
```

根目录是**有序**的，同名 skill 先到者胜——项目级要覆盖用户级就写在前面。整段留空即用上面这两个默认根目录。
`enabled=false` 时不注册工具与清单贡献，但 `/skills` 仍可用（那是「为什么什么都看不见」的唯一答案）。

| 命令 | 说明 |
| --- | --- |
| `/skills` | 列出根目录、已加载清单与扫描期问题（目录写错、缺 description、名称被前面的根目录占掉） |

## MCP（jellyfish-mcp）

连上外部的 MCP server，把它的工具当作本机工具使用：

```jsonc
{
  "plugins": {
    "configurations": {
      "jellyfish-mcp": {
        "servers": [
          {
            "id": "filesystem",
            "command": "npx",
            "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
            "env": { "SOME_TOKEN": "..." },
            "callTimeoutSeconds": 60,
            "readOnlyTools": ["read_file", "list_directory"]
          }
        ],
        "askWriteTools": true,
        "startupWaitSeconds": 5
      }
    }
  }
}
```

- **工具名带前缀**：`mcp__<server>__<tool>`。server 给的工具名可能与内置工具或另一个 server 重名，前缀让「谁占谁的位置」不成为事实；字符会被清洗成厂商允许的集合，超长时保留前缀并追加哈希。
- **连接发生在启动之后**：`startupWaitSeconds`（缺省 5 秒）内先等一等，让第一轮就能看到工具；超时就转异步，连上之后工具会自己出现。**某个 server 装错了不会让内核起不来**。
- **`tools/list_changed` 会实时跟随**：server 运行期增删工具时，模型下一轮就能看到。
- **只读与审批**：server 声明的 `readOnlyHint` 或你在 `readOnlyTools` 里写的名字都算只读；**缺省一律按可写**。可写的工具缺省要人工审批（`askWriteTools: false` 可关）。
- **协议能力**：声明 `roots`（把进程工作目录告诉 server）；**不声明也不支持 `sampling` / `elicitation`**——这两个是「server 反过来向客户端要东西」，本客户端办不到，因此被请求时回一条明确的错误而不是挂在那里等。
- **二进制内容落盘**：server 返回的图片/音频写到系统临时目录下的 `jellyfish-mcp/<pid>/`，回灌给模型的只是一个路径（base64 塞进上下文会让一次截图就撑满窗口）。插件停止时整个目录会被删掉。
- **`-cli` / `-server` 下没有审批者**：`ASK` 等于拒绝，因此这两个模式里写类 MCP 工具实际不可用（与 `shell` 同理）。

| 命令 | 说明 |
| --- | --- |
| `/mcp` | 每个 server 的连接状态、工具数与失败原因（连不上时的唯一线索） |

端到端测试会真的 fork 一个 server 子进程（仓库自带的极简实现），因此单独一个 profile：`mvn -q -Pmcp-it test`。

## 会话压缩（jellyfish-compact）

压缩是**插件能力**，不是内核内置功能。内核手里只有机制——读消息、选范围、发模型调用、校验摘要、
推进边界、记用量、落盘；「这次该压成什么样」由本插件回答：一份摘要指令（本插件 jar 里的
`summary-prompt.md`）+ 两个可选参数。

**不启用它就没有压缩**：没有插件提供摘要指令，就没有可以发给模型的摘要请求。此时

- 自动压缩不生效（上下文满了只会走 `ContextWindow` 的机械裁剪，历史在**请求里**变少）；
- `/compact` 与 `/compact preview` 直接回答「压缩不可用：没有插件提供压缩策略」；
- `/status` 的压缩一行显示「不可用（没有插件提供压缩策略）」。

三种状态一眼可辨：**没装插件**（不可用）、**装了但还没压过**（未压缩）、**压过了**（已压缩 N 条）。
插件在 `~/.jellyfish/plugins/` 里但没有列进 `jellyfish.json` 的 `plugins.enabled` 时，算「没装」；
启动日志里会有一条 `插件被禁用或版本不满足，未启动: pluginId=jellyfish-compact`。

插件里能调的只有两个数字，**摘要措辞改不了**（它在插件 jar 里）：`keepRecentMessages` 与
`maxSummaryChars`，省略即「不表态」，用 `react` 段里的缺省值。内核还会把它们钳制到合法区间——
插件写出荒谬的值不该让压缩失控。

摘要指令里用 `{maxSummaryChars}` 表示长度上限，内核替换成当次生效的值：

```markdown
… 5. 用与原文相同的语言书写，总长控制在 {maxSummaryChars} 字以内。
```

自己写一份也行：改 `jellyfish-plugin-compact/src/main/resources/summary-prompt.md` 后重新打包。占位符缺失不算错误（内核仍会按上限本地截断），但模型会少一条自我约束，因此内核记一条告警。

`/compact preview` 会把这次要付的代价先算给你看——压几条、保留几条、丢弃几条、摘要输入约多少 token。

## 脚本插件（Python / Node 桥接）

除了用 Java 写插件，还可以用 **Python 或 Node** 写。桥接插件把一个脚本目录变成内核眼里的标准
PF4J 插件，脚本与 Java 插件**同权**（11 个扩展点全开），能力边界由进程隔离 + 静态清单 + 熔断三层承担：

| 模块 | 脚本根目录（默认） | 解释器（配置键） |
| --- | --- | --- |
| `jellyfish-plugin-python` | `scripts/python/<脚本标识>/` | `python3`（`pythonPath`） |
| `jellyfish-plugin-node` | `scripts/node/<脚本标识>/` | `node`（`nodePath`） |

每个脚本目录只需一份静态 `manifest.json`（声明工具 / 命令 / 贡献 / 订阅的事件）与一个入口文件。
因此 **启动期零进程、零文件写入**：没装解释器也不影响内核启动，工具清单依然完整；首次真正调用某个脚本
时才拉起它的进程，空闲后自毁。脚本调用失败只影响它自己（每脚本一 worker 进程），连续失败按熔断冷却、
冷却后自动半开恢复。

脚本插件的 API、`manifest.json` 字段与两门语言的逐条对照见
[`examples/scripts/README.md`](examples/scripts/README.md)，仓库顶层 `examples/scripts/{python,node}/{hello,jira}`
是可直接拷贝运行的示例（`hello` 教学最小集、`jira` 真实形态，且被端到端用例直接加载）。

配置段写在 `jellyfish.json` 的 `plugins.configurations."jellyfish-plugin-python"`（或 `-node`）：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-python": {
        "scriptsRoot": "scripts/python",
        "pythonPath": "python3",
        "invokeTimeoutSeconds": 30,
        "workerIdleSeconds": 300,
        "gatewayIdleSeconds": 600
      },
      "jellyfish-plugin-node": {
        "scriptsRoot": "scripts/node",
        "nodePath": "node"
      }
    }
  }
}
```

- `scriptsRoot` 相对**进程工作目录**解析，其下每个含 `manifest.json` 的子目录是一个脚本插件；目录不存在等于「还没建脚本」（正常的冷启动状态）。
- `invokeTimeoutSeconds` 是单次调用超时，写 `0` 表示**没有截止时间**（不是「立刻超时」）；超时会隔离该脚本的 worker，并把这次失败计入熔断。
- `workerIdleSeconds` / `gatewayIdleSeconds` 分别为 worker 与网关的空闲自毁秒数（写 `0` 关闭），空闲回零是「懒启动」的配套。
- `manifestStrict`（默认 `true`）在脚本首次拉起时逐项比对清单与实现，任何漂移都报错并熔断该脚本；`dump_manifest.py` / `dump_manifest.js` 的 `--check`（比对）与 `--write`（直接落盘）用来让两者不漂移。
- `events.allow` **只收窄、不扩展**：可订阅事件清单硬编码在运行时里，这里写不存在的事件名不会扩大任何能力。
- 桥接插件 jar 本身与 Java 插件一样放在 `plugins/` 扫描目录（见上方打包命令），`jellyfish-script` 是内核仓库提供的库、不产出到 `plugins/`。

真实解释器的端到端测试在 `mvn -q -Pscript-it test`（不进 `mvn test`：单测不访问外部资源）。
