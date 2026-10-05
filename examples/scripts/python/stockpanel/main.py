# -*- coding: utf-8 -*-
"""侧栏面板：自选股一览。

它**只读** ``stock`` 脚本写下的缓存文件，不联网、不 import akshare。这是刻意的，原因是内核的
硬约束：面板处理器在**界面渲染线程上同步执行、且没有任何超时**（``UiContributions`` 只在
处理器抛错时记一条告警并跳过），因此一次网络请求就会冻住整个界面——连 ``Esc`` 都按不动。
代价是面板显示的是**上次刷新的快照**，所以刷新时刻写在标题上。

这也是它必须与取数脚本分成两个进程的原因：脚本 worker 同时只服务一个在途请求，
若共用一个 worker，回合运行中界面每秒重拉一次面板时，每一次都会排在某个取数请求后面。

面板尺寸由外壳决定，插件无权控制：侧栏起点约 20 列（内容区 18 列）、单面板最多 8 行、
终端窄于 80 列时整块隐藏。因此这里按 **24 显示列 × 8 行**设计——一行一只股票，
三列：名称 | 现价 | 涨跌幅。

涨跌用颜色说话：**涨红、跌绿、平灰**。档位名与颜色是反的（涨借 ``ERROR`` 的红色、
跌借 ``SUCCESS`` 的绿色），原因见 :func:`_change_emphasis`——框架只给语义档位，
颜色由外壳统一映射，插件说不了「红」。
"""

import json
import os
import unicodedata
from datetime import datetime

from jellyfish_sdk import contributes

#: 缺省数据目录。**必须与 stock/stock_store.py 里的同名常量保持一致**——
#: 两个脚本是独立进程、各自从自己的配置段读 ``dataDir``，缺省值不一致就会各写一份。
DEFAULT_DATA_DIR = os.path.join(os.path.expanduser("~"), ".jellyfish", "stock")

#: 一行占多少显示列：名称 8 + 空格 1 + 现价 7 + 空格 1 + 涨跌幅 7 = 24。
#:
#: **24 是算出来的，不是挑的**：面板内容宽决定侧栏宽（外壳按「内容宽 + 边框 2」向上取整，
#: 再夹进 ``[20, 终端宽/4]``），因此每多一列都要求终端更宽才不折行——24 列需要终端 ≥ 104 列。
#: 窄于这个宽度时一行会折成两行，8 行上限实际只能显示 4 只股票。这是「多显示一个现价」
#: 的代价；取 24 是「名称还放得下 4 个汉字」与「要求的终端别太宽」之间的折中。
NAME_WIDTH = 8
PRICE_WIDTH = 7
CHANGE_WIDTH = 7
ROW_WIDTH = NAME_WIDTH + 1 + PRICE_WIDTH + 1 + CHANGE_WIDTH

#: 面板最多画几行（与内核的单面板行数上限一致）。
MAX_ROWS = 8


def _num(value):
    """把可能是 None / 字符串的值转成 float。

    :param value: 原始值
    :return: float；无法转换或 NaN 时返回 None
    """
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return None
    if numeric != numeric:
        return None
    return numeric


def _width(text):
    """按终端显示列数算宽度（中日韩与全角字符算 2 列）。

    面板里必须用这个口径：股票名称全是汉字，用 ``len()`` 会让「贵州茅台」和「XD安徽凤」
    看起来一样宽，涨跌幅就再也对齐不了。

    :param text: 文本
    :return: 列数
    """
    total = 0
    for char in text or "":
        if unicodedata.combining(char):
            continue
        total += 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
    return total


def _fit(text, width):
    """按显示宽度截断，超出部分换成省略号。

    :param text: 文本
    :param width: 目标列数
    :return: 文本
    """
    text = "" if text is None else str(text)
    if _width(text) <= width:
        return text
    keep = width - 1
    if keep <= 0:
        return "…"
    kept = []
    used = 0
    for char in text:
        step = 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
        if used + step > keep:
            break
        kept.append(char)
        used += step
    return "".join(kept) + "…"


def _pad(text, width, align="left"):
    """按显示宽度补空格。

    :param text: 文本
    :param width: 目标列数
    :param align: ``left`` / ``right``
    :return: 文本
    """
    text = "" if text is None else str(text)
    gap = max(0, width - _width(text))
    return (" " * gap + text) if align == "right" else (text + " " * gap)


def _data_dir(ctx):
    """取数据目录：配置段 ``dataDir`` → 缺省 ``~/.jellyfish/stock``。

    :param ctx: 脚本上下文
    :return: 目录路径
    """
    configured = None
    if ctx is not None:
        try:
            configured = (ctx.configuration or {}).get("dataDir")
        except Exception:  # noqa: BLE001 - 配置段形态不对时回落缺省
            configured = None
    return os.path.expanduser(configured or DEFAULT_DATA_DIR)


def _load(ctx):
    """读快照缓存。

    :param ctx: 脚本上下文
    :return: ``{"updatedAt": ..., "items": [...]}``；没有可用缓存时返回 None
    """
    path = os.path.join(_data_dir(ctx), "quotes.json")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
    except (IOError, OSError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    items = payload.get("items")
    if not isinstance(items, list):
        return None
    # 过滤掉形态不对的条目：缓存文件是另一个进程写的，面板在渲染线程上跑，
    # 这里因为一条坏数据抛异常会让整块面板消失，代价远大于少画一行
    cleaned = [item for item in items if isinstance(item, dict)]
    if not cleaned:
        return None
    return {"updatedAt": str(payload.get("updatedAt") or ""), "items": cleaned}


def _title(updated_at):
    """标题带上刷新时刻；缓存跨天时退到日期。

    时刻是这块面板上**最重要的一行字**：面板从不主动联网，因此它显示的一律是快照，
    没有时刻的话用户会把它当成实时行情。

    :param updated_at: ISO 时刻
    :return: 标题
    """
    moment = None
    try:
        moment = datetime.strptime(str(updated_at)[:19], "%Y-%m-%dT%H:%M:%S")
    except (TypeError, ValueError):
        moment = None
    if moment is None:
        return "自选"
    if moment.date() != datetime.now().date():
        return "自选 %s" % moment.strftime("%m-%d")
    return "自选 %s" % moment.strftime("%H:%M")


def _price_text(value, width):
    """把现价渲染成放得进 ``width`` 列的文本。

    **价格绝不截断**——``12345.6…`` 是个错误的数字，比少一位小数更坏。因此放不下时
    逐级降精度（两位 → 一位 → 整数），而不是交给 :func:`_fit`。A 股现价绝大多数不超过
    四位数（``9999.99`` 正好 7 列），降到整数那一步实际不会被触发。

    :param value: 原始值
    :param width: 目标列数
    :return: 文本；取不到时返回 ``-``
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    for digits in (2, 1, 0):
        text = "%.*f" % (digits, numeric)
        # 价格只含数字、小数点与负号，全是一列宽，因此 len() 在这里与显示宽度等价
        if len(text) <= width:
            return text
    # 极端大数（≥ 1000 万）：一个价格字段放不下，用科学计数法至少保住量级
    return "%.1e" % numeric


def _change_emphasis(change):
    """取涨跌幅的强调档位：涨红、跌绿、平灰。

    **档位名与颜色是反的**（涨用 ``ERROR``、跌用 ``SUCCESS``），这是 A 股习惯与
    「档位是语义而不是外观」这条设计撞出来的结果：框架只给语义档位，颜色由外壳唯一的
    映射点（``jellyfish-tui/UiRender``）决定，插件说不了「红」也说不了「绿」。
    这里借 ``ERROR`` 的红色表达「涨」——对看盘的人来说「涨是红的」远比
    ``ERROR`` 这个字面意思重要；平盘与取不到都用 ``DIM``（终端里的灰）。

    :param change: 涨跌幅数值，可为 None（取不到）
    :return: 档位名
    """
    if change is None:
        return "DIM"
    if change > 0:
        return "ERROR"
    if change < 0:
        return "SUCCESS"
    return "DIM"


def _row(item):
    """把一条缓存记录渲染成面板上的一行：名称 | 现价 | 涨跌幅。

    :param item: ``{"code", "name", "price", "changePct"}``
    :return: 行字典
    """
    name = item.get("name") or item.get("code") or "-"
    change = _num(item.get("changePct"))
    change_text = "-" if change is None else "%+.2f%%" % change
    return {"segments": [
        {"text": _pad(_fit(name, NAME_WIDTH), NAME_WIDTH) + " ", "emphasis": "NORMAL"},
        # 现价跟着涨跌幅走会更抢眼，但那样一整行都在闪；这里只让涨跌幅上色，
        # 价格保持正文色——面板的用处是「哪只在动」，不是「价格是多少」
        {"text": _pad(_price_text(item.get("price"), PRICE_WIDTH), PRICE_WIDTH, "right") + " ",
         "emphasis": "NORMAL"},
        {"text": _pad(change_text, CHANGE_WIDTH, "right"),
         "emphasis": _change_emphasis(change)},
    ]}


@contributes("panel")
def panel(ctx):
    """自选股面板。

    返回 ``None`` 表示「这次没有要显示的」——自选股为空、或还没刷新过行情时就不显示，
    而不是画一块空面板（空面板比没有面板更让人困惑）。

    ``region`` 只是**软建议**：一块区域同时只显示一个面板，抢同一区域的插件由用户用
    ``/ui`` 切换，因此这里不假设自己一定显示。建议右栏是因为一行一只的窄条正合右栏的
    宽度预算（约 20 列）。
    """
    cached = _load(ctx)
    if cached is None:
        return None
    items = cached["items"]
    lines = [_row(item) for item in items[:MAX_ROWS]]
    if len(items) > MAX_ROWS:
        # 自己先收口：让「有多少只没显示」是一句明确的话，而不是外壳那句通用的截断提示
        overflow = "…还有 %d 只" % (len(items) - MAX_ROWS + 1)
        lines[-1] = {"segments": [{"text": _pad(_fit(overflow, ROW_WIDTH), ROW_WIDTH),
                                   "emphasis": "DIM"}]}
    return {"title": _title(cached["updatedAt"]), "region": "RIGHT", "lines": lines}
