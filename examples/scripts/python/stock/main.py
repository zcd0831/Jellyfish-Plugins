# -*- coding: utf-8 -*-
"""A 股行情与自选股：给模型一套取数工具，给用户一条 ``/stock`` 命令。

**它只取数、不下结论**：工具返回的是对齐好的数据表，分析由用户在对话里发起、由当前回合的
模型完成。这里刻意不写任何「超买超卖」式的判断——那种判断一旦写进工具，就再也没法在不改
代码的前提下换口径，而换口径恰恰是分析最常要做的事。

**它不自己画面板**：侧栏那块面板由 ``stockpanel`` 这个独立脚本提供。原因是内核的硬约束——
面板处理器在**界面渲染线程上同步执行且没有任何超时**，而取数要联网（akshare 一次调用
0.1~10 秒）。把两者放进同一个脚本 worker 会更糟：worker 同时只服务一个在途请求，
于是回合运行中界面每秒重拉一次面板时，每一次都会排在某个取数请求后面，整个界面卡死。
因此这里只负责「把数据取回来、写进缓存」，面板去读那份缓存。

**数据源与单位口径**见 ``stock_sources.py`` 的模块文档，那份结论是实测出来的，改之前先跑探针。
"""

from datetime import datetime

from jellyfish_sdk import ScriptError, command, contributes, tool

import stock_render as render
import stock_sources as sources
import stock_store as store

#: 自选股面板最多展示几行（与内核侧单面板 8 行的上限对齐，多出来的会显示成「还有 N 行」）。
MAX_WATCH_ROWS = 8


def _split_codes(raw):
    """把一串代码切成列表。

    分隔符收得宽一点（逗号、空格、分号、顿号、换行）：模型与用户给的写法都不统一，
    而「因为用了中文逗号就报错」纯属自找麻烦。

    :param raw: 原始字符串
    :return: 代码字符串列表
    """
    text = str(raw or "")
    for separator in (",", "，", ";", "；", "、", "\n", "\r", "\t"):
        text = text.replace(separator, " ")
    return [item.strip() for item in text.split() if item.strip()]


def _resolve(code):
    """把用户输入规范化成带市场前缀的小写形态。

    :param code: 原始代码
    :return: 带前缀的代码
    :raises ScriptError: 认不出时
    """
    try:
        return sources.symbol_of(code)
    except sources.SourceError as exc:
        raise ScriptError(str(exc))


def _short(code):
    """展示用的短形态（去掉市场前缀）。"""
    text = str(code)
    return text[2:] if len(text) > 6 else text


def _percent(value, digits=2):
    """百分比文本；缺值显示 ``-`` 而不是 ``-%``。"""
    text = render.number(value, digits)
    return text if text == "-" else text + "%"


def _moment(text):
    """把 ISO 时刻压成给人看的形态：同一天只给 ``HH:MM``，跨天带上日期。

    缓存的时刻是这块功能里最重要的一行字之一——自选股的行情是一份快照，
    没有时刻的话用户会把它当成实时价。

    :param text: ISO 时刻
    :return: 展示用文本
    """
    try:
        stamp = datetime.strptime(str(text)[:19], "%Y-%m-%dT%H:%M:%S")
    except (TypeError, ValueError):
        return str(text)
    if stamp.date() == datetime.now().date():
        return stamp.strftime("%H:%M")
    return stamp.strftime("%m-%d %H:%M")


def _fetch_quotes(codes):
    """取一批快照，返回 ``(命中列表, 未命中代码列表)``。

    :param codes: 带前缀的代码列表
    :return: 二元组
    :raises ScriptError: 全部口径都失败时
    """
    try:
        quotes = sources.spot(codes)
    except sources.SourceError as exc:
        raise ScriptError(str(exc))
    hits = []
    missing = []
    for code in codes:
        quote = quotes.get(code.lower())
        if quote is None:
            missing.append(code)
        else:
            hits.append(quote)
    return hits, missing


def _quote_rows(hits):
    """把快照字典列表转成表格行。"""
    rows = []
    for quote in hits:
        rows.append([
            _short(quote.get("code")),
            quote.get("name") or "-",
            render.number(quote.get("price")),
            render.signed_pct(quote.get("changePct")),
            render.number(quote.get("change")),
            render.number(quote.get("open")),
            render.number(quote.get("high")),
            render.number(quote.get("low")),
            render.lots(quote.get("volumeLots")),
            render.amount_yi(quote.get("amountYuan")),
            _percent(quote.get("turnover")),
            render.number(quote.get("volumeRatio")),
            render.number(quote.get("peTtm")),
            render.number(quote.get("pb")),
            render.amount_yi_plain(quote.get("totalCapYi")),
        ])
    return rows


_QUOTE_HEADERS = ["代码", "名称", "现价", "涨跌幅", "涨跌额", "今开", "最高", "最低",
                  "成交量", "成交额", "换手率", "量比", "PE(TTM)", "PB", "总市值"]
_QUOTE_ALIGNS = ["left", "left", "right", "right", "right", "right", "right", "right",
                 "right", "right", "right", "right", "right", "right", "right"]


def _render_quotes(hits, missing):
    """渲染快照表。"""
    lines = [render.table(_QUOTE_HEADERS, _quote_rows(hits), _QUOTE_ALIGNS)]
    if hits:
        lines.append("数据源：%s" % (hits[0].get("source") or "未知"))
    if missing:
        lines.append("未取到：%s" % "、".join(_short(code) for code in missing))
    return "\n".join(lines)


def _refresh_watch(ctx):
    """拉一次自选股行情并落盘缓存。

    :param ctx: 脚本上下文
    :return: ``(items, missing)``；自选股为空时返回 ``([], [])``
    :raises ScriptError: 取数全部失败时（此时旧缓存原样保留）
    """
    codes = store.load_watchlist(ctx)
    if not codes:
        return [], []
    hits, missing = _fetch_quotes(codes)
    items = []
    for quote in hits:
        items.append({
            "code": quote.get("code"),
            "name": quote.get("name") or _short(quote.get("code")),
            "price": quote.get("price"),
            "changePct": quote.get("changePct"),
        })
    store.save_quotes(ctx, datetime.now().isoformat(timespec="seconds"), items)
    return items, missing


def _missing_note(missing):
    """把「这几只没取到行情」写成一句可执行的提示。

    这是**唯一一处**产生这类文案的地方：`add` / `refresh` / 工具侧三个入口都要报同一件事，
    分开写迟早会漂移出「有的地方报、有的地方不报」——而那正是这次要修的缺陷。

    文案要同时给出两种可能，因为**这两者在腾讯接口上不可区分**：查不到的代码它连一行都不返回，
    而它同样一行都不返回的原因也可能是请求失败。把它说成「代码不存在」会在接口抖动时冤枉用户，
    说成「接口失败」又会让 003305 这种真不存在的代码永远查不出来。

    :param missing: 未取到行情的代码列表（带市场前缀）
    :return: 提示文本；没有缺失时返回空串
    """
    if not missing:
        return ""
    return ("未取到行情：%s（代码可能不存在，也可能是行情源暂时不可用；"
            "用 /stock del 移除或 /stock refresh 重试）"
            % "、".join(_short(code) for code in missing))


def _watch_summary(ctx, title=None, note=None):
    """把自选股的**缓存**渲染成一段给人看的文本（不联网）。

    :param ctx: 脚本上下文
    :param title: 覆盖首行标题
    :param note: 追加在表格之后的诊断行（缺哪只没取到行情），可为 None
    :return: 文本
    """
    codes = store.load_watchlist(ctx)
    if not codes:
        return "自选股为空。用 /stock add 600519 000858 加入。"
    cached = store.load_quotes(ctx)
    items = cached["items"]
    # 有缓存但一条行情都没取到时，不能再说「缓存于 X」——那句话会让人以为表格是空的
    # 是因为还没刷新，而实际是刷新了但一只都没取到（缺失的代码由 _missing_note 单独报）
    if not items:
        when = "（还没有行情）"
    elif cached["updatedAt"]:
        when = "（缓存于 %s）" % _moment(cached["updatedAt"])
    else:
        when = ""
    lines = [title or ("自选股 %d 只%s" % (len(codes), when))]
    if items:
        rows = [[_short(item.get("code")), item.get("name") or "-",
                 render.number(item.get("price")), render.signed_pct(item.get("changePct"))]
                for item in items]
        lines.append(render.table(["代码", "名称", "现价", "涨跌幅"], rows,
                                  ["left", "left", "right", "right"]))
    if note:
        # 诊断行紧跟在表格后面：它解释的正是「表格里为什么少了几只」，
        # 放在固定脚注之后就容易被当成又一条无关提示跳过去
        lines.append(note)
    lines.append("用 /stock refresh 刷新行情，/stock add <代码> 加入，/stock del <代码> 移除。")
    return "\n".join(lines)


def _metric_value(value, unit):
    """按指标单位渲染一个财务数值。"""
    if unit == "yi":
        return render.amount_yi(value)
    if unit == "share":
        return render.number(value)
    return _percent(value)


# ---------------------------------------------------------------- 工具


@tool(
    name="stock_quote",
    description="查 A 股实时快照（现价、涨跌幅、开高低、成交量额、换手、量比、PE、PB、市值）。"
                "一次可以查多只，代码用逗号分隔。指数加前缀，例如 sh000001 是上证指数。",
    parameters={
        "codes": {"type": "string", "description": "股票代码，多只用逗号分隔，例如 600519,000858"},
    },
    required=["codes"],
)
def stock_quote(args, ctx):
    """批量查实时快照。"""
    codes = [_resolve(item) for item in _split_codes(args.get("codes"))]
    if not codes:
        raise ScriptError("缺少参数 codes（例如 600519,000858）")
    hits, missing = _fetch_quotes(codes)
    if not hits:
        raise ScriptError("这些代码一个都没取到行情：%s" % "、".join(_short(c) for c in codes))
    # 命中的若是自选股，顺手更新面板缓存——用户刚看过的行情，面板没有理由还显示旧的
    _touch_cache(ctx, hits)
    return _render_quotes(hits, missing)


def _touch_cache(ctx, hits):
    """若命中的股票里有自选股，用这批快照更新面板缓存（不发额外请求）。

    :param ctx: 脚本上下文
    :param hits: 本次取到的快照列表
    """
    try:
        watched = store.load_watchlist(ctx)
        if not watched:
            return
        fresh = {}
        for quote in hits:
            fresh[str(quote.get("code", "")).lower()] = quote
        cached = store.load_quotes(ctx)
        merged = []
        seen = set()
        for item in cached["items"]:
            code = str(item.get("code", "")).lower()
            seen.add(code)
            quote = fresh.get(code)
            if quote is not None:
                item = {"code": code, "name": quote.get("name") or item.get("name") or code,
                        "price": quote.get("price"), "changePct": quote.get("changePct")}
            merged.append(item)
        for code in watched:
            if code.lower() in seen:
                continue
            quote = fresh.get(code.lower())
            if quote is not None:
                merged.append({"code": code.lower(), "name": quote.get("name") or code,
                               "price": quote.get("price"), "changePct": quote.get("changePct")})
        store.save_quotes(ctx, datetime.now().isoformat(timespec="seconds"), merged)
    except Exception:  # noqa: BLE001 - 面板缓存更新失败不该让工具调用失败
        pass


@tool(
    name="stock_history",
    description="查 A 股日线行情（开高低收、涨跌幅、振幅、成交量额、换手率），"
                "可选带上简单移动平均线。前复权默认开启，适合看趋势与技术指标。",
    parameters={
        "code": {"type": "string", "description": "股票代码，例如 600519"},
        "days": {"type": "integer", "description": "取最近多少个交易日，缺省 60，上限 250"},
        "adjust": {"type": "string", "description": "复权方式：qfq 前复权（缺省）/ hfq 后复权 / none 不复权"},
        "ma": {"type": "string", "description": "要算的均线窗口，逗号分隔，缺省 5,20；传空字符串则不算"},
    },
    required=["code"],
)
def stock_history(args, ctx):
    """查日线并渲染成表格。"""
    code = _resolve(args.get("code"))
    adjust = str(args.get("adjust") or "qfq").strip().lower()
    if adjust == "none":
        adjust = ""
    raw_ma = args.get("ma")
    windows = []
    if raw_ma is None:
        windows = [5, 20]
    else:
        for item in _split_codes(raw_ma):
            try:
                windows.append(int(item))
            except ValueError:
                raise ScriptError("均线窗口要写成整数，例如 ma=5,20；收到 %s" % item)
    try:
        data = sources.history(code, days=args.get("days") or 60, adjust=adjust, ma_windows=windows)
    except sources.SourceError as exc:
        raise ScriptError(str(exc))

    adjustable = {"qfq": "前复权", "hfq": "后复权", "": "不复权"}.get(adjust, adjust)
    title = "%s %s 日线（%s，%s）最近 %d 个交易日" % (
        data["code"], data["name"] or "", adjustable, data["source"], len(data["rows"]))
    headers = ["日期", "开盘", "收盘", "最高", "最低", "涨跌幅", "振幅", "成交量", "成交额", "换手率"]
    for window in windows:
        headers.append("MA%d" % window)
    aligns = ["left"] + ["right"] * (len(headers) - 1)
    rows = []
    for row in data["rows"]:
        cells = [row["date"], render.number(row["open"]), render.number(row["close"]),
                 render.number(row["high"]), render.number(row["low"]),
                 render.signed_pct(row["changePct"]), _percent(row["amplitude"]),
                 render.lots(row["volumeLots"]), render.amount_yi(row["amountYuan"]),
                 _percent(row["turnover"])]
        for window in windows:
            cells.append(render.number(row["ma"].get(window)))
        rows.append(cells)
    lines = [title, "", render.table(headers, rows, aligns)]
    lines.append("")
    lines.append("涨跌幅与振幅按相邻收盘价自行计算：前复权序列在除权日不连续，那天会有一点偏差。")
    return "\n".join(lines)


@tool(
    name="stock_fundamentals",
    description="查 A 股公司概况（行业、上市日期、主营）与关键财务指标"
                "（营收、净利润、ROE、毛利率、资产负债率、每股指标等，按期对比）。",
    parameters={
        "code": {"type": "string", "description": "股票代码，例如 600519"},
        "periods": {"type": "integer", "description": "取最近多少期定期报告，缺省 4，上限 8"},
    },
    required=["code"],
)
def stock_fundamentals(args, ctx):
    """查基本面并渲染成表格。"""
    code = _resolve(args.get("code"))
    try:
        data = sources.fundamentals(code, periods=args.get("periods") or 4)
    except sources.SourceError as exc:
        raise ScriptError(str(exc))

    lines = []
    profile = data["profile"]
    lines.append("公司  %s（%s）" % (profile.get("name") or data["code"], data["code"]))
    details = []
    if profile.get("industry"):
        details.append("行业 %s" % profile["industry"])
    if profile.get("listDate"):
        details.append("上市 %s" % profile["listDate"])
    if details:
        lines.append("      " + "    ".join(details))
    if profile.get("mainBusiness"):
        lines.append("主营  %s" % profile["mainBusiness"][:80])
    lines.append("")
    if data["metrics"]:
        headers = ["指标"] + data["periods"]
        rows = []
        for metric in data["metrics"]:
            cells = [metric["name"]]
            for value in metric["values"]:
                cells.append(_metric_value(value, metric["unit"]))
            rows.append(cells)
        lines.append(render.table(headers, rows, ["left"] + ["right"] * len(data["periods"])))
        lines.append("")
        lines.append("单位：金额类为亿元，每股类为元，其余为百分比；报告期用 Q1/H1/Q3/年报 标记。")
    sources_note = "数据源：巨潮资讯（公司概况） + 新浪财经（关键指标）"
    lines.append(sources_note)
    return "\n".join(lines)


@tool(
    name="stock_moneyflow",
    description="查 A 股个股资金流（主力/超大单/大单/中单/小单的净额与净占比）。"
                "若东财逐日明细取不到，会退到同花顺的累计口径并在结果里说明。",
    parameters={
        "code": {"type": "string", "description": "股票代码，例如 600519"},
        "days": {"type": "integer", "description": "取最近多少个交易日，缺省 20，上限 60"},
    },
    required=["code"],
)
def stock_moneyflow(args, ctx):
    """查资金流并渲染成表格。"""
    code = _resolve(args.get("code"))
    try:
        data = sources.moneyflow(code, days=args.get("days") or 20)
    except sources.SourceError as exc:
        raise ScriptError(str(exc))

    lines = ["%s 资金流（数据源：%s）" % (data["code"], data["source"])]
    if data["note"]:
        lines.append("注意：%s" % data["note"])
    lines.append("")
    if data["source"] == "东方财富":
        rows = []
        for row in data["rows"]:
            rows.append([row["date"], render.number(row["close"]), _percent(row["changePct"]),
                         render.amount_yi(row["main"]), _percent(row["mainPct"]),
                         render.amount_yi(row["extraLarge"]), render.amount_yi(row["large"]),
                         render.amount_yi(row["medium"]), render.amount_yi(row["small"])])
        aligns = ["left"] + ["right"] * (len(data["columns"]) - 1)
        lines.append(render.table(data["columns"], rows, aligns))
        lines.append("")
        lines.append("净额正值为流入、负值为流出；净占比是该档净额占当日成交额的比例。")
    else:
        rows = [[row["label"], row["value"]] for row in data["rows"]]
        lines.append(render.table(data["columns"], rows, ["left", "right"]))
    return "\n".join(lines)


@tool(
    name="stock_watch",
    description="维护用户的自选股列表（list 查看、add 加入、remove 移除、refresh 刷新行情）。"
                "自选股会显示在界面侧栏的面板上。",
    parameters={
        "action": {"type": "string",
                   "description": "list / add / remove / refresh 之一"},
        "codes": {"type": "string", "description": "add 与 remove 时给代码，多只用逗号分隔"},
    },
    required=["action"],
)
def stock_watch(args, ctx):
    """维护自选股。"""
    action = str(args.get("action") or "list").strip().lower()
    if action == "list":
        return _watch_summary(ctx)
    if action == "refresh":
        items, missing = _refresh_watch(ctx)
        return _watch_summary(ctx, title="已刷新 %d 只自选股" % len(items),
                              note=_missing_note(missing))
    if action in ("add", "remove", "del", "delete"):
        codes = [_resolve(item) for item in _split_codes(args.get("codes"))]
        if not codes:
            raise ScriptError("action=%s 需要给 codes" % action)
        current = store.load_watchlist(ctx)
        if action == "add":
            added = [code for code in codes if code not in current]
            for code in added:
                current.append(code)
            store.save_watchlist(ctx, current)
            if not added:
                return "这几只已经在自选股里了。"
            try:
                items, missing = _refresh_watch(ctx)
            except ScriptError as exc:
                return "已加入 %s，但一只都没取到行情（%s）。" % (
                    "、".join(_short(code) for code in added), exc)
            text = "已加入 %s。当前自选股 %d 只。" % (
                "、".join(_short(code) for code in added), len(current))
            note = _missing_note(missing)
            if note:
                text += "\n" + note
            return text
        removed = [code for code in codes if code in current]
        for code in removed:
            current.remove(code)
        store.save_watchlist(ctx, current)
        cached = store.load_quotes(ctx)
        kept = [item for item in cached["items"]
                if str(item.get("code", "")).lower() in set(current)]
        store.save_quotes(ctx, cached["updatedAt"], kept)
        if not removed:
            return "这几只本来就不在自选股里。"
        return "已移除 %s。当前自选股 %d 只。" % (
            "、".join(_short(code) for code in removed), len(current))
    raise ScriptError("不认识的 action：%s（要 list / add / remove / refresh）" % action)


# ---------------------------------------------------------------- 命令


@command(name="stock", summary="A 股行情与自选股", usage="/stock [add|del|refresh|clear] [代码...]")
def stock(tokens, raw, ctx):
    """``/stock`` 看自选股，``/stock 600519`` 看快照，``/stock add/del/refresh/clear`` 维护自选。

    自选股是**用户级**的数据（存在 ``~/.jellyfish/stock``）、本身不依赖会话，
    但这条命令**声明为需要会话**（``session_required`` 缺省 True），因此 TUI 首页不可用：
    首页敲 ``/stock`` 不会执行（按会话约定当作用户的话发给模型），首页的 ``/help``
    与补全清单里也不再列出它。

    **刻意不声明候选查询（``has_options``）**：那样按补全键会弹出选择页，
    而这条命令的用法很窄——五个子命令，``/stock`` 与 ``/stock help`` 都已列出。
    因此它的能力只有「执行」这一路，``tokens`` 恒为真实值（不会是 ``None``）。
    """
    if not tokens:
        return {"kind": "OK", "output": _watch_summary(ctx)}

    head = tokens[0].strip().lower()
    rest = tokens[1:]

    if head in ("add", "del", "remove", "clear", "refresh"):
        return _stock_maintenance(head, rest, ctx)

    if head in ("help", "?"):
        return {"kind": "OK", "output": _stock_help()}

    try:
        codes = [_resolve(item) for item in tokens]
        hits, missing = _fetch_quotes(codes)
        if not hits:
            return {"kind": "ERROR", "output": "这些代码一个都没取到行情：%s"
                                              % "、".join(_short(code) for code in codes)}
        _touch_cache(ctx, hits)
        return {"kind": "OK", "output": _render_quotes(hits, missing)}
    except ScriptError as exc:
        return {"kind": "ERROR", "output": str(exc)}


def _stock_help():
    """``/stock help`` 的正文。"""
    return "\n".join([
        "用法：",
        "  /stock                    看自选股（读缓存，不联网）",
        "  /stock 600519 000858      查实时快照",
        "  /stock add 600519         加入自选股并刷新",
        "  /stock del 600519         从自选股移除",
        "  /stock refresh            重新拉取自选股行情",
        "  /stock clear              清空自选股",
        "",
        "模型侧另有 5 个工具：stock_quote / stock_history / stock_fundamentals /",
        "stock_moneyflow / stock_watch——直接说要分析哪只股票即可。",
        "自选股存在 ~/.jellyfish/stock，面板刷新与缓存时刻见侧栏（/ui right）。",
    ])


def _stock_maintenance(head, rest, ctx):
    """处理 add / del / clear / refresh 四个子命令。"""
    if head == "clear":
        codes = store.load_watchlist(ctx)
        store.save_watchlist(ctx, [])
        store.save_quotes(ctx, "", [])
        return {"kind": "OK", "output": "已清空自选股（%d 只）。" % len(codes)}

    if head == "refresh":
        try:
            items, missing = _refresh_watch(ctx)
        except ScriptError as exc:
            return {"kind": "ERROR", "output": str(exc)}
        if not items and not missing:
            return {"kind": "OK", "output": "自选股为空。用 /stock add 600519 加入。"}
        return {"kind": "OK", "output": _watch_summary(
            ctx, title="已刷新 %d 只自选股" % len(items), note=_missing_note(missing))}

    if not rest:
        return {"kind": "ERROR", "output": "用法：/stock %s <代码>，例如 /stock %s 600519"
                                          % (head, head)}
    try:
        codes = [_resolve(item) for item in rest]
    except ScriptError as exc:
        return {"kind": "ERROR", "output": str(exc)}
    current = store.load_watchlist(ctx)

    if head == "add":
        added = [code for code in codes if code not in current]
        for code in added:
            current.append(code)
        store.save_watchlist(ctx, current)
        if not added:
            return {"kind": "OK", "output": "这几只已经在自选股里了。"}
        title = "已加入 %s" % "、".join(_short(code) for code in added)
        try:
            items, missing = _refresh_watch(ctx)
        except ScriptError as exc:
            # 加是加进去了（用户的意思就是「加」），但一只都没取到行情——如实说出来，
            # 不然面板会永远少这几只而用户不知道为什么
            return {"kind": "OK", "output": "%s，但一只都没取到行情（%s）。" % (title, exc)}
        return {"kind": "OK", "output": _watch_summary(
            ctx, title=title, note=_missing_note(missing))}

    removed = [code for code in codes if code in current]
    for code in removed:
        current.remove(code)
    store.save_watchlist(ctx, current)
    cached = store.load_quotes(ctx)
    keeping = set(current)
    store.save_quotes(ctx, cached["updatedAt"],
                      [item for item in cached["items"]
                       if str(item.get("code", "")).lower() in keeping])
    if not removed:
        return {"kind": "OK", "output": "这几只本来就不在自选股里。"}
    return {"kind": "OK", "output": "已移除 %s。当前自选股 %d 只。"
                                   % ("、".join(_short(code) for code in removed), len(current))}


# ---------------------------------------------------------------- 类型级贡献


@contributes("prompt")
def prompt(ctx):
    """把「这个脚本能干什么」与用户的自选股告诉模型。

    自选股要进提示词，是因为用户会说「看看我的股票」——模型必须知道那指的是哪几只，
    否则它只能反问。这里只读一个很小的本地文件，每轮拼提示词时现读，代价可以忽略。
    """
    codes = store.load_watchlist(ctx)
    base = ("A 股数据可用：stock_quote（实时快照）、stock_history（日线，含均线）、"
            "stock_fundamentals（公司概况与财务指标）、stock_moneyflow（资金流）、"
            "stock_watch（维护自选股）。工具只返回数据表，结论由你分析。")
    if not codes:
        return base
    cached = {}
    for item in store.load_quotes(ctx)["items"]:
        cached[str(item.get("code", "")).lower()] = item
    names = []
    for code in codes:
        item = cached.get(code.lower()) or {}
        names.append("%s（%s）" % (_short(code), item.get("name") or "名称未知"))
    return "%s 用户的自选股：%s——用户说「我的股票」时指的就是这几只。" % (base, "、".join(names))
