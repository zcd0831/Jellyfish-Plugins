'use strict';

/**
 * Jellyfish Node 脚本插件 SDK。
 *
 * 脚本作者只与本模块打交道：声明「我提供了什么」，剩下的（进程、协议、清单校验、熔断、回收）
 * 都由宿主负责。因此本模块刻意不感知协议细节，只做三件事：
 *
 * 1. **声明**：`tool()` / `command()` / `contributes()` / `subscribe()` 把参数记进注册表；
 * 2. **分发**：按扩展点类型把请求交给对应的函数，并把返回值整形成协议要求的形状；
 * 3. **生成清单**：`dumpManifest()` 把注册表还原成 `manifest.json`。
 *
 * 声明与清单必须一致，这是本方案唯一的高危点，因此两者都从这里出发：清单由代码生成，
 * 而不是另写一份。严格校验（`manifestStrict`）在 worker 启动时把两者比一遍，
 * 不一致就拒绝服务——宁可报错，也不要让模型按一份不存在的工具定义去调用。
 *
 * **与 Python 版逐条对应**：两边的方法名、参数名、返回值形状都刻意保持一致
 * （`tool` / `command` / `command_options` / `contributes` / `subscribe` / `declarations` /
 * `dump_manifest` / `compare_with` / `invoke`）。差异只在语言本身：
 * 装饰器变成「声明函数 + 就地注册」，缺省描述不再从文档字符串里取（JS 拿不到注释），
 * 因此 JS 作者要显式写 description——它正是模型看到的那句。
 */


/** 本模块所在目录：网关资源抽取目录，也就是 SDK 与 worker 所在的地方。 */
const HERE = __dirname;

/** 读能力档与解析路径：两者都是 Node 内置模块，不需要 npm install。 */
const fs = require('fs');
const path = require('path');

/**
 * 让脚本里的 `require('jellyfish_sdk')` 在任何工作目录下都能解析出来。
 *
 * 脚本用**包名**而不是相对路径引入 SDK（与 Python 版的 `import jellyfish_sdk` 对称），
 * 而 Node 的非相对引入只查 `node_modules` 链与 `NODE_PATH`，不会像 Python 那样
 * 「先看脚本自己的目录」——脚本目录里没有 SDK，SDK 在网关资源目录里。
 * 因此谁加载脚本，谁就有责任先把这条路铺好：
 *
 * - 网关走的是**公开手段**：给 worker 的子进程环境设 `NODE_PATH`（见 gateway.js 的 spawn）；
 * - 离线生成器与手工调试没有那条链路，因此在进程内补一次（本方法）。
 *
 * 进程内补这一下用到了 `Module._initPaths`，它是 Node 的内部函数（`NODE_PATH` 只在进程
 * 启动时读一次，改完必须请 Node 重算搜索路径）。它从 Node 0.10 起就存在，且是循环里
 * 唯一的私有点，因此这里显式判一下：拿不到时返回 `false`，由调用方给出可操作的提示，
 * 而不是让脚本以「找不到模块」的形态失败。
 *
 * @returns {boolean} 铺好了返回 `true`
 */
function ensureResolvable() {
    // eslint-disable-next-line global-require
    const Module = require('module');
    const current = process.env.NODE_PATH ? process.env.NODE_PATH.split(require('path').delimiter) : [];
    if (current.indexOf(HERE) < 0) {
        process.env.NODE_PATH = [HERE].concat(current).join(require('path').delimiter);
    }
    if (typeof Module._initPaths !== 'function') {
        return false;
    }
    Module._initPaths();
    return true;
}

/** 扩展点类型名 → 处理函数。类型名与 Java 侧 codec 的 typeName() 必须逐字一致。 */
const handlers = new Map();

/** 工具声明，顺序即声明顺序，生成的清单与它保持同序（便于人读 diff）。 */
const tools = [];

/** 命令声明。 */
const commands = [];

/** 候选查询声明。 */
const commandOptionDecls = [];

/** 订阅的事件名。 */
const subscriptions = [];

/** 周期任务声明。条目只放清单需要的键（处理函数进 `handlers` 表）；未给间隔时不写 `intervalSeconds`。 */
const schedules = [];

/** 声明过的贡献类型，用于检测「同一类型声明了两个函数」。 */
const contributionTypes = new Set();

/**
 * 带路由键、且路由键来自用户配置的处理器声明（目前只有 `model_catalog`）。
 *
 * 每条是 `{type, route}`；route 由用户配置决定（provider 名），脚本无法从自己的声明里推出来。
 */
const handlerDecls = [];

/**
 * 可以发布的事件名（与 Java 侧 ScriptEventFactory 的白名单一致）。
 *
 * 只有两类，而且都是「自由载荷」：通知的 payload 任意 JSON，告警只有一句文本。
 * 所以脚本**造不出内核语义事件**（工具完成、权限判定之类）——那类事件是内核事实的转述，
 * 指标、审计与界面都按「它是真的」来消费，让脚本能造等于开了一条往审计里写假账的路。
 *
 * 这里再留一份的原因只是**报错更近**：不在这份清单里时在本地就提醒一句，
 * 免得「事件发出去但没人收到」变成一个需要翻宿主日志的问题。
 * 真正的裁决仍在宿主侧，因此这份清单哪怕过时也只会多一句提醒，不会吞掉事件。
 */
const EMITTABLE_EVENTS = Object.freeze(['PluginNotificationEvent', 'ConfigWarningEvent']);

/**
 * 本脚本的配置段：由 worker 在 **加载脚本之前** 注入，因此模块顶层读也拿得到。
 * 内容来自 `plugins.configurations.<桥接插件>.scripts.<脚本 id>`，
 * 内核已完成双源合并与 `${ENV}` 插值。
 */
let currentConfiguration = {};

/**
 * 注入本脚本的配置段。
 *
 * 由 worker 调用，脚本作者不需要也不应该调它。时机必须是「加载脚本之前」，
 * 否则脚本在模块顶层读 `configuration()` 会拿到空对象，而那种失败看起来就像
 * 「配置没生效」——最难归因的一类现场。
 *
 * @param {object|null} values 配置映射，可为 `null`（等价空）
 */
function setConfiguration(values) {
    currentConfiguration = Object.assign({}, values || {});
}

/**
 * 获取本脚本的配置段。
 *
 * 与 `ctx.configuration` 是同一份，差别只在拿到的时机：本函数在模块顶层就能调。
 * 密钥写在这里比写在脚本目录的文件里更一致，也与 Java 插件的
 * `PluginContext.configuration()` 同一条通道。
 *
 * @returns {object} 配置映射，保证非 `null`
 */
function configuration() {
    return currentConfiguration;
}

/**
 * 权限拦截能表达的三态；刻意没有「放行」——脚本只能收紧，不能放宽内核已经允许的调用。
 */
const PERMISSION_VERDICTS = Object.freeze(['ABSTAIN', 'ASK', 'DENY']);

/**
 * 脚本侧的可预期失败。
 *
 * 抛出它等于告诉宿主「这次调用失败了」，宿主会把它转成协议错误回灌给模型
 * （工具调用表现为「工具执行失败：…」，回合继续）。**不要**用返回值里的
 * `{error: ...}` 表达失败——那会被当成正常输出，语义静默错位。
 */
class ScriptError extends Error {
    /**
     * 构造脚本错误。
     *
     * @param {string} message 可读原因
     */
    constructor(message) {
        super(message);
        this.name = 'ScriptError';
    }
}

/**
 * 工具返回值：正文之外再带上给界面看的元数据。
 *
 * 普通返回值就是 `output`；只有需要一行摘要或失败标记时才用它。
 * `summary` 是轨迹行上显示的一句话（给人看，不是给模型看），`terminal` 取不等于
 * `COMPLETED` 的值时让界面出警示标记（例如 `FAILED` / `TIMEOUT`），`metadata`
 * 里还可以放任意结构化事实（内核只透传、不解释）。
 *
 * 它**不进模型上下文**：模型看到的仍是 `output` 那段文本。
 */
class ToolResult {
    /**
     * 构造工具结果。
     *
     * @param {*} output 回灌给模型的正文（任意 JSON）
     * @param {object} [details] `{summary, terminal, metadata}`，均可省略
     */
    constructor(output, details) {
        const shape = details || {};
        this.output = output === undefined ? null : output;
        const merged = Object.assign({}, shape.metadata || {});
        if (shape.summary !== undefined && shape.summary !== null) {
            merged.summary = shape.summary;
        }
        if (shape.terminal !== undefined && shape.terminal !== null) {
            merged.terminal = shape.terminal;
        }
        this.metadata = merged;
    }
}

/**
 * 一次调用的上下文。
 *
 * 只暴露脚本真正需要的东西：身份、会话标识与原始请求。刻意不暴露宿主对象，
 * 也不提供「回调宿主」的能力——脚本能做的事只有「处理这次请求并返回结果」。
 */
class ScriptContext {
    /**
     * 构造上下文。
     *
     * @param {string} scriptId 脚本标识
     * @param {object} payload 原始请求载荷
     * @param {Function|null} emitter 事件发布回调
     */
    constructor(scriptId, payload, emitter) {
        this.scriptId = scriptId;
        this.payloadData = payload || {};
        this.emitter = emitter || null;
    }

    /** 当前会话标识；该扩展点没有会话上下文时为 `null`。 */
    get sessionId() {
        return this.payloadData.sessionId === undefined ? null : this.payloadData.sessionId;
    }

    /** 当前 agent 标识；仅权限拦截等扩展点会带。 */
    get agentId() {
        return this.payloadData.agentId === undefined ? null : this.payloadData.agentId;
    }

    /** 原始请求载荷（只读用途，改它不会影响宿主）。 */
    get payload() {
        return Object.assign({}, this.payloadData);
    }

    /**
     * 本脚本的配置段（`plugins.configurations.<桥接插件>.scripts.<脚本 id>`）。
     *
     * 内核已完成双源合并与 `${ENV}` 插值，因此这里拿到的就是最终值。
     * 与模块级 `configuration()` 同一份。
     *
     * @returns {object} 配置映射，保证非 `null`
     */
    get configuration() {
        return currentConfiguration;
    }

    /**
     * 派生当前会话的那个会话；根会话（用户直接对话的那一个）为 `null`。
     *
     * 子代理有独立的会话，而协作状态往往要落在父会话上——该用哪个键只有内核知道，
     * 模型无从指定。与 `sessionId` 的分工：后者是「我自己」，前者是「我属于谁」。
     *
     * @returns {string|null} 父会话标识
     */
    get parentSessionId() {
        return this.payloadData.parentSessionId === undefined ? null : this.payloadData.parentSessionId;
    }

    /** 本次调用所在的 run；顶层回合或进程级调用为 `null`。 */
    get runId() {
        return this.payloadData.runId === undefined ? null : this.payloadData.runId;
    }

    /** 本次调用所在的 run 树根；不在任何 run 上时为 `null`。 */
    get rootRunId() {
        return this.payloadData.rootRunId === undefined ? null : this.payloadData.rootRunId;
    }

    /**
     * 发布一条事件。
     *
     * **尽力而为，没有返回值，也不要依赖它一定送达**：事件通道的契约是「可以丢」，
     * 丢弃可能是宿主队列满、网关没在运行、或没有 worker 在听。真正需要可靠传递的信息
     * 请用返回值。
     *
     * `name` 见 {@link EMITTABLE_EVENTS}；`payload` 是任意 JSON（仅
     * `ConfigWarningEvent` 要求 `message` 非空）。宿主会校验并可能拒绝，
     * 拒绝只记日志——脚本侧拿不到裁决，这也是「尽力而为」的一部分。
     *
     * @param {string} name 事件名
     * @param {object} payload 事件载荷，可为 `null`
     */
    emitEvent(name, payload) {
        if (!this.emitter) {
            throw new ScriptError('当前上下文不支持发布事件');
        }
        if (EMITTABLE_EVENTS.indexOf(name) < 0) {
            process.stderr.write(`[${this.scriptId}] 事件 ${name} 不在可发布清单 [`
                + `${EMITTABLE_EVENTS.join(', ')}] 内，宿主会拒绝\n`);
        }
        this.emitter(name, payload || {});
    }
}

/**
 * 声明一个工具，并返回处理函数本身。
 *
 * `parameters` 是 JSON Schema 的 `properties` 部分，`required` 是必填参数名列表：
 * 它们会原样进入 `ToolDescriptor`，也就是模型看到的工具定义，因此必须与函数真正
 * 接受的参数一致——不一致的后果是模型按错误的签名调用，而错误只在运行期以
 * 「参数缺失」的形式出现。
 *
 * 这里**没有** `readOnly`：哪些工具在某个模式下可用完全由用户决定
 * （例如 plan 插件的 `jellyfish.json` 段 `plugins.configurations.jellyfish-plugin-plan.readOnlyTools`），
 * 工具无法自称只读。传了它会被**当场拒绝**——静默忽略会让作者以为自己声明成功了。
 *
 * JS 里没有装饰器，因此用法是「声明 + 就地注册」，返回值就是那个函数：
 * ```js
 * const greet = tool({name: 'hello_greet', description: '按名字打招呼'},
 *                    (params, ctx) => '你好，' + params.args.name);
 * ```
 *
 * @param {object} spec 声明：`name` / `description` / `parameters` / `required`
 * @param {Function} handler 处理函数，签名 `(params, ctx)`；工具用 `params.args`
 * @returns {Function} 同一个处理函数，便于赋值给变量
 */
function tool(spec, handler) {
    requireName(spec, 'tool');
    if (tools.some((item) => item.name === spec.name)) {
        throw new ScriptError(`工具名重复声明: ${spec.name}`);
    }
    if (spec.readOnly !== undefined) {
        throw new ScriptError('readOnly 已不再支持：哪些工具可用由用户在各模式插件的 readOnlyTools 里声明');
    }
    tools.push({
        name: spec.name,
        description: spec.description === undefined ? null : spec.description,
        parameters: spec.parameters || {},
        required: listOf(spec.required),
    });
    handlers.set(`tool\u0000${spec.name}`, handler);
    return handler;
}

/**
 * 声明一条命令，并返回处理函数本身。
 *
 * `hasOptions: true` 表示本命令也回答候选查询（二级选择页）。它与 `commandOptions()` 是
 * 同一件事的两个入口，二者只能选一个：同时声明会被清单校验判为冲突——
 * 那必然是作者写错了，而写错的后果是「命令能执行、选择页永远空、且没有任何报错」。
 *
 * 走这个入口意味着**同一个函数**要回答两条路，而两条路给的 `params` 不同：执行给真实的
 * `tokens` / `raw`，候选查询给 `tokens === null` / `raw === null`。因此函数要按
 * `params.tokens === null` 分支（`tokens` 为空数组则是「用户没输入参数的执行」，两者不是一回事）：
 *
 * ```js
 * command({ name: 'x', hasOptions: true }, (params, ctx) => {
 *     if (params.tokens === null) {
 *         return { choices: [...] };   // 候选查询（按下补全键时）
 *     }
 *     return '执行结果';                // 执行
 * });
 * ```
 *
 * 候选查询必须**只读且快**（它跑在用户按键的那一拍上），因此更常见的是用 `commandOptions()`
 * 把它放在单独的函数里。与 Python 版的唯一差别：JS 拿不到参数名，因此这里无法像 Python 那样
 * 在声明期就把写错的签名挡掉，但漏掉分支的后果同样是响的——
 * `params.tokens` 是 `null`，`null.map(...)` 会立刻抛错。
 *
 * @param {object} spec 声明：`name` / `summary` / `usage` / `aliases` / `hasOptions` / `sessionRequired`
 * @param {Function} handler 处理函数，签名 `(params, ctx)`；命令用 `params.tokens` 与 `params.raw`
 * @returns {Function} 同一个处理函数
 *
 * `sessionRequired` 声明「这条命令是不是必须有会话才能工作」，**缺省 `true`（保守）**。
 * 声明为 `false` 的命令在没有当前会话时（TUI 首页）也能执行，因此外壳不会把用户手敲的它
 * 当成普通对话发给模型。默认值取 `true` 的理由是：反过来默认「不需要」会让一条依赖会话的命令
 * 在无会话时静默地变成一句提示词，而作者根本没这么想过。
 */
function command(spec, handler) {
    requireName(spec, 'command');
    if (commands.some((item) => item.name === spec.name)) {
        throw new ScriptError(`命令名重复声明: ${spec.name}`);
    }
    commands.push({
        name: spec.name,
        descriptor: {
            summary: spec.summary === undefined ? null : spec.summary,
            usage: spec.usage === undefined ? null : spec.usage,
            aliases: listOf(spec.aliases),
            sessionRequired: spec.sessionRequired === undefined ? true : Boolean(spec.sessionRequired),
        },
        hasOptions: Boolean(spec.hasOptions),
    });
    handlers.set(`command\u0000${spec.name}`, handler);
    if (spec.hasOptions) {
        handlers.set(`command_options\u0000${spec.name}`, handler);
        commandOptionDecls.push({ name: spec.name, implied: true });
    }
    return handler;
}

/**
 * 声明「本函数回答这条命令的候选查询」。
 *
 * 它与 `command({name, hasOptions: true})` 等价，用于把候选查询放在单独的函数里
 * （候选查询必须只读且快，常常与执行逻辑不是同一段代码）。
 *
 * 单独一个函数时通常不需要 `params.tokens`；要用也行，它在候选查询这条路上恒为 `null`
 * （与 `hasOptions: true` 那条入口同一套约定）。
 *
 * @param {string} commandName 命令名
 * @param {Function} handler 处理函数，签名 `(params, ctx)`，返回候选列表
 * @returns {Function} 同一个处理函数
 */
function commandOptions(commandName, handler) {
    if (commandOptionDecls.some((item) => item.name === commandName && item.implied)) {
        throw new ScriptError(`命令 ${commandName} 已用 hasOptions 声明候选查询，不要重复声明`);
    }
    if (commandOptionDecls.some((item) => item.name === commandName)) {
        throw new ScriptError(`命令 ${commandName} 重复声明候选查询`);
    }
    commandOptionDecls.push({ name: commandName, implied: false });
    handlers.set(`command_options\u0000${commandName}`, handler);
    return handler;
}

/**
 * 声明一个类型级扩展点贡献。
 *
 * 支持的类型：`prompt` / `status_line` / `panel` / `permission` /
 * `session_persist` / `session_restore` / `session_delete` / `compaction` /
 * `tool_argument_pre` / `tool_result_post` / `turn_context` /
 * `session_before_close` / `session_before_fork` / `compaction_pre` /
 * `tool_activation` / `request_tuning` / `aging_strategy` / `input_transform` / `turn_before`。
 * 带路由键的点（`model_catalog` 的 provider 名、`input_directive` 的标记）用 `handler`。
 *
 * 同一类型只能声明一个函数：清单里的 `contributions` 是「类型名集合」，
 * 它表达不了「同一个类型挂两个函数」，因此第二个声明会被当场拒绝，
 * 而不是留到运行期变成「其中一个函数永远不会被调用」。
 *
 * 处理函数必须只读且快（`status_line` 与 `panel` 在界面渲染线程内联执行），
 * 且**不得发布事件**。
 *
 * @param {string} typeName 扩展点类型名
 * @param {Function} handler 处理函数，签名 `(params, ctx)`
 * @returns {Function} 同一个处理函数
 */
function contributes(typeName, handler) {
    if (contributionTypes.has(typeName)) {
        throw new ScriptError(`贡献类型重复声明: ${typeName}`);
    }
    contributionTypes.add(typeName);
    // 路由键就是类型名自身：类型级扩展点没有第二个维度，统一成 (类型, 类型)
    // 可以让分发逻辑只认一种键形状
    handlers.set(`${typeName}\u0000${typeName}`, handler);
    return handler;
}

/**
 * 声明订阅某个事件。
 *
 * 处理函数在事件到达时被调用，签名固定为 `(event, ctx)`：`event` 是事件字段表
 * （含 `event` 名字、`eventId`、`occurredAt`、`sessionId`，以及该事件的标量业务字段），
 * `ctx` 是 {@link ScriptContext}。返回值被忽略——事件是通知，没有「回答」这回事。
 *
 * **事件可以丢**，因此不要把它当成可靠投递：宿主队列满、网关没在运行、
 * 这个脚本的 worker 正忙或还没起过，事件都会跳过。需要可靠性的逻辑请写成工具。
 *
 * 处理器抛出的异常只记 stderr，不影响其它事件、也不影响在途调用：
 * 一个坏处理器不该把整个脚本带走。事件名必须是内核认识的（见宿主文档），
 * 写错会在清单校验时被拒绝。
 *
 * @param {...string} eventNames 事件名，可传多个
 * @returns {Function} 一个把处理函数原样返回的包装，便于 `module.exports` 风格使用
 */
function subscribe(...eventNames) {
    return (handler) => {
        for (const name of eventNames) {
            // **重复订阅必须当场拒绝**，与工具/命令/贡献/周期任务同一个口径：
            // 事件到处理器是一对一映射，后一个会**静默盖掉**前一个——现象是「那个处理器
            // 好像从来没被调用过」，而清单里只列了一个事件名，校验与日志都不会有任何提示。
            // 真要在一个事件上做两件事，就写成一个函数里依次做
            if (handlers.has(`event\u0000${name}`)) {
                throw new ScriptError(`事件 ${name} 重复订阅`);
            }
            if (subscriptions.indexOf(name) < 0) {
                subscriptions.push(name);
            }
            handlers.set(`event\u0000${name}`, handler);
        }
        return handler;
    };
}

/**
 * 声明一个周期任务。
 *
 * 宿主（桥接插件）会按 `intervalSeconds` 的节奏调用它，**到点由宿主的定时器发起**：
 * 脚本自己没有主循环、进程空闲还会被回收，因此脚本无法自行计时——这是在脚本侧做定时
 * 唯一的形态。处理函数签名固定为 `(params, ctx)`（也可以不声明参数），返回值被忽略。
 *
 * **成功之后宿主会代发一次「界面内容失效」事件**：脚本发布不了那个事件
 * （见 {@link EMITTABLE_EVENTS}），而周期任务的语义就是「我后台更新了自己贡献的内容」，
 * 因此这一步由宿主代劳。没有它，定时抓到的新数据只会写进文件，而屏上那块面板不会自己重画。
 *
 * `intervalSeconds` 缺省 `5`、**下限 1 秒**。下限存在的理由不是安全，而是诚实：
 * 宿主对界面内容的显示粒度就是秒级，比它更快的刷新只是重复问同一个数字。
 * 不写这个键时由宿主按缺省与用户配置决定；写了的会被写进清单，而且还能被用户的配置段覆写：
 * `plugins.configurations.<桥接插件>.scripts.<脚本 id>.schedules.<任务名>.intervalSeconds`
 * （配了越界值会回落声明值并告警，不会让脚本起不来）。
 *
 * **失败只记日志、并且不会让这个任务停掉**，但也不会发失效事件——数据没变，
 * 发一次只会让所有面板白跑一遍。因此任务体应当自己做该做的容错。
 *
 * ```js
 * const refresh = periodic({ name: 'refresh', intervalSeconds: 60 }, (params, ctx) => {
 *     return loadData();   // 返回值被忽略：成果要写进自己贡献的内容或文件里
 * });
 * ```
 *
 * @param {{name: string, intervalSeconds: number}} spec 声明：`name` 必填且在本脚本内唯一（宿主按它路由）
 * @param {Function} handler 处理函数，签名 `(params, ctx)`
 * @returns {Function} 同一个处理函数
 */
function periodic(spec, handler) {
    requireName(spec, 'periodic');
    const name = spec.name;
    if (schedules.some((item) => item.name === name)) {
        throw new ScriptError(`周期任务重复声明: ${name}`);
    }
    const entry = { name: name };
    // `null` 与 `undefined` 同义（都是「没给」），与 Python 侧那个缺省值 `None` 取值一致
    if (spec.intervalSeconds !== undefined && spec.intervalSeconds !== null) {
        if (!Number.isInteger(spec.intervalSeconds)) {
            throw new ScriptError(`周期任务 ${name} 的间隔必须是整数秒: `
                + `${JSON.stringify(spec.intervalSeconds)}`);
        }
        if (spec.intervalSeconds < 1) {
            throw new ScriptError(`周期任务 ${name} 的间隔不能小于 1 秒: ${spec.intervalSeconds}`);
        }
        entry.intervalSeconds = spec.intervalSeconds;
    }
    schedules.push(entry);
    handlers.set(`periodic\u0000${name}`, handler);
    return handler;
}

/**
 * 声明一个带路由键的处理器（目前只有 `model_catalog` 需要）。
 *
 * 路由键由**用户配置**决定（provider 名），脚本无法从自己的声明里推出来，因此必须显式写出来。
 * 它对应清单里的 `handlers` 条目，处理函数走的是「同键唯一」那一路（与工具、命令同构）。
 *
 * @param {{type: string, route: string}} spec 类型名与路由键
 * @param {Function} fn 处理函数，签名 `(params, ctx)`
 * @returns {Function} 同一个处理函数
 */
function handler(spec, fn) {
    const type = spec && spec.type;
    const route = spec && spec.route === undefined ? null : String(spec.route).trim();
    if (!type || !route) {
        throw new ScriptError('handler 需要非空的 type 与 route');
    }
    if (handlerDecls.some((item) => item.type === type && item.route === route)) {
        throw new ScriptError(`处理器重复声明: ${type} route=${route}`);
    }
    handlerDecls.push({ type: type, route: route });
    handlers.set(`${type}\u0000${route}`, fn);
    return fn;
}

/**
 * 返回全部声明，供 worker 做清单校验与 `--dump-manifest` 使用。
 *
 * @returns {object} 声明表
 */
function declarations() {
    return {
        tools: tools,
        commands: commands,
        commandOptions: commandOptionDecls,
        contributions: Array.from(contributionTypes).sort(),
        events: subscriptions.slice(),
        handlers: handlerDecls.map((item) => Object.assign({}, item)),
        schedules: schedules.map((item) => Object.assign({}, item)),
    };
}

/**
 * 把声明还原成 manifest。
 *
 * 只输出清单需要的字段：`handler` 这类只有运行期才有意义的东西不能进清单，
 * 否则清单结构就不再是「协议定义的一份数据」。
 *
 * @param {string} scriptId 脚本标识，可为空
 * @param {string} entry 入口文件名
 * @returns {object} 清单
 */
function dumpManifest(scriptId, entry) {
    const manifest = { entry: entry || 'main.js' };
    if (scriptId) {
        manifest.id = scriptId;
    }
    manifest.tools = tools.map((item) => ({
        name: item.name,
        description: item.description,
        parameters: item.parameters,
        required: item.required,
    }));
    manifest.commands = commands.map((item) => ({
        name: item.name,
        descriptor: item.descriptor,
        hasOptions: item.hasOptions,
    }));
    manifest.commandOptions = commandOptionDecls
        .filter((item) => !item.implied)
        .map((item) => ({ name: item.name }));
    if (contributionTypes.size > 0) {
        manifest.contributions = Array.from(contributionTypes).sort();
    }
    if (subscriptions.length > 0) {
        manifest.events = subscriptions.slice();
    }
    if (handlerDecls.length > 0) {
        manifest.handlers = handlerDecls.map((item) => Object.assign({}, item));
    }
    if (schedules.length > 0) {
        // 条目的 `name` 与可选的 `intervalSeconds` 在这里逐个点名：清单结构是协议的一部分，
        // 不能靠「声明表里恰好没多别的键」来保证
        manifest.schedules = schedules.map((item) => {
            const entry = { name: item.name };
            if (item.intervalSeconds !== undefined) {
                entry.intervalSeconds = item.intervalSeconds;
            }
            return entry;
        });
    }
    return manifest;
}

/**
 * 把声明与清单比一遍。
 *
 * 返回问题描述列表（空列表表示一致）。**比较的是名字集合而不是整份清单**：
 * 描述文本、参数 Schema 属于「给人看的信息」，改了它们不需要脚本作者同步改清单，
 * 而名字集合一旦不一致，模型看到的就是一份不存在的工具定义。
 *
 * 两种形态都接受：完整清单（`{name: ...}` 对象数组，即 `dumpManifest()` 的产物）
 * 与名字摘要（字符串数组，即宿主下发的那份）。宿主只下发名字，是因为它要判断的
 * 就是名字集合，带上整份清单会逼着网关跟随清单 schema 的每次演进。
 *
 * @param {object} manifest 清单或清单摘要
 * @returns {string[]} 问题描述，空数组表示一致
 */
function compareWith(manifest) {
    const problems = [];
    const source = manifest || {};
    compareNames(problems, 'tools', tools.map((item) => item.name), namesOf(source.tools));
    compareNames(problems, 'commands', commands.map((item) => item.name), namesOf(source.commands));
    // 只比显式声明的候选查询：`hasOptions` 的那部分由 commands 表达，
    // 清单里也不重复列（两者同时出现本来就被判为冲突）
    compareNames(problems, 'commandOptions',
        commandOptionDecls.filter((item) => !item.implied).map((item) => item.name),
        namesOf(source.commandOptions));
    compareNames(problems, 'contributions', Array.from(contributionTypes).sort(),
        listOf(source.contributions));
    compareNames(problems, 'events', subscriptions.slice(), listOf(source.events));
    // 路由处理器按 `type::route` 比：宿主下发的是这个键，路由名写错一样是「模型调不到」
    compareNames(problems, 'handlers',
        handlerDecls.map((item) => `${item.type}::${item.route}`),
        listOf(source.handlers));
    // 周期任务按名字比：名字写错的表现是「任务静默不跑」，而清单校验是唯一会有人看的地方
    compareNames(problems, 'schedules', schedules.map((item) => item.name), namesOf(source.schedules));
    return problems;
}

/**
 * 把「对象数组」或「字符串数组」都归一成名字列表。
 *
 * @param {*} value 待归一的值
 * @returns {string[]} 名字列表
 */
function namesOf(value) {
    const names = [];
    for (const item of listOf(value)) {
        if (item && typeof item === 'object') {
            if (item.name !== undefined && item.name !== null) {
                names.push(item.name);
            }
        } else if (item !== undefined && item !== null) {
            names.push(item);
        }
    }
    return names;
}

/**
 * 比较两边的名字集合，把差异写进问题列表。
 *
 * @param {string[]} problems 问题列表
 * @param {string} label 维度名
 * @param {string[]} declared 代码里声明的名字
 * @param {string[]} expected 清单里声明的名字
 */
function compareNames(problems, label, declared, expected) {
    const left = new Set(declared);
    const right = new Set(expected);
    const missing = declared.filter((name) => !right.has(name)).sort();
    const extra = expected.filter((name) => !left.has(name)).sort();
    if (missing.length > 0) {
        problems.push(`${label}: 代码里声明了但清单没有 ${missing.join(', ')}`);
    }
    if (extra.length > 0) {
        problems.push(`${label}: 清单声明了但代码没有 ${extra.join(', ')}`);
    }
}

/**
 * 扩展点类型名 → (实参构造, 结果整形)。
 *
 * 集中成一张表是为了让「每个扩展点怎么被调用、返回值要长什么样」一眼可见。
 * 若散落成 if/else，新增一个扩展点时最容易漏掉的恰恰是「结果形状」那一半，
 * 而漏掉的后果是宿主拿到一个形状不对的载荷后静默地当成「脚本没返回内容」。
 */
/**
 * 扩展点实参构造与结果整形：键名来自 extension-points.json 的 args / shape 字段。
 *
 * 「有哪些扩展点、每个用哪个实参构造与整形」这份事实只有一份，就在那个 JSON 里；
 * 这里只按它引用到的键提供实现。因此新增一个扩展点时，只要能复用既有键，
 * 本文件一行都不用改——那份名单也不会再有第二份抄写。
 */
const ARGS = new Map();
const SHAPES = new Map();

/** 能力档文件位置：与 SDK 同目录（网关资源抽取目录下的 script/）。 */
const CAPABILITY_FILE = path.join(HERE, 'extension-points.json');

/**
 * 登记一个键对应的实参构造。
 *
 * @param {string} key 键名（来自能力档的 args 字段）
 * @param {Function} builder 载荷 → 处理函数的第一个参数
 */
function defineArgs(key, builder) {
    ARGS.set(key, builder);
}

/**
 * 登记一个键对应的结果整形。
 *
 * @param {string} key 键名（来自能力档的 shape 字段）
 * @param {Function} shaper 处理函数返回值 → 协议结果
 */
function defineShape(key, shaper) {
    SHAPES.set(key, shaper);
}

/**
 * 按能力档构建「类型名 → (实参构造, 结果整形)」。
 *
 * 文件缺失、或引用了本 SDK 没有实现的键时**当场报错**：那意味着 SDK 与能力档不是同一版，
 * 静默降级只会把问题推迟到某次调用上，以「脚本没返回内容」的形态出现。
 *
 * @returns {Map<string, {buildParams: Function, shape: Function}>} 分发表
 */
function loadDispatch() {
    let table;
    try {
        table = JSON.parse(fs.readFileSync(CAPABILITY_FILE, 'utf8'));
    } catch (error) {
        throw new ScriptError(`读取扩展点能力档失败: ${CAPABILITY_FILE} (${error.message})`);
    }
    const built = new Map();
    for (const entry of table.in || []) {
        const argsKey = entry.args;
        const shapeKey = entry.shape;
        if (!ARGS.has(argsKey) || !SHAPES.has(shapeKey)) {
            throw new ScriptError(`扩展点能力档引用了 SDK 未实现的键: ${entry.type} `
                + `(args=${argsKey}, shape=${shapeKey})；SDK 与能力档版本不一致`);
        }
        built.set(entry.type, { buildParams: ARGS.get(argsKey), shape: SHAPES.get(shapeKey) });
    }
    return built;
}

/**
 * 把结果整形成「一个映射」，不是对象时给出缺省值。
 *
 * @param {*} result 处理函数返回值
 * @param {object} fallback 缺省值
 * @returns {object} 映射或缺省值
 */
function asMapping(result, fallback) {
    const value = fallback === undefined ? null : fallback;
    return result !== null && typeof result === 'object' && !Array.isArray(result) ? result : value;
}

// ---- 工具 -----------------------------------------------------------------

defineArgs('tool', (payload) => ({ args: payload.arguments || {} }));

// 任意 JSON 都能作为 output：字符串、数字、对象、数组都合法，
// 因为内核的截断与序列化对它们的处理是统一的（见 ToolCodec）
defineShape('tool', (result) => {
    if (result instanceof ToolResult) {
        const payload = { output: result.output };
        if (Object.keys(result.metadata).length > 0) {
            payload.metadata = result.metadata;
        }
        return payload;
    }
    return { output: result === undefined ? null : result };
});

// ---- 命令 -----------------------------------------------------------------

defineArgs('command', (payload) => {
    const arguments_ = payload.arguments || {};
    return { tokens: listOf(arguments_.tokens), raw: arguments_.raw || '' };
});

defineShape('command', (result) => {
    if (result === undefined || result === null) {
        return { kind: 'OK', output: null };
    }
    if (typeof result === 'string') {
        return { kind: 'OK', output: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('命令返回值必须是字符串或 {kind, output, choices}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.kind === undefined) {
        shaped.kind = 'OK';
    }
    return shaped;
});

// ---- 命令候选查询 ---------------------------------------------------------

// 与执行那条路**同一套形状**，只是两个实参为 null：约定「tokens === null ⇒ 这次是候选查询」。
// 早先这里给的是空对象，于是「同一个函数回答两条路」（hasOptions: true）在用户按下补全键时
// 才会炸，而报出来的是与候选查询看不出关系的 undefined 相关错误
defineArgs('command_options', () => ({ tokens: null, raw: null }));

defineShape('choices', (result) => {
    if (result === undefined || result === null) {
        return { choices: [] };
    }
    if (Array.isArray(result)) {
        return { choices: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('候选查询返回值必须是列表或 {choices: [...]}');
    }
    if (mapping.choices === undefined) {
        // 缺 choices 是最难查的一种写法：hasOptions 的函数忘了按 tokens === null 分支时，
        // 返回的正是执行结果（常常是 {kind, output}），而补齐缺省会让它安静地变成
        // 「没有候选」——选择页空着，没有任何报错
        throw new ScriptError('候选查询返回值里必须有 choices 键：返回候选列表，或 {choices: [...]}');
    }
    return Object.assign({}, mapping);
});

// ---- 只返回一段文本的贡献 -------------------------------------------------

/**
 * 把「一段文本」整形成协议形状。
 *
 * @param {*} result 处理函数返回值
 * @returns {object|null} 形状化结果
 */
function shapeText(result) {
    if (result === undefined || result === null) {
        return null;
    }
    if (typeof result === 'string') {
        return { text: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('该扩展点返回值必须是字符串、{text: ...} 或 null');
    }
    return mapping;
}

defineArgs('none', () => ({}));
defineShape('text', shapeText);

// ---- 面板 -----------------------------------------------------------------

defineShape('panel', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (Array.isArray(result)) {
        return { lines: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('面板返回值必须是 null、{title, region, lines} 或行列表');
    }
    return mapping;
});

// ---- 权限拦截 -------------------------------------------------------------

defineShape('permission', (result) => {
    if (result === undefined || result === null || result === false) {
        return { verdict: 'ABSTAIN' };
    }
    if (result === true) {
        return { verdict: 'DENY' };
    }
    if (typeof result === 'string') {
        if (result.trim().toLowerCase() === 'ask') {
            return { verdict: 'ASK' };
        }
        return { verdict: 'DENY', reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError("权限拦截返回值必须是 null、布尔、'ask'、原因字符串或 {verdict, reason}");
    }
    if (Object.keys(mapping).length === 0) {
        return { verdict: 'ABSTAIN' };
    }
    if (mapping.verdict !== undefined) {
        return shapedVerdict(mapping);
    }
    if (mapping.denied !== undefined) {
        // 旧写法：denied=true 等价 DENY
        const shaped = { verdict: mapping.denied ? 'DENY' : 'ABSTAIN' };
        if (mapping.reason !== undefined && mapping.reason !== null) {
            shaped.reason = mapping.reason;
        }
        return shaped;
    }
    throw new ScriptError('权限拦截返回值含未知键，允许：verdict / reason（旧写法为 denied / reason）');
});

/**
 * 校验并归一 `{verdict, reason}` 写法。
 *
 * 没有「放行」这一态：脚本只能收紧，不能放宽内核已经允许的调用。
 * 未知裁定一律报错而不是静默按无异议处理——内核随后按拒绝处理：
 * 宁可这次调用被拒（可见、可重试），也不让一道本该有人看的调用静默放行。
 *
 * @param {Record<string, unknown>} mapping 脚本返回的映射
 * @returns {Record<string, unknown>} 协议载荷
 */
function shapedVerdict(mapping) {
    const verdict = mapping.verdict;
    if (typeof verdict !== 'string' || !PERMISSION_VERDICTS.includes(verdict.trim().toUpperCase())) {
        throw new ScriptError(
            `verdict 必须是 ${PERMISSION_VERDICTS.join(' / ')}，实际是 ${JSON.stringify(verdict)}`
        );
    }
    const shaped = { verdict: verdict.trim().toUpperCase() };
    if (mapping.reason !== undefined && mapping.reason !== null) {
        shaped.reason = mapping.reason;
    }
    return shaped;
}

// ---- 会话持久化 / 恢复 / 删除 ---------------------------------------------

/**
 * 三个没有结果类型的扩展点的整形函数。
 *
 * 有返回值一律忽略而不是报错：「多返回了一个东西」不该让一次已经完成的持久化看起来失败。
 *
 * @returns {null} 恒为 `null`
 */
function shapeNothing() {
    return null;
}

defineArgs('snapshot', (payload) => ({ snapshot: payload.snapshot }));
defineShape('nothing', shapeNothing);

defineShape('sessions', (result) => {
    if (result === undefined || result === null) {
        return { sessions: [] };
    }
    if (Array.isArray(result)) {
        return { sessions: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('会话恢复返回值必须是列表或 {sessions: [...]}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.sessions === undefined) {
        shaped.sessions = [];
    }
    return shaped;
});

defineArgs('session_id', (payload) => ({ sessionId: payload.sessionId }));

// ---- 压缩策略 -------------------------------------------------------------

defineArgs('request', (payload) => Object.assign({}, payload || {}));
defineShape('mapping', (result) => asMapping(result, null));

// ---- 二期打通的能力档（数据进出、不在渲染线程/启动期）------------------------

/** 参数改写能表达的三态；刻意没有「放行」——改写之后照旧要过权限判定。 */
const ARGUMENT_OUTCOMES = Object.freeze(['ABSTAIN', 'REPLACE', 'DENY']);

/**
 * 参数改写裁定的整形。
 *
 * 接受的写法：`null` / `false`（不改）、`true`（拒绝）、原因字符串（带理由的拒绝）、
 * `{arguments: {...}}`（替换）、`{outcome, arguments, reason}`。
 * 未知 outcome 一律报错而不是静默按「不改」处理。
 */
defineShape('argument_decision', (result) => {
    if (result === undefined || result === null || result === false) {
        return { outcome: 'ABSTAIN' };
    }
    if (result === true) {
        return { outcome: 'DENY' };
    }
    if (typeof result === 'string') {
        return { outcome: 'DENY', reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('参数改写返回值必须是 null、布尔、原因字符串或 {outcome, arguments, reason}');
    }
    if (mapping.outcome !== undefined) {
        const outcome = String(mapping.outcome || '').trim().toUpperCase();
        if (!ARGUMENT_OUTCOMES.includes(outcome)) {
            throw new ScriptError(`outcome 必须是 ${ARGUMENT_OUTCOMES.join(' / ')}，`
                + `实际是 ${JSON.stringify(mapping.outcome)}`);
        }
        const shaped = { outcome: outcome };
        if (outcome === 'REPLACE') {
            shaped.arguments = mapping.arguments || {};
        }
        if (mapping.reason !== undefined && mapping.reason !== null) {
            shaped.reason = mapping.reason;
        }
        return shaped;
    }
    if (mapping.arguments !== undefined) {
        return { outcome: 'REPLACE', arguments: mapping.arguments || {} };
    }
    throw new ScriptError('参数改写返回值含未知键，允许：outcome / arguments / reason');
});

defineArgs('tool_argument_pre', (payload) => ({
    agentId: payload.agentId === undefined ? null : payload.agentId,
    toolName: payload.toolName,
    arguments: payload.arguments || {},
    source: payload.source,
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

/**
 * 结果整形的整形：`null` 表示不改；`{output, metadata}` 只改给出的那一项。
 *
 * **输出保持原始类型**：字符串就是字符串、结构化对象就是结构化对象。
 */
defineShape('result_adjustment', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('结果整形返回值必须是 null 或 {output, metadata}');
    }
    const shaped = {};
    if (mapping.output !== undefined && mapping.output !== null) {
        shaped.output = mapping.output;
    }
    if (mapping.metadata !== undefined && mapping.metadata !== null) {
        shaped.metadata = mapping.metadata;
    }
    return Object.keys(shaped).length > 0 ? shaped : null;
});

defineArgs('tool_result_post', (payload) => ({
    agentId: payload.agentId === undefined ? null : payload.agentId,
    toolName: payload.toolName,
    arguments: payload.arguments || {},
    output: payload.output === undefined ? null : payload.output,
    metadata: payload.metadata || {},
    failed: Boolean(payload.failed),
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

defineShape('turn_context', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (typeof result === 'string') {
        return { text: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('回合上下文返回值必须是 null、字符串或 {text: ...}');
    }
    return mapping;
});

defineArgs('turn_context', (payload) => ({
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
    userInput: payload.userInput || '',
    nested: Boolean(payload.nested),
}));

/** 生命周期裁定的整形：`null` / `false` 放行，`true` 拦下，字符串是带理由的拦下。 */
defineShape('lifecycle_verdict', (result) => {
    if (result === undefined || result === null || result === false) {
        return { cancel: false };
    }
    if (result === true) {
        return { cancel: true };
    }
    if (typeof result === 'string') {
        return { cancel: true, reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('生命周期裁定必须是 null、布尔、原因字符串或 {cancel, reason}');
    }
    const shaped = { cancel: Boolean(mapping.cancel) };
    if (mapping.reason !== undefined && mapping.reason !== null) {
        shaped.reason = mapping.reason;
    }
    return shaped;
});

defineArgs('session_before_close', (payload) => ({
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
    agentId: payload.agentId === undefined ? null : payload.agentId,
    reason: payload.reason,
    vetoSupported: Boolean(payload.vetoSupported),
}));

defineArgs('session_before_fork', (payload) => ({
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
    agentId: payload.agentId === undefined ? null : payload.agentId,
    messageId: payload.messageId === undefined ? null : payload.messageId,
    cutIndex: payload.cutIndex,
    messageCount: payload.messageCount,
}));

/** 压缩指令的整形：`null` 放行，`{cancel: true}` 拦下，`{keepRecent: n}` 改保留条数。 */
defineShape('compaction_directive', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('压缩指令必须是 null 或 {cancel, reason, keepRecent}');
    }
    if (mapping.cancel) {
        const shaped = { cancel: true };
        if (mapping.reason !== undefined && mapping.reason !== null) {
            shaped.reason = mapping.reason;
        }
        return shaped;
    }
    if (mapping.keepRecent !== undefined && mapping.keepRecent !== null) {
        return { keepRecent: mapping.keepRecent };
    }
    return null;
});

defineArgs('compaction_pre', (payload) => ({
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
    trigger: payload.trigger,
    messageCount: payload.messageCount,
    tokensBefore: payload.tokensBefore,
    keepRecentMessages: payload.keepRecentMessages,
    previousBoundaryMessageId: payload.previousBoundaryMessageId === undefined
        ? null : payload.previousBoundaryMessageId,
}));

/** 模型目录的整形：空列表含义是「我不表态」，回落成配置里的 models。 */
defineShape('model_catalog', (result) => {
    if (result === undefined || result === null) {
        return { models: [] };
    }
    if (Array.isArray(result)) {
        return { models: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('模型目录返回值必须是 null、列表或 {models: [...]}');
    }
    const shaped = Object.assign({}, mapping);
    if (shaped.models === undefined) {
        shaped.models = [];
    }
    return shaped;
});

defineArgs('model_catalog', (payload) => ({
    providerName: payload.providerName,
    providerType: payload.providerType === undefined ? null : payload.providerType,
}));

// ---- 热路径与提交路径的扩展点（三期）-------------------------------------

/**
 * 工具激活裁定的整形。
 *
 * `null` = 不表态（后续插件继续）；`true` = 明确要它可见（能压过后面的插件）；
 * `false` / 原因字符串 = 明确隐藏。布尔会把「不想管」与「明确要它可见」变成同一个值，
 * 因此这里把它们区分开。
 */
defineShape('tool_activation', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (result === true) {
        return { visible: true };
    }
    if (result === false) {
        return { hidden: true };
    }
    if (typeof result === 'string') {
        return { hidden: true, reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('工具激活返回值必须是 null、布尔、原因字符串或 {visible, hidden, reason}');
    }
    if (mapping.visible) {
        return { visible: true };
    }
    if (mapping.hidden) {
        const shaped = { hidden: true };
        if (mapping.reason !== undefined && mapping.reason !== null) {
            shaped.reason = mapping.reason;
        }
        return shaped;
    }
    return null;
});

defineArgs('tool_activation', (payload) => ({
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
    agentId: payload.agentId === undefined ? null : payload.agentId,
    toolName: payload.toolName,
    description: payload.description === undefined ? null : payload.description,
}));

/** 请求调优的整形：`null` 表示不改；只读已知字段（cacheKey / cacheRetention / cacheBreakpoints）。 */
defineShape('request_tuning', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('请求调优返回值必须是 null 或 {cacheKey, cacheRetention, cacheBreakpoints}');
    }
    return mapping;
});

defineArgs('request_tuning', (payload) => ({
    providerType: payload.providerType === undefined ? null : payload.providerType,
    modelId: payload.modelId === undefined ? null : payload.modelId,
    defaultCacheKey: payload.defaultCacheKey === undefined ? null : payload.defaultCacheKey,
    messageCount: payload.messageCount,
    toolCount: payload.toolCount,
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

/** 老化策略的整形：`null` 表示不改；只读已知字段。 */
defineShape('aging_strategy', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('老化策略返回值必须是 null 或 {keepRecentMessages, agingPercent, stubText}');
    }
    return mapping;
});

defineArgs('aging_strategy', (payload) => ({
    messageCount: payload.messageCount,
    usedTokens: payload.usedTokens,
    budgetTokens: payload.budgetTokens,
    compressionBoundary: payload.compressionBoundary,
    defaultKeepRecentMessages: payload.defaultKeepRecentMessages,
    defaultAgingPercent: payload.defaultAgingPercent,
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

/**
 * 输入改写的整形：`null` 不改；字符串 = 改成这句；
 * `{handled: true, notice: ...}` = 接过去、不进对话（像命令那样，只给用户一句说明）。
 */
defineShape('input_transform', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (typeof result === 'string') {
        return { text: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('输入改写返回值必须是 null、字符串或 {text, handled, notice}');
    }
    if (mapping.handled) {
        const shaped = { handled: true };
        if (mapping.notice !== undefined && mapping.notice !== null) {
            shaped.notice = mapping.notice;
        }
        return shaped;
    }
    if (mapping.text !== undefined && mapping.text !== null) {
        return { text: mapping.text };
    }
    return null;
});

defineArgs('input_transform', (payload) => ({
    text: payload.text || '',
    source: payload.source,
    hasSession: Boolean(payload.hasSession),
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

/** 回合前指令的整形：`null` 放行；`true` / 原因字符串 = 拦下；`{input: ...}` = 改写输入。 */
defineShape('turn_before', (result) => {
    if (result === undefined || result === null || result === false) {
        return null;
    }
    if (result === true) {
        return { cancel: true };
    }
    if (typeof result === 'string') {
        return { cancel: true, reason: result };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('回合前指令必须是 null、布尔、原因字符串或 {cancel, input}');
    }
    if (mapping.cancel) {
        const shaped = { cancel: true };
        if (mapping.reason !== undefined && mapping.reason !== null) {
            shaped.reason = mapping.reason;
        }
        return shaped;
    }
    if (mapping.input !== undefined && mapping.input !== null) {
        return { input: mapping.input };
    }
    return null;
});

defineArgs('turn_before', (payload) => ({
    agentId: payload.agentId === undefined ? null : payload.agentId,
    input: payload.input || '',
    nested: Boolean(payload.nested),
    depth: payload.depth,
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

/**
 * 输入指令的整形：`null` / `{unclaimed: true}` 不认领；字符串或 `{toolName, arguments}`
 * 声明一次工具调用（真正的执行走内核完整的权限与审批链）。
 */
defineShape('input_directive', (result) => {
    if (result === undefined || result === null) {
        return null;
    }
    if (typeof result === 'string') {
        return { toolName: result, arguments: {} };
    }
    const mapping = asMapping(result, null);
    if (mapping === null) {
        throw new ScriptError('输入指令返回值必须是 null、工具名字符串或 {toolName, arguments}');
    }
    if (mapping.unclaimed || !mapping.toolName) {
        return null;
    }
    return { toolName: mapping.toolName, arguments: mapping.arguments || {} };
});

defineArgs('input_directive', (payload) => ({
    marker: payload.marker,
    input: payload.input || '',
    sessionId: payload.sessionId === undefined ? null : payload.sessionId,
}));

// ---- 事件 -----------------------------------------------------------------

defineArgs('event', (payload) => ({ event: payload }));

// ---- 周期任务 -------------------------------------------------------------

defineArgs('periodic', (payload) => ({ name: payload.name }));

/**
 * 类型名 → (实参构造, 结果整形)。
 *
 * 事件与周期任务都**不是扩展点**（它们是协议里的方法：前者由内核通知脚本，
 * 后者由宿主定时器发起），因此能力档里没有它们；但两者走同一张分发表，
 * 所以要在加载能力档之后各补一条。
 */
const dispatch = loadDispatch();
dispatch.set('event', {
    buildParams: ARGS.get('event'),
    // 事件处理器的返回值没有去处：事件是通知，不是请求。返回 null 让调用方
    // 不必因为脚本「顺手 return 了一个值」而报错
    shape: () => null,
});
dispatch.set('periodic', {
    buildParams: ARGS.get('periodic'),
    // 周期任务的返回值同样没有接收方：它不进任何人的上下文，也不落盘
    shape: shapeNothing,
});

/**
 * 按类型分发一次调用。
 *
 * 处理函数抛出的任何异常都由调用方（worker）转成协议错误：脚本失败必须走
 * 「失败」这条路，而不是返回一个看起来正常的空结果——后者会让模型以为
 * 「脚本说没有内容」，问题就此静默。
 *
 * **handler 可以是 async 的**（返回 Promise），这里会等它；同步 handler 行为不变。
 * 因此本函数返回 Promise，调用方必须 `await`。
 *
 * @param {string} scriptId 脚本标识，用于填上下文
 * @param {string} typeName 扩展点类型名
 * @param {string} routeKey 路由键（工具名 / 命令名）；类型级扩展点用类型名
 * @param {object} payload 请求载荷
 * @param {Function|null} emitter 事件发布回调（由 worker 注入）；`null` 表示当前上下文不支持发布事件
 * @returns {Promise<*>} 结果载荷，可为 `null`
 */
async function invoke(scriptId, typeName, routeKey, payload, emitter) {
    const lookupKey = routeKey === undefined || routeKey === null ? typeName : routeKey;
    const handler = handlers.get(`${typeName}\u0000${lookupKey}`);
    if (!handler) {
        throw new ScriptError(`没有处理 ${typeName}=${lookupKey} 的函数`);
    }
    const entry = dispatch.get(typeName);
    if (!entry) {
        throw new ScriptError(`未知的扩展点类型: ${typeName}`);
    }
    const context = new ScriptContext(scriptId, payload, emitter);
    const result = await handler(entry.buildParams(payload || {}), context);
    return entry.shape ? entry.shape(result) : null;
}

/**
 * 校验声明里必须有名字。
 *
 * @param {object} spec 声明
 * @param {string} kind 声明种类，用于报错
 */
function requireName(spec, kind) {
    if (!spec || typeof spec.name !== 'string' || spec.name.trim() === '') {
        throw new ScriptError(`${kind} 声明缺少非空的 name`);
    }
}

/**
 * 把可空值归一成数组。
 *
 * @param {*} value 待归一的值
 * @returns {Array} 数组，保证非 `null`
 */
function listOf(value) {
    if (value === undefined || value === null) {
        return [];
    }
    return Array.isArray(value) ? value.slice() : [value];
}

module.exports = {
    EMITTABLE_EVENTS,
    ScriptError,
    ToolResult,
    ensureResolvable,
    ScriptContext,
    configuration,
    setConfiguration,
    tool,
    handler,
    command,
    commandOptions,
    contributes,
    subscribe,
    periodic,
    declarations,
    dumpManifest,
    compareWith,
    invoke,
};
