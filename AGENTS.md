# AGENTS.md

本文件用于指导 AI 编码代理在**官方插件仓库**中工作。修改代码前请先阅读。

> **本文件是常驻上下文，只放「每次动手前都该知道的规则」**：项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。
> 逐个插件的用法与配置在 `README.md`；内核侧的契约与约束在内核仓库的 `docs/constraints.md`，**本仓库不重写一份**。
> 逐条的设计推导与实测数据在对应类的 javadoc 里，本文不重复。

## 项目描述

Jellyfish 官方插件仓库：把内核的扩展点变成一组可直接安装的能力。

- **插件是独立打包的 PF4J jar**，由内核在启动期扫描加载，与内核之间**没有编译期依赖**——只经
  `jellyfish-api` 的 SPI 交互。因此本仓库是**独立的 Maven reactor**，`jellyfish-api` 取自本地仓库。
- **一个插件一个子模块**（PF4J 是「一个 jar 一个 `plugin.properties`」）。
- 除 Java 插件外，还有 **Python / Node 脚本插件**两条桥接路径（`jellyfish-script` 是它们共用的机制层库）。
- 运行环境 **JDK 1.8**（**不要使用 Java 9+ 的 API 或语法**）。

## 常用命令

```bash
mvn -q compile
mvn -q test                         # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q package -DskipTests          # 打包全部插件
./cpPlugins.sh                      # 把 target/*.jar 拷进 ~/.jellyfish/plugins/
mvn -q -Pshell-it test              # shell 插件端到端（真 /bin/sh，会真起进程再杀掉）
mvn -q -Pmcp-it test                # MCP 插件端到端（真 fork 进程跑仓库自带的 echo server）
mvn -q -Pscript-it test             # 真实 python3 / node 的端到端
```

## 内核升级后要做的检查

内核一侧改了 `jellyfish-api` 之后，**只有下面几条全绿才算真的没问题**：

```bash
(cd ../Jellyfish && mvn -q install -DskipTests)   # 0. 不能省
mvn -q clean package                              # 1. 必须 clean
mvn -q -Pshell-it test                            # 2. 三个端到端 profile 一个都不能漏
mvn -q -Pmcp-it test
mvn -q -Pscript-it test
```

- **第 0 步不能省**：本仓库是独立 reactor，`jellyfish-api` / `jellyfish-infra` 来自本地 Maven 仓库；
  忘了它表现为「内核明明改了，插件这边编译却看不到」。
- **必须 `clean`**：增量编译会复用按旧 `jellyfish-api` 编出的 `target/`，于是**编译问题被伪装成运行期问题**。
- **`mvn test` / `mvn package` 不跑 `*IT`**：三个 `-P*-it` 一个都不能漏，否则坏掉的端到端不会被发现。

## 技术架构

| 模块 | 职责 |
| --- | --- |
| `jellyfish-plugin-tools` | 五个文件工具 + `ask_user` 提问工具 + `@` 文件引用 |
| `jellyfish-plugin-shell` | `shell` 工具 + 命令策略 + `!命令` 输入指令（commons-exec 以 shade 打进插件包） |
| `jellyfish-plugin-session-file` | 会话持久化（一会话一 JSON + git） |
| `jellyfish-plugin-todo` | 待办工具族 + `/todo` + 回合上下文 / 状态栏 / 面板贡献 |
| `jellyfish-plugin-project` | 探测工作目录下的 `AGENTS.md` 并按大小决定内联还是给路径；`/init` 让模型读仓库后写出它 |
| `jellyfish-plugin-compact` | 压缩策略（摘要指令 `summary-prompt.md` + 两个数量参数） |
| `jellyfish-plugin-skills` | 按目录发现 `SKILL.md`，正文由 `skill` 工具按需加载 |
| `jellyfish-plugin-mcp` | MCP 客户端（Jackson 以 shade 打进插件包） |
| `jellyfish-plugin-workflow` | 声明式 spec 编排（并发派生子代理并聚合） |
| `jellyfish-plugin-plan` | plan 模式（类型级权限拦截 + `/plan` 命令 + 会话扩展条目） |
| `jellyfish-plugin-resmon` | 资源监控：JVM 与磁盘占用采样，只读 `/resmon` 命令 + 右栏面板（纯只读，不做清理） |
| `jellyfish-plugin-python` | Python 桥接（网关 + 每脚本一 worker 进程；`jellyfish-script` 以 shade 打进） |
| `jellyfish-plugin-node` | Node 桥接（与 Python 同构，零第三方依赖） |
| `jellyfish-script` | 脚本桥接的**机制层库**，不产出到 `plugins/`（在内核仓库也有同名模块） |
| `examples/scripts` | 可直接运行的示例脚本，被端到端用例直接加载 |

**源码结构范式**：`resources/plugin.properties` + `PluginConfig`（配置段解析）+ `JellyfishPlugin` 实现
+ 各扩展点 handler。**一门语言 = 一个 `ScriptLanguage` 实现 + 一个薄插件 + 一份网关资源**；
桥接骨架（探测解释器 → 扫描清单 → 按脚本注册 → 接通事件与熔断 → 注册 `/<语言>` 命令 → 按序关闭）
全在 `ScriptBridgePlugin` 里，子类只提供 `resolveConfig` 与 `createLanguage`。

## 关键不变量

- **`jellyfish-api` 永远是 `provided`，shade 的 `includes` 里绝不能出现它**：插件 jar 里不含 api 的类，
  因此插件拿到的值类型永远是**运行期内核那一份**——内核给值类型加字段时插件什么都不用做。
  自带 api 会遮蔽父加载器里的同名类，让新字段静默丢失。
- **跨边界值类型恰好一个可见构造器**：新增字段只能用新静态工厂补，**不加兼容构造器**。
  引用 api 的值类型优先用静态工厂；**测试夹具反过来，必须用全字段构造器**。
- **老插件「什么都不做」是受支持的形态**：新增扩展点的兼容底线是「0 个 handler 时内核走一条与改造前逐字段
  一致的路径」；用不上新点就不要动既有注册。
- **内核新增扩展点时，脚本能力档会先红**：`ExtensionPointCoverageTest` 枚举 `jellyfish-api` 里**全部**
  `ExtensionRequest` 子类，未在 `jellyfish-script/src/main/resources/script/extension-points.json` 里分档
  （`in` / `planned` / `excluded`）即构建失败。**先做分档决策，再决定要不要实现。**
- **脚本能力档不是「与 Java 插件同权」**：只覆盖数据进出、且不在渲染线程 / 启动期的扩展点。
  `excluded` 里的两类不要试图打通——返回 Java 对象的、跑在渲染线程或启动期的。
- **注册窗口是插件的整个存活期**（不限 `start()`）；`stop()` 之后一切注册 / 发布当场失败（fail-closed），
  产生注册的后台线程必须在 `stop()` 返回前停下来。
- **插件往外壳推内容只走 `PluginContext.present`**，不要新建通道；面板与状态栏的**内容**仍走拉取。
- **插件拿不到会话与工作目录**，也**不能新开会话或起回合**（`PluginContext` 里没有那样的入口）。
- **工具结果摘要与异常消息都是「给人看的一行」**：成功返回时用 `ToolMetadata.KEY_SUMMARY` 写一句
  「刚才那一行到底是什么事」（必须单行）；抛 `JellyfishException` 时它的**首行**会显示在内核的轨迹行上，
  因此消息要写成一句给人看的原因，**不要放密钥或大段内容**。
- **插件停止时必须终止在途子进程**，否则用户看到的是「jellyfish 都退出了，那条命令还在跑」。
- **示例脚本被端到端用例直接加载**，因此示例不会腐烂；改示例时 `manifest.json` 与声明必须一起改
  （`dump_manifest --check` 就是给这件事用的）。
  `python/stock` 与 `python/stockpanel` 是**刻意的例外**（要联网、要 `akshare`，不进 `-Pscript-it`），
  改完要按 `stock/DESIGN.md` 的「怎么重新验证」手动跑一遍。

## 编码规范

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律用构造器注入 `@Inject`，不用字段注入。
- 注释用中文，说明「为什么」而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释；类注释加 `@author zcd`；方法注释用 `@param` 写清每个参数、
  用 `@return` 写清返回值。
- 异常统一抛 `JellyfishException`。
- 工具方法 / 常量类用 `final` + 私有构造器。
- **需要把配置段或 JSON 转成对象时，插件必须自带 Jackson 并以 shade 打进 jar**（内核的序列化封装在
  `jellyfish-infra` 里，插件看不到）。
- 所有代码均需满足 sonar 规范要求。

## 单元测试规范

- JUnit5 + Mockito，JaCoCo 收集覆盖率。
- 测试类包路径与被测类一致；命名 `{被测试类名}Test`。
- 方法名 `{被测试方法}_should_{预期结果}_when_{条件}`；一个方法只验一个行为；测试独立、可重复、无外部依赖。
- 结构遵循 Given/When/Then 或 Arrange/Act/Assert。
- 只 mock 外部依赖或协作者，不 mock 被测类、POJO、DTO。
- 用 `@ExtendWith(MockitoExtension.class)`，统一 JUnit5 API，不混用 JUnit4。
- 断言用 JUnit5 `Assertions` 或 AssertJ，异常用 `assertThrows`。
- 多组输入用 `@ParameterizedTest`，覆盖正常、边界、异常场景。
- 单测不启动 Spring 容器，不访问数据库、网络等真实外部资源（要真起进程 / 解释器的走 `-P*-it`）。
- **构造内核服务时用不到的依赖传桩**：本仓库的测试用真实的 `ExtensionRegistry` / `EventChannel`
  （注册与派发走的就是内核那条路径），但 `RuntimeInfoHolder` / `ActionQueue` / `SessionManager` 等对多数
  用例毫无意义，直接 mock。**内核服务里真正被测的那部分用真的，其余用桩。**

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
- **改了插件的行为就同步改文档**：新增工具 / 命令 / 配置字段时，`README.md` 的对应位置要一起改。
