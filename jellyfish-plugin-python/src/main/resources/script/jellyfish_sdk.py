# -*- coding: utf-8 -*-
"""Jellyfish Python 脚本插件 SDK。

脚本作者只与本模块打交道：用装饰器声明「我提供了什么」，剩下的（进程、协议、清单校验、
熔断、回收）都由宿主负责。因此本模块刻意不感知协议细节，只做三件事：

1. **声明**：把 ``@tool`` / ``@command`` / ``@contributes`` 等装饰器的参数记进注册表；
2. **分发**：按扩展点类型把请求交给对应的函数，并把返回值整形成协议要求的形状；
3. **生成清单**：``dump_manifest()`` 把注册表还原成 ``manifest.json``。

声明与清单必须一致，这是本方案唯一的高危点，因此两者都从这里出发：清单由代码生成，
而不是另写一份。严格校验（``manifestStrict``）在 worker 启动时把两者比一遍，
不一致就拒绝服务——宁可报错，也不要让模型按一份不存在的工具定义去调用。
"""

import json
import inspect
import os
import sys

# ---------------------------------------------------------------- 声明注册表

# 扩展点类型名 → 处理函数。类型名与 Java 侧 codec 的 typeName() 必须逐字一致。
_HANDLERS = {}

#: 处理函数 → 它声明的关键字参数名集合（``None`` 表示接受全部，即带 ``**kwargs``）。
#: 按函数对象缓存（函数本身被 ``_HANDLERS`` 持有，不存在回收后 id 复用的问题）。
_SIGNATURE_CACHE = {}


def _accepted_kwargs(func):
    """取处理函数声明的关键字参数名。

    **为什么要看签名**：扩展点的实参由能力档的 args 函数给出，而处理器往往只用得上其中一两个
    （例如 ``session_before_close`` 只看 ``veto_supported``）。强制作者把用不到的参数也
    一个一个列进签名，是把「协议里有什么」泄到「实现要用什么」上；不列又会在调用时报
    「unexpected keyword argument」——两种都不好。

    因此这里采「声明什么就给什么」：签了名的参数才传，带 ``**kwargs`` 的收全部。
    这与命令候选查询那套签名约束同源（那里也只能给函数声明过的槽位）。

    :param func: 处理函数
    :return: 声明的参数名集合；``None`` 表示接受全部
    """
    if func in _SIGNATURE_CACHE:
        return _SIGNATURE_CACHE[func]
    try:
        parameters = inspect.signature(func).parameters
    except (TypeError, ValueError):
        # 没有可内省的签名（内建函数、某些 C 扩展）：按「收全部」处理，保持原行为
        _SIGNATURE_CACHE[func] = None
        return None
    if any(item.kind == inspect.Parameter.VAR_KEYWORD for item in parameters.values()):
        accepted = None
    else:
        accepted = frozenset(name for name in parameters if name != "ctx")
    _SIGNATURE_CACHE[func] = accepted
    return accepted

#: 候选查询调用时固定填 ``None`` 的两个实参：约定「``tokens is None`` ⇒ 这次是候选查询」。
#: 放在 SDK 而不是某个装饰器里，是为了让 ``has_options=True`` 与 ``@command_options``
#: 两个入口共用同一份约定——两处各写一遍的话，它们迟早会不一致
_OPTION_SLOTS = ("tokens", "raw")

# 工具/命令声明，顺序即声明顺序，生成的清单与它保持同序（便于人读 diff）。
_TOOLS = []
_COMMANDS = []
_COMMAND_OPTIONS = []
_SUBSCRIPTIONS = []

# 声明过的贡献类型，用于检测「同一类型声明了两个函数」。
_CONTRIBUTION_TYPES = set()

#: 带路由键、且路由键来自用户配置的处理器声明（目前只有 ``model_catalog``）。
_HANDLERS_DECL = []

# 可以发布的事件名（与 Java 侧 ScriptEventFactory 的白名单一致）。
#
# 只有两类，而且都是「自由载荷」：通知的 payload 任意 JSON，告警只有一句文本。
# 所以脚本**造不出内核语义事件**（工具完成、权限判定之类）——那类事件是内核事实的转述，
# 指标、审计与界面都按「它是真的」来消费，让脚本能造等于开了一条往审计里写假账的路。
#
# 这里再留一份的原因只是**报错更近**：不在这份清单里时在本地就提醒一句，
# 免得「事件发出去但没人收到」变成一个需要翻宿主日志的问题。
# 真正的裁决仍在宿主侧，因此这份清单哪怕过时也只会多一句提醒，不会吞掉事件。
EMITTABLE_EVENTS = frozenset(("PluginNotificationEvent", "ConfigWarningEvent"))

#: 本脚本的配置段：由 worker 在 **import 脚本之前** 注入，因此模块顶层读也拿得到。
#: 内容来自 ``plugins.configurations.<桥接插件>.scripts.<脚本 id>``，
#: 内核已完成双源合并与 ``${ENV}`` 插值。
_CONFIGURATION = {}


def set_configuration(values):
    """注入本脚本的配置段。

    由 worker 调用，脚本作者不需要也不应该调它。时机必须是「import 脚本之前」，
    否则脚本在模块顶层读 ``configuration()`` 会拿到空映射，而那种失败看起来就像
    「配置没生效」——最难归因的一类现场。

    :param values: 配置映射，可为 ``None``（等价空）
    """
    global _CONFIGURATION
    _CONFIGURATION = dict(values or {})


def configuration():
    """获取本脚本的配置段。

    与 ``ctx.configuration`` 是同一份，差别只在拿到的时机：本函数在模块顶层就能调。

    :return: 只读用途的配置映射，保证非 ``None``
    """
    return _CONFIGURATION


class ScriptError(Exception):
    """脚本侧的可预期失败。

    抛出它等于告诉宿主「这次调用失败了」，宿主会把它转成协议错误回灌给模型
    （工具调用表现为「工具执行失败：…」，回合继续）。**不要**用返回值里的
    ``{"error": ...}`` 表达失败——那会被当成正常输出，语义静默错位。
    """


class ToolResult:
    """工具返回值：正文之外再带上给界面看的元数据。

    普通返回值就是 ``output``；只有需要一行摘要或失败标记时才用它。
    ``summary`` 是轨迹行上显示的一句话（给人看，不是给模型看），``terminal`` 取不等于
    ``COMPLETED`` 的值时让界面出警示标记（例如 ``FAILED`` / ``TIMEOUT``），``metadata``
    里还可以放任意结构化事实（内核只透传、不解释）。

    它**不进模型上下文**：模型看到的仍是 ``output`` 那段文本。

    :param output: 回灌给模型的正文（任意 JSON）
    :param summary: 单行摘要，可为 ``None``
    :param terminal: 终止原因，可为 ``None``（缺省表示正常完成）
    :param metadata: 其它结构化元数据，可为 ``None``
    """

    def __init__(self, output=None, summary=None, terminal=None, metadata=None):
        self.output = output
        merged = dict(metadata or {})
        if summary is not None:
            merged["summary"] = summary
        if terminal is not None:
            merged["terminal"] = terminal
        self.metadata = merged

    def __repr__(self):
        return "ToolResult(output=%r, metadata=%r)" % (self.output, self.metadata)


class ScriptContext:
    """一次调用的上下文。

    只暴露脚本真正需要的东西：身份、会话标识与原始请求。刻意不暴露宿主对象，
    也不提供「回调宿主」的能力——脚本能做的事只有「处理这次请求并返回结果」。
    """

    def __init__(self, script_id, payload, emitter=None):
        self.script_id = script_id
        self._payload = payload or {}
        self._emitter = emitter

    @property
    def session_id(self):
        """当前会话标识；该扩展点没有会话上下文时为 ``None``。"""
        return self._payload.get("sessionId")

    @property
    def agent_id(self):
        """当前 agent 标识；仅权限拦截等扩展点会带。"""
        return self._payload.get("agentId")

    @property
    def payload(self):
        """原始请求载荷（只读用途，改它不会影响宿主）。"""
        return dict(self._payload)

    @property
    def configuration(self):
        """本脚本的配置段（``plugins.configurations.<桥接插件>.scripts.<脚本 id>``）。

        内核已完成双源合并与 ``${ENV}`` 插值，因此这里拿到的就是最终值。
        与模块级 ``configuration()`` 同一份；密钥写在这里比写在脚本目录的文件里更一致，
        也与 Java 插件的 ``PluginContext.configuration()`` 同一条通道。
        """
        return _CONFIGURATION

    @property
    def parent_session_id(self):
        """派生当前会话的那个会话；根会话（用户直接对话的那一个）为 ``None``。

        子代理有独立的会话，而协作状态往往要落在父会话上——该用哪个键只有内核知道，
        模型无从指定。与 ``session_id`` 的分工：后者是「我自己」，前者是「我属于谁」。
        """
        return self._payload.get("parentSessionId")

    @property
    def run_id(self):
        """本次调用所在的 run；顶层回合或进程级调用为 ``None``。"""
        return self._payload.get("runId")

    @property
    def root_run_id(self):
        """本次调用所在的 run 树根；不在任何 run 上时为 ``None``。"""
        return self._payload.get("rootRunId")

    def emit_event(self, name, payload=None):
        """发布一条事件。

        **尽力而为，没有返回值，也不要依赖它一定送达**：事件通道的契约是「可以丢」，
        丢弃可能是宿主队列满、网关没在运行、或没有 worker 在听。真正需要可靠传递的信息
        请用返回值。

        ``name`` 见 :data:`EMITTABLE_EVENTS`；``payload`` 是任意 JSON（仅
        ``ConfigWarningEvent`` 要求 ``message`` 非空）。宿主会校验并可能拒绝，
        拒绝只记日志——脚本侧拿不到裁决，这也是「尽力而为」的一部分。

        :param name: 事件名
        :param payload: 事件载荷，可为 ``None``
        """
        if self._emitter is None:
            raise ScriptError("当前上下文不支持发布事件")
        if name not in EMITTABLE_EVENTS:
            print("[%s] 事件 %s 不在可发布清单 %s 内，宿主会拒绝"
                  % (self.script_id, name, sorted(EMITTABLE_EVENTS)), file=sys.stderr, flush=True)
        self._emitter(name, payload or {})

    def __repr__(self):
        return "ScriptContext(script_id=%r, session_id=%r)" % (self.script_id, self.session_id)


# ---------------------------------------------------------------- 装饰器


def tool(name, description=None, parameters=None, required=None):
    """声明一个工具。

    ``parameters`` 是 JSON Schema 的 ``properties`` 部分，``required`` 是必填参数名列表：
    它们会原样进入 ``ToolDescriptor``，也就是模型看到的工具定义，因此必须与函数真正
    接受的参数一致——不一致的后果是模型按错误的签名调用，而错误只在运行期以
    「参数缺失」的形式出现。

    这里**没有** ``read_only`` 参数：哪些工具在某个模式下可用完全由用户决定
    （例如 plan 插件的 ``jellyfish.json`` 段 ``plugins.configurations.jellyfish-plugin-plan.readOnlyTools``），
    工具无法自称只读——那会让名单只增不减，用户没法把工具拿出来。
    """

    def decorate(func):
        if any(item["name"] == name for item in _TOOLS):
            raise ScriptError("工具名重复声明: %s" % name)
        _TOOLS.append({
            "name": name,
            "description": description or _first_doc_line(func),
            "parameters": parameters or {},
            "required": list(required or []),
            "handler": func,
        })
        _HANDLERS[("tool", name)] = func
        return func

    return decorate


def command(name, summary=None, usage=None, aliases=None, has_options=False, session_required=True):
    """声明一条命令。

    ``has_options=True`` 表示本命令也回答候选查询（二级选择页）。它与 ``@command_options``
    是同一件事的两个入口，二者只能选一：同时声明会被清单校验判为冲突——
    那必然是作者写错了，而写错的后果是「命令能执行、选择页永远空、且没有任何报错」。

    走这个入口意味着**同一个函数**要回答两条路，而两条路给的实参不同：执行给真实的
    ``tokens`` / ``raw``，候选查询给 ``tokens=None`` / ``raw=None``。因此函数要按
    ``tokens is None`` 分支（而 ``tokens == []`` 是「用户没输入参数的执行」，两者不是一回事）。
    分支写不出来的函数（只声明了 ``ctx``）会被**当场拒绝**——
    原先那种写法要等用户按下补全键才炸，报出来的还是一个关于参数个数的 ``TypeError``。

    最小可用写法::

        @command(name="x", has_options=True)
        def x(tokens, raw, ctx):
            if tokens is None:
                return {"choices": [...]}   # 候选查询（按下补全键时）
            return "执行结果"                # 执行

    候选查询必须**只读且快**（它跑在用户按键的那一拍上），因此更常见的是用
    ``@command_options`` 把它放在单独的函数里。

    ``session_required`` 声明「这条命令是不是必须有会话才能工作」，**缺省 True（保守）**。
    声明为 False 的命令在没有当前会话时（TUI 首页）也能执行，因此外壳不会把用户手敲的它
    当成普通对话发给模型。默认值取 True 的理由是：反过来默认「不需要」会让一条依赖会话的命令
    在无会话时静默地变成一句提示词，而作者根本没这么想过。
    """

    def decorate(func):
        if any(item["name"] == name for item in _COMMANDS):
            raise ScriptError("命令名重复声明: %s" % name)
        _COMMANDS.append({
            "name": name,
            "descriptor": {
                "summary": summary or _first_doc_line(func),
                "usage": usage,
                "aliases": list(aliases or []),
                "sessionRequired": bool(session_required),
            },
            "hasOptions": bool(has_options),
            "handler": func,
        })
        _HANDLERS[("command", name)] = func
        if has_options:
            _HANDLERS[("command_options", name)] = _options_handler(func, name, True)
            _COMMAND_OPTIONS.append({"name": name, "handler": func, "implied": True})
        return func

    return decorate


def command_options(command_name):
    """声明「本函数回答这条命令的候选查询」。

    它与 ``@command(name, has_options=True)`` 等价，用于把候选查询放在单独的函数里
    （候选查询必须只读且快，常常与执行逻辑不是同一段代码）。

    单独一个函数时通常只需要 ``ctx``；声明了 ``tokens`` / ``raw`` 的也能用，
    它们在候选查询这条路上恒为 ``None``（与 ``has_options=True`` 那条入口同一套约定）。
    """

    def decorate(func):
        if any(item["name"] == command_name and item.get("implied") for item in _COMMAND_OPTIONS):
            raise ScriptError("命令 %s 已用 has_options 声明候选查询，不要重复声明" % command_name)
        if any(item["name"] == command_name for item in _COMMAND_OPTIONS):
            raise ScriptError("命令 %s 重复声明候选查询" % command_name)
        _COMMAND_OPTIONS.append({"name": command_name, "handler": func, "implied": False})
        _HANDLERS[("command_options", command_name)] = _options_handler(func, command_name, False)
        return func

    return decorate


def _options_handler(func, command_name, implied):
    """把「回答候选查询」包成固定形状，并在声明期就把签名问题挡掉。

    ``command_options`` 这条路的实参是空的（``_args_none``），而执行那条路给
    ``tokens`` / ``raw``。同一个函数被挂到两条路上时，这个差别就是崩溃的来源，
    因此这里统一成：

    * 函数声明了 ``tokens`` / ``raw`` 就填 ``None``（约定 ``tokens is None`` ⇒ 这次是候选查询）；
    * 什么都没声明（也不带 ``**kwargs``）就只给 ``ctx``；
    * 声明了别的**必填**参数，或 ``has_options=True`` 却看不出这一次是候选查询，
      一律在**声明期**抛 ``ScriptError``。

    :param func: 处理函数
    :param command_name: 命令名，用于报错
    :param implied: 是否来自 ``has_options=True``（同一函数回答两条路）
    :return: 只接受 ``ctx`` 的包装函数
    """
    parameters = inspect.signature(func).parameters
    takes_kwargs = any(item.kind == inspect.Parameter.VAR_KEYWORD for item in parameters.values())
    slots = {}
    for name, item in parameters.items():
        if item.kind == inspect.Parameter.VAR_KEYWORD or name == "ctx":
            continue
        if name in _OPTION_SLOTS:
            slots[name] = None
            continue
        if item.kind == inspect.Parameter.VAR_POSITIONAL or item.default is not inspect.Parameter.empty:
            continue
        raise ScriptError(
            "命令 %s 的候选查询处理器有一个没有默认值的参数 %s：候选查询只会填 %s（均为 None）与 ctx。"
            "给它一个默认值，或者把它去掉：\n\n%s"
            % (command_name, name, " / ".join(_OPTION_SLOTS),
               _options_example(command_name, func.__name__)))
    if takes_kwargs:
        slots.update({name: None for name in _OPTION_SLOTS})
    if implied and "tokens" not in slots:
        raise ScriptError(
            "命令 %s 声明了 has_options，但它的函数看不出这一次是候选查询还是执行："
            "候选查询给的是 tokens=None，而函数没有声明 tokens。\n\n%s\n\n"
            "只想回答候选查询的话，用单独的函数：\n\n"
            "    @command_options(\"%s\")\n    def %s_options(ctx):\n"
            "        return {\"choices\": [...]}"
            % (command_name, _options_example(command_name, func.__name__), command_name, command_name))

    def answer(ctx):
        return func(**slots, ctx=ctx)

    return answer


def _options_example(command_name, func_name):
    """给「同一个函数回答两条路」的最小可用写法，用于报错信息。

    报错里带可照抄的代码，是因为这类错误的现场（用户按键补全）离写法很远：
    只说「参数个数不对」帮不上任何忙。

    :param command_name: 命令名
    :param func_name: 函数名
    :return: 可直接粘贴的示例代码
    """
    return ("    @command(name=\"%s\", has_options=True)\n"
            "    def %s(tokens, raw, ctx):\n"
            "        if tokens is None:\n"
            "            return {\"choices\": [...]}   # 候选查询\n"
            "        return \"执行结果\"                # 执行（tokens == [] 表示没带参数）"
            % (command_name, func_name))


def contributes(type_name):
    """声明一个类型级扩展点贡献。

    支持的类型：``prompt`` / ``status_line`` / ``panel`` / ``permission`` /
    ``session_persist`` / ``session_restore`` / ``session_delete`` / ``compaction`` /
    ``tool_argument_pre`` / ``tool_result_post`` / ``turn_context`` /
    ``session_before_close`` / ``session_before_fork`` / ``compaction_pre`` /
    ``tool_activation`` / ``request_tuning`` / ``aging_strategy`` / ``input_transform`` / ``turn_before``。
    带路由键的点（``model_catalog`` 的 provider 名、``input_directive`` 的标记）用 ``handler``。

    同一类型只能声明一个函数：清单里的 ``contributions`` 是「类型名集合」，
    它表达不了「同一个类型挂两个函数」，因此第二个声明会被当场拒绝，
    而不是留到运行期变成「其中一个函数永远不会被调用」。

    处理函数必须只读且快（``status_line`` 与 ``panel`` 在界面渲染线程内联执行），
    且**不得发布事件**。
    """

    def decorate(func):
        if type_name in _CONTRIBUTION_TYPES:
            raise ScriptError("贡献类型重复声明: %s" % type_name)
        _CONTRIBUTION_TYPES.add(type_name)
        # 路由键就是类型名自身：类型级扩展点没有第二个维度，统一成 (类型, 类型)
        # 可以让分发逻辑只认一种键形状
        _HANDLERS[(type_name, type_name)] = func
        return func

    return decorate


def subscribe(*event_names):
    """声明订阅某个事件。

    装饰的函数在事件到达时被调用，签名固定为 ``(event, ctx)``：``event`` 是事件字段表
    （含 ``event`` 名字、``eventId``、``occurredAt``、``sessionId``，以及该事件的标量业务字段），
    ``ctx`` 是 :class:`ScriptContext`。返回值被忽略——事件是通知，没有「回答」这回事。

    **事件可以丢**，因此不要把它当成可靠投递：宿主队列满、网关没在运行、
    这个脚本的 worker 正忙或还没起过，事件都会跳过。需要可靠性的逻辑请写成工具。

    处理器抛出的异常只记 stderr，不影响其它事件、也不影响在途调用：
    一个坏处理器不该把整个脚本带走。事件名必须是内核认识的（见宿主文档），
    写错会在清单校验时被拒绝。
    """

    def decorate(func):
        for name in event_names:
            if name not in _SUBSCRIPTIONS:
                _SUBSCRIPTIONS.append(name)
            _HANDLERS[("event", name)] = func
        return func

    return decorate


# ---------------------------------------------------------------- 清单生成


def handler(type_name, route):
    """声明一个带路由键的处理器（目前只有 ``model_catalog`` 需要）。

    路由键由**用户配置**决定（provider 名），脚本无法从自己的声明里推出来，因此必须显式写出来。
    它对应清单里的 ``handlers`` 条目，而处理函数走的是「同键唯一」那一路（与工具、命令同构）。

    :param type_name: 扩展点类型名（如 ``model_catalog``）
    :param route: 路由键（如 provider 名）
    """

    def decorate(func):
        cleaned = None if route is None else str(route).strip()
        if not cleaned:
            raise ScriptError("handler 的 route 不能为空")
        if any(item["type"] == type_name and item["route"] == cleaned
               for item in _HANDLERS_DECL):
            raise ScriptError("处理器重复声明: %s route=%s" % (type_name, cleaned))
        _HANDLERS_DECL.append({"type": type_name, "route": cleaned})
        _HANDLERS[(type_name, cleaned)] = func
        return func

    return decorate


def declarations():
    """返回全部声明，供 worker 做清单校验与 ``--dump-manifest`` 使用。"""
    return {
        "tools": _TOOLS,
        "commands": _COMMANDS,
        "commandOptions": _COMMAND_OPTIONS,
        "contributions": sorted(_CONTRIBUTION_TYPES),
        "events": list(_SUBSCRIPTIONS),
        "handlers": [dict(item) for item in _HANDLERS_DECL],
    }


def dump_manifest(script_id=None, entry="main.py"):
    """把声明还原成 manifest。

    只输出清单需要的字段：``handler`` 这类只有运行期才有意义的东西不能进清单，
    否则清单结构就不再是「协议定义的一份数据」。
    """
    manifest = {"entry": entry}
    if script_id:
        manifest["id"] = script_id
    manifest["tools"] = [
        {
            "name": item["name"],
            "description": item["description"],
            "parameters": item["parameters"],
            "required": item["required"],
        }
        for item in _TOOLS
    ]
    manifest["commands"] = [
        {"name": item["name"], "descriptor": item["descriptor"], "hasOptions": item["hasOptions"]}
        for item in _COMMANDS
    ]
    manifest["commandOptions"] = [
        {"name": item["name"]} for item in _COMMAND_OPTIONS if not item["implied"]
    ]
    if _CONTRIBUTION_TYPES:
        manifest["contributions"] = sorted(_CONTRIBUTION_TYPES)
    if _SUBSCRIPTIONS:
        manifest["events"] = list(_SUBSCRIPTIONS)
    if _HANDLERS_DECL:
        manifest["handlers"] = [dict(item) for item in _HANDLERS_DECL]
    return manifest


def compare_with(manifest):
    """把声明与清单比一遍。

    返回问题描述列表（空列表表示一致）。**比较的是名字集合而不是整份清单**：
    描述文本、参数 Schema 属于「给人看的信息」，改了它们不需要脚本作者同步改清单，
    而名字集合一旦不一致，模型看到的就是一份不存在的工具定义。

    两种形态都接受：完整清单（``{"name": ...}`` 对象数组，即 ``dump_manifest()`` 的产物）
    与名字摘要（字符串数组，即宿主下发的那份）。宿主只下发名字，是因为它要判断的
    就是名字集合，带上整份清单会逼着网关跟随清单 schema 的每次演进。
    """
    problems = []
    _compare_names(problems, "tools", [item["name"] for item in _TOOLS],
                   _names(manifest.get("tools")))
    _compare_names(problems, "commands", [item["name"] for item in _COMMANDS],
                   _names(manifest.get("commands")))
    # 只比显式声明的候选查询：``has_options=True`` 的那部分由 commands 表达，
    # 清单里也不重复列（两者同时出现本来就被判为冲突）
    _compare_names(problems, "commandOptions",
                   [item["name"] for item in _COMMAND_OPTIONS if not item["implied"]],
                   _names(manifest.get("commandOptions")))
    _compare_names(problems, "contributions", sorted(_CONTRIBUTION_TYPES),
                   list(manifest.get("contributions", [])))
    _compare_names(problems, "events", _SUBSCRIPTIONS, list(manifest.get("events", [])))
    # 路由处理器按 ``type::route`` 比：宿主下发的是这个键，路由名写错一样是「模型调不到」
    _compare_names(problems, "handlers",
                   [item["type"] + "::" + item["route"] for item in _HANDLERS_DECL],
                   list(manifest.get("handlers", [])))
    return problems


def _names(value):
    """把「对象数组」或「字符串数组」都归一成名字列表。"""
    names = []
    for item in value or []:
        if isinstance(item, dict):
            if item.get("name") is not None:
                names.append(item["name"])
        else:
            names.append(item)
    return names


def _compare_names(problems, label, declared, expected):
    missing = sorted(set(declared) - set(expected))
    extra = sorted(set(expected) - set(declared))
    if missing:
        problems.append("%s: 代码里声明了但清单没有 %s" % (label, ", ".join(missing)))
    if extra:
        problems.append("%s: 清单声明了但代码没有 %s" % (label, ", ".join(extra)))


# ---------------------------------------------------------------- 分发

# 扩展点实参构造与结果整形：键名来自 extension-points.json 的 args / shape 字段。
#
# 「有哪些扩展点、每个用哪个实参构造与整形」这份事实只有一份，就在那个 JSON 里；
# 这里只按它引用到的键提供实现。因此新增一个扩展点时，只要能复用既有键，
# 本文件一行都不用改——那份名单也不会再有第二份抄写。
_ARGS = {}
_SHAPES = {}

#: 能力档文件位置：与 SDK 同目录（网关资源抽取目录下的 script/）。
_CAPABILITY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "extension-points.json")


def _define_args(key, builder):
    _ARGS[key] = builder


def _define_shape(key, shaper):
    _SHAPES[key] = shaper


def _load_dispatch():
    """按能力档构建「类型名 → (实参构造, 结果整形)」。

    文件缺失、或引用了本 SDK 没有实现的键时**当场报错**：那意味着 SDK 与能力档不是同一版，
    静默降级只会把问题推迟到某次调用上，以「脚本没返回内容」的形态出现。

    :return: 类型名 → (实参构造, 结果整形)
    """
    try:
        with open(_CAPABILITY_FILE, "r", encoding="utf-8") as handle:
            table = json.load(handle)
    except (IOError, ValueError) as error:
        raise ScriptError("读取扩展点能力档失败: %s (%s)" % (_CAPABILITY_FILE, error))
    dispatch = {}
    for entry in table.get("in", []):
        type_name = entry.get("type")
        args_key = entry.get("args")
        shape_key = entry.get("shape")
        if args_key not in _ARGS or shape_key not in _SHAPES:
            raise ScriptError(
                "扩展点能力档引用了 SDK 未实现的键: %s (args=%r, shape=%r)；"
                "SDK 与能力档版本不一致" % (type_name, args_key, shape_key))
        dispatch[type_name] = (_ARGS[args_key], _SHAPES[shape_key])
    return dispatch


def _as_mapping(result, default=None):
    return result if isinstance(result, dict) else default


# ---- 工具 -----------------------------------------------------------------


def _args_tool(payload):
    return {"args": payload.get("arguments") or {}}


def _shape_tool(result):
    # 任意 JSON 都能作为 output：字符串、数字、对象、数组都合法，
    # 因为内核的截断与序列化对它们的处理是统一的（见 ToolCodec）
    if isinstance(result, ToolResult):
        payload = {"output": result.output}
        if result.metadata:
            payload["metadata"] = result.metadata
        return payload
    return {"output": result}


_define_args("tool", _args_tool)
_define_shape("tool", _shape_tool)


# ---- 命令 -----------------------------------------------------------------


def _args_command(payload):
    arguments = payload.get("arguments") or {}
    return {"tokens": list(arguments.get("tokens") or []), "raw": arguments.get("raw") or ""}


def _shape_command(result):
    if result is None:
        return {"kind": "OK", "output": None}
    if isinstance(result, str):
        return {"kind": "OK", "output": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("命令返回值必须是字符串或 {kind, output, choices}")
    shaped = dict(mapping)
    shaped.setdefault("kind", "OK")
    return shaped


_define_args("command", _args_command)
_define_shape("command", _shape_command)


# ---- 命令候选查询 ---------------------------------------------------------


def _args_none(payload):
    return {}


def _shape_choices(result):
    if result is None:
        return {"choices": []}
    if isinstance(result, (list, tuple)):
        return {"choices": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("候选查询返回值必须是列表或 {choices: [...]}")
    if "choices" not in mapping:
        # 缺 choices 是最难查的一种写法：``has_options=True`` 的函数忘了按 ``tokens is None``
        # 分支时，返回的正是执行结果（常常是 ``{"kind": "OK", "output": ...}``），
        # 而补齐缺省会让它安静地变成「没有候选」——选择页空着，没有任何报错
        raise ScriptError("候选查询返回值里必须有 choices 键：返回候选列表，或 {choices: [...]}")
    return dict(mapping)


_define_args("command_options", _args_none)
_define_shape("choices", _shape_choices)


# ---- 只返回一段文本的贡献 -------------------------------------------------


def _shape_text(result):
    if result is None:
        return None
    if isinstance(result, str):
        return {"text": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("该扩展点返回值必须是字符串、{text: ...} 或 None")
    return mapping


_define_args("none", _args_none)
_define_shape("text", _shape_text)


# ---- 面板 -----------------------------------------------------------------


def _shape_panel(result):
    if result is None:
        return None
    if isinstance(result, (list, tuple)):
        return {"lines": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("面板返回值必须是 None、{title, region, lines} 或行列表")
    return mapping


_define_shape("panel", _shape_panel)


# ---- 权限拦截 -------------------------------------------------------------

#: 权限拦截能表达的三态；刻意没有「放行」。
_PERMISSION_VERDICTS = ("ABSTAIN", "ASK", "DENY")


def _shape_permission(result):
    """权限拦截返回值整形。

    接受的写法：

    * ``None`` / ``False`` —— 无异议（不改变内核策略的结论）；
    * ``True`` —— 拒绝；
    * ``"ask"`` —— 升级为人工审批；
    * 其他字符串 —— 带理由的拒绝；
    * ``{"verdict": "ABSTAIN"/"ASK"/"DENY", "reason": ...}``；
    * ``{"denied": bool, "reason": ...}`` —— 旧写法，仍接受。

    **没有「放行」这一写法**：脚本只能收紧，不能放宽内核已经允许的调用。
    未知裁定一律报错而不是静默按无异议处理——写错一个字母就让一道本该有人看的调用直接放行，
    是最难排查的那种错。
    """
    if result is None or result is False:
        return {"verdict": "ABSTAIN"}
    if result is True:
        return {"verdict": "DENY"}
    if isinstance(result, str):
        if result.strip().lower() == "ask":
            return {"verdict": "ASK"}
        return {"verdict": "DENY", "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError('权限拦截返回值必须是 None、布尔、"ask"、原因字符串或 {verdict, reason}')
    if not mapping:
        return {"verdict": "ABSTAIN"}
    if "verdict" in mapping:
        return _shaped_verdict(mapping)
    if "denied" in mapping:
        shaped = {"verdict": "DENY" if mapping.get("denied") else "ABSTAIN"}
        if mapping.get("reason") is not None:
            shaped["reason"] = mapping["reason"]
        return shaped
    raise ScriptError("权限拦截返回值含未知键，允许：verdict / reason（旧写法为 denied / reason）")


def _shaped_verdict(mapping):
    """校验并归一 ``{verdict, reason}`` 写法。

    :param mapping: 脚本返回的映射
    :return: 协议载荷
    """
    verdict = mapping.get("verdict")
    if not isinstance(verdict, str) or verdict.strip().upper() not in _PERMISSION_VERDICTS:
        raise ScriptError("verdict 必须是 ABSTAIN / ASK / DENY，实际是 %r" % (verdict,))
    shaped = {"verdict": verdict.strip().upper()}
    if mapping.get("reason") is not None:
        shaped["reason"] = mapping["reason"]
    return shaped


_define_shape("permission", _shape_permission)


# ---- 会话持久化 / 恢复 / 删除 ---------------------------------------------


def _args_snapshot(payload):
    return {"snapshot": payload.get("snapshot")}


def _shape_nothing(result):
    # 这三个扩展点没有结果类型（协议里是 Void）。有返回值一律忽略而不是报错：
    # 「多返回了一个东西」不该让一次已经完成的持久化看起来失败
    return None


_define_args("snapshot", _args_snapshot)
_define_shape("nothing", _shape_nothing)


def _shape_sessions(result):
    if result is None:
        return {"sessions": []}
    if isinstance(result, (list, tuple)):
        return {"sessions": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("会话恢复返回值必须是列表或 {sessions: [...]}")
    shaped = dict(mapping)
    shaped.setdefault("sessions", [])
    return shaped


_define_shape("sessions", _shape_sessions)


def _args_session_id(payload):
    return {"session_id": payload.get("sessionId")}


_define_args("session_id", _args_session_id)


# ---- 压缩策略 -------------------------------------------------------------


def _args_request(payload):
    return {"request": dict(payload or {})}


_define_args("request", _args_request)
_define_shape("mapping", _as_mapping)


# ---- 二期打通的能力档（数据进出、不在渲染线程/启动期）------------------------


def _args_tool_argument_pre(payload):
    return {
        "agent_id": payload.get("agentId"),
        "tool_name": payload.get("toolName"),
        "arguments": payload.get("arguments") or {},
        "source": payload.get("source"),
        "session_id": payload.get("sessionId"),
    }


#: 参数改写能表达的三态；刻意没有「放行」——改写之后照旧要过权限判定。
_ARGUMENT_OUTCOMES = ("ABSTAIN", "REPLACE", "DENY")


def _shape_argument_decision(result):
    """参数改写裁定的整形。

    接受的写法：``None`` / ``False``（不改）、``True``（拒绝）、原因字符串（带理由的拒绝）、
    ``{"arguments": {...}}``（替换）、``{"outcome": ..., "arguments": ..., "reason": ...}``。
    未知 outcome 一律报错而不是静默按「不改」处理。
    """
    if result is None or result is False:
        return {"outcome": "ABSTAIN"}
    if result is True:
        return {"outcome": "DENY"}
    if isinstance(result, str):
        return {"outcome": "DENY", "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("参数改写返回值必须是 None、布尔、原因字符串或 {outcome, arguments, reason}")
    if "outcome" in mapping:
        outcome = str(mapping.get("outcome") or "").strip().upper()
        if outcome not in _ARGUMENT_OUTCOMES:
            raise ScriptError("outcome 必须是 %s，实际是 %r"
                              % (" / ".join(_ARGUMENT_OUTCOMES), mapping.get("outcome")))
        shaped = {"outcome": outcome}
        if outcome == "REPLACE":
            shaped["arguments"] = mapping.get("arguments") or {}
        if mapping.get("reason") is not None:
            shaped["reason"] = mapping["reason"]
        return shaped
    if "arguments" in mapping:
        return {"outcome": "REPLACE", "arguments": mapping["arguments"] or {}}
    raise ScriptError("参数改写返回值含未知键，允许：outcome / arguments / reason")


def _args_tool_result_post(payload):
    return {
        "agent_id": payload.get("agentId"),
        "tool_name": payload.get("toolName"),
        "arguments": payload.get("arguments") or {},
        "output": payload.get("output"),
        "metadata": payload.get("metadata") or {},
        "failed": bool(payload.get("failed")),
        "session_id": payload.get("sessionId"),
    }


def _shape_result_adjustment(result):
    """结果整形的整形：``None`` 表示不改；``{output, metadata}`` 只改给出的那一项。

    **输出保持原始类型**：字符串就是字符串、结构化对象就是结构化对象。
    """
    if result is None:
        return None
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("结果整形返回值必须是 None 或 {output, metadata}")
    shaped = {}
    if mapping.get("output") is not None:
        shaped["output"] = mapping["output"]
    if mapping.get("metadata") is not None:
        shaped["metadata"] = mapping["metadata"]
    return shaped or None


def _args_turn_context(payload):
    return {
        "session_id": payload.get("sessionId"),
        "user_input": payload.get("userInput") or "",
        "nested": bool(payload.get("nested")),
    }


def _shape_turn_context(result):
    if result is None:
        return None
    if isinstance(result, str):
        return {"text": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("回合上下文返回值必须是 None、字符串或 {text: ...}")
    return mapping


def _args_session_before_close(payload):
    return {
        "session_id": payload.get("sessionId"),
        "agent_id": payload.get("agentId"),
        "reason": payload.get("reason"),
        "veto_supported": bool(payload.get("vetoSupported")),
    }


def _args_session_before_fork(payload):
    return {
        "session_id": payload.get("sessionId"),
        "agent_id": payload.get("agentId"),
        "message_id": payload.get("messageId"),
        "cut_index": payload.get("cutIndex"),
        "message_count": payload.get("messageCount"),
    }


def _shape_lifecycle_verdict(result):
    """生命周期裁定的整形：``None`` / ``False`` 放行，``True`` 拦下，字符串是带理由的拦下。"""
    if result is None or result is False:
        return {"cancel": False}
    if result is True:
        return {"cancel": True}
    if isinstance(result, str):
        return {"cancel": True, "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("生命周期裁定必须是 None、布尔、原因字符串或 {cancel, reason}")
    shaped = {"cancel": bool(mapping.get("cancel"))}
    if mapping.get("reason") is not None:
        shaped["reason"] = mapping["reason"]
    return shaped


def _args_compaction_pre(payload):
    return {
        "session_id": payload.get("sessionId"),
        "trigger": payload.get("trigger"),
        "message_count": payload.get("messageCount"),
        "tokens_before": payload.get("tokensBefore"),
        "keep_recent_messages": payload.get("keepRecentMessages"),
        "previous_boundary_message_id": payload.get("previousBoundaryMessageId"),
    }


def _shape_compaction_directive(result):
    """压缩指令的整形：``None`` 放行，``{"cancel": true}`` 拦下，``{"keepRecent": n}`` 改保留条数。"""
    if result is None:
        return None
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("压缩指令必须是 None 或 {cancel, reason, keepRecent}")
    if mapping.get("cancel"):
        shaped = {"cancel": True}
        if mapping.get("reason") is not None:
            shaped["reason"] = mapping["reason"]
        return shaped
    if mapping.get("keepRecent") is not None:
        return {"keepRecent": mapping["keepRecent"]}
    return None


def _args_model_catalog(payload):
    return {
        "provider_name": payload.get("providerName"),
        "provider_type": payload.get("providerType"),
    }


def _shape_model_catalog(result):
    """模型目录的整形：空列表含义是「我不表态」，回落成配置里的 models。"""
    if result is None:
        return {"models": []}
    if isinstance(result, (list, tuple)):
        return {"models": list(result)}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("模型目录返回值必须是 None、列表或 {models: [...]}")
    shaped = dict(mapping)
    shaped.setdefault("models", [])
    return shaped


_define_args("tool_argument_pre", _args_tool_argument_pre)
_define_shape("argument_decision", _shape_argument_decision)
_define_args("tool_result_post", _args_tool_result_post)
_define_shape("result_adjustment", _shape_result_adjustment)
_define_args("turn_context", _args_turn_context)
_define_shape("turn_context", _shape_turn_context)
_define_args("session_before_close", _args_session_before_close)
_define_args("session_before_fork", _args_session_before_fork)
_define_shape("lifecycle_verdict", _shape_lifecycle_verdict)
_define_args("compaction_pre", _args_compaction_pre)
_define_shape("compaction_directive", _shape_compaction_directive)
_define_args("model_catalog", _args_model_catalog)
_define_shape("model_catalog", _shape_model_catalog)


# ---- 热路径与提交路径的扩展点（三期）------------------------------------


def _args_tool_activation(payload):
    return {
        "session_id": payload.get("sessionId"),
        "agent_id": payload.get("agentId"),
        "tool_name": payload.get("toolName"),
        "description": payload.get("description"),
    }


def _shape_tool_activation(result):
    """工具激活裁定的整形。

    ``None`` = 不表态（后续插件继续）；``True`` = 明确要它可见（能压过后面的插件）；
    ``False`` / 原因字符串 = 明确隐藏。布尔会把「不想管」与「明确要它可见」变成同一个值，
    因此这里把它们区分开。
    """
    if result is None:
        return None
    if result is True:
        return {"visible": True}
    if result is False:
        return {"hidden": True}
    if isinstance(result, str):
        return {"hidden": True, "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("工具激活返回值必须是 None、布尔、原因字符串或 {visible, hidden, reason}")
    if mapping.get("visible"):
        return {"visible": True}
    if mapping.get("hidden"):
        shaped = {"hidden": True}
        if mapping.get("reason") is not None:
            shaped["reason"] = mapping["reason"]
        return shaped
    return None


def _args_request_tuning(payload):
    return {
        "provider_type": payload.get("providerType"),
        "model_id": payload.get("modelId"),
        "default_cache_key": payload.get("defaultCacheKey"),
        "message_count": payload.get("messageCount"),
        "tool_count": payload.get("toolCount"),
        "session_id": payload.get("sessionId"),
    }


def _shape_request_tuning(result):
    """请求调优的整形：``None`` 表示不改；只读已知字段（cacheKey / cacheRetention / cacheBreakpoints）。"""
    if result is None:
        return None
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("请求调优返回值必须是 None 或 {cacheKey, cacheRetention, cacheBreakpoints}")
    return mapping


def _args_aging_strategy(payload):
    return {
        "message_count": payload.get("messageCount"),
        "used_tokens": payload.get("usedTokens"),
        "budget_tokens": payload.get("budgetTokens"),
        "compression_boundary": payload.get("compressionBoundary"),
        "default_keep_recent_messages": payload.get("defaultKeepRecentMessages"),
        "default_aging_percent": payload.get("defaultAgingPercent"),
        "session_id": payload.get("sessionId"),
    }


def _shape_aging_strategy(result):
    """老化策略的整形：``None`` 表示不改；已知字段 keepRecentMessages / agingPercent /
    stubText / stubTextsByTool。"""
    if result is None:
        return None
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("老化策略返回值必须是 None 或 {keepRecentMessages, agingPercent, stubText}")
    return mapping


def _args_input_transform(payload):
    return {
        "text": payload.get("text") or "",
        "source": payload.get("source"),
        "has_session": bool(payload.get("hasSession")),
        "session_id": payload.get("sessionId"),
    }


def _shape_input_transform(result):
    """输入改写的整形：``None`` 不改；字符串 = 改成这句；``{"handled": true, "notice": ...}``
    = 接过去、不进对话（像命令那样，只给用户一句说明）。"""
    if result is None:
        return None
    if isinstance(result, str):
        return {"text": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("输入改写返回值必须是 None、字符串或 {text, handled, notice}")
    if mapping.get("handled"):
        shaped = {"handled": True}
        if mapping.get("notice") is not None:
            shaped["notice"] = mapping["notice"]
        return shaped
    if mapping.get("text") is not None:
        return {"text": mapping["text"]}
    return None


def _args_turn_before(payload):
    return {
        "agent_id": payload.get("agentId"),
        "input": payload.get("input") or "",
        "nested": bool(payload.get("nested")),
        "depth": payload.get("depth"),
        "session_id": payload.get("sessionId"),
    }


def _shape_turn_before(result):
    """回合前指令的整形：``None`` 放行；``True`` / 原因字符串 = 拦下；``{"input": ...}`` = 改写输入。"""
    if result is None or result is False:
        return None
    if result is True:
        return {"cancel": True}
    if isinstance(result, str):
        return {"cancel": True, "reason": result}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("回合前指令必须是 None、布尔、原因字符串或 {cancel, input}")
    if mapping.get("cancel"):
        shaped = {"cancel": True}
        if mapping.get("reason") is not None:
            shaped["reason"] = mapping["reason"]
        return shaped
    if mapping.get("input") is not None:
        return {"input": mapping["input"]}
    return None


def _args_input_directive(payload):
    return {
        "marker": payload.get("marker"),
        "input": payload.get("input") or "",
        "session_id": payload.get("sessionId"),
    }


def _shape_input_directive(result):
    """输入指令的整形：``None`` / ``{"unclaimed": true}`` 不认领；字符串或 ``{toolName, arguments}``
    声明一次工具调用（真正的执行走内核完整的权限与审批链）。"""
    if result is None:
        return None
    if isinstance(result, str):
        return {"toolName": result, "arguments": {}}
    mapping = _as_mapping(result)
    if mapping is None:
        raise ScriptError("输入指令返回值必须是 None、工具名字符串或 {toolName, arguments}")
    if mapping.get("unclaimed") or not mapping.get("toolName"):
        return None
    return {"toolName": mapping["toolName"], "arguments": mapping.get("arguments") or {}}


_define_args("tool_activation", _args_tool_activation)
_define_shape("tool_activation", _shape_tool_activation)
_define_args("request_tuning", _args_request_tuning)
_define_shape("request_tuning", _shape_request_tuning)
_define_args("aging_strategy", _args_aging_strategy)
_define_shape("aging_strategy", _shape_aging_strategy)
_define_args("input_transform", _args_input_transform)
_define_shape("input_transform", _shape_input_transform)
_define_args("turn_before", _args_turn_before)
_define_shape("turn_before", _shape_turn_before)
_define_args("input_directive", _args_input_directive)
_define_shape("input_directive", _shape_input_directive)


# ---- 事件 -----------------------------------------------------------------


def _args_event(payload):
    return {"event": payload}


def _shape_nothing_ignored(_result):
    # 事件处理器的返回值没有去处：事件是通知，不是请求。返回 None 让调用方
    # 不必因为脚本「顺手 return 了一个值」而报错
    return None


_define_args("event", _args_event)

#: 类型名 → (实参构造, 结果整形)。事件不是扩展点（它是协议里的通知方法），
#: 但走同一张分发表，因此在这里单独补一条。
_DISPATCH = _load_dispatch()
_DISPATCH["event"] = (_ARGS["event"], _shape_nothing_ignored)


# ---- 入口 -----------------------------------------------------------------


def invoke(script_id, type_name, route_key, payload, emitter=None):
    """按类型分发一次调用。

    处理函数抛出的任何异常都由调用方（worker）转成协议错误：脚本失败必须走
    「失败」这条路，而不是返回一个看起来正常的空结果——后者会让模型以为
    「脚本说没有内容」，问题就此静默。

    :param script_id: 脚本标识，用于填上下文
    :param type_name: 扩展点类型名
    :param route_key: 路由键（工具名 / 命令名）；类型级扩展点用类型名
    :param payload: 请求载荷
    :param emitter: 事件发布回调（由 worker 注入）；``None`` 表示当前上下文不支持发布事件
    :return: 结果载荷，可为 ``None``
    """
    lookup_key = route_key if route_key is not None else type_name
    handler = _HANDLERS.get((type_name, lookup_key))
    if handler is None:
        raise ScriptError("没有处理 %s=%s 的函数" % (type_name, lookup_key))
    arguments, shaper = _DISPATCH[type_name]
    context = ScriptContext(script_id, payload, emitter)
    kwargs = arguments(payload or {})
    accepted = _accepted_kwargs(handler)
    if accepted is not None:
        kwargs = {name: value for name, value in kwargs.items() if name in accepted}
    result = handler(**kwargs, ctx=context)
    return shaper(result) if shaper is not None else None


def _first_doc_line(func):
    """取函数文档的第一行作为缺省描述。"""
    doc = inspect.getdoc(func)
    if not doc:
        return None
    for line in doc.splitlines():
        if line.strip():
            return line.strip()
    return None


__all__ = [
    "ScriptError",
    "ToolResult",
    "EMITTABLE_EVENTS",
    "ScriptContext",
    "configuration",
    "tool",
    "handler",
    "command",
    "command_options",
    "contributes",
    "subscribe",
    "declarations",
    "dump_manifest",
    "compare_with",
    "invoke",
]
