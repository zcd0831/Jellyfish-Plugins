# 脚本插件示例

两种语言各两个示例，都可以直接跑。它们同时是**端到端测试的输入**——`mvn -Pscript-it test`
会真的把 `examples/scripts/<语言>/{hello,jira}` 跑起来（调用工具、执行命令、收事件、验清单），
所以这里的代码不会像放在文档里的示例那样腐烂。

| 示例 | 它演示什么 |
| --- | --- |
| `hello/` | 最小可用：一个只读工具 + 一个可写工具 + 一条命令（带别名与候选）+ 两种贡献（`prompt` / `status_line`）+ 订阅一个事件 |
| `jira/` | 更接近真实插件：多个工具（只读与可写分开声明）、带别名/用法的命令、候选查询写在单独的函数里、`prompt` + `panel` 贡献、**订阅并发布**事件 |

`jira` 是**内存里的假工单系统**（没有任何网络调用，重启 worker 就忘光）。真实的插件会把 HTTP
调用放在这里，而那正是脚本进程隔离的价值所在：依赖装在脚本自己的环境里，崩了也只波及它自己。

## 两门语言

同一件事在两边逐条对应，API 名刻意取得一样，会一门就会另一门：

| | Python | Node |
| --- | --- | --- |
| 位置 | `scripts/python/<id>/`（默认） | `scripts/node/<id>/`（默认） |
| 入口 | `main.py`（由 `manifest.json` 的 `entry` 指向） | `main.js` |
| 引入 SDK | `from jellyfish_sdk import tool, command` | `const { tool, command } = require('jellyfish_sdk')` |
| 声明能力 | 装饰器 `@tool(...)` / `@command(...)` | 声明函数 `tool({...}, fn)` / `command({...}, fn)` |
| 处理器签名 | `def f(args, ctx)`（命令是 `tokens, raw, ctx`） | `(params, ctx)`，工具读 `params.args`、命令读 `params.tokens` / `params.raw` |
| 候选查询（二级选择页） | `has_options=True` 或 `@command_options("x")` | `hasOptions: true` 或 `commandOptions('x', fn)` |
| 命令是否依赖会话 | `session_required=False`（缺省 `True`，保守） | `sessionRequired: false`（缺省 `true`，保守） |
| 缺省描述 | 取函数文档字符串第一行 | 必须显式写 `description`（JS 拿不到注释） |
| 缺省解释器 | `python3`（配置键 `pythonPath`） | `node`（配置键 `nodePath`） |
| 清单生成器 | `dump_manifest.py` | `dump_manifest.js` |
| 模块格式 | — | CommonJS（入口与 SDK 都用 `require`，ESM 不在当前范围内） |

Node 侧零第三方依赖：网关与 worker 只用 Node 内置模块，因此**不需要 `npm install`**；
你自己的脚本当然可以有自己的 `node_modules`（那是脚本目录的事，与宿主无关）。

## 读配置：`ctx.configuration`

脚本自己的配置段写在桥接插件的 `scripts.<脚本 id>` 下（脚本目录名就是脚本 id）：

```json
{
  "plugins": {
    "configurations": {
      "jellyfish-plugin-python": {
        "scripts": { "web": { "provider": "brave", "apiKey": "${BRAVE_API_KEY}" } }
      }
    }
  }
}
```

```python
@tool(name="web_search", description="联网搜索")
def web_search(args, ctx):
    cfg = ctx.configuration          # 与 module 级 configuration() 是同一份
    return call_provider(cfg["provider"], cfg["apiKey"], args["query"])
```

```javascript
tool({ name: 'web_search', description: '联网搜索' }, async (params, ctx) => {
    const cfg = ctx.configuration;   // 与 module 级 configuration() 是同一份
    return await callProvider(cfg.provider, cfg.apiKey, params.args.query);
});
```

- `${ENV}` 由内核插值，因此密钥不落在配置文件里；**不能靠环境变量直接传给脚本**——
  脚本进程的环境是严格白名单（只透传解释器运行与依赖解析必需的那几个），
  这是有意的安全取舍，因此这条配置段就是脚本拿密钥的唯一通道。
- **注入发生在 import 脚本之前**，所以模块顶层 `configuration()` 也拿得到值。

## 给界面看的一行：`ToolResult`

普通返回值就是给模型的正文；需要让轨迹行上多一句话或带上失败标记时，返回 `ToolResult`：

```python
from jellyfish_sdk import tool, ToolResult

@tool(name="web_search", description="联网搜索")
def web_search(args, ctx):
    hits = search(args["query"])
    return ToolResult(render(hits), summary="搜到 %d 条" % len(hits))
```

```javascript
const { tool, ToolResult } = require('jellyfish_sdk');
tool({ name: 'web_search', description: '联网搜索' }, (params) => {
    const hits = search(params.args.query);
    return new ToolResult(render(hits), { summary: `搜到 ${hits.length} 条` });
});
```

- `summary` 是**给人看**的一句话（外壳接在工具名后面显示），不是给模型的——模型看到的仍是正文。
- `terminal` 取不等于 `COMPLETED` 的值（如 `FAILED` / `TIMEOUT`）时界面出警示标记。
- 它**不进模型上下文、不进会话消息**；元数据只透传给外壳与审计。

## 调用者身份：`ctx.parent_session_id` / `run_id` / `root_run_id`

子代理有自己独立的会话与 run，工具常常需要知道「我此刻在替谁干活」（协作状态落在哪个会话、
这次改动属于哪次委派）：

```python
@tool(name="todo_claim", description="认领一条待办")
def todo_claim(args, ctx):
    # 子代理落在父会话上，根会话落在自己身上——这个键由内核给，模型无法伪造
    key = ctx.parent_session_id or ctx.session_id
    return claim(key)
```

- 根会话的 `parent_session_id`、顶层回合的 `run_id` / `root_run_id` 都是 `None`（Node 里是 `null`），
  与内核侧同名缺省一致。
- **不要用 `session_id` 做跨 run 协作的键**：子代理有独立会话，那样会各写一份。

## async handler（Node）

Node 侧的工具 / 命令 / 贡献 / 事件处理器都可以是 `async`：桥接会 `await` 它，
因此可以直接用 `fetch` / `undici` / `@mozilla/readability` 这类异步生态（Python 无需这个改造，
它的 HTTP 客户端本来就是同步的）。

```javascript
tool({ name: 'web_fetch', description: '抓取网页正文' },
     async (params) => (await fetch(params.args.url)).text());
```

- **在途请求仍然只有一个**：worker 仍是一次只处理一帧，只是允许 handler 等 I/O。
  脚本作者依旧不必考虑并发。
- **事件处理器的 async 不会被等**：事件是旁路，不能拖住后续请求。

## 取消：脚本看不到，但调用会被中止

用户在界面上按下 Esc 时，这个回合的取消令牌会传到桥接层；**在途的那次脚本调用会被中止**
（与超时路径同一条链：宿主发指令、网关杀掉该脚本的 worker），调用以一句「已被取消」失败回灌给模型。

```python
@tool(name="web_fetch", description="抓取网页")
def web_fetch(args, ctx):
    # 一次长网络请求：用户取消时本进程会被直接杀掉，这里拿不到任何通知
    return fetch(args["url"])
```

- **脚本侧拿不到取消标志**：worker 是单线程的（Python 阻塞在 HTTP 上、Node 的同步 handler 占着事件循环），
  一次在途调用期间它读不到任何新帧——所以一个可轮询的取消标志在模型上就不成立。
  这是进程边界决定的，不是省事。
- 因此**不要依赖取消来做资源清理**：写到一半的文件、开到一半的连接都会随进程一起消失。
  需要收尾就用 `finally`（同进程内）或幂等的重试。
- 取消**不计入熔断**：它是用户主权，不是脚本的毛病。

## 更多可声明的扩展点

除 `prompt` / `status_line` / `panel` / `permission` / `session_persist` / `session_restore` /
`session_delete` / `compaction` 之外，还有六个类型级贡献：

| 贡献类型 | 它回答什么 | 返回值写法 |
| --- | --- | --- |
| `tool_argument_pre` | 工具参数要不要改写（在权限判定**之前**） | `None` 不改、`{"arguments": {...}}` 替换、原因字符串或 `True` 拒绝 |
| `tool_result_post` | 工具结果要不要整形（在截断与落盘**之前**） | `None` 不改、`{"output": ..., "metadata": {...}}` 只改给出的那一项 |
| `turn_context` | 本轮要随用户消息一起送达的即时状态 | 一段文本（或 `{"text": ...}`） |
| `session_before_close` | 会话关闭前能不能关 | `None` 放行、`True` 或原因字符串拦下 |
| `session_before_fork` | 会话分支前能不能分 | 同上 |
| `compaction_pre` | 这次压缩要不要做、保留多少条 | `None` 放行、`{"cancel": true, "reason": ...}` 或 `{"keepRecent": n}` |
| `tool_activation` | 某个工具该不该进本次工具清单 | `None` 不管、`True` 明确可见、`False` 或原因字符串 / `{"hidden": true}` 隐藏 |
| `input_transform` | 用户按回车后的原文要不要改写 | `None` 不改、字符串或 `{"text": ...}` 替换、`{"handled": true, "notice": ...}` 接过去不进对话 |
| `turn_before` | 这个回合要不要起 | `None` 放行、`True` 或原因字符串拦下、`{"input": ...}` 改写本轮输入 |
| `request_tuning` | 这次请求的缓存参数（**热路径**） | `None` 不改、`{"cacheKey": ..., "cacheRetention": ..., "cacheBreakpoints": n}` |
| `aging_strategy` | 较早工具结果怎么老化（**热路径**） | `None` 不改、`{"keepRecentMessages": n, "agingPercent": n, "stubText": ..., "stubTextsByTool": {...}}` |

```python
@contributes("turn_context")
def turn_context(ctx):
    # 只声明用得上的槽位：会话内会变的状态走这里，而不是提示词贡献
    return "现在：%s" % now()

@contributes("tool_result_post")
def tool_result_post(tool_name, output, ctx):
    # 必须排在截断与落盘之前：事后改文本会与 _path 里的内容永久分叉
    return {"output": redact(output), "metadata": {"summary": "已脱敏"}}
```

**带路由键的扩展点用 `@handler` 声明**（Python 与 Node 各叫 `handler`）：路由键来自**用户配置**
（目前只有 `model_catalog`，路由键是 provider 名），脚本无法从自己的声明里推出来，因此必须写出来，
并且**要在清单里重复一份**：

```python
@handler("model_catalog", route="local")
def local_catalog(provider_name, provider_type, ctx):
    return [{"id": "qwen2.5", "contextLength": 32768}]
```

```json
{
  "entry": "main.py",
  "handlers": [{ "type": "model_catalog", "route": "local" }]
}
```

- **空模型列表是「我不表态」**，回落成配置里 `models` 写的那份，而不是「这个 provider 没有模型」。
- 路由名写错一样会在启动时被清单一致性校验拦下（比对的是 `type::route`）。

`input_directive` 也走 `handler`，但路由键是**标记本身**（`!` / `@`）：

```python
@handler("input_directive", route="!")
def bang(marker, input, ctx):
    # 只声明「这行输入该当成哪次工具调用」；真正的执行走内核完整的权限与审批链
    return {"toolName": "shell", "arguments": {"command": input}}
```

```json
{ "entry": "main.py", "handlers": [{ "type": "input_directive", "route": "!" }] }
```

### 热路径点：`request_tuning` / `aging_strategy` 不冷启动

这两个点**每次组装请求都会被问到**，而脚本调用是一次进程往返、网关又是懒启动的。因此桥接层
对它们：① worker 没热着就**不冷启动**，直接返回「不表态」（调用点走保守缺省）；
② 热着时也只给 **2 秒**截止（超时不杀 worker，但如实上报失败，好让熔断能把它关掉）。

> **推论**：只提供这两个贡献、别的一个点都不提供的脚本**永远不会被拉起**。
> 要让它跑起来，至少还要提供一个会被真正调用的点（工具、命令、`turn_context`、`turn_before` …）。

## 跑起来

每个示例目录就是一个脚本插件，目录名就是脚本标识：

```bash
mkdir -p scripts/python scripts/node
cp -r hello jira scripts/python/          # 默认位置：进程工作目录下的 scripts/python
cp -r hello jira scripts/node/            # Node 侧同理：scripts/node
```

在 `<工作目录>/.jellyfish/jellyfish.json` 里（或全局 `~/.jellyfish/jellyfish.json`）可选地配置：

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
        "nodePath": "node",
        "invokeTimeoutSeconds": 30,
        "workerIdleSeconds": 300,
        "gatewayIdleSeconds": 600
      }
    }
  }
}
```

两段配置除了解释器键（`pythonPath` / `nodePath`）与脚本根目录默认值之外完全一样——
它们由同一份公共实现解析（`ScriptBridgeConfig`），因此不会出现「超时语义两边不同」这类漂移。

启动后：

- `/python`、`/node` 看各自的台账（脚本、已登记能力、网关与 worker 的 PID、熔断、事件计数、单脚本问题）；
- `/hello 世界`、`/hi 世界`（别名）、`/jira PROJ-1 DONE`；
- 工具 `hello_greet` / `hello_remember` / `jira_read` / `jira_create` 直接由模型调用。

**启动 Agent 不会拉起任何脚本进程**：第一次真正用到某个脚本时才 fork 它的 worker，
空闲超时后自行退场（进程数与常驻内存回到 0）。Node 侧同理（`spawn` + 一条 `init` 帧）。

## 清单与实现必须一致

对宿主而言，脚本的能力**只有** `manifest.json` 这一个来源，而实现的事实写在声明里。
两者不一致时脚本会**拒绝服务**（宁可明确失败，也不要「模型按一份不存在的工具定义去调用」）。

因此**改完脚本一定要同步清单**。生成器就在网关资源目录里（两个语言各一份）：

```bash
# Python
python3 ~/.jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira --write
python3 ~/.jellyfish/gateway/python/<digest>/script/dump_manifest.py ./jira --check

# Node
node ~/.jellyfish/gateway/node/<digest>/script/dump_manifest.js ./jira --write
node ~/.jellyfish/gateway/node/<digest>/script/dump_manifest.js ./jira --check
```

- `--check` 与目录里那份比对，不一致退 1，并**按名字**报差异（「清单里多出了这一项: 幽灵工具」
  「tools[jira_read].description: 实现是 a，清单是 b」）；
- `--write` 直接写盘，**推荐**：用 shell 重定向（`> manifest.json`）会让 shell 先把目标文件
  截空，而生成器恰好要读它来确认入口文件名（两个生成器都对空文件做了容错，但没必要踩）。

生成的清单只包含清单需要的字段，且**会被内核的严格校验器读一遍**——这一条有端到端测试守着。
`manifestStrict: false` 可以把「不一致」降级成告警继续服务，但那只是在延长定位时间。

## 写自己的脚本时最容易踩的几处

- **清单里的描述与参数决定模型看到的工具定义**：这里的 `description` / `parameters` / `required`
  与声明里的值是两份（一致性校验只比名字），写错的表现是模型按错误签名调用。
- **没有只读声明这回事**：哪些工具在某个模式下可用完全由用户决定（例如 plan 插件的
  `plugins.configurations.jellyfish-plan.readOnlyTools`），
  脚本无法自称只读（Python / Node 的 `read_only` / `readOnly` 参数已移除，清单里写 `readOnly`
  会被判未知键并拒绝加载）。**那份名单里不写，开启 plan 时就一个工具都用不了**。
- **用脚本自己的目录定位文件**（Python `__file__` / Node `__dirname`）：相对路径会落到进程的
  cwd，那是宿主的工作目录。
- **失败要抛 `ScriptError`**，不要返回 `{"error": ...}`：后者会被当成正常输出。
- **事件会丢，且对忙的 worker 直接丢**：只用来做通知与统计，不要用它传递必须到达的结果。
  另外注意「观察者效应」——你在事件之后紧接着发起的调用，本身就会让推送撞上忙碌窗口。
- **脚本不需要考虑并发**：worker 是单线程的（收请求、跑处理器、收事件都在同一个循环里），
  同一个脚本的并发调用会被网关排队。
- **候选查询与执行是两条路，而且它只该读不该写**：用户按下补全键时被调用的那一次，
  拿到的是 `tokens = None`（Node 里是 `null`）——**这就是「这次是候选查询」的标记**。
  执行那次一定给真实的 `tokens`（`[]` 表示用户没输入参数，与 `None` 不是一回事）。
  两个入口各走一个示例：`hello` 用 `has_options=True` 让同一个函数按 `tokens is None` 分支，
  `jira` 把候选查询写在单独的函数里（真实脚本更常用这个，因为候选查询必须只读且快）。
  写成 `has_options=True` 却看不出两条路的函数会在**启动时**被拒绝，报错里带着可照抄的写法。
- **卡死的脚本会被强杀**，两边都由网关执行：Python 侧先 SIGTERM（信号处理器直接 `os._exit`），
  Node 侧 SIGTERM 往往无效（事件循环被同步代码占着），因此靠的是到点后的 SIGKILL。
