# -*- coding: utf-8 -*-
"""渲染层：按终端显示宽度对齐的纯文本表格。

**为什么必须自己算宽度**：内核按 Unicode 东亚宽度口径计算列宽（``DisplayWidth``），A 股
名称里全是汉字、还有一个半角空格（「五 粮 液」），用 ``len()`` 数会把每个汉字算成 1 列，
表格立刻歪掉。这里用同一个口径（``unicodedata.east_asian_width`` 的 W/F 算 2 列）。

**为什么单位要在这里缩**：a 股的成交额是十二位数字（4797246636.0），一列数字铺开就没法读。
缩成「亿 / 万手」之后一屏能放下，而精度损失对「看一眼行情」这个用途没有影响。
"""

import unicodedata


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


def display_width(text):
    """按终端显示列数算宽度。

    :param text: 文本
    :return: 列数；东亚宽字符与全角字符算 2 列，组合符算 0 列
    """
    total = 0
    for char in text or "":
        if unicodedata.combining(char):
            continue
        total += 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
    return total


def pad(text, width, align="left"):
    """按显示宽度补空格。

    :param text: 文本
    :param width: 目标列数
    :param align: ``left`` / ``right`` / ``center``
    :return: 补齐后的文本
    """
    text = "" if text is None else str(text)
    gap = max(0, width - display_width(text))
    if align == "right":
        return " " * gap + text
    if align == "center":
        left = gap // 2
        return " " * left + text + " " * (gap - left)
    return text + " " * gap


def truncate(text, width, ellipsis="…"):
    """按显示宽度截断，并留出省略号的位置。

    :param text: 文本
    :param width: 目标列数
    :param ellipsis: 省略号
    :return: 截断后的文本
    """
    text = "" if text is None else str(text)
    if display_width(text) <= width:
        return text
    keep = width - display_width(ellipsis)
    if keep <= 0:
        return ellipsis
    kept = []
    used = 0
    for char in text:
        step = 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
        if used + step > keep:
            break
        kept.append(char)
        used += step
    return "".join(kept) + ellipsis


def table(headers, rows, aligns=None, indent="  "):
    """渲染对齐表格：列宽取该列所有单元格的最大显示宽度。

    :param headers: 表头
    :param rows: 行列表，元素是单元格字符串列表
    :param aligns: 每列的对齐方式，缺省全左对齐
    :param indent: 每行前缀
    :return: 表格文本
    """
    widths = [display_width(header) for header in headers]
    cells = []
    for row in rows:
        line = ["" if cell is None else str(cell) for cell in row]
        cells.append(line)
        for index, cell in enumerate(line):
            if index < len(widths):
                widths[index] = max(widths[index], display_width(cell))
    lines = [indent + "  ".join(pad(header, widths[index])
                                for index, header in enumerate(headers))]
    lines.append(indent + "  ".join("-" * width for width in widths))
    for line in cells:
        parts = []
        for index, width in enumerate(widths):
            cell = line[index] if index < len(line) else ""
            align = aligns[index] if aligns and index < len(aligns) else "left"
            parts.append(pad(cell, width, align))
        lines.append(indent + "  ".join(parts))
    return "\n".join(lines)


def number(value, digits=2):
    """定长小数，缺值显示 ``-``。

    :param value: 数值
    :param digits: 小数位
    :return: 文本
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    return ("%%.%df" % digits) % numeric


def signed_pct(value, digits=2):
    """带正负号的百分比，缺值显示 ``-``。

    :param value: 百分数
    :param digits: 小数位
    :return: 文本
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    return ("%+." + str(int(digits)) + "f%%") % numeric


def amount_yi(value):
    """把「元」缩成亿 / 万。

    :param value: 以元为单位的数值
    :return: 文本
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    sign = "-" if numeric < 0 else ""
    magnitude = abs(numeric)
    if magnitude >= 1e8:
        return "%s%.2f亿" % (sign, magnitude / 1e8)
    if magnitude >= 1e4:
        return "%s%.1f万" % (sign, magnitude / 1e4)
    return "%s%.0f" % (sign, magnitude)


def amount_yi_plain(value):
    """把「亿元」为单位的数值原样格式化（腾讯接口给的市值就是这个单位）。

    :param value: 以亿为单位的数值
    :return: 文本
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    if abs(numeric) >= 1e4:
        return "%.2f万亿" % (numeric / 1e4)
    return "%.2f亿" % numeric


def lots(value):
    """把「手」缩成万手。

    :param value: 以手为单位的数值
    :return: 文本
    """
    numeric = _num(value)
    if numeric is None:
        return "-"
    if abs(numeric) >= 1e4:
        return "%.2f万手" % (numeric / 1e4)
    return "%.0f手" % numeric
