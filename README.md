# Jellyfish 官方插件

> 本文件由内核仓库 `README.md` 中的插件章节抽取而成，供手动迁移到独立的官方插件仓库。
> 迁移时把下面的相对路径视为**插件仓库根目录**下的路径（不再有 `jellyfish-plugins/` 前缀）。

官方插件**一个插件一个子模块**（PF4J 是「一个 jar 一个 `plugin.properties`」）：

| 模块 | plugin.id | 提供什么 |
| --- | --- | --- |
| `jellyfish-plugin-tools` | `jellyfish-plugin-tools` | 五个文件工具：`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_files` |
| `jellyfish-plugin-session-file` | `jellyfish-plugin-session-file` | 会话持久化：一个会话一个 JSON 文件，并用 git 管理历史 |
| `jellyfish-plugin-todo` | `jellyfish-plugin-todo` | 会话待办：模型可写的 `todo_write` 工具 + 只读 `/todo` + 随本轮消息送达的待办块 + 状态栏进度 + 左栏清单面板 |
| `jellyfish-plugin-project` | `jellyfish-plugin-project` | 项目约定：探测工作目录下的 `AGENTS.md`，**小文件内联原文、大文件只给路径**（阈值可配） |
| `jellyfish-plugin-compact` | `jellyfish-plugin-compact` | 会话压缩策略：提供摘要指令与保留条数/摘要上限（**不装它就没有压缩**，见下文） |
| `jellyfish-plugin-python` | `jellyfish-plugin-python` | Python 脚本插件运行时：把 `scripts/python/<id>/` 下的脚本目录变成标准插件（控制面网关 + 每脚本一 worker 进程） |
| `jellyfish-plugin-node` | `jellyfish-plugin-node` | Node 脚本插件运行时：与 Python 同构（同一套协议与进程模型），零第三方依赖 |
| `jellyfish-plugin-shell` | `jellyfish-plugin-shell` | 命令行：`shell` 工具（`/bin/sh -c` 执行命令原文）+ 命令策略（白名单准入、可信表免审批、只读不打扰、灾难形状拒绝、其余审批）。**没有沙箱**，见下文 |
| `jellyfish-plugin-skills` | `jellyfish-plugin-skills` | skills：按目录发现 `SKILL.md`，元信息常驻 system prompt、正文由模型用 `skill` 工具按需加载，见下文 |
| `jellyfish-plugin-mcp` | `jellyfish-plugin-mcp` | MCP 客户端：stdio 连外部 MCP server，把它的工具以 `mcp__<server>__<tool>` 接入，见下文 |
| `jellyfish-plugin-workflow` | `jellyfish-plugin-workflow` | 编排：`workflow` 工具接受一份**声明式 spec**，按依赖并发派生子代理并聚合结果（内核只出原语，见下文） |
| `jellyfish-plugin-plan` | `jellyfish-plugin-plan` | plan 模式：`/plan [on|off]` 在会话内开关，开启时只有白名单里的工具可用（白名单来自 `plugins.configurations.jellyfish-plugin-plan.readOnlyTools`），见下文 |

`jellyfish-plugin-tools` 的五个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit`、`max_bytes` | 按行分片读取，命中 `limit` 或 `max_bytes` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path`、`offset`、`limit` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类；大目录分页 |
| `grep_files` | `pattern`、`path`、`max_results`、`max_line_chars`、`max_bytes` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

其中 `read_file`、`list_dir`、`grep_files` 是只读工具，`write_file` 与 `edit_file` 会改动工作目录。
**「只读」在权限层没有内置含义**：能不能在某个模式下放行，由提供那个模式的插件按你写的名单决定
（官方 `jellyfish-plugin-plan` 即此形态，见下文）。

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
cp jellyfish-plugin-workflow/target/jellyfish-plugin-workflow-*.jar plugins/
cp jellyfish-plugin-plan/target/jellyfish-plugin-plan-*.jar plugins/
```

插件配置写在 `jellyfish.json` 的 `plugins.configurations.<pluginId>` 段：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-session-file": {
        "sessionDir": "~/.jellyfish/sessions",
        "gitEnabled": true
      },
      "jellyfish-plugin-todo": {
        "todoDir": "~/.jellyfish/todos"
      },
      "jellyfish-plugin-plan": {
        "readOnlyTools": ["read_file", "list_dir", "grep_files", "todo_write"]
      },
      "jellyfish-plugin-compact": {
        "keepRecentMessages": 20,
        "maxSummaryChars": 4000
      },
      "jellyfish-plugin-project": {
        "maxInlineBytes": 32768
      },
      "jellyfish-plugin-shell": {
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

- `readOnlyTools`（`jellyfish-plugin-plan`）：**plan 模式的工具白名单**，用户写哪些工具名，`/plan on` 之后就只有哪些可用。
  - **不写就等于开启时全部不可用**——白名单语义下「用户没表态」与「用户不准」是同一件事。
  - 工具**无法自称只读**：`ToolDescriptor` 里已没有该字段（提供方声明过的旧写法也已从脚本 SDK 与 MCP 侧移除）。
    早先的口径是「提供方声明 ∪ 用户配置」，那让名单只增不减、判定权还落在被判定的一方。
  - 与插件热部署无关：名单只跟这份配置走，插件装上 / 卸下不会改变它。
  - **注意区分同名键**：`jellyfish-plugin-mcp` 的每个 server 配置段里也有一个 `readOnlyTools`，那个只影响**该插件的审批策略**
    （写类工具要不要问人），与 plan 模式无关。
- `sessionDir`（默认 `~/.jellyfish/sessions`）：会话文件目录。会话是跨项目的运行态数据，因此默认放全局级目录。
- `gitEnabled`（默认 `true`）：首次落盘时在 `sessionDir` 里 `git init`，此后**每次内容变化的落盘留一次提交**（内容没变则不写文件、也不提交）。机器上没有 git 时只告警，文件照常落盘。
- `todoDir`（默认 `~/.jellyfish/todos`）：待办文件目录，一个会话一个 JSON 文件，空表会删掉文件。
- `keepRecentMessages` / `maxSummaryChars`（`jellyfish-plugin-compact`，**都可省略**）：本插件对压缩参数的覆盖值；省略时用内核 `react` 段的缺省值。省略是「不表态」，不是「用 0」。
- `maxInlineBytes`（`jellyfish-plugin-project`，默认 `32768` 即 32 KiB）：约定文件**多大以内可以把原文放进 system prompt**。超过它只给路径指引；写 `0` 表示从不内联（彻底关掉内联的逃生门）。上限 1 MiB，超出或为负数会在启动期直接报错。约定文件名固定为 `AGENTS.md`，查找基准固定为进程工作目录——这两项不可配。
- `timeoutSeconds`（`jellyfish-plugin-shell`，默认 `120`）：命令最多允许跑多久；单次调用可以用 `timeout_seconds` 参数覆盖，并被 `maxTimeoutSeconds`（默认 `1800`）钳制。**不支持「不超时」**——保留一个上限，避免配置写错变成无限等待。
- `idleTimeoutSeconds`（`jellyfish-plugin-shell`，默认 `0` 即**关闭**）：连续多久没有任何输出就判定卡住。墙钟回答「最多跑多久」，它回答「多久没动静就当死了」：一条持续打印进度的 `mvn test` 跑 20 分钟不该被误杀，而 `docker build` 之类确实可能长时间无输出，所以缺省不开。**模型不能设置它**，它是用户的环境策略。
- `environment`（`jellyfish-plugin-shell`）：额外注入或覆盖的环境变量。子进程默认**继承**父进程环境，但名字匹配 `*KEY*` / `*TOKEN*` / `*SECRET*` / `*PASSWORD*` / `*CREDENTIAL*` 的变量**不会**传下去（工具输出会送到远端 LLM），另有一组防挂死默认值（`PAGER=cat`、`GIT_PAGER=cat`、`GIT_TERMINAL_PROMPT=0`、`TERM=dumb`、`NO_COLOR=1`、`DEBIAN_FRONTEND=noninteractive`）。这里写的值可以盖掉默认值。`sensitivePatterns` 用于**追加**剔除模式。
- `allowedCommands`（`jellyfish-plugin-shell`，默认 `[]`）：**非空即默认拒绝**的前缀白名单，支持 `git status` 这种两 token 形式（单 token 覆盖该命令的全部子命令）。它服务于 `-cli` / `-server` 这类没有人在场批准的模式，且**不受 `commandPolicy.enabled` 影响**。
- `commandPolicy`（`jellyfish-plugin-shell`）：命令策略段，四个键都**可省略**。
  - `enabled`（默认 `true`）：只关**分类器**这个便利机制。`false` 时只读表与「其余命令问人」都不再表态，但**白名单、可信表、拒绝形状照旧生效**——它们不是分类器的一部分。
  - `trustedCommands`（默认 `[]`）：**可信命令表，命中即免审批**（无异议、不弹批准框）。粒度与白名单一致。它是「白名单里的命令为什么还要点批准」的答案：白名单只管「能不能跑」，免审批归这张表。
  - `readOnlyCommands`（默认 `[]`）：**追加**在内置只读表之后，用于把用户认为只读的命令纳入免打扰范围。内置表**不可替换**，只能追加。
  - `deniedPatterns`（默认 `[]`）：**追加**在内置拒绝形状（`rm -rf /`、`mkfs`、`of=/dev/`、`:(){`）之后。同样是追加，内置那几条不可撤销。
- **`allowedCommands` 与 `trustedCommands` 要在两处各写一遍才算「能跑且不打扰」**。这不是冗余失误，是刻意分工：前者回答能不能跑（默认拒绝），后者回答要不要问人（默认问）。也因此，「允许跑但每次都要我批准」这种姿态仍然写得出来——只配白名单、不配可信表即可。

## 待办（jellyfish-plugin-todo）

待办是模型的计划草稿，由插件自己持有（内核不再有会话待办字段、也没有 `/todo` 系统命令）：

| 面 | 扩展点 | 说明 |
| --- | --- | --- |
| `todo_write` 工具 | `ToolCallRequest` | 模型写待办的唯一入口。**整表覆盖**：传完整的新列表，上次列过而这次没列出的项视为删除，空数组表示清空；状态取 `pending` / `in_progress` / `completed`，缺省按 `pending` 处理 |
| `todo_claim` 工具 | `ToolCallRequest` | **子代理**认领一条还没人做的待办（无参数，认领「下一条」）：原子地标成 `in_progress` 并记下自己的 run 标识。一个 run 只认领一条，重复调用不会多领；认领不到不等待、不重试，返回一句话。顶层回合不在任何 run 上，会被拒绝 |
| `todo_done` 工具 | `ToolCallRequest` | 把一条待办标成完成（`content` 传原文）。别人正认领着的条目只有认领者能完成，无主的条目谁都可以完成（父回合回收计划就是这种情形） |
| `todo_release` 工具 | `ToolCallRequest` | 把一条待办**放回去**（清认领、回未开始，谁都能接）：用于「我没做它」（领错了、没时间、被叫去做别的） |
| `todo_block` 工具 | `ToolCallRequest` | 把一条待办记成**卡住**（`content` + `reason` 必填）：用于「它做不了」。卡住之后它不会再被任何子代理认领，直到有人放回或父回合重写清单 |
| `/todo` 命令 | `CommandRequest` | 只读地列出当前会话待办（写入只走 `todo_write`，不给同一份状态第二套写入语义） |
| 上下文注入 | `TurnContextRequest` | 每轮把待办块拼在本轮用户消息前面送达，模型始终看得见自己的计划；没有待办时不注入。**不再走 `PromptContributionRequest`**：system prompt 是缓存前缀的第 0 个 token，待办每勾掉一项都会作废整个请求（连同全部历史），而随消息落盘是 append-only 的 |
| 状态栏进度 | `StatusLineContributionRequest` | 状态栏尾部显示 `待办 2/5`，有进行中项时补 `· 进行中 1`、有卡住项时补 `· 卡住 1`（进度涨不上去的两种原因必须分得开），不敲命令也能看到还剩几件事；没有待办时不占位 |
| 选型规则 | `PromptContributionRequest`（`STATIC`） | 一段编译期固定的文字：**什么时候**用 `task`、什么时候用 `workflow` 的 spec、什么时候把活写进待办让子代理认领。**只有选型走它**——状态进 system prompt 会让每次勾掉一件事都作废整段缓存前缀 |
| 左栏清单 | `PanelContributionRequest` | 在左栏常驻显示完整清单（已完成项整行变暗、进行中项高亮：`[~]`），被认领的条目还带「谁在做」（`· researcher`；认领者已结束而条目还没标完成时转警示档 `· researcher（已结束）`；不知道是谁时说 `· 认领者未知`），建议放左栏；没有待办时不占区域 |
| run 通知订阅 | `AgentRunProgressEvent` | 把待办里的认领者 run 标识翻成人看得懂的子代理类型与在场状态。订阅的是**内核**的事件类型，与任何编排插件无关 |

四态的写法与人看到的标记一一对应：`pending` = `[ ]`、`in_progress` = `[~]`、`completed` = `[x]`、
`blocked` = `[!]`（卡住，原因记在同一条的 `reason` 上；清单里有进行中项时，注入的待办块标题会补一句图例）。
状态取值读的时候忽略大小写与连字符（`In-Progress` 也认），写出去的一律是小写下划线。

**「做不了」为什么必须是一个状态**：没有它，一条做不了的活只能一直停在 `pending`，于是一批一批的
子代理反复把它领走、反复失败——抢单式协作最容易烧钱的地方就是这个。卡住的条目**既不算完成、也不可认领**：
要重新做，得先由认领者或父回合把它放回（换个做法）。

**块尾还有一句批间引导**：清单回答「还剩什么」，而下一步该做什么并不自明——所以块尾按当前状态现算一句
（「还有 N 条没人做，可以派子代理用 `todo_claim` 认领它们；M 条已经在做，别再派一遍；K 条卡住了，
需要你或用户决定怎么做」），只写用得上的那几段。这正是「批间决策」在插件侧的落点。

**谁可以动哪一条**：认领者可以完成、放回、卡住自己那条；父回合（不在任何 run 上）可以**放回或卡住任何一条**
（收拾残局），但**不能替别人说「做完了」**——那是一个关于工作结果的声明，要么由做过的人说，
要么由父回合整表重写来明确表达。已完成的条目不接受退回。

参数非法（`todos` 不是数组、项不是对象、`content` 为空、`status` 不在取值内）会**当场报错**，并作为工具结果回灌给模型让它自己改，而不是静默落盘一份坏数据。报错消息会**带上实际收到的值**（`实际为 "doing"`）：只说「实际为 String」时模型改不动，只会原样重试。待办写完会广播一次 UI 失效事件，因此状态栏进度与清单面板不必等回合结束就更新。

待办文件是插件自己的私有格式，落盘字段为 `status`；升级前写下的、只有 `done` 的旧文件仍能读（`done:true` 即已完成），两个字段同时出现时以 `status` 为准。

**父子共享同一份清单**：子代理有自己的会话，而待办按会话归属。为了让它们协作，工具用的键是
「子代理落到父会话、根会话落到自己身上」（键来自内核交给工具的 `parentSessionId`，**不是模型能填的参数**）。
于是子代理的 `todo_write` / `todo_claim` / `todo_done` 读写的就是父回合那一份清单——父回合看得见谁在做什么，
子代理也不再各写一份、在盘上留下孤儿文件。父回合的**整表覆盖不会抹掉认领**：内容没变且已被认领的条目会继承
认领者，正在做的条目也不会被一次重写打回 `pending`（那等于把一件正在做的事放回池子）。

**并发正确性靠插件自己的锁**：认领与完成都是「读出整份列表 → 改一条 → 写回」，全部在 `TodoStore` 的同一个
`synchronized` 方法内完成。它是进程内单实例，因此那把方法锁就是唯一的锁；调用方拿不到「读一半」的列表，
也就拼不出一次有竞态的读-改-写。

面板是「独占型」贡献：它建议落在左栏，但外壳可以忽略这个建议，也可能被用户用 `/ui` 改到别处。
注意它占的是左栏，而外壳在左右侧栏合计超预算时会**先砍右栏**——所以窄终端上左栏的代价是右栏不可见
（用 `/ui left off` 可以把它让出去）。

`todo_write` 只写插件自己的待办文件、不动工作目录里的项目文件。注意**在 plan 模式下它并不自动可用**：只读与否只看用户在
`plugins.configurations.jellyfish-plugin-plan.readOnlyTools` 里写了什么，工具自己没有发言权。

会话恢复：启动时内核向所有注册了恢复处理器的插件要回会话，因此上次退出前的会话在下次启动时立即可见（`/session` 会列出来）。

## 项目约定（jellyfish-plugin-project）

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

## 命令行（jellyfish-plugin-shell）

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

## skills（jellyfish-plugin-skills）

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
- **`skill` 工具是只读的**：读一份说明不该需要写权限，因此值得把它写进 plan 模式的白名单。
- **改了 `SKILL.md` 立即生效**：目录缓存按文件修改时间失效，不需要重启，也不需要 `/reload`。

配置（`jellyfish.json`）：

```jsonc
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-skills": {
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

## 编排（jellyfish-plugin-workflow）

让模型一次说清「几件事、什么顺序、怎么合起来」，由插件把它们变成一批并行跑的子代理：

```json
{"spec": {
  "name": "调研并写方案",
  "steps": [
    {"id": "probe-a", "agent": "scout",   "prompt": "调研 A 方向……"},
    {"id": "probe-b", "agent": "scout",   "prompt": "调研 B 方向……"},
    {"id": "plan",    "agent": "planner", "prompt": "结合 A 与 B 的结论给出方案",
     "needs": ["probe-a", "probe-b"]}
  ],
  "aggregate": {"mode": "summarize", "agent": "planner"}
}}
```

- **没有 `needs` 的步骤立刻开始，因此它们并行执行**；`needs` 让某一步等前几步结束。
- **`when` 只有三个取值**：`always`（缺省）、`on_success`、`on_failure`（后者必须声明 `needs`）。
  没有循环、变量、表达式，也没有重试——要改主意就重新提交一份 spec。
- **`aggregate.mode`**：`collect` 按顺序拼接各步结论；`summarize` 再派一个子代理把它们汇成一段结论。
- 步骤最多 12 步。**校验全部发生在派生任何子代理之前**，写错的 spec 不会先烧掉几个子代理再报错。
- 子代理来自内核的**委派端口**（`PluginContext.delegations()`），与内置的 `task` 工具走同一条代码路径：
  并发上限、深度、墙钟与 token 预算、取消、用量归集与归档的行为完全一致。插件不自己起线程池——
  并发度由内核的 governor 决定，超出的 run 在内核侧排队。
- **一次 `workflow` 会占住一条工具执行线程**直到整批跑完（与 `task` 同口径）。执行期间每个步骤的开始与结局
  会写在「运行中的工具」区域；按 `Esc` 取消回合会掐断全部在途 run。
- **不装它也能用**：只需要「派一个子代理做一件事」时用内置的 `task`；卸载本插件即回退到那个形态。
- **跑的时候看得见**：消息区上方有一块「编排」面板，逐行显示每一步的状态（`[ ]` 待执行 / `[~]` 进行中 /
  `[x]` 完成 / `[!]` 失败 / `[-]` 跳过）与「已完成 X/Y 步」。每步的状态变化都会通知外壳重新拉取一次面板，
  跑完即消失——它展示的是「此刻在跑什么」，历史另有内核的 run 归档。放上方而不是停靠区，是因为停靠区
  已经排了内核的子代理面板，而「哪一步」和「谁在跑」在编排进行时同时都要看。

本插件没有配置项。

## MCP（jellyfish-plugin-mcp）

连上外部的 MCP server，把它的工具当作本机工具使用：

```jsonc
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-mcp": {
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
- **只读与审批**：只读**只认你写的 `readOnlyTools`**（server 自填的 `readOnlyHint` 不再采纳）；**缺省一律按可写**。可写的工具缺省要人工审批（`askWriteTools: false` 可关）。这里的 `readOnlyTools` **只影响本插件的审批策略**，与 plan 插件的白名单无关——后者是另一个插件配置段里的同名键（见上文「插件配置」）。
- **协议能力**：声明 `roots`（把进程工作目录告诉 server）；**不声明也不支持 `sampling` / `elicitation`**——这两个是「server 反过来向客户端要东西」，本客户端办不到，因此被请求时回一条明确的错误而不是挂在那里等。
- **二进制内容落盘**：server 返回的图片/音频写到系统临时目录下的 `jellyfish-plugin-mcp/<pid>/`，回灌给模型的只是一个路径（base64 塞进上下文会让一次截图就撑满窗口）。插件停止时整个目录会被删掉。
- **`-cli` / `-server` 下没有审批者**：`ASK` 等于拒绝，因此这两个模式里写类 MCP 工具实际不可用（与 `shell` 同理）。

| 命令 | 说明 |
| --- | --- |
| `/mcp` | 每个 server 的连接状态、工具数与失败原因（连不上时的唯一线索） |

端到端测试会真的 fork 一个 server 子进程（仓库自带的极简实现），因此单独一个 profile：`mvn -q -Pmcp-it test`。

## plan 模式（jellyfish-plugin-plan）

**一套「先看，别改」的工作方式**：开启后只有你列在 `readOnlyTools` 里的工具可用，其余一律被拒
（拒绝理由会告诉模型「plan 模式下只能使用只读工具：……」，它就不会反复去试一个注定失败的写操作）。

```
/plan            查看当前状态（并给出 on / off 候选）
/plan on         开启：只有白名单里的工具可用
/plan off        关闭：恢复正常
```

- **开关按会话存**（存在会话扩展条目里，随会话一起落盘与恢复）：同一个进程里可以一个会话开着、另一个关着。
- **子代理不继承**：开关挂在会话上，子代理是另一个会话，因此默认不受限制。
- **白名单来自配置段**：`plugins.configurations.jellyfish-plugin-plan.readOnlyTools`，**不写就等于开启时一个工具都用不了**
  （白名单语义）。因此建议把只读的常用工具都列上：`read_file`、`list_dir`、`grep_files`、`todo_write`、`skill`……
- **不换工具清单，只拦执行**：模型看到的工具清单不变，写操作在执行时被拒。这是刻意的——清单进的是请求前缀里
  很靠前的位置，换集合会让那一轮之后的前缀（连同全部历史）全部作废。
- **`-cli` / `-server` 下同样有效**：它不依赖审批者，因此没有人在场的模式里也能用它兜住写操作。
- 装了这个插件才有 `/plan`；不带它的内核里没有「模式」这个概念，也没有 `--mode` 参数。

## 会话压缩（jellyfish-plugin-compact）

压缩是**插件能力**，不是内核内置功能。内核手里只有机制——读消息、选范围、发模型调用、校验摘要、
推进边界、记用量、落盘；「这次该压成什么样」由本插件回答：一份摘要指令（本插件 jar 里的
`summary-prompt.md`）+ 两个可选参数。

**不启用它就没有压缩**：没有插件提供摘要指令，就没有可以发给模型的摘要请求。此时

- 自动压缩不生效（上下文满了只会走 `ContextWindow` 的机械裁剪，历史在**请求里**变少）；
- `/compact` 与 `/compact preview` 直接回答「压缩不可用：没有插件提供压缩策略」；
- `/status` 的压缩一行显示「不可用（没有插件提供压缩策略）」。

三种状态一眼可辨：**没装插件**（不可用）、**装了但还没压过**（未压缩）、**压过了**（已压缩 N 条）。
插件在 `~/.jellyfish/plugins/` 里但没有列进 `jellyfish.json` 的 `plugins.enabled` 时，算「没装」；
启动日志里会有一条 `插件被禁用或版本不满足，未启动: pluginId=jellyfish-plugin-compact`。

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
PF4J 插件，能力边界由进程隔离 + 静态清单 + 熔断三层承担。

**脚本不是「与 Java 插件同权」**：两者在机制上同构（同样的注册、owner、回收、生命周期），
但**扩展点覆盖的是一份「能力档」而不是全部**——已打通 24 个（工具、命令、候选查询、模型目录、
输入指令，提示词 / 状态栏 / 面板贡献，权限拦截，会话持久化三段，压缩策略，工具参数改写与结果整形，
回合上下文，会话关闭前与分支前，压缩前，工具激活，输入改写，回合开始前，请求调优，老化策略）。哪些待做、哪些明确不做，
写在 `jellyfish-script/src/main/resources/script/extension-points.json`，并由单测守着：
**内核新增扩展点而未分档，本仓库会构建失败**。明确不做的两类是「返回 Java 对象的扩展点」
与「跑在渲染线程 / 启动期的扩展点」——脚本调用是一次可能冷启动的进程往返，那些位置付不起。

| 模块 | 脚本根目录（默认） | 解释器（配置键） |
| --- | --- | --- |
| `jellyfish-plugin-python` | `scripts/python/<脚本标识>/` | `python3`（`pythonPath`） |
| `jellyfish-plugin-node` | `scripts/node/<脚本标识>/` | `node`（`nodePath`） |

每个脚本目录只需一份静态 `manifest.json`（声明工具 / 命令 / 贡献 / 订阅的事件 / 周期任务）与一个入口文件。
因此 **启动期零进程、零文件写入**：没装解释器也不影响内核启动，工具清单依然完整；首次真正调用某个脚本
时才拉起它的进程，空闲后自毁。脚本调用失败只影响它自己（每脚本一 worker 进程），连续失败按熔断冷却、
冷却后自动半开恢复。

脚本插件的 API、`manifest.json` 字段与两门语言的逐条对照见
[`examples/scripts/README.md`](examples/scripts/README.md)，仓库顶层 `examples/scripts/{python,node}/{hello,jira}`
是可直接拷贝运行的示例（`hello` 教学最小集、`jira` 真实形态，且被端到端用例直接加载）；
另有仅 Python 的 `examples/scripts/python/web/`——一个**零第三方依赖的联网搜索与网页抓取插件**
（`web_search` / `web_fetch`）。它**什么都不配也能搜**：`provider` 缺省是 `auto`，兜底到 Exa 的
公开 MCP 端点（无需 key，代价是查询会经过 Exa）；一旦配了自建 `endpoint`，`auto` 就优先用它。
自带 SSRF 防护（搜索端点按「配置来源可信」放行，`web_fetch` 的目标永远不放行，另有 `allowRanges`
给 TUN/假 IP 代理豁免网段），是「脚本插件能不能写真实东西」的现成答案。

`examples/scripts/python/{stock,stockpanel}/` 是另一个真实形态：**A 股行情与自选股**（`/stock` 命令、
5 个取数工具、侧栏面板）。它演示两件真实插件一定会遇到的事——数据源要按「主口径 + 兜底」写
（东财的推流域名在部分网络下会被远端直接断开，单一口径等于时好时坏），以及**联网的脚本必须与
画面板的脚本分成两个进程**（面板处理器跑在界面渲染线程上、没有任何超时，一次网络请求就会冻住
整个界面）。数据源矩阵与单位口径见
[`examples/scripts/python/stock/DESIGN.md`](examples/scripts/python/stock/DESIGN.md)；
它依赖 `akshare`，因此**不进端到端用例**，改完要手动验证。

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
        "gatewayIdleSeconds": 600,
        "scripts": {
          "web": { "provider": "auto", "endpoint": "", "timeoutSeconds": 20 }
        }
      },
      "jellyfish-plugin-node": {
        "scriptsRoot": "scripts/node",
        "nodePath": "node"
      }
    }
  }
}
```

- `scripts` 是**逐脚本配置段**：`scripts.<脚本 id>`（脚本目录名就是脚本 id）只属于那一个脚本，
  值对桥接层不透明——写密钥、baseUrl、超时随便写。脚本用 `ctx.configuration` 读
  （Python 还可用模块级 `configuration()`，在模块顶层就能调）。字符串值里的 `${ENV}` 照常插值，
  因此与 Java 插件是同一条密钥通道。**密钥不能靠环境变量传给脚本**：脚本进程的环境是严格白名单
  （仅解释器运行与依赖解析必需的那几个），这是有意的安全取舍。脚本 id 写错会在启动时告警。

  > ⚠️ **`${ENV}` 是硬失败，不是「取不到就留空」**：配置里写了 `${SOME_KEY}` 而环境变量没设，
  > 整个进程**启动即失败**（`environment variable is not set: SOME_KEY`）。因此上面例子里的
  > `apiKey` 是空字符串而不是占位符——写上占位符就是个定时炸弹，换个 shell、换台机器就起不来。
  > 确实要用的密钥才写 `${...}`，并且保证它总是已设。
- **async handler（Node）**：工具 / 命令 / 贡献 / 事件的处理函数都可以是 `async`，桥接会 `await` 它，
  因此 Node 脚本可以直接用 `fetch` / `undici` / `@mozilla/readability` 这类异步生态。
  这不改变「在途请求只有一个」——worker 仍是一次只处理一帧，只是允许 handler 等 I/O。
  事件处理器同样可以是 async，但它**不会被等待**（事件是旁路，不能拖住请求）。Python 无需这个改造。
- **工具结果可带元数据**：返回 `ToolResult(正文, summary=..., terminal=...)` 时，`summary` 会显示在轨迹行上、
  `terminal` 不等于 `COMPLETED` 时出警示标记；它们不进模型上下文。普通返回值仍是 output，老脚本不受影响。
- **工具能拿到调用者身份**：`ctx.parent_session_id` / `run_id` / `root_run_id`（Node 为驼峰）。
  跨 run 协作的键要从内核取，**不要用 `session_id`**——子代理有独立会话，用它做键会各写一份。
- **取消会中止在途调用**：回合的取消令牌传到桥接层后，在途脚本调用失败并隔离 worker（与超时同一条链）。
  脚本侧看不到取消标志（worker 单线程，在途调用期间读不到新帧），因此不要依赖取消做资源清理；
  取消也不计入熔断。
- **带路由键、路由键又来自用户配置的扩展点用 `@handler` 声明**：`model_catalog`（路由键是
  provider 名）与 `input_directive`（路由键是标记本身，如 `!`）。SDK 侧是
  `@handler("input_directive", route="!")`（Node 是 `handler({type, route}, fn)`），
  清单里还要写一份 `"handlers": [{"type": ..., "route": ...}]`。
  其余类型级扩展点走普通的 `contributes`（`tool_argument_pre` / `tool_result_post` /
  `turn_context` / `session_before_close` / `session_before_fork` / `compaction_pre` /
  `tool_activation` / `input_transform` / `turn_before` / `request_tuning` / `aging_strategy`），
  写法见 [`examples/scripts/README.md`](examples/scripts/README.md)。
- **热路径点不冷启动**：`request_tuning` / `aging_strategy` 是「每次组装请求都会被问到」的点。
  worker 没热着时它们直接返回「不表态」（不冷启动），热着时也只用 2 秒截止。
  推论：**只提供这两个贡献的脚本永远不会被拉起**——要让它跑起来，至少还要提供一个会被真正调用的点。
- **输出捕获明确不做**：它解决的是无界流式输出（长命令），而脚本工具返回的是一次性的整个值；
  内核已会把超大结果落盘并给出恢复路径，不存在内容丢失。
- `scriptsRoot` 相对**进程工作目录**解析，其下每个含 `manifest.json` 的子目录是一个脚本插件；目录不存在等于「还没建脚本」（正常的冷启动状态）。
- `invokeTimeoutSeconds` 是单次调用超时，写 `0` 表示**没有截止时间**（不是「立刻超时」）；超时会隔离该脚本的 worker，并把这次失败计入熔断。
- `workerIdleSeconds` / `gatewayIdleSeconds` 分别为 worker 与网关的空闲自毁秒数（写 `0` 关闭），空闲回零是「懒启动」的配套。
- `manifestStrict`（默认 `true`）在脚本首次拉起时逐项比对清单与实现，任何漂移都报错并熔断该脚本；`dump_manifest.py` / `dump_manifest.js` 的 `--check`（比对）与 `--write`（直接落盘）用来让两者不漂移。
- `events.allow` **只收窄、不扩展**：可订阅事件清单硬编码在运行时里，这里写不存在的事件名不会扩大任何能力。
- 桥接插件 jar 本身与 Java 插件一样放在 `plugins/` 扫描目录（见上方打包命令），`jellyfish-script` 是内核仓库提供的库、不产出到 `plugins/`。

### 周期任务（`schedules`）

脚本**没有自己的主循环**（worker 只在被调用时活着，空闲还会自毁），因此「到点做一件事」这个
发起方只能落在桥接层：清单里声明一条 `schedules`，桥接插件就按它的间隔调用脚本里同名的处理函数。

```json
"schedules": [ { "name": "refresh", "intervalSeconds": 60 } ]
```

```python
@periodic(name="refresh", interval_seconds=60)   # Node: periodic({name: 'refresh', intervalSeconds: 60}, fn)
def refresh_watch(ctx):
    reload_cache()          # 返回值被忽略
```

- **成功之后由桥接层代发一次 `UiInvalidatedEvent`**：脚本发布不了这个事件（可发布事件只有
  `PluginNotificationEvent` / `ConfigWarningEvent`），而周期任务的语义就是「我后台更新了自己
  贡献的内容」。没有这一步，定时抓到的新数据只会写进文件，而屏上的面板永远不会自己重画——
  这是「脚本也能后台刷新界面」唯一的通路。
- **间隔语义是「上一次跑完 + 间隔」**（`scheduleWithFixedDelay`），因此天然不会重入：
  取数慢到十几秒也不会堆起第二次调用。
- **缺省 5 秒、下限 1 秒**（下限的理由不是安全而是诚实：外壳对面板的显示粒度就是秒级）；
  可被配置覆盖：`scripts.<脚本 id>.schedules.<任务名>.intervalSeconds`，覆盖值越界会回落声明值并
  发一条配置告警。**只支持固定间隔，不做 cron**（时区、错过触发、夏令时是一整套语义，不在这一版的范围内）。
- **失败只记一条日志，不会让任务停掉**：`scheduleWithFixedDelay` 的任务体抛异常会让后续触发
  **静默停止**，因此桥接层自己吞掉了异常；失败时也**不**发失效事件——数据没变，
  重画面板只是让所有面板白跑一遍。
- **一个脚本一条守护线程**（进程退出不被它拖住），关闭插件时**先停定时器、再关网关**。
- **代价**：定时调用会让该脚本的 worker 不再空闲自毁（缺省空闲 300 秒回收）。

真实解释器的端到端测试在 `mvn -q -Pscript-it test`（不进 `mvn test`：单测不访问外部资源）。
