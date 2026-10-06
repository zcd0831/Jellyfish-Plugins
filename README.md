# Jellyfish 官方插件

[Jellyfish](https://github.com/zcd0831/Jellyfish) 内核的官方插件集合，以及**插件开发教程**。

插件是**独立打包的 PF4J jar**，由内核在启动期扫描加载，与内核之间**没有编译期依赖**——只经
`jellyfish-api` 的 SPI 交互。因此插件可以在内核之外单独构建、单独发版。

| 仓库 | 是什么 |
| --- | --- |
| [Jellyfish](https://github.com/zcd0831/Jellyfish) | 内核：三种外壳（CLI / TUI / Server）、装配、扩展点与插件运行时、插件 SPI |
| **Jellyfish-Plugins**（本仓库） | 官方插件 + 插件开发教程 |
| [Jellyfish-Spring-Boot-Starter](https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter) | 把内核接进已有的 Spring Boot 应用 |

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 8（与内核一致，**不要使用 Java 9+ 的 API 或语法**） |
| Maven | 3.2.5 以上 |
| 内核 | 已 `mvn install` 到本地仓库（见下） |

**本仓库是独立的 reactor**：`jellyfish-api` 不来自内核源码树，而是本地 Maven 仓库里那一份。因此改插件之前先装内核：

```bash
(cd ../Jellyfish && mvn -q install -DskipTests)
```

忘了这一步的表现是「内核明明改了，插件这边编译却看不到」。

## 构建与安装

```bash
mvn -q package -DskipTests      # 打包全部插件
./cpPlugins.sh                  # 把 target/*.jar 拷进扫描目录（缺省 ~/.jellyfish/plugins/）
```

**新增 / 删除插件 jar 需要重启 jellyfish**——扫描目录与插件集合只在启动期确定（`config.json` 的
`plugins.roots` 决定扫描哪里）。启用 / 禁用名单与逐插件配置段写在 `jellyfish.json`，改动由 `/reload` 生效。

也可以只装用得到的几个：

```bash
mvn -q package -DskipTests
mkdir -p ~/.jellyfish/plugins
cp jellyfish-plugin-tools/target/jellyfish-plugin-tools-*.jar ~/.jellyfish/plugins/
```

## 插件一览

| 模块 | plugin.id | 提供什么 |
| --- | --- | --- |
| `jellyfish-plugin-tools` | 同名 | 五个文件工具：`read_file`、`write_file`、`edit_file`、`list_dir`、`grep_files`；提问工具 `ask_user`；以及输入框的 `@` 文件引用 |
| `jellyfish-plugin-shell` | 同名 | `shell` 工具（`/bin/sh -c` 执行命令原文）+ 命令策略（白名单准入、可信表免审批、只读不打扰、灾难形状拒绝、其余审批）+ `!命令` 输入指令。**没有沙箱** |
| `jellyfish-plugin-session-file` | 同名 | 会话持久化：一个会话一个 JSON 文件，并用 git 管理历史 |
| `jellyfish-plugin-todo` | 同名 | 会话待办：`todo_write` / `todo_claim` / `todo_done` / `todo_release` / `todo_block` 五个工具 + 只读 `/todo` + 随本轮消息送达的待办块 + 状态栏进度 + 左栏清单面板 |
| `jellyfish-plugin-project` | 同名 | 项目约定：探测工作目录下的 `AGENTS.md`，**小文件内联原文、大文件只给路径**（阈值可配） |
| `jellyfish-plugin-compact` | 同名 | 会话压缩策略：摘要指令 + 保留条数 / 摘要上限（**不装它就没有压缩**） |
| `jellyfish-plugin-skills` | 同名 | skills：按目录发现 `SKILL.md`，元信息常驻 system prompt、正文由模型用 `skill` 工具按需加载 |
| `jellyfish-plugin-mcp` | 同名 | MCP 客户端：stdio 连外部 MCP server，把它的工具以 `mcp__<server>__<tool>` 接入 |
| `jellyfish-plugin-workflow` | 同名 | 编排：`workflow` 工具接受一份**声明式 spec**，按依赖并发派生子代理并聚合结果 |
| `jellyfish-plugin-plan` | 同名 | plan 模式：`/plan [on\|off]` 在会话内开关，开启时只有白名单里的工具可用 |
| `jellyfish-plugin-python` | 同名 | Python 脚本插件运行时：把 `scripts/python/<id>/` 下的脚本目录变成标准插件 |
| `jellyfish-plugin-node` | 同名 | Node 脚本插件运行时：与 Python 同构（同一套协议与进程模型），零第三方依赖 |

`jellyfish-script` 是**库**（Python / Node 桥接共用的机制层），不产出到 `plugins/`；它由两个桥接插件
以 shade 的方式打进各自的 jar。

## 工具（jellyfish-plugin-tools）

五个文件工具，其中三个只读：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `read_file` | `path`、`offset`、`limit`、`max_bytes` | 按行分片读取，命中 `limit` 或 `max_bytes` 会提示续读；相对路径按**进程工作目录**解析 |
| `write_file` | `path`、`content` | 整文件覆盖写（UTF-8），输出区分「新建」与「覆盖」，父目录自动创建 |
| `edit_file` | `path`、`old_text`、`new_text`、`replace_all` | 字面量精确替换；匹配到多处且未声明 `replace_all` 时**报错而不改文件** |
| `list_dir` | `path`、`offset`、`limit` | 只列一层，目录优先 + `/` 后缀，不过滤 `target` 之类；大目录分页 |
| `grep_files` | `pattern`、`path`、`max_results`、`max_line_chars`、`max_bytes` | 逐行正则，返回 `文件:行号:内容`；跳过 `.git`/`target`/`node_modules` 与二进制文件 |

外加一个提问工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `ask_user` | `question`、`options` | 把一个问题连同 2~6 个候选项摆到用户面前，等他选一个或自己填；答案以工具结果回灌给模型 |

`ask_user` 是这个插件里**唯一持有外部依赖**的工具：它经 `PluginContext.askUser()` 拿到内核的提问端口
（其余工具都是无状态的纯函数）。几点必须知道的口径：

- **它不是审批**：用户选任何一项都不会放行任何工具，只是把答案告诉模型。权限仍然只由内核的
  `PermissionManager` 收口。
- **拿不到答复不算失败**：没有交互界面的外壳（`-cli`）、子代理回合、等待超时，三种都返回**成功的**工具结果，
  内容说明原因并让模型自行判断。做成失败会让模型以为环境出错、反复重试同一个提问。
- **子代理回合里当场拒绝**：外层界面按当前会话取待答项，而子代理有独立的会话，它的提问不会出现在任何界面上。
- **超时**来自 `jellyfish.json` 的 `ask.timeoutSeconds`（缺省 120 秒），不是本插件的配置段。

本插件没有自己的配置项。

- **`read_file` 单行就超过 `max_bytes` 时报错，不切短**：切短会输出一行「看起来完整、实际残缺」的内容，
  模型无从判断自己拿到的是不是全文。错误文案给出三条出路——缩小 `limit`、调大 `max_bytes`、
  或改用 `grep_files` 定位。多行累加超预算仍照旧分页（内容还在文件里，可按 `offset` 续读）。
- **`@路径` 引用**：输入框敲 `@` 弹补全面板（目录在前、可逐层往下钻），接受后路径写进输入框。
  它**不内联文件内容**——读取由模型调用 `read_file` 完成，因此大文件不会撑爆上下文，
  权限与 `max_bytes` 照旧生效。
- **「只读」在权限层没有内置含义**：能不能在某个模式下放行，由提供那个模式的插件按你写的名单决定
  （官方 `jellyfish-plugin-plan` 即此形态）。

## 命令行（jellyfish-plugin-shell）

`shell` 工具让模型在本机执行命令。**它没有沙箱**：命令以本进程的权限运行，能读写你这个用户的任意文件。

| 参数 | 说明 |
| --- | --- |
| `command` | 命令行原文，交给 `/bin/sh -c` 执行——管道、重定向、通配符都按 shell 语义工作 |
| `cwd` | 工作目录，相对路径按进程工作目录解析；缺省为进程工作目录 |
| `timeout_seconds` | 本次调用的超时秒数，被 `maxTimeoutSeconds` 钳制 |

- **每次调用都是一个全新的 shell**，`cd` 不跨调用保留：需要换目录就用 `cd 目录 && 命令` 或传 `cwd`。
- **`stdin` 在启动后立即关闭**：`vi` / `ssh` / `sudo` 这类交互式命令会立刻失败（TUI 处于 raw 模式，
  子进程直接写终端会把界面画烂）。
- **stdout 与 stderr 合并**成一条流（到达顺序，像终端）。
- **非零退出码是结果而不是错误**：`grep` 没匹配到返回 1，这在输出里如实报告；工具调用本身不算失败。
- **输出边产生边捕获**：内存占用与输出体积无关，超限部分落盘，回灌给模型的是头尾预览加路径。
  被终止时输出里会说明是墙钟超时、静默判定还是取消，并附上已捕获的部分输出。
- **执行期的输出会实时显示**：`-cli` 写到 stderr，`-tui` 在消息区显示「运行中的工具轨迹」块。
- **`!命令` 输入指令**：手动执行一条命令，**不进模型**；执行完把「命令回显 + 输出」作为一条用户消息
  落进会话，因此下一次提问时模型看得到结果。它复用与模型调用完全相同的执行链路，**照旧过权限与审批**；
  `Esc` 可以中断正在跑的命令。

### 配置

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `timeoutSeconds` | `120` | 命令最多允许跑多久；单次调用可用 `timeout_seconds` 参数覆盖，并被 `maxTimeoutSeconds`（`1800`）钳制。**不支持「不超时」** |
| `maxTimeoutSeconds` | `1800` | 单次调用能声明的超时上限 |
| `idleTimeoutSeconds` | `0`（关闭） | 连续多久没有任何输出就判定卡住。「墙钟」回答「最多跑多久」，它回答「多久没动静就当死了」。**模型不能设置它**，它是用户的环境策略 |
| `environment` | `{}` | 额外注入或覆盖的环境变量。子进程**继承**父进程环境，但名字匹配 `*KEY*` / `*TOKEN*` / `*SECRET*` / `*PASSWORD*` / `*CREDENTIAL*` 的变量**不会**传下去（工具输出会送到远端 LLM）；另有一组防挂死默认值（`PAGER=cat`、`GIT_PAGER=cat`、`GIT_TERMINAL_PROMPT=0`、`TERM=dumb`、`NO_COLOR=1`、`DEBIAN_FRONTEND=noninteractive`），这里写的值可以盖掉默认值；`sensitivePatterns` 用于**追加**剔除模式 |
| `allowedCommands` | `[]` | **非空即默认拒绝**的准入白名单，支持 `git status` 这种两 token 形式（单 token 覆盖该命令的全部子命令）。**它只管准入，不管审批**，也**不受 `commandPolicy.enabled` 影响** |
| `commandPolicy.enabled` | `true` | 只关**分类器**这个便利机制。`false` 时只读表与「其余命令问人」都不再表态，但白名单、可信表、拒绝形状照旧生效 |
| `commandPolicy.trustedCommands` | `[]` | **可信命令表，命中即免审批**。粒度与白名单一致 |
| `commandPolicy.readOnlyCommands` | `[]` | **追加**在内置只读表之后。内置表**不可替换**，只能追加 |
| `commandPolicy.deniedPatterns` | `[]` | **追加**在内置拒绝形状（`rm -rf /`、`mkfs`、`of=/dev/`、`:(){`）之后，同样不可撤销 |

### 权限与分类器

每次调用依次过五道，先拒绝后免打扰、最后才问人：

1. `allowedCommands` 非空且没命中 → **直接拒绝**（默认拒绝的硬门）；
2. 命中拒绝形状（`rm -rf /`、`mkfs`、`dd of=/dev/`）→ **直接拒绝**；
3. 命中 `commandPolicy.trustedCommands` → **免审批**，直接执行；
4. 命中只读表（`ls`、`git status`、`cat`……）→ **免审批**；
5. 其余 → **升级为人工审批**，你会看到一个批准框。

第 2 步排在第 3 步之前是刻意的：把 `rm` 写进可信表，`rm -rf /` 依旧被拒。

- **分类器不是安全边界**：`FOO=bar cmd`、`$(...)`、`&&` 链都能绕过它。它的价值是让你不必为每次
  `git status` 点一次批准——否则你最终会把 `shell` 从 `askTools` 里整个拿掉，那才是真正的风险。
- **`trustedCommands` 是免审批表，白名单不解除审批**：这是最容易踩的一处——`allowedCommands` 里的
  `mvn test` 每次仍然弹批准框，因为白名单只回答「能不能跑」。要它静默执行，就把同一条前缀也写进
  `trustedCommands`。**「允许跑但每次都要我批准」这种姿态仍然写得出来**——只配白名单、不配可信表即可。
- **`find` / `git fetch` / `npm test` 刻意不算只读**：`find -delete` 会删东西，`git fetch` 改 ref，
  `npm test` 执行仓库里的任意代码。它们免审批的唯一正当路径是可信表。
- **默认配置（`shell` 不在 `askTools` 里）就是推荐的姿态**；**把 `shell` 写进 `askTools` 则是
  「每条命令都要批准」**（连 `git status` 也要）：核心策略的 `ASK` 无法被插件的「无异议」降级，
  插件的裁定只能收紧、不能放宽。想要最强姿态就用它，代价是噪音。
- **`-cli` / `-server` 下没有人在场**：`askTools` 等于禁用，而 **`ASK` 就是拒绝**。于是免审批的放行口
  只剩两个——被分类器判为只读的命令，以及 `trustedCommands` 里的命令（白名单非空时还得先过白名单）。
  **想在无人值守下跑 `mvn test` 这类非只读命令，必须把它写进 `trustedCommands`。**
- **不做目录围栏**：它可绕过（`cd /`、绝对路径、`sh -c` 嵌套），与文件工具没有围栏也不自洽。
  真正的边界是审批加白名单。
- **Windows 未验证**：非 POSIX 平台映射成 `cmd.exe /c`，但没有在真机上跑过。
- **进程树只能尽力杀**：`sh -c` 的直接子进程是 shell，杀掉它不一定带走 `npm run dev` 拉起的孙进程。
  工具会用 `pgrep -P` 递归尽力而为，**杀不干净是已知边界**。

端到端测试会真的起进程再杀掉它们，因此单独一个 profile：`mvn -q -Pshell-it test`。

## 会话持久化（jellyfish-plugin-session-file）

一个会话一个 JSON 文件，并用 git 管理历史。装了它，`--session` / `/resume` / `/session` 才有内容可恢复；
启动时内核向所有注册了恢复处理器的插件要回会话，因此上次退出前的会话在下次启动时立即可见。

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `sessionDir` | `~/.jellyfish/sessions` | 会话文件目录。会话是跨项目的运行态数据，因此默认放全局级目录 |
| `gitEnabled` | `true` | 首次落盘时在 `sessionDir` 里 `git init`，此后**每次内容变化的落盘留一次提交**（内容没变则不写文件、也不提交）。机器上没有 git 时只告警，文件照常落盘 |

落盘失败会**上抛**（那一刻起「状态已变」与「状态已落盘」必须同生共死）；git 出错与坏文件只告警。

## 待办（jellyfish-plugin-todo）

待办是模型的计划草稿，由插件自己持有（内核没有会话待办字段、也没有 `/todo` 系统命令）：

| 面 | 说明 |
| --- | --- |
| `todo_write` | 模型写待办的唯一入口。**整表覆盖**：传完整的新列表，上次列过而这次没列出的项视为删除，空数组表示清空；状态取 `pending` / `in_progress` / `completed`，缺省 `pending` |
| `todo_claim` | **子代理**认领一条还没人做的待办（无参数，认领「下一条」）：原子地标成 `in_progress` 并记下自己的 run 标识。一个 run 只认领一条。顶层回合不在任何 run 上，会被拒绝 |
| `todo_done` | 把一条待办标成完成（`content` 传原文）。别人正认领着的条目只有认领者能完成；无主的条目谁都可以完成 |
| `todo_release` | 把一条待办**放回去**（清认领、回未开始，谁都能接）：用于「我没做它」 |
| `todo_block` | 把一条待办记成**卡住**（`content` + `reason` 必填）：用于「它做不了」。卡住之后不会再被任何子代理认领，直到有人放回或父回合重写清单 |
| `/todo` | 只读地列出当前会话待办（写入只走 `todo_write`） |
| 上下文注入 | 每轮把待办块拼在本轮用户消息前面送达，模型始终看得见自己的计划；没有待办时不注入。**不走 system prompt**：那是缓存前缀的第 0 个 token，待办每勾掉一项都会作废整个请求 |
| 状态栏进度 | 状态栏尾部显示 `待办 2/5`；有进行中项补 `· 进行中 1`、有卡住项补 `· 卡住 1`；没有待办时不占位 |
| 左栏清单 | 左栏常驻显示完整清单（已完成项整行变暗、进行中项高亮 `[~]`），被认领的条目还带「谁在做」（认领者已结束而条目还没标完成时转警示档）；没有待办时不占区域 |
| 选型提示词 | 一段编译期固定的文字：**什么时候**用 `task`、什么时候用 `workflow` 的 spec、什么时候把活写进待办让子代理认领。只有选型走它 |

四态的标记一一对应：`pending` = `[ ]`、`in_progress` = `[~]`、`completed` = `[x]`、`blocked` = `[!]`。
状态取值读的时候忽略大小写与连字符（`In-Progress` 也认），写出去的一律是小写下划线。

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `todoDir` | `~/.jellyfish/todos` | 待办文件目录，一个会话一个 JSON 文件，空表会删掉文件 |

- **「做不了」为什么必须是一个状态**：没有它，一条做不了的活只能一直停在 `pending`，于是一批一批的
  子代理反复把它领走、反复失败——抢单式协作最容易烧钱的地方就是这个。卡住的条目**既不算完成、也不可认领**。
- **父子共享同一份清单**：工具用的键是「子代理落到父会话、根会话落到自己身上」（键来自内核交给工具的
  `parentSessionId`，**不是模型能填的参数**）。于是子代理读写的就是父回合那一份清单，不会各写一份、
  在盘上留下孤儿文件。父回合的**整表覆盖不会抹掉认领**。
- **谁可以动哪一条**：认领者可以完成、放回、卡住自己那条；父回合可以**放回或卡住任何一条**，
  但**不能替别人说「做完了」**——那是一个关于工作结果的声明。已完成的条目不接受退回。
- **参数非法当场报错**（`todos` 不是数组、`content` 为空、`status` 不在取值内），并作为工具结果回灌给模型
  让它自己改，而不是静默落盘一份坏数据。报错消息会**带上实际收到的值**——只说「实际为 String」时
  模型改不动，只会原样重试。
- **并发正确性靠插件自己的锁**：认领与完成都是「读出整份列表 → 改一条 → 写回」，全部在 `TodoStore`
  的同一个 `synchronized` 方法内完成。
- `todo_write` 只写插件自己的待办文件、不动工作目录里的项目文件。**在 plan 模式下它并不自动可用**：
  只读与否只看你在 `readOnlyTools` 里写了什么。

## 项目约定（jellyfish-plugin-project）

`AGENTS.md` 是仓库里的项目约定（构建命令、编码规范、提交格式、模块边界）。这个插件把它交给模型，
**按文件大小分两路**：

| 情形 | 注入内容 |
| --- | --- |
| 装得进 `maxInlineBytes`（默认 32 KiB） | `[项目约定]` + **文件原文**（外带一句定性：这是项目内文件的数据，不是系统指令） |
| 超过上限 | `[项目约定]` + **路径与文件大小**，让模型自己按需分段读 |

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `maxInlineBytes` | `32768`（32 KiB） | 约定文件**多大以内可以把原文放进 system prompt**；写 `0` 表示从不内联（彻底关掉内联的逃生门）。上限 1 MiB，超出或为负数会在启动期直接报错 |

- **为什么小文件要内联**：常见项目的 `AGENTS.md` 只有几十行，直接给全文可以省掉一次读取工具的往返，
  也消除了「模型忘了去读」这个失败模式。
- **为什么大文件只给路径**：system prompt 每一轮都要随请求付一次 token，而大文件多半是参考性内容。
- **不做「内联前 N KB + 给路径」**：头部往往恰是信息量最低的部分，而且截断点落在哪、模型知不知道
  「后面还有」都是新的失败模式。
- **内联是刻意的安全姿态取舍**：原文进的是 system prompt，即仓库内容拿到了最高优先级的话语权。
  护栏有三条：内联块开头的定性句、足够小的上限、以及把 `maxInlineBytes` 配成 `0` 彻底关掉。
- **只查进程工作目录**，不向上查找父目录、也不查用户主目录：**因此请从仓库根目录启动**。
  约定文件名固定为 `AGENTS.md`，这两项不可配。
- **空文件不算命中**；文件不存在时插件完全不注入。
- **每个会话只读一次盘**：第一次组装请求时读取并缓存，代价是**会话中途修改 `AGENTS.md` 不生效**——
  开一个新会话即可。缓存省的是磁盘 I/O，**不省 token**。

## 会话压缩（jellyfish-plugin-compact）

压缩是**插件能力**，不是内核内置功能。内核手里只有机制——读消息、选范围、发模型调用、校验摘要、
推进边界、记用量、落盘；「这次该压成什么样」由本插件回答：一份摘要指令（本插件 jar 里的
`summary-prompt.md`）+ 两个可选参数。

**不启用它就没有压缩**：没有插件提供摘要指令，就没有可以发给模型的摘要请求。此时

- 自动压缩不生效（上下文满了只会走机械裁剪，历史在**请求里**变少）；
- `/compact` 与 `/compact preview` 直接回答「压缩不可用：没有插件提供压缩策略」；
- `/status` 的压缩一行显示「不可用（没有插件提供压缩策略）」。

三种状态一眼可辨：**没装插件**（不可用）、**装了但还没压过**（未压缩）、**压过了**（已压缩 N 条）。
插件在 `~/.jellyfish/plugins/` 里但没有列进 `jellyfish.json` 的 `plugins.enabled` 时算「没装」；
启动日志里会有一条 `插件被禁用或版本不满足，未启动: pluginId=jellyfish-plugin-compact`。

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `keepRecentMessages` | 省略 | 本插件对压缩保留条数的覆盖值；省略时用内核 `react.compactKeepRecentMessages`（20）。省略是「不表态」，不是「用 0」 |
| `maxSummaryChars` | 省略 | 摘要长度上限的覆盖值；省略时用 `react.compactMaxSummaryChars`（4000）。内核还会把它们钳制到合法区间 |

**摘要措辞改不了**（它在插件 jar 里）。摘要指令里用 `{maxSummaryChars}` 表示长度上限，内核替换成当次生效的值：

```markdown
… 5. 用与原文相同的语言书写，总长控制在 {maxSummaryChars} 字以内。
```

想改措辞就改 `jellyfish-plugin-compact/src/main/resources/summary-prompt.md` 后重新打包。
占位符缺失不算错误（内核仍会按上限本地截断），但模型会少一条自我约束，因此内核记一条告警。

`/compact preview` 会把这次要付的代价先算给你看——压几条、保留几条、丢弃几条、摘要输入约多少 token。

## skills（jellyfish-plugin-skills）

把一个目录里的说明文件变成模型可以按需取用的「技能」。约定很简单：**一个子目录一个 skill，
正文写在 `SKILL.md` 里**。

```markdown
---
name: pdf-processing
description: 处理 PDF 时使用：拆分、合并、提取文本
---

# 用法

1. 用 `scripts/merge.py` 合并……
2. 细节见 `references/api.md`
```

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `roots` | `["~/.jellyfish/skills", "./.jellyfish/skills"]` | skill 根目录，**有序**，同名 skill 先到者胜——项目级要覆盖用户级就写在前面 |
| `maxSkills` | `50` | 最多加载多少个 skill |
| `maxDescriptionChars` | `200` | 单条描述的长度上限 |
| `maxBodyBytes` | `65536` | 单份 `SKILL.md` 正文的字节上限 |

- **`description` 必填**：它是模型判断「该不该用这个 skill」的唯一依据，缺了整条会被跳过并在 `/skills`
  里说明原因。
- **`name` 可省**：省略时用目录名。头部只认 `---` 围栏里的扁平 `key: value`（支持 `>` / `|` 折行与引号），
  **不是完整 YAML**。
- **三层渐进披露**：名称 + 描述常驻 system prompt；模型判断相关时调 `skill` 工具把正文取回来；
  正文里提到的附带文件（`references/`、`scripts/`）用 `read_file` / `shell` 按需读取。
- **`skill` 工具是只读的**：读一份说明不该需要写权限，因此值得把它写进 plan 模式的白名单。
- **改了 `SKILL.md` 立即生效**：目录缓存按文件修改时间失效，不需要重启，也不需要 `/reload`。
- `enabled=false` 时不注册工具与清单贡献，但 `/skills` 仍可用（那是「为什么什么都看不见」的唯一答案）。

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
  并发上限、深度、墙钟与 token 预算、取消、用量归集与归档的行为完全一致。插件不自己起线程池。
- **一次 `workflow` 会占住一条工具执行线程**直到整批跑完（与 `task` 同口径）；按 `Esc` 取消回合会掐断
  全部在途 run。
- **不装它也能用**：只需要「派一个子代理做一件事」时用内置的 `task`；卸载本插件即回退到那个形态。
- **跑的时候看得见**：消息区上方有一块「编排」面板，逐行显示每一步的状态
  （`[ ]` 待执行 / `[~]` 进行中 / `[x]` 完成 / `[!]` 失败 / `[-]` 跳过）与「已完成 X/Y 步」。

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

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `servers[].id` | — | server 标识，工具名前缀用它 |
| `servers[].command` / `args` / `env` | — | 起 server 子进程的方式 |
| `servers[].callTimeoutSeconds` | `60` | 单次工具调用的超时 |
| `servers[].readOnlyTools` | `[]` | **本插件审批策略**用的只读名单（与 plan 插件的同名键无关） |
| `askWriteTools` | `true` | 写类工具是否要人工审批 |
| `startupWaitSeconds` | `5` | 启动期等多久让第一轮就能看到工具；超时就转异步 |

- **工具名带前缀**：`mcp__<server>__<tool>`。server 给的工具名可能与内置工具或另一个 server 重名，
  前缀让「谁占谁的位置」不成为事实；字符会被清洗成厂商允许的集合，超长时保留前缀并追加哈希。
- **连接发生在启动之后**：**某个 server 装错了不会让内核起不来**。
- **`tools/list_changed` 会实时跟随**：server 运行期增删工具时，模型下一轮就能看到。
- **只读与审批**：只读**只认你写的 `readOnlyTools`**（server 自填的 `readOnlyHint` 不再采纳）；
  **缺省一律按可写**。
- **协议能力**：声明 `roots`（把进程工作目录告诉 server）；**不声明也不支持 `sampling` / `elicitation`**
  ——这两个是「server 反过来向客户端要东西」，本客户端办不到，因此被请求时回一条明确的错误而不是挂在那里等。
- **二进制内容落盘**：server 返回的图片 / 音频写到系统临时目录下的 `jellyfish-plugin-mcp/<pid>/`，
  回灌给模型的只是一个路径（base64 塞进上下文会让一次截图就撑满窗口）。插件停止时整个目录会被删掉。
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

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `readOnlyTools` | `[]` | **plan 模式的工具白名单**。**不写就等于开启时一个工具都用不了**（白名单语义：「用户没表态」与「用户不准」是同一件事） |

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-plan": {
        "readOnlyTools": ["read_file", "list_dir", "grep_files", "todo_write", "skill"]
      }
    }
  }
}
```

- **开关按会话存**（存在会话扩展条目里，随会话一起落盘与恢复）：同一个进程里可以一个会话开着、另一个关着。
- **子代理不继承**：开关挂在会话上，子代理是另一个会话，因此默认不受限制。
- **工具无法自称只读**：`ToolDescriptor` 里没有该字段，谁能进名单完全由用户说。
- **不换工具清单，只拦执行**：模型看到的工具清单不变，写操作在执行时被拒——清单进的是请求前缀里很靠前的
  位置，换集合会让那一轮之后的前缀（连同全部历史）全部作废。
- **`-cli` / `-server` 下同样有效**：它不依赖审批者，因此没有人在场的模式里也能用它兜住写操作。
- 装了这个插件才有 `/plan`；不带它的内核里没有「模式」这个概念，也没有 `--mode` 参数。

## 脚本插件：用 Python / Node 写插件

除了用 Java 写插件，还可以用 **Python 或 Node** 写。桥接插件把一个脚本目录变成内核眼里的标准
PF4J 插件，能力边界由进程隔离 + 静态清单 + 熔断三层承担。

| 模块 | 脚本根目录（默认） | 解释器（配置键） |
| --- | --- | --- |
| `jellyfish-plugin-python` | `scripts/python/<脚本标识>/` | `python3`（`pythonPath`） |
| `jellyfish-plugin-node` | `scripts/node/<脚本标识>/` | `node`（`nodePath`） |

每个脚本目录只需一份静态 `manifest.json`（声明工具 / 命令 / 贡献 / 订阅的事件 / 周期任务）与一个入口文件。
因此 **启动期零进程、零文件写入**：没装解释器也不影响内核启动，工具清单依然完整；首次真正调用某个脚本时
才拉起它的进程，空闲后自毁。脚本调用失败只影响它自己（每脚本一 worker 进程），连续失败按熔断冷却、
冷却后自动半开恢复。

**脚本不是「与 Java 插件同权」**：两者在机制上同构（同样的注册、owner、回收、生命周期），但
**扩展点覆盖的是一份「能力档」而不是全部**：具体支持哪些、哪些明确不做，写在
`jellyfish-script/src/main/resources/script/extension-points.json`，并由单测守着：
**内核新增扩展点而未分档，本仓库会构建失败**。

**完整的脚本插件 API、`manifest.json` 字段表与两门语言的逐条对照见
[`examples/scripts/README.md`](examples/scripts/README.md)。** 仓库顶层
`examples/scripts/{python,node}/{hello,jira}` 是可直接拷贝运行的示例（`hello` 教学最小集、`jira` 真实形态，
且被端到端用例直接加载，因此不会腐烂）；另有仅 Python 的 `examples/scripts/python/web/`——一个
**零第三方依赖的联网搜索与网页抓取插件**（`web_search` / `web_fetch`，自带 SSRF 防护），
以及 `examples/scripts/python/{stock,stockpanel}/`——**A 股行情与自选股**（`/stock` 命令、5 个取数工具、
侧栏面板，依赖 `akshare`，不进端到端用例）。

### 配置

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

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `scriptsRoot` | `scripts/python` / `scripts/node` | 脚本根目录，相对**进程工作目录**解析；其下每个含 `manifest.json` 的子目录是一个脚本插件。目录不存在等于「还没建脚本」 |
| `pythonPath` / `nodePath` | `python3` / `node` | 解释器 |
| `invokeTimeoutSeconds` | `30` | 单次调用超时，写 `0` 表示**没有截止时间**（不是「立刻超时」）；超时会隔离该脚本的 worker 并计入熔断 |
| `workerIdleSeconds` | `300` | worker 的空闲自毁秒数（写 `0` 关闭） |
| `gatewayIdleSeconds` | `600` | 网关的空闲自毁秒数（写 `0` 关闭） |
| `manifestStrict` | `true` | 脚本首次拉起时逐项比对清单与实现，任何漂移都报错并熔断该脚本 |
| `scripts.<脚本 id>` | — | **逐脚本配置段**，只属于那一个脚本（脚本目录名就是脚本 id），值对桥接层不透明——写密钥、baseUrl、超时随便写。脚本用 `ctx.configuration` 读（Python 还可用模块级 `configuration()`）。**脚本 id 写错会在启动时告警** |

- **密钥不能靠环境变量传给脚本**：脚本进程的环境是严格白名单（仅解释器运行与依赖解析必需的那几个），
  这是有意的安全取舍。与 Java 插件同一条密钥通道是 `${ENV}` 插值。

  > ⚠️ **`${ENV}` 是硬失败，不是「取不到就留空」**：配置里写了 `${SOME_KEY}` 而环境变量没设，
  > 整个进程**启动即失败**（`environment variable is not set: SOME_KEY`）。确实要用的密钥才写
  > `${...}`，并且保证它总是已设。
- **回调可以是 async（Node）**：工具 / 命令 / 贡献 / 事件的处理函数都可以是 `async`，桥接会 `await` 它，
  因此 Node 脚本可以直接用 `fetch` / `undici` / `@mozilla/readability` 这类异步生态。
  这不改变「在途请求只有一个」——worker 仍是一次只处理一帧。事件处理器同样可以是 async，
  但它**不会被等待**（事件是旁路，不能拖住请求）。Python 无需这个改造。
- **工具结果可带元数据**：返回 `ToolResult(正文, summary=..., terminal=...)` 时，`summary` 会显示在轨迹行上、
  `terminal` 不等于 `COMPLETED` 时出警示标记；它们不进模型上下文。
- **工具能拿到调用者身份**：`ctx.parent_session_id` / `run_id` / `root_run_id`（Node 为驼峰）。
  跨 run 协作的键要从内核取，**不要用 `session_id`**——子代理有独立会话，用它做键会各写一份。
- **取消会中止在途调用**：在途脚本调用失败并隔离 worker（与超时同一条链）。脚本侧看不到取消标志，
  因此不要依赖取消做资源清理；取消也不计入熔断。
- **带路由键、路由键又来自用户配置的扩展点用 `@handler` 声明**（`model_catalog` 与 `input_directive`）；
  其余类型级扩展点走普通的 `contributes`。写法见 [`examples/scripts/README.md`](examples/scripts/README.md)。
- **热路径点不冷启动**：`request_tuning` / `aging_strategy` 每次组装请求都会被问到，worker 没热着时它们
  直接返回「不表态」。**推论：只提供这两个贡献的脚本永远不会被拉起。**
- **`events.allow` 只收窄、不扩展**：可订阅事件清单硬编码在运行时里，这里写不存在的事件名不会扩大任何能力。
- **输出捕获明确不做**：它解决的是无界流式输出（长命令），而脚本工具返回的是一次性的整个值；
  内核已会把超大结果落盘并给出恢复路径，不存在内容丢失。
- 桥接插件 jar 本身与 Java 插件一样放在 `plugins/` 扫描目录。

### 周期任务（`schedules`）

脚本**没有自己的主循环**（worker 只在被调用时活着，空闲还会自毁），因此「到点做一件事」这个发起方
只能落在桥接层：清单里声明一条 `schedules`，桥接插件就按它的间隔调用脚本里同名的处理函数。

```json
"schedules": [ { "name": "refresh", "intervalSeconds": 60 } ]
```

```python
@periodic(name="refresh", interval_seconds=60)   # Node: periodic({name: 'refresh', intervalSeconds: 60}, fn)
def refresh_watch(ctx):
    reload_cache()          # 返回值被忽略
```

- **成功之后由桥接层代发一次 `UiInvalidatedEvent`**：脚本发布不了这个事件，而周期任务的语义就是
  「我后台更新了自己贡献的内容」。没有这一步，定时抓到的新数据只会写进文件，而屏上的面板永远不会自己重画。
- **间隔语义是「上一次跑完 + 间隔」**，因此天然不会重入。
- **缺省 5 秒、下限 1 秒**；可被 `scripts.<脚本 id>.schedules.<任务名>.intervalSeconds` 覆盖，
  覆盖值越界会回落声明值并发一条配置告警。**只支持固定间隔，不做 cron。**
- **失败只记一条日志，不会让任务停掉**；失败时也**不**发失效事件。
- **一个脚本一条守护线程**；关闭插件时**先停定时器、再关网关**。
- **代价**：定时调用会让该脚本的 worker 不再空闲自毁（缺省空闲 300 秒回收）。

真实解释器的端到端测试在 `mvn -q -Pscript-it test`（不进 `mvn test`：单测不访问外部资源）。

## 开发你自己的插件

一个插件就是一个 jar：一份 `plugin.properties` + 一个 `JellyfishPlugin` 实现 + 若干扩展点处理器。
内核负责发现、装载、回收它，你只需要回答「注册什么」与「怎么处理」。

### 1. 最小骨架

```
my-plugin/
├── pom.xml
└── src/main/
    ├── java/com/example/myplugin/MyPlugin.java
    └── resources/plugin.properties
```

`plugin.properties` 放在 jar 根目录，PF4J 按名查找：

```properties
plugin.id=my-plugin
plugin.class=com.example.myplugin.MyPlugin
plugin.version=0.0.1
plugin.provider=com.example
plugin.description=一句话说明这个插件做什么
plugin.requires=*
jellyfish.tags=example
```

`plugin.id` 是插件身份，同时也是注册表的 **owner**；`plugin.class` 必须有一个**公开无参构造器**。
`plugin.requires` 写不通配的语义化版本会归一到 `*`（内核版本是 `0.0.1-SNAPSHOT`，非法 semver，只能写通配）。

### 2. `pom.xml`

```xml
<parent>
    <groupId>zcd</groupId>
    <artifactId>jellyfish-plugins</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</parent>

<artifactId>my-plugin</artifactId>

<dependencies>
    <!-- 内核契约必须 provided：PF4J 的插件类加载器是子优先的，自带 api 会遮蔽父加载器里的同名类 -->
    <dependency>
        <groupId>zcd</groupId>
        <artifactId>jellyfish-api</artifactId>
        <version>${project.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

三条规矩：

- **`jellyfish-api` 永远 `provided`**：插件 jar 里不含它的类，因此插件拿到的值类型永远是**运行期内核
  那一份**——内核给值类型加字段时插件什么都不用做。
- **shade 的 `includes` 里绝不能出现 `jellyfish-api`**：那会让插件带着旧版本的值类型跑，新字段静默丢失。
  有私有依赖（Jackson、commons-exec 之类）时才配 shade，把它们打进插件包。
- 新增插件要在父 `pom.xml` 的 `<modules>` 里登记，并往 `cpPlugins.sh` 加一行。

### 3. 实现 `JellyfishPlugin`

```java
package com.example.myplugin;

import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

public final class MyPlugin implements JellyfishPlugin {

    @Override
    public void start(PluginContext context) {
        ToolDescriptor descriptor = new ToolDescriptor("hello", "按名字打招呼",
                /* parametersSchema */ ..., /* order */ 0);
        context.handle(ToolCallRequest.class, "hello", descriptor, request -> {
            String name = String.valueOf(request.getArguments().get("name"));
            return new ToolCallResult("hello", "你好，" + name + "！");
        });
    }
}
```

`start(PluginContext)` 是唯一的必需方法：

- **注册窗口是插件的整个存活期，不是 `start()` 之内**：`start()` 返回到 `stop()` 之前都可以注册、订阅、发布。
- `start()` 抛错 → 插件转 `FAILED`，框架回收已完成的注册，**不保证**再调 `stop()`。
- `stop()`（默认空实现）用来释放你自己申请的资源（连接、线程、子进程）。处理器与事件订阅**不用自行反注册**
  ——框架按 owner 一次收干净；`Subscription.close()` 是主动注销的正式手段，属可选优化。
- **`stop()` 之后一切注册 / 发布当场失败（fail-closed）**：产生注册的后台线程必须在 `stop()` 返回前停下来。

### 4. `PluginContext` 能做什么

| 方法 | 用途 |
| --- | --- |
| `pluginId()` | 本插件标识（日志前缀、命名空间） |
| `subContext(childId)` | 派生 `pluginId::childId` 子身份的子上下文，可再派生；**子身份恒从当前身份派生，无法越界** |
| `configuration()` | 本插件的配置段（见第 6 节），不可变 |
| `runtimeInfo()` | 进程级只读快照：外壳种类、有无 UI、能否审批、有无终端。**不含 `sessionId` / `agentId` / `cwd`**，用途是优雅降级 |
| `handle(type, routeKey, descriptor, handler, options)` | 注册**带路由键**的处理器（工具名、命令名、标记字符……），**同一「类型 + 路由键」至多一个** |
| `contribute(type, descriptor, handler, options)` | 注册**类型级**贡献（0..N 个，查找按 `order` 升序） |
| `observe(eventType, filter, listener)` | 订阅通知（`filter` 为 `null` 收全部） |
| `emit(event)` | 发布通知（单向、无返回值、失败只记账） |
| `submit(action)` | 向正在跑的**顶层回合**投递一个动作；**入队即返回**，结果靠轮询 `ActionHandle` |
| `present(contribution)` | 往外壳推一条展示数据或「我脏了」的提示（见第 7 节） |
| `delegations()` | 子代理委派端口（与内置 `task` 同一条代码路径） |
| `putExtensionEntry` / `removeExtensionEntry` / `extensionEntries` | 会话内属于本插件的命名空间条目（随会话落盘，不进模型上下文） |

两条容易踩的边界：**插件拿不到会话与工作目录**（工具的相对路径按进程工作目录解析）；
**插件不能新开会话、也不能起回合**——`PluginContext` 里根本没有那样的入口，`subContext` 也无法越界。

### 5. 扩展点分两类，别选错

- **带路由键的**（`handle`，同键唯一）：`ToolCallRequest`（路由键 = 工具名）、`CommandRequest`（命令名）、
  `CommandOptionRequest`（命令名）、`InputDirectiveRequest` / `InputReferenceRequest`（标记字符，如 `!` `@`）、
  `ModelCatalogRequest`（provider 名）。两个插件抢同一个键会在**插件启动时**以 `DUPLICATE_HANDLER` 当场暴露。
- **类型级贡献**（`contribute`，0..N 个）：提示词、状态栏、面板、回合上下文、权限拦截、工具参数改写与结果
  整形、工具激活、输入改写、会话持久化、压缩策略、老化策略等。

合并规则只有两类，都写在**调用点**而不是注册表里：

- **链式**（如输入改写、工具参数改写）：逐环传递，最后一环天然生效，不存在「谁胜」。
- **合并**：**`order` 最小且声明了该字段的那一个胜出**。

**新增字段 / 扩展点时**：请求与结果类型放 `api`，**恰好一个可见构造器 + 静态工厂**，新增字段只能用新静态
工厂补、**不加兼容构造器**（Jackson 反序列化要求）。同时**必须在 `jellyfish-script` 的
`extension-points.json` 里给它分档**（`in` / `planned` / `excluded`），否则本仓库构建失败。

### 6. 读自己的配置

用户在 `jellyfish.json` 的 `plugins.configurations.<pluginId>` 下写你的配置，你在插件里这样读：

```java
public void start(PluginContext context) {
    Map<String, Object> configuration = context.configuration();   // 未配置时是空映射，不是 null
    MyConfig config = MyConfig.from(configuration);
    ...
}

final class MyConfig {
    private static final String DEFAULT_DIR = "~/.jellyfish/my-plugin";

    static MyConfig from(Map<String, Object> configuration) {
        Object raw = configuration.get("myDir");
        String dir = raw instanceof String ? ((String) raw).trim() : "";
        return new MyConfig(dir.isEmpty() ? DEFAULT_DIR : dir);
    }
}
```

- `${ENV}` 插值与双源（全局级 + 项目级）合并**已由内核完成**，你拿到的就是最终值。
- 惯例是「值对象 + 静态工厂 `from(Map)`」，键写错 / 缺省值兜底都在这里处理。
- **`~` 内核不展开**（它只在配置文件自身的路径段上做这件事），插件需要自己展开。
- 需要把配置段或 JSON 转成对象时，**插件必须自带 Jackson 并以 shade 打进 jar**
  （内核的序列化封装在 `jellyfish-infra` 里，插件看不到）。
- 启动时发现配置可疑，用 `emit(new ConfigWarningEvent(...))` 告诉用户，**不要阻断启动**。

### 7. 往外壳推内容

- **推送事件、拉取状态**：临时通知（`NOTICE`）与「我贡献的内容脏了」（`INVALIDATED`）走 `present`；
  面板与状态栏的**内容**走拉取（`PanelContributionRequest` / `StatusLineContributionRequest`）。
  把状态也改成推送会立刻产生第二份真源。
- 三条不能忘的边界：它是**展示数据**（不进模型上下文、不落盘、不产生 `LlmMessage`）；它**可丢**
  （每 owner 有界、同 key 可合并、满即丢最新），因此 `DROPPED_QUEUE_FULL` **不能当失败去重试**；
  `SESSION` scope 要求会话已存在，**内核不会因此新建会话**。
- `lines` 里的控制字符由**渲染面**负责滤掉，内核不做内容改写。
- **插件不可能自己造 TamboUI 组件**（子优先类加载器会让 `Element` 不是同一个 Class）。

### 8. 想替换内核自带的工具或命令

内核自带的系统命令与 `task` 工具以 `owner=core` 注册，且**先于插件启动**。要顶替它们，显式声明 override：

```java
context.handle(ToolCallRequest.class, "task", myDescriptor, myHandler,
        RegisterOptions.override(true));
```

覆盖**不是就地替换，而是压在链顶之上**：旧登记仍在表里、只是不生效，你的注册一被回收（插件停止、
`/reload` 重启插件）它就自动重新生效。因此不存在「插件走了，被它顶掉的内核工具永久消失」这种事故。

### 9. 打包、安装与调试

```bash
mvn -q package -DskipTests
cp my-plugin/target/my-plugin-*.jar ~/.jellyfish/plugins/
# 重启 jellyfish（新增 / 删除插件 jar 只在启动期确定）
```

- **新增 / 删除 jar 必须重启**；只改配置段可以 `/reload`（内核会**只重启配置段变了的插件**）。
- 插件加载失败不会让内核起不来，但要在启动日志里找原因；`/help` 里看不到你的命令时，先确认 jar 在
  `config.json` 的 `plugins.roots` 目录下、且没被 `plugins.disabled` 排掉。
- **单测**：`jellyfish-api` 是 `provided`，但测试期可以引 `jellyfish-infra`（`test` scope）拿到真实的
  `ExtensionRegistry` / `EventChannel`——注册与派发走的就是内核那条路径，换成假的只是验证「我以为内核会怎么查」。
  内核服务里真正被测的那部分用真的，其余用桩。

### 10. 内核升级后要做的检查

内核一侧改了 `jellyfish-api` 之后，按顺序跑下面几条，**只有全绿才算真的没问题**：

```bash
(cd ../Jellyfish && mvn -q install -DskipTests)   # 0. 不能省
mvn -q clean package                              # 1. 必须 clean
mvn -q -Pshell-it test                            # 2. 三个端到端 profile
mvn -q -Pmcp-it test
mvn -q -Pscript-it test
```

- **第 0 步不能省**：本仓库是独立 reactor，`jellyfish-api` 来自本地 Maven 仓库。忘了它的表现是「内核明明
  改了，插件这边编译却看不到」。
- **必须 `clean`**：增量编译会复用按旧 `jellyfish-api` 编出的 `target/`，于是**编译问题被伪装成运行期问题**
  （实例：内核给 `SessionSnapshot` 加字段之后，不带 clean 报的是 11 条 `NoSuchMethodError`）。
- **三个 `-P*-it` 一个都不能漏**：`mvn test` / `mvn package` **不跑 `*IT`**，坏掉的端到端不会被它们发现。
- **引用 api 的值类型优先用静态工厂**；**测试夹具反过来，必须用全字段构造器**——夹具要填满全部字段，
  否则「后加字段读不回来」的失真会被静默吞掉（往返测试照样全绿，而现场是「文件写出来了，重启后那个属性
  悄悄回到默认值」）。

## 常见问题

**装了插件但 `/help` 里没有它的命令？**
先确认 jar 在 `config.json` 的 `plugins.roots` 指向的目录里（PF4J 只在目录下一层找 `plugin.properties`），
再确认没被 `jellyfish.json` 的 `plugins.disabled` 排掉。新增 jar 必须**重启**。

**`-cli` / `-server` 下写类工具总是被拒？**
那两个外壳没有审批者，而 `ASK` 就是拒绝。要么别把工具放进 `askTools`，要么为 `shell` 配
`commandPolicy.trustedCommands`（白名单只管准入、不解除审批）。

**改了插件配置段要重启吗？**
不用，`/reload` 会**只重启配置段变了的插件**。但改 `config.json`、新增 / 删除 jar 必须重启进程。

**MCP server 连不上？**
敲 `/mcp` 看连接状态、工具数与失败原因——那是唯一的线索。连不上不会让内核起不来。

**脚本插件报「环境变量未设置」？**
配置段里写了 `${SOME_KEY}` 而环境变量没设是**硬失败**（整个进程启动即失败）。确实要用的密钥才写
`${...}`，并保证它总是已设。

**改完 `SKILL.md` 要重启吗？**
不用，目录缓存按文件修改时间失效。但**会话中途修改 `AGENTS.md` 不生效**（project 插件每个会话只读一次盘），
开个新会话即可。

## License

[MIT](LICENSE)
