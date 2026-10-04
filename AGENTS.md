# AGENTS_PLUGINS.md

本文件用于指导 AI 编码代理在**官方插件仓库**中工作。修改代码前请先阅读。

> 本文件由内核仓库 `AGENTS.md` 中与官方插件相关的内容抽取而成，供手动迁移到独立仓库。
> 迁移时把下面的路径视为**插件仓库根目录**下的路径（不再有 `jellyfish-plugins/` 前缀）。
> 插件与内核之间没有编译期依赖，只经 `jellyfish-api` 的 SPI 与内核交互；内核侧契约见内核仓库的 `AGENTS.md`。

> **只写已落地事实**：正文只描述当前代码中存在的模块与类，未落地与明确不做的部分见「已知边界与后续项」。**推导与实测数据在对应类的注释里，这里只留规则**。

## 常用命令

```bash
mvn -q -Pscript-it test             # 真实 python3 的端到端（不进 mvn test：单测不访问外部资源）
mvn -q compile
mvn -q package -DskipTests
mvn -q test                         # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Pshell-it test              # shell 插件端到端（真 /bin/sh，会真起进程再杀掉）
mvn -q -Pmcp-it test                # MCP 插件端到端（真 fork 进程跑仓库自带的 echo server）
```

## 内核升级后要做的检查

内核一侧改了 `jellyfish-api` 之后，按顺序跑下面几条，**只有全绿才算真的没问题**：

```bash
# 0. 先把新内核装进本地仓库：本仓库是独立的 reactor，插件的 api 从这里取（见下）
(cd ../Jellyfish && mvn -q install -DskipTests)

# 1. 必须 clean
mvn -q clean package

# 2. 三个端到端 profile 一个都不能漏
mvn -q -Pshell-it test
mvn -q -Pmcp-it test
mvn -q -Pscript-it test
```

- **第 0 步不能省**：本仓库是独立的 reactor，`jellyfish-api` / `jellyfish-infra` 不来自内核源码树，而是本地
  Maven 仓库里那一份（`mvn -o -pl <某模块> dependency:build-classpath` 可以看到路径）。忘了这一步的表现是
  「内核明明改了，插件这边编译却看不到」。
- **必须 `clean`，不能只看 `mvn package`**：增量编译会复用按旧 `jellyfish-api` 编出的 `target/`，
  于是**编译问题被伪装成运行期问题**。实例：内核给 `SessionSnapshot` 加四个字段之后，
  不带 clean 跑报的是 11 条 `NoSuchMethodError`，看着像「新旧 api 运行期不兼容」；clean 之后才是真相
  （构造器参数个数不匹配，是编译问题）。
- **三个 `-P*-it` 一个都不能漏**：`mvn test` / `mvn package` **不跑 `*IT`**，坏掉的端到端目标不会被它们发现。
  实例：`jellyfish.test.examples` 的路径多退了一级，`PythonScriptIT` 四条用例全红，而 `mvn package` 一直绿。
- **引用 api 的值类型优先用静态工厂；测试夹具反过来，必须用全字段构造器**：跨边界值类型规定「恰好一个可见
  构造器」（Jackson 反序列化要求），**新增字段只能加静态工厂、不加兼容构造器**。所以用 `new` 的代码会在内核
  加字段时立刻编不过，用 `of(...)` 的不用改——兼容工厂替新字段补缺省值。**夹具是刻意的例外，也正是最要紧的
  那一处**：夹具要填满全部字段，否则「后加字段读不回来」的失真会被静默吞掉——往返测试照样全绿，而现场是
  「文件写出来了，重启后那个属性悄悄回到默认值」。
- **`jellyfish-api` 是 `provided`，插件 jar 里不含它的类**：因此插件拿到的值类型永远是**运行期内核那一份**，
  内核给值类型加字段时插件**什么都不用做**。实测：改造前打的旧插件 jar 在新内核上照样写出 15 个字段并被正确
  读回。推论：**shade 的 `includes` 里绝不能出现 `jellyfish-api`**——那会让插件带着旧版本的值类型跑，
  新字段静默丢失。
- **老插件「什么都不做」是受支持的形态**：新增扩展点的兼容底线是「0 个 handler 时内核走一条与改造前逐字段
  一致的路径」，因此用不上新点就不要动既有注册——为了用新点而改既有注册才是有风险的那一侧。
  契约文档都在内核仓库（`docs/constraints/extensions.md` 的「新增扩展点的公共约定」与
  `docs/design/extension-points.md` 的逐条决策），本仓库不重写一份。
- **插件往外壳推内容走 `PluginContext.present(ShellContribution)`，不要新建一条通道**：
  它能把一条临时通知（`NOTICE`）或一次「我贡献的内容脏了」（`INVALIDATED`）推给活着的外壳，
  而**不需要有回合在跑**。三条不能忘的边界（详见 `docs/constraints/extensions.md`）：
  它是**展示数据**，不进模型上下文、不落盘、不产生 `LlmMessage`；它**可丢**
  （每 owner 有界、同 key 可合并），因此 `DROPPED_QUEUE_FULL` 不能当失败去重试；
  `SESSION` scope 要求会话已存在，内核**不会**因此新建会话。
  面板与状态栏的**内容**仍走拉取（`PanelContributionRequest` / `StatusLineContributionRequest`）。

## 官方插件一览

官方插件（PF4J 是「一个 jar 一个 `plugin.properties`」）：

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `jellyfish-plugin-tools` | 五个文件工具：`read_file` / `write_file` / `edit_file` / `list_dir` / `grep_files` | api（provided） |
| `jellyfish-plugin-session-file` | 会话持久化：一个会话一个 JSON 文件 + git 管理历史 | api（provided） |
| `jellyfish-plugin-todo` | 会话待办：`todo_write` / `todo_claim` / `todo_done` / `todo_release` / `todo_block` 工具 + `/todo` + 选型提示词/回合块/状态栏/面板贡献；父子共享同一份清单（子代理落在父会话上） | api（provided） |
| `jellyfish-plugin-project` | 项目约定：探测工作目录下 `AGENTS.md`，小文件内联原文、大文件只给路径 | api（provided） |
| `jellyfish-plugin-compact` | 压缩策略：摘要指令 + 保留条数与摘要上限；不启用它压缩整体不可用 | api（provided） |
| `jellyfish-plugin-shell` | 命令行：`shell` 工具（`/bin/sh -c` 执行命令原文）+ 命令策略（白名单准入 / 可信表免审批 / 只读不打扰 / 灾难形状拒绝 / 其余审批）。**无沙箱**，能读写本用户任意文件；自带 commons-exec（**shade 进插件包**，内核 classpath 上不出现它） | api（provided）、commons-exec（shade） |
| `jellyfish-plugin-skills` | skills：按目录发现 `SKILL.md`，元信息进 system prompt、正文由模型按需用 `skill` 工具加载、附带文件交给已有工具读取。零第三方依赖（frontmatter 手写极简解析，不引 YAML） | api（provided） |
| `jellyfish-plugin-mcp` | MCP 客户端：stdio 连外部 server，把它的工具以 `mcp__<server>__<tool>` 注册进内核，支持 `tools/list_changed`、`roots`，**不声明也不支持 sampling / elicitation**。自带 Jackson（**shade 进插件包**） | api（provided）、jackson-databind（shade） |
| `jellyfish-plugin-workflow` | 编排：`workflow` 工具接受声明式 spec（步骤 / 依赖 / 静态条件 / 聚合），按依赖层序并发派生子代理；提示词贡献（例句与选型）+ 编排面板贡献（每步状态）。子代理来自 `PluginContext.delegations()`（内核的委派端口，与 `task` 同一条代码路径）；**零第三方依赖**——spec 从工具参数拿到时已经是 Map / List / String | api（provided） |
| `jellyfish-plugin-plan` | plan 模式：类型级权限贡献（白名单外的工具一律拒绝）+ `/plan [on|off]` 命令 + 状态栏片段 + 回合上下文提示。开关存**会话扩展条目**（随会话落盘与恢复），白名单来自 `plugins.configurations.jellyfish-plan.readOnlyTools`。**零第三方依赖** | api（provided） |
| `jellyfish-plugin-sparkline` | 火花线：订阅内核已有的三个通知（`LlmCallCompletedEvent` / `LlmCallFailedEvent` / `ToolCallCompletedEvent`）在内存里按会话攒采样点，面板贡献把缓存命中率、失败滑窗率与输入 token 规模各画成一行块字符（建议落右栏），采样后广播 `UiInvalidatedEvent`。**零第三方依赖**（不持久化、无新扩展点） | api（provided） |
| `jellyfish-plugin-pet` | 宠物：订阅内核通知（`LlmCallCompletedEvent` / `LlmCallFailedEvent` / `ToolCallCompletedEvent` / `TurnCancelledEvent` / `SessionClosedEvent`）养出每个会话一只宠物——疲惫（连续工作时长，五分钟空闲重置）、肥胖（累计 token）、伤痕（工具连续失败）、警惕（被打断）、夜行（深夜活动），面板贡献画成「精灵 + 两根条 + 形态行」（建议落右栏）。**零第三方依赖**（不持久化、不依赖任何别的插件） | api（provided） |
| `jellyfish-plugin-node` | Node 桥接插件：与 python 插件同构（同一个 `ScriptBridgePlugin` 骨架），差异只有 `NodeLanguage` 与网关资源 `script/gateway.js`（Node 事件循环）、`script/worker.js`、`script/jellyfish_sdk.js`、`script/script_wire.js`、`script/dump_manifest.js`。**零第三方依赖**（只用 Node 内置模块，因此不需要 npm install） | api（provided）、jellyfish-script（shade） |
| `jellyfish-plugin-python` | Python 桥接插件：读静态清单完成注册、自带 `/<lang>` 状态命令（含熔断与事件计数）、把每个脚本调用都经熔断装饰器转发、把内核事件推给脚本（`ScriptEventBridge`），把 Python 脚本插件以标准 PF4J 插件的形态接入内核（控制面单进程 + 每脚本一 worker 进程）。网关资源 `script/gateway.py`（单线程 select 事件循环）、`script/worker.py`、`script/jellyfish_sdk.py`（脚本作者唯一的 API）、`script/script_wire.py`（分帧）、`script/dump_manifest.py`（清单生成器，`gateway.py --dump-manifest` 转发同一入口）。示例插件见仓库顶层 `examples/scripts/python/`（`hello` 教学最小集、`jira` 真实形态），**端到端用例直接加载它们**，因此示例不会腐烂 | api（provided）、jellyfish-script（shade） |

源码结构同构：`resources/plugin.properties` + `PluginConfig` + `JellyfishPlugin` 实现 + 各扩展点 handler。

- **官方插件**：tools 五个文件工具（三个只读）；session-file 一会话一 JSON + git（落盘失败上抛、git/坏文件只告警）；todo `todo_write` + `/todo` + 回合上下文/状态栏/面板贡献 + 删除清理（待办块走 `TurnContextRequest` 随本轮用户消息送达，**不进 system prompt**——那是缓存前缀的第 0 个 token，待办每变一次就会作废整个请求）；plan 见下；project 按 `maxInlineBytes`（默认 32 KiB，0=不内联）内联 `AGENTS.md` 原文或只给路径（一会话只读一次）；compact 压缩策略；skills 见下；mcp 见下。
- **plan 插件（`jellyfish-plan`）**：模式类授权是**插件**的事——内核没有权限模式字段、没有枚举、没有 `/mode`、没有 `--mode`。四条不可动的边界：① **只拦执行、不换工具清单**（清单进的是缓存前缀里很靠前的位置，换集合会让那一轮之后的前缀全部作废）；② **开关按会话存扩展条目**（`putExtensionEntry`，随会话落盘与恢复，不自建文件），因此**子代理不继承**；③ **白名单为空 = 一个都不许**，拒绝文案必须点明 `plugins.configurations.jellyfish-plan.readOnlyTools` 并配一条「每种配置只喊一次」的 `ConfigWarningEvent`；④ 提示词注入走 `TurnContextRequest`（模式会在会话中途切换，放进 system prompt 等于每次切换都作废整个请求）。
- **project 插件必须从仓库根目录启动**：查找基准是进程工作目录（与内核 `ToolPaths` 同一处），不做向上查找。

## 新增一门语言（桥接插件）

- **注册来源是脚本目录下的静态 `manifest.json`，协议里没有任何注册方法**：因此 `start()` 期**零进程、零文件写入**，解释器缺失或损坏不影响内核启动、工具清单依然完整（PF4J 看到的永远是标准插件）。运行架构是「控制面单实例（每语言一个常驻 gateway，不跑业务）+ 数据面按脚本隔离（每脚本一 worker，懒启动、空闲自毁）」，真实解释器的端到端测试在 `mvn -Pscript-it test`。
- **一门语言 = 一个 `ScriptLanguage` 实现 + 一个薄插件 + 一份该语言的网关资源**，机制层（`jellyfish-script`，在内核仓库）不动。语言适配只回答四件事：怎么启动（`startCommand`）、启动前怎么探测（`probeCommand`）、进程带什么环境（`environment`，白名单而非清空）、网关由哪几个文件组成（`gatewayResources`）。
- **桥接插件的骨架全在内核的 `jellyfish-script/ScriptBridgePlugin`**：探测解释器 → 扫描清单 → 逐脚本按 `pluginId::scriptId` 注册 → 接通事件桥接与熔断 → 注册 `/<语言>` 命令 → 按序关闭。子类只提供 `resolveConfig`（解释器写在哪一个键上、两个默认值）与 `createLanguage`。**要往子类里加第二件事之前，先问它是不是语言无关的**：是就该往上收（判断依据见 `ScriptLanguage` 的 javadoc）。
- **配置解析、台账渲染与语言无关**：`ScriptBridgeConfig` / `ScriptLedger` 一份服务所有语言；解释器键名保留各语言自己的名字（`pythonPath` / `nodePath`），由薄插件传进去——用户翻配置时找的是他那门语言的词。
- **`gatewayResources()` 归语言适配**：它是「这门语言的网关由哪几个文件组成」，与解释器路径同类；留给调用方就等于同一份知识在多处各写一遍，而不一致的表现是「网关少了一个文件」——只在第一次调用时才看得见。
- **两门语言的脚本 API 逐条对应**（`tool` / `command` / `contributes` / `subscribe` / `dumpManifest` / `compareWith`），差异只在语言本身：Python 用装饰器、Node 用「声明 + 就地注册」；Python 从文档字符串取缺省描述，Node 必须显式写 `description`（JS 拿不到注释）。**命令名片上的 `session_required` / `sessionRequired` 同样逐条对应，缺省 `True` / `true`**。
- **候选查询的约定两侧必须一致：`tokens is None`（`tokens === null`）表示「这次是候选查询」**，执行给真实的 `tokens`（`[]` 表示用户没输入参数，与 `None` 不是一回事）。`has_options=True` 让**同一个函数**回答两条路，因此只有这条标记能区分它们；Python 侧还会在**声明期**拒绝看不出两条路的签名并附最小写法，Node 侧拿不到参数名、只能靠 `null` 解引用报错——**约定共享、校验不必对称**。返回一个映射却没有 `choices` 键一律报错，不再补齐成空候选：那正是「忘了分支」的安静版本。
- **卡死的 worker 怎么收，两门语言的答案不同**：Python 的信号处理器直接 `os._exit`（CPython 在信号处理器返回后才恢复被中断的调用），因此 SIGTERM 一般够用、强杀只在关闭路径上兜底；**Node 的信号处理器排在事件循环上，卡在同步 JS 里的 worker 收不到 SIGTERM**，因此两段式关闭的第二步必须在网关的循环里做。
- **Node 侧的差异都由语言本身带来**：用 `spawn` + 一条 `init` 帧把脚本目录 / 入口 / 清单送进 worker（Node 没有 `fork`；走 argv 会让清单在进程列表里可见、还会撞参数长度上限）；收尾后必须显式 `process.exit(code)`（`resume` 过的 stdin 是活句柄，会让事件循环一直转）；`require('jellyfish_sdk')` 靠网关给 worker 设 `NODE_PATH`（非相对引入只查 `node_modules` 链与 `NODE_PATH`）。Node 运行时零第三方依赖（`script/{gateway,worker,jellyfish_sdk,script_wire,dump_manifest}.js`），示例见 `examples/scripts/node/`。
- **协议通道必须同步写**：Python 靠 `PYTHONUNBUFFERED` + 流锁，Node 靠把 `process.stdout.write` 换成同步写。异步缓冲的后果是「一个大结果帧被脚本自己的一行日志半路插入」，而现场是「偶尔收到一帧解析不了」。
- **离线生成器与运行期入口分开**：清单生成是开发期动作（进程里只该有一个脚本被加载），网关是运行期进程（同时管多个脚本）。`dump_manifest` 与 `gateway --dump-manifest` 共用同一个入口，但网关那侧是延迟加载的，正常路径上连读都不读它。
- **清单生成器改完实现必须跑一遍**：`--check` 按名字报差异、`--write` 直接落盘（推荐；shell 重定向会先把目标文件截空，而生成器要读它确认入口名）。**注意 `--check` 只比名字**——名片的 `summary` / `usage` / `aliases` / `sessionRequired` 漂移它看不出来，因此改了名片必须 `--write`，别把 `--check` 通过当成「清单是最新的」。

- **内核新增扩展点时，脚本能力档会先红**：`jellyfish-script` 的 `ExtensionPointCoverageTest` 枚举 `jellyfish-api` 里
  **全部** `ExtensionRequest` 子类，未在 `jellyfish-script/src/main/resources/script/extension-points.json` 里分档
  （`in` / `planned` / `excluded`）即构建失败。**先做分档决策，再决定要不要实现**——那份名单是「脚本覆盖到哪」
  这条契约的唯一真源，缺失它会让覆盖范围悄声漂移（曾长期停在 11/28）。
- **脚本能力档不是「与 Java 插件同权」**：只覆盖数据进出、且不在渲染线程 / 启动期的扩展点。
  `excluded` 里的两类不要试图打通：**返回 Java 对象的**（`ProviderRegistrationRequest` 要一个 `LlmTransport`，
  脚本给不了）、**跑在渲染线程或启动期的**（`ToolRenderHintRequest` / `InputReferenceRequest` /
  `ShortcutContributionRequest`）——脚本调用是一次可能冷启动的进程往返，这些位置不能付这个代价。

## 脚本插件的两条能力

- **逐脚本配置段**：`plugins.configurations.<桥接插件>.scripts.<脚本 id>` 是该脚本自己的配置，
  值对桥接层不透明（想写密钥、baseUrl、超时都行），`${ENV}` 插值照常生效。脚本用
  `ctx.configuration` 读；Python 另有模块级 `configuration()`（在模块顶层就能调，因此注入发生在 **import 脚本之前**）。
  **环境变量仍是严格白名单**：密钥不能靠 env 传给脚本，要走上一条配置段——这是有意的安全取舍，不要为了「方便」改成全量透传。
  脚本 id 写错（脚本目录不存在）会在启动时发一条 `ConfigWarningEvent`；`scripts` 段写成非对象当场报错。
- **async handler（Node）**：`tool` / `command` / `contributes` / `subscribe` 的处理函数都可以返回 Promise，
  桥接会 `await` 它。**这不改变「在途请求只有一个」**：worker 仍然一次只处理一帧，只是允许 handler 等 I/O。
  两条实现细节不能丢：① 在途期间必须抑制空闲看门狗（同步 handler 会饿死定时器，async 不会，
  否则「改成 async 反而被自己杀掉」）；② 事件处理器**不被 await**（事件是旁路，不能拖住请求）。
  Python 侧不需要这个改造——它的 HTTP 客户端本来就是同步的。
- **工具结果可以带元数据**：返回 `ToolResult(output, summary=..., terminal=...)` 时，
  `summary` 进 `ToolMetadata.KEY_SUMMARY`（轨迹行上的一句话），`terminal` 不等于 `COMPLETED`
  时界面出警示标记；它们**不进模型上下文**。普通返回值仍然就是 output，老脚本不受影响。
- **工具能拿到调用者身份**：`ctx.parent_session_id` / `run_id` / `root_run_id`（Node 为
  `parentSessionId` / `runId` / `rootRunId`）。跨 run 协作的键要从内核取，不能用 `sessionId`——
  子代理有独立会话，用它做键会各写一份。
- **取消会中止在途调用**：回合的 `CancellationToken` 传到桥接层后，在途脚本调用失败并隔离 worker
  （与超时同一条链）。**脚本侧拿不到取消标志**：worker 是单线程的，在途调用期间读不到新帧，
  因此不要依赖取消做资源清理。取消**不计入熔断**（用户主权 ≠ 脚本故障）。
- **输出捕获明确不做**：`ToolOutputSink` 解决的是**无界流式**输出（长命令边跑边产生、进程一结束就没了），
  而脚本工具的输出是一次性返回的整个值。内核已会把超大的单次结果落盘并在信封里带上恢复路径，
  因此不存在「内容丢了」，不需要为它新增一条出站帧 + 网关路由 + per-call sink 绑定。
  需要限制时用工具自己的 `max_bytes` 类参数。
- **带路由键、但路由键来自用户配置的扩展点走清单的 `handlers` 字段**：目前只有 `model_catalog`
  （路由键是 provider 名）。它不能用 `contributions` 声明（那个字段只收类型级），也不能用
  `tools` / `commands`（那是专用字段）。SDK 侧是 `@handler("model_catalog", route="local")`（Node 是
  `handler({type, route}, fn)`），清单里写 `"handlers": [{"type": ..., "route": ...}]`。
  一致性校验比对的是 `type::route`，路由名写错会在启动时被拦下。

## 示例脚本与进程

- **示例脚本在仓库顶层 `examples/scripts/{python,node}/`，且被端到端用例直接加载**：示例是从进程工作目录之外的路径被加载的（先拷进临时脚本根目录，因为 `hello` 会往自己的目录写便签），因此「示例能不能用」有 CI 守着——放在文档里的示例代码会腐烂，这份不会。改示例时 `manifest.json` 与声明必须一起改，`dump_manifest --check` 就是给这件事用的；两门语言的示例共用一份 `examples/scripts/README.md`，差异列成一张表，会一门就会另一门。

## 命令行与进程（jellyfish-shell）

- **`shell` 没有沙箱**：命令以本进程权限执行，能读写本用户任意文件。这是能力而非漏洞，但必须让用户知道。
- **`shell` 默认不进 `askTools`，而这是刻意的**：分类器会把只读命令判成无异议（静默执行）、把其余命令升级为 `ASK`（弹一次批准框）。把 `shell` 写进 `askTools` 则是「每条命令都批准」——核心策略的 `ASK` 无法被插件的 `ABSTAIN` 降级，插件裁定只能收紧不能放宽。这一点常被写反，改动前先看 `PermissionManager.decide`。
- **不做目录围栏**：可绕过（`cd /`、绝对路径、`sh -c` 嵌套）、与 `read_file` / `write_file` 没有围栏不自洽、还会挡住合法需求。真正的边界是审批加白名单。
- **命令原文交给 `/bin/sh -c`**，因此管道、重定向、通配符按 shell 语义工作；每次调用都是新 shell，`cd` 不跨调用保留（要换目录就传 `cwd` 或 `cd X && cmd`）。Windows 映射 `cmd.exe /c` 但**未验证**。
- **`stdin` 在启动后立即关闭**：交互式命令（`vi` / `ssh` / `sudo`）必须快速失败，且绝不能抢终端——TUI 处于 raw 模式，子进程直接写终端会把界面画烂。
- **stdout 与 stderr 合并为一条流**（到达顺序，像终端）；**必须持续排空**，即使已经放弃保留内容——停止读取会让子进程因管道写满而永久阻塞，表现是「命令卡死」。
- **非零退出码如实报告，不抛异常**：`grep` 返回 1 是信息；抛异常会把「命令说了没有」与「命令根本没跑起来」混成一件事。
- **两道计时器互相独立**：墙钟（缺省 120 秒，模型可用 `timeout_seconds` 覆盖并被 `maxTimeoutSeconds` 钳制，缺省 1800）与静默（`idleTimeoutSeconds`，**缺省关闭**，只有用户能配）。前者回答「最多跑多久」，后者回答「多久没动静就当死了」；有些命令确实长时间无输出，因此静默缺省不开。**两者与取消在同一个等待循环里判定**，同一次调用的终止只有一个发起方——这也是不使用 `ExecuteWatchdog` 的原因。
- **终止链是 TERM → 宽限 → KILL，并尽力杀进程树**：只杀直接子进程会让 `npm run dev` 拉起的孙进程继续跑（「报告已终止，端口却还占着」）。JDK 8 没有 `ProcessHandle.descendants()`，只能靠 `pgrep -P` 递归，**杀不干净是已知边界**。
- **取消回调只发信号**：它可能在界面渲染线程上执行，因此不等待、不递归；完整的终止链由等待循环在几十毫秒内接手。**判定顺序必须是「先看令牌，再看进程是否退出」**——取消回调会直接杀进程，先判退出会把取消误报成正常完成。
- **环境是「继承 + 默认剔除敏感变量 + 防挂死」**：丢掉 `PATH` 会让几乎所有命令 command not found，因此不采用严格白名单；代价是剔除敏感变量必须默认开启（名字匹配 `*KEY*` / `*TOKEN*` / `*SECRET*` / `*PASSWORD*` / `*CREDENTIAL*` 的变量不传子进程）——工具输出会送到远端 LLM。防挂死注入 `PAGER=cat` / `GIT_PAGER=cat` / `GIT_TERMINAL_PROMPT=0` / `TERM=dumb` / `NO_COLOR=1` / `DEBIAN_FRONTEND=noninteractive`。
- **命令分类器是便利机制，不是安全边界**：按命令原文的前缀匹配，`FOO=bar cmd`、`$(...)`、`&&` 链、`sh -c` 嵌套都能绕过。它的价值是让只读查询不再打扰人，从而避免用户因为嫌烦把 `shell` 从 `askTools` 里整个拿掉。`find` / `git fetch` / `npm test` **刻意不算只读**（`find -delete`、改远端 ref、执行仓库里的任意代码），它们免审批的唯一正当路径是 `trustedCommands`——内置表保持保守，用户的判断写成显式配置。**但在 `-cli` / `-server` 下它事实上是承重的**：那里没有审批者（`ASK` 即拒绝），于是「被判只读」与「命中 `trustedCommands`」成了仅有的两个放行口——这两个模式必须配好白名单与可信表，不能只靠分类器。
- **准入、免审批、分类器是三件事**：`allowedCommands` 非空即**默认拒绝**（给 `-cli` / `-server` 这类没有人在场的模式准备的安全网），回答「能不能跑」；`commandPolicy.trustedCommands` 命中即 **ABSTAIN**，回答「要不要问人」；分类器只是「减少打扰」这个便利机制。三者**都不受 `commandPolicy.enabled` 影响**——那个开关关掉的只是分类器。
- **白名单不解除审批**：`allowedCommands` 里的 `mvn test` 每次仍然弹批准框，它只解除默认拒绝，命令之后还要过可信表与只读表。把「能不能跑」与「要不要问人」合回一个键，就再也表达不出「允许跑但每次都要批准」了。
- **`trustedCommands` 排在拒绝形状之后，且两者都排在只读判断之前**：免审批回答的是「要不要问人」，不是「连 `rm -rf /` 也放行」。改 `CommandPolicy.verdict` 的顺序前先想清楚这一条。
- **插件停止时必须终止在途命令**（`stop()` → 杀在途），否则用户看到的是「jellyfish 都退出了，那条命令还在跑」。

## skills 插件

- **三层渐进披露各有落点，且第三条不归它管**：名称+描述走 `PromptContributionRequest` 常驻 system prompt（第一层）；正文走 `skill` 工具按需取回（第二层）；正文里提到的附带文件交给已有的 `read_file` / `shell`（第三层）——本插件**不自己执行任何东西**，重复一份只会多出第二条路径解析与权限口径。
- **清单不写进工具参数的 enum**：`ToolDescriptor` 在注册那一刻就固定，而 skill 是目录里现扫出来的。走提示词贡献则是每轮现算，新增一个 skill 下一轮模型就看得见——与子代理把「可委派类型」放贡献里是同一条理由。
- **`skill` 工具是只读的**：读一份说明不该需要写权限，因此值得把它写进 plan 模式的白名单。
- **目录默认 `~/.jellyfish/skills` 与 `./.jellyfish/skills`**：与内核的约定目录（全局 `~/.jellyfish/`、项目 `./.jellyfish/`）一致；根目录是有序的，同名 skill 先到者胜（项目级要覆盖用户级就写在前面）。
- **缺 `description` 的 skill 整条跳过并记问题**：描述是模型选中它的唯一依据，没有它这个 skill 永远不会被加载，让它在清单里占一行废信息反而更难查。
- **目录缓存按「文件系统签名」失效，不是定时重扫**：签名由各根目录与已发现 skill 的目录 / `SKILL.md` 的修改时间拼成，只做 `stat`。时间判据要么白扫、要么让「改了却不生效」重新出现，而 `/reload` 只重启配置段变了的插件，救不了「改了 `SKILL.md`」。

## MCP 插件

- **它是插件而不是内核能力**：MCP 只是「发现并转发一批外部工具」，没有碰循环的结构，也不需要内核才知道的事实。放进内核会让不用 MCP 的用户也背上这段连接管理。
- **连接发生在启动之后，且不在启动线程上做完**：工具清单只有连上才知道，而连上要起进程、要握手。全部同步做完，等于让「某个 server 装错了」变成「内核起不来」——与脚本桥接「`start()` 期零进程」同一条纪律。折中是 `startupWaitSeconds`（缺省 5 秒）：等一等让第一轮就有工具，超时就转异步（内核 `ToolCatalog` 每轮现取注册表，工具会自己出现）。
- **它是注册窗口演进的第一个真实需求**：工具在运行期注册与注销，因此它依赖「注册窗口是插件存活期」那条契约；若改回「只能在 `start()` 内注册」，这个插件就无法以现在的形态存在。
- **工具名必须带 `mcp__<server>__` 前缀并清洗字符**：名字由 server 决定，server 之间与 server 和内置工具之间都可能重名（`read_file` 就是典型）；而厂商对 function name 有共同约束，带点号的名字会让**整次请求**被拒（不是「这个工具不可用」，是「这一轮对话发不出去」）。超长时保留可读前缀并追加哈希，否则截断会把两个工具变成同一个名字。
- **只读只认用户声明、缺省可写**：`readOnlyTools` 是用户在 server 配置里写的工具名清单；**server 自填的 `annotations.readOnlyHint` 不再采纳**——那是第三方进程对自己的评价，不该由它决定我们放宽什么。写类工具缺省经 `PermissionCheckRequest` 判为 `ASK`（`askWriteTools` 可关），与 shell 分类器一样是**便利机制而不是安全边界**。注意这里的 `readOnlyTools` **只影响本插件的审批策略**，与 plan 插件的名单无关（后者是 `plugins.configurations.jellyfish-plan.readOnlyTools`，唯一来源是用户配置）。
- **不声明也不支持 sampling / elicitation**：`initialize` 里只声明 `roots`（答得上来），sampling / elicitation 真被请求时回一条明确的 `-32601`。声明了却办不到比不声明更糟：server 会按「客户端支持」去规划它的行为；而不回则会让对面等到它自己的超时。
- **`tools/list` 必须处理分页**：工具多的 server 会分页返回，只取第一页的表现是「工具少了一大半，而日志里什么异常都没有」。
- **`tools/list_changed` 的重扫必须转到另一条线程**：通知是在**读线程**上收到的，而重扫要发一个请求并等应答——应答只能由同一条读线程投递。直接在读线程上做就是一条线程等它自己（实测会挂到超时）。
- **`isError` 是正常结果而不是异常**：它是 server 明确答复的「工具跑了但没成」，要如实带上 `ToolMetadata.KEY_TERMINAL` 让界面出警示；超时、进程退出、JSON 非法才是调用失败（抛异常）。
- **二进制内容落盘而不是塞 base64**：base64 体积是原文件的 4/3，一次截图就能把上下文窗口撑满。落盘目录带 PID（`<tmp>/jellyfish-mcp/<pid>`），插件停止时整棵删掉——不会误删另一个并行进程的文件。
- **stdio 分帧就是「一行一条消息」，`stderr` 必须单独排空**：合进去会让 JSON 流里混进服务端日志（表现为「偶尔收到一帧解析不了」），而不排空则会让日志写满管道缓冲区、把 server 卡死。
- **关停要杀进程树**：最常见的用法是 `npx -y <package>`，真正干活的是孙进程；只杀直接子进程的现场表现是「插件已经停了，server 还占着端口」。JDK 8 没有 `ProcessHandle.descendants()`，靠 `pgrep -P` 递归，**杀不干净是已知边界**（`Process.pid()` 是 Java 9+，因此 PID 取不到时就只能杀到直接子进程）。

## 已知边界与后续项

- **`shell`**：**已落地**（`jellyfish-plugin-shell`，commons-exec shade 进插件包）。**已知边界**：Windows 映射未验证；进程树只能尽力杀（`pgrep -P` 不存在或没权限时退化为只杀直接子进程）；分类器可被 `FOO=bar cmd` / `$(...)` / `&&` 链绕过。**明确不做**：每次调用的预览预算覆盖（调大是上下文脚枪、调小不如直接在命令里写 `head -50`）；只读分类对重定向与复合命令不设防（真正的防线是审批框里那条完整命令原文，要收紧应当在白名单那一层，要免审批则写 `trustedCommands`）。
- **`shell` 明确不做**（需要时另开一期）：沙箱 / 权限降级 / 容器内执行（要硬隔离就把 jellyfish 整个跑进容器，那是唯一的硬边界）、命令黑名单与「解析式安全」、目录围栏、后台进程 / 常驻服务 / `shell_kill`（需要会话级进程注册表 + 输出重定向 API + 会话关闭清理）、**会话级工作目录**（牵动 `Session` 快照、持久化、恢复兼容与所有工具的路径解析，v1 用 `cwd` 参数）、落盘文件的引用计数式保留、TUI 审批的「本次会话记住该决定」。

## 编码约定

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律使用构造器注入 `@Inject`，不使用字段注入。
- 内核与插件之间的交互只走 `ExtensionRegistry` / `EventChannel`，禁止引入第三方事件总线（如 Guava EventBus）。
- 注释使用中文，说明“为什么”而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释，类注释要加`@author zcd`，方法注释要用`@param`写清楚每个参数、用`@return`写清楚返回值。
- 异常统一抛出 `JellyfishException`。
- **工具结果摘要与异常消息都是「给人看的一行」**：成功返回时用 `ToolMetadata.KEY_SUMMARY` 写一句「刚才那一行到底是什么事」（五个文件工具与 `todo_write` 已这么做，摘要必须单行）；抛 `JellyfishException` 时它的**首行**会作为失败原因显示在内核的轨迹行上，因此消息要写成一句给人看的原因（「文件不存在: /x/y」），**不要放密钥或大段内容**。非 `JellyfishException` 的异常不会暴露消息。
- 工具方法/常量类使用 `final` + 私有构造器（如 `ProviderTypes`、`LlmClients`）。
- 所有代码均需要满足sonar规范要求。

## 单元测试

- 使用 Junit5 + Mockito 进行单元测试，使用 JaCoCo 收集单元测试覆盖率。
- 单元测试类的包路径与被测试类的路径一致。
- 单元测试类命名统一按`{被测试类命}Test`格式。
- 测试方法命名统一按 `{被测试方法}_should_{预期结果}_when_{条件}` 格式。
- 一个测试方法只验证一个行为，测试独立、可重复、无外部依赖。
- 测试结构遵循 Given/When/Then 或 Arrange/Act/Assert。
- 只 mock 外部依赖或协作者，不 mock 被测类、POJO、DTO。
- 使用 `@ExtendWith(MockitoExtension.class)`，统一 JUnit5 API，不混用 JUnit4。
- 断言使用 JUnit5 `Assertions` 或 AssertJ，异常用 `assertThrows`。
- 多组输入用 `@ParameterizedTest`，覆盖正常、边界、异常场景。
- 单元测试不启动 Spring 容器，不访问数据库、网络等真实外部资源。
- **构造内核服务（`PluginContextFactory` / `PluginContextImpl`）时用不到的依赖传桩**：本仓库的测试用真实的
  `ExtensionRegistry` / `EventChannel`（注册与派发走的就是内核那条路径，换成假的就只是验证「我以为内核会怎么查」），
  但 `PluginContextFactory` 的 `RuntimeInfoHolder` / `ActionQueue` / `SessionManager` 对多数用例毫无意义，
  直接 `Mockito.mock(SessionManager.class)`。这条常被写反：**内核服务里真正被测的那部分用真的，其余用桩**，
  不要因为「反正是测试」就把整条链路都换成假的。
  推论：内核给这类服务的构造器加依赖时，本仓库会有一批用例同时编不过——那是设计上应该发生的事（见「内核升级后要做的检查」），
  按桩补齐即可，不要为了少改几处去给内核加「测试专用」的便捷构造器。

## Git 约定

- 只 `git add` 本会话实际修改的文件，禁止 `git add -A` / `git add .`。
- 提交前先 `git status` 确认暂存范围。
- 提交信息格式：`{feat,fix,docs,refactor,chore}[(scope)]: 描述`，描述简洁说明改动。
- 不要提交密钥、真实 apiKey 或本地配置文件。
- 提交代码前必须先得到允许。

## 语言

- 使用中文沟通

## 行为准则

- 开发时必须严格按与用户确认的方案执行，如果开发过程中发现方案有问题，先征求用户意见，禁止私自变更方案。
