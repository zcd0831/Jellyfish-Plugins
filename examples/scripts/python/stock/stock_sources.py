# -*- coding: utf-8 -*-
"""取数层：所有网络请求集中在这里，每个口径都配兜底。

**为什么必须兜底**：东方财富系接口在这台机器上时通时断——实测同一个函数连续调用会出现
「一次成功、三次失败」，失败形态是远端直接断开（``RemoteDisconnected``）而不是超时。
只挂一个东财口径会让插件时好时坏，而偶发失败是最难排查的一类故障。因此凡是东财口径
都配一个非东财的备选。

**为什么 akshare 是延迟加载的**：脚本 worker 到第一次被调用才 fork，而 ``import akshare``
要拖着 pandas / lxml 一起进来（实测 1.5 秒以上）。放在模块顶层会让「只看一眼自选股」这类
本可以毫秒返回的调用也付这份代价；更糟的是 akshare 装坏了会让整个脚本加载失败，
而不是只让需要数据的调用失败。

**单位口径**（实测钉死，改之前先跑一遍探针）：腾讯快照的成交量是「手」、成交额是「万元」、
市值是「亿元」；新浪日线的 ``volume`` 是「股」、``turnover`` 是比例（×100 才是百分比）；
东财日线的成交量是「手」、换手率是百分比。
"""

import contextlib
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta

#: 腾讯行情接口的请求头。带 UA 是必要的——不带会被拒，而这不是 akshare 的默认行为，
#: 是我们自己发请求，所以要自己保证。
_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

#: 腾讯实时快照接口：一次请求可以带多只，实测 4 只耗时 0.1 秒。
_TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q=%s"

#: 快照接口的超时（它是毫秒级接口，给 8 秒已很宽裕）。
_SPOT_TIMEOUT_SECONDS = 8.0

#: 东财历史接口的超时。它偶尔会挂住，必须自己设上限，否则会一直占着 worker。
_HISTORY_TIMEOUT_SECONDS = 20.0

#: 日线一次最多返回多少根，防止把上下文撑满。
MAX_HISTORY_DAYS = 250

#: 资金流一次最多返回多少个交易日。
MAX_FLOW_DAYS = 60

_PREFIXED = re.compile(r"^(sh|sz|bj)\d{6}$", re.IGNORECASE)
_PLAIN = re.compile(r"^\d{6}$")

#: akshare 模块句柄与加载失败原因。失败原因要缓存，否则每次调用都要重付一次几秒的 import。
_akshare_module = None
_akshare_failure = None


class SourceError(Exception):
    """取数失败。

    由 ``main.py`` 转成 ``ScriptError``：内核会把异常消息的**首行**显示在轨迹行上，
    因此消息写成一句给人看的原因（「取不到 600519 的日线」），而不是堆栈。
    """


@contextlib.contextmanager
def _quiet_stdout():
    """把第三方库往 stdout 写的杂音改道到 stderr。

    脚本进程的 stdout 是**协议通道**（一帧一行的 JSON），任何第三方库往它写一个字节都会让
    宿主解析失败、把这个脚本隔离。akshare 的部分函数会用 tqdm 打进度条（实测默认走 stderr，
    但那是它的默认值而不是契约），因此这里统一兜一层。

    :return: 上下文管理器
    """
    original = sys.stdout
    sys.stdout = sys.stderr
    try:
        yield
    finally:
        sys.stdout = original


def _ak():
    """延迟加载 akshare。

    :return: akshare 模块
    :raises SourceError: akshare 不可用时
    """
    global _akshare_module, _akshare_failure
    if _akshare_module is not None:
        return _akshare_module
    if _akshare_failure is not None:
        raise SourceError(_akshare_failure)
    try:
        with _quiet_stdout():
            import akshare
    except Exception as exc:  # noqa: BLE001 - 任何加载失败都该变成一句人话
        _akshare_failure = (
            "akshare 不可用（%s）：请确认解释器 %s 里装了它，或用 plugins.configurations."
            "jellyfish-plugin-python.pythonPath 指到装好它的解释器" % (exc, sys.executable)
        )
        raise SourceError(_akshare_failure)
    _akshare_module = akshare
    return akshare


def _brief(exc):
    """把异常压成一句话，用于拼接「各口径分别为什么失败」。

    :param exc: 异常
    :return: 一行以内的描述
    """
    text = str(exc).strip().splitlines()[0] if str(exc).strip() else exc.__class__.__name__
    return text[:80]


def _num(value):
    """把可能是 NaN / None / 字符串的值转成 float。

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


def market_of(code):
    """推断代码所属市场。

    带前缀的输入原样接受（``sh000001`` 因此可以表示上证指数，而 ``000001`` 是平安银行）；
    纯数字按首位推断：6 开头是沪市，0/3 开头是深市，4/8/9 开头是北交所。

    :param code: 用户输入的代码
    :return: ``sh`` / ``sz`` / ``bj``
    :raises SourceError: 认不出时
    """
    text = (code or "").strip().lower()
    if _PREFIXED.match(text):
        return text[:2]
    if not _PLAIN.match(text):
        raise SourceError("代码格式不对：%s（要 6 位数字，或带 sh/sz/bj 前缀）" % code)
    if text[0] == "6":
        return "sh"
    if text[0] in ("0", "3"):
        return "sz"
    if text[0] in ("4", "8", "9"):
        return "bj"
    raise SourceError("认不出 %s 属于哪个市场（沪 6、深 0/3、北 4/8/9）" % code)


def symbol_of(code):
    """规范成 ``sh600519`` 形态（腾讯与新浪接口要的）。

    :param code: 用户输入的代码
    :return: 带市场前缀的代码
    """
    text = (code or "").strip().lower()
    if _PREFIXED.match(text):
        return text
    return market_of(text) + text


def plain_code(code):
    """规范成 6 位纯数字（东财与同花顺接口要的）。

    :param code: 用户输入的代码
    :return: 6 位数字
    """
    text = (code or "").strip().lower()
    return text[2:] if _PREFIXED.match(text) else text


# ------------------------------------------------------------------ 实时快照


def spot(codes):
    """批量实时快照。

    三条口径依次尝试：腾讯行情接口（最快，实测 0.1 秒）→ akshare 腾讯全市场 →
    akshare 新浪全市场。**只要拿到一只就返回**，缺的那几只由调用方如实报告——
    退回更慢的口径去补一两只，不如让用户知道哪只没取到。

    :param codes: 代码列表
    :return: ``{带市场前缀的代码: 快照字典}``；全部口径都失败时抛 SourceError
    :raises SourceError: 三条口径都拿不到数据时
    """
    symbols = []
    for code in codes:
        symbols.append(symbol_of(code))
    attempts = []
    for label, loader in (("腾讯行情", _spot_tencent),
                          ("腾讯全市场", _spot_akshare_tencent),
                          ("新浪全市场", _spot_akshare_sina)):
        try:
            quotes = loader(symbols)
        except Exception as exc:  # noqa: BLE001 - 换下一个口径
            attempts.append("%s：%s" % (label, _brief(exc)))
            continue
        if quotes:
            return quotes
        attempts.append("%s：没返回数据" % label)
    raise SourceError("取不到实时行情（%s）" % "；".join(attempts))


def _spot_tencent(symbols):
    """腾讯行情接口（裸 HTTP，GBK 编码）。

    :param symbols: 带前缀的代码列表
    :return: ``{带前缀的代码: 快照字典}``
    """
    request = urllib.request.Request(_TENCENT_QUOTE_URL % ",".join(symbols),
                                     headers={"User-Agent": _UA})
    with urllib.request.urlopen(request, timeout=_SPOT_TIMEOUT_SECONDS) as response:
        text = response.read().decode("gbk", "replace")
    quotes = {}
    for chunk in text.split(";"):
        chunk = chunk.strip()
        if "=" not in chunk:
            continue
        key, _, raw = chunk.partition("=")
        key = key.strip()
        if key.startswith("v_"):
            key = key[2:]
        # 键一律用**带市场前缀**的形态：``sh000001``（上证指数）与 ``sz000001``（平安银行）
        # 的纯数字部分完全相同，用纯代码做键会让两者互相覆盖
        key = key.lower()
        fields = raw.strip().strip('"').split("~")
        # 少于 50 个字段说明这一行不是行情（腾讯对查不到的代码不返回该行，
        # 但返回空串或短行的情况也见过），宁可丢掉也不要让下标越界
        if len(fields) < 50 or not fields[2]:
            continue
        quotes[key] = {
            "code": key,
            "name": fields[1].strip(),
            "price": _num(fields[3]),
            "prevClose": _num(fields[4]),
            "open": _num(fields[5]),
            "high": _num(fields[33]),
            "low": _num(fields[34]),
            "change": _num(fields[31]),
            "changePct": _num(fields[32]),
            "volumeLots": _num(fields[36]),
            "amountYuan": (_num(fields[37]) or 0.0) * 10000.0,
            "turnover": _num(fields[38]),
            "peTtm": _num(fields[39]),
            "amplitude": _num(fields[43]),
            "pb": _num(fields[46]),
            "floatCapYi": _num(fields[44]),
            "totalCapYi": _num(fields[45]),
            "volumeRatio": _num(fields[49]),
            "time": fields[30],
            "source": "腾讯行情",
        }
    return quotes


def _spot_akshare_tencent(symbols):
    """兜底一：akshare 的腾讯全市场快照。慢（实测 5 秒）但一次拿全市场。

    :param symbols: 带前缀的代码列表
    :return: ``{带前缀的代码: 快照字典}``
    """
    with _quiet_stdout():
        frame = _ak().stock_zh_a_spot_tx()
    wanted = {}
    for symbol in symbols:
        wanted[symbol.lower()] = symbol
    quotes = {}
    for _, row in frame.iterrows():
        code = str(row.get("code", "")).lower()
        if code not in wanted:
            continue
        quotes[code] = {
            "code": code,
            "name": str(row.get("name", "")).strip(),
            "price": _num(row.get("zxj")),
            "prevClose": None,
            "open": None,
            "high": None,
            "low": None,
            "change": _num(row.get("zd")),
            "changePct": _num(row.get("zdf")),
            "volumeLots": _num(row.get("volume")),
            "amountYuan": (_num(row.get("turnover")) or 0.0) * 10000.0,
            "turnover": _num(row.get("hsl")),
            "peTtm": _num(row.get("pe_ttm")),
            "amplitude": _num(row.get("zf")),
            "pb": _num(row.get("pn")),
            "floatCapYi": _num(row.get("ltsz")),
            "totalCapYi": _num(row.get("zsz")),
            "volumeRatio": _num(row.get("lb")),
            "time": "",
            "source": "腾讯全市场",
        }
    return quotes


def _spot_akshare_sina(symbols):
    """兜底二：akshare 的新浪全市场快照。最慢（实测 24 秒），只在腾讯两条口径都挂时用。

    :param symbols: 带前缀的代码列表
    :return: ``{带前缀的代码: 快照字典}``
    """
    with _quiet_stdout():
        frame = _ak().stock_zh_a_spot()
    wanted = set()
    for symbol in symbols:
        wanted.add(symbol.lower())
    quotes = {}
    for _, row in frame.iterrows():
        code = str(row.get("代码", "")).lower()
        if code not in wanted:
            continue
        quotes[code] = {
            "code": code,
            "name": str(row.get("名称", "")).strip(),
            "price": _num(row.get("最新价")),
            "prevClose": _num(row.get("昨收")),
            "open": _num(row.get("今开")),
            "high": _num(row.get("最高")),
            "low": _num(row.get("最低")),
            "change": _num(row.get("涨跌额")),
            "changePct": _num(row.get("涨跌幅")),
            "volumeLots": (_num(row.get("成交量")) or 0.0) / 100.0,
            "amountYuan": _num(row.get("成交额")),
            "turnover": None,
            "peTtm": None,
            "amplitude": None,
            "pb": None,
            "floatCapYi": None,
            "totalCapYi": None,
            "volumeRatio": None,
            "time": str(row.get("时间戳", "")),
            "source": "新浪全市场",
        }
    return quotes


# ------------------------------------------------------------------ 日线


def history(code, days=60, adjust="qfq", ma_windows=(5, 20)):
    """日线区间数据，含可选均线。

    主口径是新浪（快、稳），兜底是东财（带涨跌幅与换手率，但在这台机器上时通时断）。
    两个口径的列名与单位都不一样，统一到同一份结构后再返回。

    :param code: 代码
    :param days: 取最近多少个交易日
    :param adjust: ``""`` 不复权 / ``qfq`` 前复权 / ``hfq`` 后复权
    :param ma_windows: 要算的均线窗口，空表示不算
    :return: ``{"code", "name", "adjust", "source", "rows": [...]}``
    :raises SourceError: 两个口径都失败时
    """
    if adjust not in ("", "qfq", "hfq"):
        adjust = "qfq"
    limit = max(5, min(int(days or 60), MAX_HISTORY_DAYS))
    end = datetime.now()
    # 交易日只占自然日的七成左右，再留点余量，免得取够不 limit 根
    start = end - timedelta(days=int(limit * 1.7) + 25)
    market = market_of(code)
    attempts = []
    frame = None
    source = None

    if market in ("sh", "sz"):
        try:
            with _quiet_stdout():
                frame = _ak().stock_zh_a_daily(symbol=symbol_of(code),
                                               start_date=start.strftime("%Y%m%d"),
                                               end_date=end.strftime("%Y%m%d"),
                                               adjust=adjust)
            source = "新浪财经"
        except Exception as exc:  # noqa: BLE001 - 换东财
            attempts.append("新浪：%s" % _brief(exc))
            frame = None

    if frame is None or len(frame) == 0:
        try:
            with _quiet_stdout():
                frame = _ak().stock_zh_a_hist(symbol=plain_code(code), period="daily",
                                              start_date=start.strftime("%Y%m%d"),
                                              end_date=end.strftime("%Y%m%d"),
                                              adjust=adjust,
                                              timeout=_HISTORY_TIMEOUT_SECONDS)
            source = "东方财富"
        except Exception as exc:  # noqa: BLE001 - 两个口径都挂了
            attempts.append("东财：%s" % _brief(exc))
            raise SourceError("取不到 %s 的日线（%s）" % (code, "；".join(attempts)))

    rows = _history_rows(frame, source)
    if not rows:
        raise SourceError("取不到 %s 的日线（返回了空数据）" % code)
    rows = rows[-limit:]
    _apply_moving_average(rows, ma_windows)
    return {
        "code": plain_code(code),
        "name": _name_of(code),
        "adjust": adjust,
        "source": source,
        "rows": rows,
    }


def _name_of(code):
    """顺带取一次名称，取不到就留空。

    工具输出里带上名称能让模型少犯「把代码记错公司」的错，而快照接口是毫秒级的，
    多这一次请求很划算。**但它绝不能让日线失败**：名称只是修饰，数据才是结果，
    因此这里吞掉异常。
    """
    try:
        quote = spot([code]).get(symbol_of(code).lower())
        return (quote or {}).get("name") or ""
    except Exception:  # noqa: BLE001 - 名称取不到不影响日线
        return ""


def _history_rows(frame, source):
    """把两个口径的日线帧统一成同一份行结构。

    涨跌幅与振幅两个口径都不一定给（新浪就没有），因此一律用相邻收盘价自己算。
    代价是**除权日那天会有偏差**（前复权序列在该日不连续），这不是错误而是口径本身的性质。

    :param frame: akshare 返回的 DataFrame
    :param source: 数据源名，决定按哪套列名解读
    :return: 行列表
    """
    rows = []
    previous_close = None
    for _, row in frame.iterrows():
        if source == "新浪财经":
            date = str(row.get("date", ""))[:10]
            open_price = _num(row.get("open"))
            close = _num(row.get("close"))
            high = _num(row.get("high"))
            low = _num(row.get("low"))
            volume = (_num(row.get("volume")) or 0.0) / 100.0
            amount = _num(row.get("amount"))
            turnover = _num(row.get("turnover"))
            turnover = turnover * 100.0 if turnover is not None else None
        else:
            date = str(row.get("日期", ""))[:10]
            open_price = _num(row.get("开盘"))
            close = _num(row.get("收盘"))
            high = _num(row.get("最高"))
            low = _num(row.get("最低"))
            volume = _num(row.get("成交量"))
            amount = _num(row.get("成交额"))
            turnover = _num(row.get("换手率"))
        change_pct = None
        amplitude = None
        if previous_close and close is not None:
            change_pct = (close - previous_close) / previous_close * 100.0
            if high is not None and low is not None:
                amplitude = (high - low) / previous_close * 100.0
        rows.append({
            "date": date,
            "open": open_price,
            "close": close,
            "high": high,
            "low": low,
            "volumeLots": volume,
            "amountYuan": amount,
            "changePct": change_pct,
            "amplitude": amplitude,
            "turnover": turnover,
            "ma": {},
        })
        if close is not None:
            previous_close = close
    return rows


def _apply_moving_average(rows, ma_windows):
    """就地算简单移动平均。

    :param rows: 行列表
    :param ma_windows: 窗口列表
    """
    closes = [row["close"] for row in rows]
    for window in ma_windows or ():
        try:
            size = int(window)
        except (TypeError, ValueError):
            continue
        if size < 2:
            continue
        running = 0.0
        for index, value in enumerate(closes):
            running += value
            if index >= size:
                running -= closes[index - size]
            if index >= size - 1:
                rows[index]["ma"][size] = running / float(size)


# ------------------------------------------------------------------ 基本面


#: 财务摘要里以「元」为单位的指标——它们要缩成亿元，否则一屏全是十几位数字。
_AMOUNT_METRICS = ("营业总收入", "营业成本", "净利润", "扣非净利润", "归母净利润",
                   "股东权益合计(净资产)", "经营现金流量净额", "商誉")


def fundamentals(code, periods=4):
    """公司概况 + 关键财务指标。

    :param code: 代码
    :param periods: 取最近多少期（定期报告），1~8
    :return: ``{"code", "profile": {...}, "metrics": [...], "periods": [...]}``
    :raises SourceError: 两个口径都失败时
    """
    limit = max(1, min(int(periods or 4), 8))
    title = plain_code(code)
    profile = {}
    attempts = []

    try:
        with _quiet_stdout():
            frame = _ak().stock_profile_cninfo(symbol=title)
        if len(frame) > 0:
            row = frame.iloc[0]
            profile = {
                "name": str(row.get("A股简称") or "").strip(),
                "industry": str(row.get("所属行业") or "").strip(),
                "listDate": str(row.get("上市日期") or "").strip(),
                "mainBusiness": str(row.get("主营业务") or "").strip(),
            }
    except Exception as exc:  # noqa: BLE001 - 概况缺了不影响指标
        attempts.append("概况：%s" % _brief(exc))

    metrics = []
    periods_label = []
    try:
        with _quiet_stdout():
            abstract = _ak().stock_financial_abstract(symbol=title)
        columns = [str(name) for name in abstract.columns]
        # 前两列是「选项 / 指标」，其后全是报告期列，最新的在最前面
        if len(columns) > 2:
            periods_label = columns[2:2 + limit]
        chosen = abstract[abstract["选项"] == "常用指标"]
        for _, row in chosen.iterrows():
            values = []
            for column in periods_label:
                values.append(_num(row.get(column)))
            metrics.append({"name": str(row.get("指标")), "values": values,
                            "unit": _unit_of(str(row.get("指标")))})
    except Exception as exc:  # noqa: BLE001 - 指标缺了不影响概况
        attempts.append("指标：%s" % _brief(exc))

    if not profile and not metrics:
        raise SourceError("取不到 %s 的基本面（%s）" % (code, "；".join(attempts)))
    return {
        "code": title,
        "profile": profile,
        "metrics": metrics,
        "periods": [_period_label(name) for name in periods_label],
    }


def _period_label(raw):
    """把 ``20260630`` 这类报告期压成 ``26H1`` 形态。

    用半年标记（H1/H2）而不是 Q1~Q4，是因为 A 股定期报告实际只有四个披露时点，
    而「Q2 与 H1」「Q4 与年报」是同一份数据——用 H 标记不会让人误以为拿到了两份不同的数。

    :param raw: 原始报告期字符串
    :return: 压缩后的标签
    """
    text = str(raw).strip()
    if len(text) != 8 or not text.isdigit():
        return text
    year = text[2:4]
    suffix = {"0331": "Q1", "0630": "H1", "0930": "Q3", "1231": "年报"}.get(text[4:])
    return year + (suffix or text[4:6])


def _unit_of(metric_name):
    """判断一个财务指标该用什么单位渲染。

    :param metric_name: 指标名
    :return: ``"yi"``（亿元）/ ``"share"``（元/股）/ ``"percent"``（百分比）
    """
    if metric_name in _AMOUNT_METRICS:
        return "yi"
    if metric_name.startswith("每股") or metric_name.startswith("基本每股") \
            or metric_name.startswith("摊薄每股"):
        return "share"
    return "percent"


# ------------------------------------------------------------------ 资金流


def moneyflow(code, days=20):
    """个股资金流。

    主口径是东财（逐日的五档净额与净占比），但实测限制频、成功率很低；兜底是同花顺的
    个股资金流排行——它只有一个「资金流入净额」总量，而且档位是「即时 / 3 日 / 5 日 / 10 日 /
    20 日排行」这种粗粒度，因此按 ``days`` 就近取档。两条口径的信息量差很多，
    返回结构里带 ``source`` 与 ``note``，由调用方如实告诉用户。

    :param code: 代码
    :param days: 关心的交易日跨度
    :return: ``{"code", "source", "note", "columns", "rows"}``
    :raises SourceError: 两条口径都失败时
    """
    limit = max(1, min(int(days or 20), MAX_FLOW_DAYS))
    market = market_of(code)
    attempts = []

    try:
        with _quiet_stdout():
            frame = _ak().stock_individual_fund_flow(stock=plain_code(code), market=market)
        rows = []
        for _, row in frame.tail(limit).iterrows():
            rows.append({
                "date": str(row.get("日期", ""))[:10],
                "close": _num(row.get("收盘价")),
                "changePct": _num(row.get("涨跌幅")),
                "main": _num(row.get("主力净流入-净额")),
                "mainPct": _num(row.get("主力净流入-净占比")),
                "extraLarge": _num(row.get("超大单净流入-净额")),
                "large": _num(row.get("大单净流入-净额")),
                "medium": _num(row.get("中单净流入-净额")),
                "small": _num(row.get("小单净流入-净额")),
            })
        if rows:
            return {"code": plain_code(code), "source": "东方财富", "note": "",
                    "columns": ["日期", "收盘", "涨跌幅", "主力净额", "主力净占比",
                                "超大单", "大单", "中单", "小单"],
                    "rows": rows, "days": limit}
    except Exception as exc:  # noqa: BLE001 - 换同花顺
        attempts.append("东财：%s" % _brief(exc))

    rank, span = _nearest_rank(limit)
    try:
        with _quiet_stdout():
            frame = _ak().stock_fund_flow_individual(symbol=rank)
        hit = frame[frame["股票代码"].astype(str) == plain_code(code)]
        if len(hit) > 0:
            row = hit.iloc[0]
            return {"code": plain_code(code), "source": "同花顺", "note": (
                "东财的逐日明细这次没取到，已退到同花顺的「%s」口径——它只有一个累计净额，"
                "没有主力/超大单/大单的五档拆分。" % rank),
                "columns": ["口径", "资金流入净额"],
                "rows": [{"label": "%s累计" % span,
                          "value": str(row.get("资金流入净额", "")).strip()}],
                "days": limit}
    except Exception as exc:  # noqa: BLE001 - 两条口径都挂了
        attempts.append("同花顺：%s" % _brief(exc))

    raise SourceError("取不到 %s 的资金流（%s）" % (code, "；".join(attempts)))


def _nearest_rank(days):
    """把「想要多少天」映射成同花顺的排行档位。

    :param days: 交易日跨度
    :return: ``(档位名, 中文跨度描述)``
    """
    for threshold, rank, span in ((1, "即时", "当日"), (3, "3日排行", "3 日"),
                                  (5, "5日排行", "5 日"), (10, "10日排行", "10 日")):
        if days <= threshold:
            return rank, span
    return "20日排行", "20 日"


def self_check():
    """给开发期用的一句话自检：解释器与脚本目录。

    :return: 描述字符串
    """
    return "python=%s data=%s" % (sys.version.split()[0],
                                  os.path.expanduser("~/.jellyfish/stock"))
