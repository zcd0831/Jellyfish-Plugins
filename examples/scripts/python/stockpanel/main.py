# -*- coding: utf-8 -*-
"""侧栏面板：自选股一览。

它**只读** ``stock`` 脚本写下的缓存文件，不联网、不 import akshare。这是刻意的，原因是内核的
硬约束：面板处理器在**界面渲染线程上同步执行、且没有任何超时**（``UiContributions`` 只在
处理器抛错时记一条告警并跳过），因此一次网络请求就会冻住整个界面——连 ``Esc`` 都按不动。
代价是面板显示的是**上次刷新的快照**，所以刷新时刻写在标题上。

这也是它必须与取数脚本分成两个进程的原因：脚本 worker 同时只服务一个在途请求，
若共用一个 worker，回合运行中界面每秒重拉一次面板时，每一次都会排在某个取数请求后面。

面板尺寸由外壳决定，插件无权控制：侧栏起点约 20 列（内容区 18 列）、单面板最多 8 行、
终端窄于 80 列时整块隐藏。因此这里按 **18 显示列 × 8 行**设计——一行一只股票。
"""

import json
import os
import unicodedata
from datetime import datetime

from jellyfish_sdk import contributes

#: 缺省数据目录。**必须与 stock/stock_store.py 里的同名常量保持一致**——
#: 两个脚本是独立进程、各自从自己的配置段读 ``dataDir``，缺省值不一致就会各写一份。
DEFAULT_DATA_DIR = os.path.join(os.path.expanduser("~"), ".jellyfish", "stock")

#: 一行占多少显示列：名称 10 + 空格 1 + 涨跌幅 7。
NAME_WIDTH = 10
CHANGE_WIDTH = 7
ROW_WIDTH = NAME_WIDTH + 1 + CHANGE_WIDTH

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


def _row(item):
    """把一条缓存记录渲染成面板上的一行。

    :param item: ``{"code", "name", "price", "changePct"}``
    :return: 行字典
    """
    name = item.get("name") or item.get("code") or "-"
    change = _num(item.get("changePct"))
    if change is None:
        text = "-"
        emphasis = "DIM"
    else:
        text = "%+.2f%%" % change
        if change > 0:
            emphasis = "ACCENT"
        elif change < 0:
            emphasis = "ERROR"
        else:
            emphasis = "DIM"
    return {"segments": [
        {"text": _pad(_fit(name, NAME_WIDTH), NAME_WIDTH) + " ", "emphasis": "NORMAL"},
        {"text": _pad(text, CHANGE_WIDTH, "right"), "emphasis": emphasis},
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
