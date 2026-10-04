# -*- coding: utf-8 -*-
"""落盘层：自选股清单与快照缓存。

两条硬约束：

1. **原子写**：面板是**另一个脚本进程**（``stockpanel``），它会随时读缓存文件。
   直接 ``open(path, "w")`` 会让读方有概率读到半截 JSON——表现是「面板偶尔整块消失」，
   而这种偶发故障最难归因。因此一律写临时文件再 ``os.replace`` 换上去
   （同一目录内换名是原子的）。
2. **有界**：缓存只存自选股那几只，不存全市场。面板在渲染线程上读它，
   文件越大、解析越慢，界面就越容易被拖住。

自选股存在**用户级**目录（不是脚本目录、也不是会话）：它是用户的偏好，
而脚本目录随时可能被重新拷贝覆盖。
"""

import json
import os
import tempfile

#: 缺省数据目录。**必须与 stockpanel/main.py 里的同名常量保持一致**——
#: 两个脚本是独立进程、各自从自己的配置段读 ``dataDir``，缺省值不一致就会各写一份。
DEFAULT_DATA_DIR = os.path.join(os.path.expanduser("~"), ".jellyfish", "stock")


def data_dir(ctx):
    """取数据目录：配置段 ``dataDir`` → 缺省 ``~/.jellyfish/stock``。

    :param ctx: 脚本上下文，可为 None
    :return: 目录路径（已展开 ``~``）
    """
    configured = None
    if ctx is not None:
        try:
            configured = (ctx.configuration or {}).get("dataDir")
        except Exception:  # noqa: BLE001 - 配置段形态不对时回落缺省，不要让面板读不到文件
            configured = None
    return os.path.expanduser(configured or DEFAULT_DATA_DIR)


def watchlist_path(ctx):
    """自选股文件路径。

    :param ctx: 脚本上下文
    :return: 路径
    """
    return os.path.join(data_dir(ctx), "watchlist.json")


def quotes_path(ctx):
    """快照缓存路径。

    :param ctx: 脚本上下文
    :return: 路径
    """
    return os.path.join(data_dir(ctx), "quotes.json")


def read_json(path, default):
    """读一份 JSON；文件不存在或内容坏掉时回落缺省值。

    :param path: 路径
    :param default: 缺省值
    :return: 解析结果或缺省值
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (IOError, OSError, ValueError):
        return default


def write_json(path, payload):
    """原子写一份 JSON。

    :param path: 路径
    :param payload: 可序列化的内容
    """
    directory = os.path.dirname(path) or "."
    if not os.path.isdir(directory):
        os.makedirs(directory)
    handle, temporary = tempfile.mkstemp(dir=directory, prefix=".tmp-", suffix=".json")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        if os.path.exists(temporary):
            os.remove(temporary)
        raise


def load_watchlist(ctx):
    """读自选股代码列表。

    :param ctx: 脚本上下文
    :return: 代码列表（6 位数字，去重且保持顺序）
    """
    payload = read_json(watchlist_path(ctx), {})
    codes = payload.get("codes") if isinstance(payload, dict) else None
    if not isinstance(codes, list):
        return []
    ordered = []
    for code in codes:
        text = str(code).strip()
        if text and text not in ordered:
            ordered.append(text)
    return ordered


def save_watchlist(ctx, codes):
    """写自选股代码列表。

    :param ctx: 脚本上下文
    :param codes: 代码列表
    """
    write_json(watchlist_path(ctx), {"codes": list(codes)})


def load_quotes(ctx):
    """读快照缓存。

    :param ctx: 脚本上下文
    :return: ``{"updatedAt": ..., "items": [...]}``；没有缓存时 ``items`` 为空
    """
    payload = read_json(quotes_path(ctx), {})
    if not isinstance(payload, dict):
        return {"updatedAt": "", "items": []}
    items = payload.get("items")
    return {
        "updatedAt": str(payload.get("updatedAt") or ""),
        "items": items if isinstance(items, list) else [],
    }


def save_quotes(ctx, updated_at, items):
    """写快照缓存。

    :param ctx: 脚本上下文
    :param updated_at: 刷新时刻（``HH:MM`` 形态，直接给面板显示）
    :param items: ``[{"code", "name", "price", "changePct"}]``
    """
    write_json(quotes_path(ctx), {"updatedAt": updated_at, "items": list(items)})
