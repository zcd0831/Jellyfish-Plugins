"""联网搜索与网页抓取（Python 脚本插件，零第三方依赖）。

它演示的是一个**真实形态**的脚本插件：有自己的配置段、有外部 HTTP 调用、有自己的错误语义，
并且只依赖标准库——因此拷进 ``scripts/python/`` 就能用，不需要 ``pip install`` 任何东西。

三件事值得照着看：

1. **密钥与端点从 ``ctx.configuration`` 读**，而不是从环境变量。脚本进程的环境是严格白名单
   （不携带 JVM 的密钥），配置段是脚本拿密钥的唯一通道，而且 ``${ENV}`` 由内核插值；
2. **失败要抛 ``ScriptError``**，不要返回 ``{"error": ...}``——后者会被当成正常输出，
   模型会以为「一切正常，结果就是这几个字」；
3. **摘要走 ``ToolResult``**：正文给模型，``summary`` 给人（外壳接在工具名后面显示）。

**开箱即用**：什么都不配也能搜——``provider`` 缺省是 ``auto``，会兜底到 Exa 的公开 MCP
端点（不需要任何 key）。那条路会把查询发到 Exa 的服务器上，介意的话配一个自建端点，
``auto`` 就会优先用它（见 ``_resolve_provider``）。

配置段（``plugins.configurations.jellyfish-plugin-python.scripts.web``）::

    {
      "provider": "auto",                 // auto | searxng | brave | exa-mcp
      "endpoint": "https://searx.example.org",   // searxng 用
      "apiKey": "",                       // brave 用
      "exaMcpUrl": "",                    // 覆盖 Exa 的公开端点（自建网关时用）
      "allowRanges": [],                  // 豁免 SSRF 防护的网段，见下
      "timeoutSeconds": 15,
      "maxBytes": 200000,
      "maxChars": 20000
    }

.. warning::
   ``apiKey`` 写 ``"${BRAVE_API_KEY}"`` 是可行的，但**只有在你确实设了那个环境变量时才写**：
   内核的 ``${ENV}`` 插值是硬失败（取不到就报 ``environment variable is not set`` 并让进程起不来），
   不是「取不到就留空」。所以这里给的是空字符串，而不是一个看着无害的占位符。
"""

import html
import html.parser
import ipaddress
import json
import os
import re
import socket
import urllib.error
import urllib.parse
import urllib.request

from jellyfish_sdk import ScriptError, ToolResult, contributes, tool

#: 自己的目录要用 __file__ 定位：脚本可能从任何工作目录被拉起
HERE = os.path.dirname(os.path.abspath(__file__))

#: 缺省值。与配置段里能写的键一一对应。
DEFAULT_PROVIDER = "auto"
DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_MAX_BYTES = 200000
DEFAULT_MAX_CHARS = 20000

#: Exa 的公开 MCP 端点：不需要 key，是插件的零配置兜底。
DEFAULT_EXA_MCP_URL = "https://mcp.exa.ai/mcp"

#: 那个端点上我们要调的工具名。
EXA_MCP_TOOL = "web_search_exa"

#: 缺省 User-Agent：不少站点会拒绝没有 UA 的请求（403），而内置的默认 UA 恰好是那种。
DEFAULT_USER_AGENT = "jellyfish-web/1.0 (+https://github.com/zcd/jellyfish)"


# ---------------------------------------------------------------- 配置


def _config(ctx):
    """把配置段读成一个带缺省值的字典。

    每次都从 ``ctx.configuration`` 现读，而不是在模块顶层缓存：``/reload`` 之后配置段变了，
    缓存住的那份会静默地继续用旧值——那正是「改了配置没生效」最难查的一种。
    """
    raw = ctx.configuration or {}
    if "allowPrivateAddresses" in raw:
        # 这个键原本是「内网也放行」的总闸，而它**同时**放开了 web_fetch 的目标地址。
        # 留着它就会让人以为「我配了内网搜索服务，所以抓取也能进内网」——因此宁可直接报错，
        # 也不要静默忽略：静默忽略掉的那次配置，就是最难查的那类 bug。
        raise ScriptError(
            "scripts.web.allowPrivateAddresses 已移除。它原本同时放开了搜索端点与 web_fetch 的目标，"
            "而后者是模型给的地址，不该被「我有内网搜索服务」这件事顺手放开。现在：\n"
            "  · 搜索端点（endpoint / exaMcpUrl）本来就来自你的配置，直接可用，不需要开关；\n"
            "  · 确实要抓内网地址时用 allowRanges 写明网段，例如 [\"192.168.1.0/24\"]。")
    return {
        "provider": str(raw.get("provider") or DEFAULT_PROVIDER).strip().lower(),
        "endpoint": _text(raw.get("endpoint")),
        "apiKey": _text(raw.get("apiKey")),
        "exaMcpUrl": _text(raw.get("exaMcpUrl")) or DEFAULT_EXA_MCP_URL,
        "timeoutSeconds": _positive_int(raw.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS),
        "maxBytes": _positive_int(raw.get("maxBytes"), DEFAULT_MAX_BYTES),
        "maxChars": _positive_int(raw.get("maxChars"), DEFAULT_MAX_CHARS),
        "allowRanges": _parse_ranges(raw.get("allowRanges")),
        "userAgent": _text(raw.get("userAgent")) or DEFAULT_USER_AGENT,
    }


def _text(value):
    """取非空字符串；其它一律返回 None。"""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _positive_int(value, default):
    """取正整数；缺失或非法时用缺省值（非法配置不该让工具整个消失）。"""
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


# ---------------------------------------------------------------- SSRF 防护


def _parse_ranges(entries):
    """把 ``allowRanges`` 里的网段字符串解析成网段对象。

    **非法项直接报错，不静默跳过**：把 ``192.168.1.0/24`` 写成 ``192.168.1.0/33`` 时，
    静默忽略会让人以为「已经放行了」，然后在一个莫名其妙的失败上耗半天。
    """
    networks = []
    for entry in entries or []:
        text = _text(entry)
        if not text:
            continue
        try:
            network = ipaddress.ip_network(text, strict=False)
        except ValueError:
            raise ScriptError(
                "allowRanges 里不是合法网段: %r（形如 198.18.0.0/15 或 10.0.0.1/32）" % text)
        if network.prefixlen == 0:
            # 0.0.0.0/0 与 ::/0 等于把整道防护关掉，那不是「配置」而是「误操作」
            raise ScriptError("allowRanges 不接受 %s：那等于关掉整道防护" % network)
        networks.append(network)
    return tuple(networks)


def _origin(parsed):
    """取 URL 的「来源」（协议 + 主机 + 端口），用来判定可信端点。"""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    return (parsed.scheme, (parsed.hostname or "").lower(), port)


def _trusted_origins(*urls):
    """把用户配置里的端点标成可信来源。

    ``web_fetch`` 永远不调用它——那是这条边界的关键：能拿到这个豁免的只有
    「你自己写在配置里」的地址，模型无论说什么都换不来。
    """
    origins = set()
    for url in urls:
        if url:
            origins.add(_origin(urllib.parse.urlsplit(url)))
    return origins


def _assert_url_allowed(url, networks=(), trusted_origins=()):
    """校验一个 URL 可以访问，返回解析结果。

    **这是一道真实的防线，不是形式**：``web_fetch`` 的输入来自模型，而模型可能被网页内容
    诱导去访问 ``http://169.254.169.254/``（云元数据）或 ``http://10.0.0.1``（内网服务）。
    没有它，这个工具就是一台内网探测器。

    两道「放行」的口子，**范围刻意不同**：

    * ``trusted_origins``：用户配置里的服务端点（SearXNG 的 ``endpoint``、Exa 的
      ``exaMcpUrl``）。它来自配置文件而不是模型，所以「我的搜索服务在内网」不需要额外开关。
    * ``networks``（``allowRanges``）：整段网段的豁免，**搜索与抓取都生效**。它是给
      「TUN + 假 IP 代理」用的：Clash / Surge / Mihomo 这类代理会把**公网域名**解析成保留网段
      （典型是 ``198.18.0.0/15``），不豁免的话正常网页反而全被拦。刻意要求写明网段，
      而不是给一个布尔开关——开关会被顺手打开然后忘掉。

    判定顺序是「先解析成 IP，再逐个判」：只看字面量会漏掉 ``localtest.me`` 这类解析到
    127.0.0.1 的域名。DNS 重绑定（解析之后再改）不在覆盖范围内，那需要让「解析」与「连接」
    用同一个 IP，标准库的 urllib 做不到。
    """
    if not url or not str(url).strip():
        raise ScriptError("url 不能为空")
    parsed = urllib.parse.urlsplit(str(url).strip())
    if parsed.scheme not in ("http", "https"):
        raise ScriptError("只支持 http/https，实际是 %r" % (parsed.scheme or "(空)"))
    host = parsed.hostname
    if not host:
        raise ScriptError("url 里没有主机名: %s" % url)
    if _origin(parsed) in trusted_origins:
        return parsed
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except socket.gaierror as error:
        raise ScriptError("域名解析失败: %s (%s)" % (host, error))
    for info in infos:
        address = info[4][0]
        try:
            ip = ipaddress.ip_address(address)
        except ValueError:
            continue
        if not _is_blocked_address(ip):
            continue
        if any(ip in network for network in networks):
            continue
        raise ScriptError(
            "内网地址不可访问: %s → %s\n"
            "（如果这是你自己的服务，或你的代理把公网域名解析到了保留网段，"
            "把它加进 scripts.web.allowRanges，例如 [\"198.18.0.0/15\"]）" % (host, address))
    return parsed


def _is_blocked_address(ip):
    """判断一个 IP 是不是「不该被工具访问」的地址。"""
    return (ip.is_private or ip.is_loopback or ip.is_link_local
            or ip.is_reserved or ip.is_multicast or ip.is_unspecified)


class _GuardedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """重定向逐跳校验。

    **必须逐跳查，只在入口查一次是不够的**：``https://evil.example/`` 完全可以 302 到
    ``http://169.254.169.254/``，而 ``urllib`` 默认会自己跟过去。

    可信来源与网段豁免一起带过来，因此「自己的 SearXNG 跳到自己的另一个路径」不会被拦，
    而「公网页面跳到元数据地址」照旧被拦。
    """

    def __init__(self, networks, trusted_origins):
        self._networks = networks
        self._trusted_origins = trusted_origins

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _assert_url_allowed(newurl, self._networks, self._trusted_origins)
        return urllib.request.HTTPRedirectHandler.redirect_request(
            self, req, fp, code, msg, headers, newurl)


# ---------------------------------------------------------------- HTTP


def _http_error_message(error, url):
    """把 HTTP 状态码翻成「下一步该做什么」。"""
    if error.code == 401:
        return "HTTP 401（鉴权失败）: %s\n（检查 scripts.web.apiKey 是否正确）" % url
    if error.code == 429:
        return ("HTTP 429（被限速）: %s\n"
                "（公开搜索端点会限速；稍后再试，或改成自己的后端：endpoint / apiKey）" % url)
    return "HTTP %d: %s" % (error.code, url)


def _http_request(url, config, trusted_origins=(), accept=None, extra_headers=None, data=None):
    """发一次请求，返回 ``(最终 URL, content-type, bytes)``。

    只在这一处做网络调用，因此超时、UA、重定向校验、字节上限都只有一份实现。
    """
    _assert_url_allowed(url, config["allowRanges"], trusted_origins)
    opener = urllib.request.build_opener(
        _GuardedRedirectHandler(config["allowRanges"], trusted_origins))
    headers = {"User-Agent": config["userAgent"], "Accept-Encoding": "identity"}
    if accept:
        headers["Accept"] = accept
    if extra_headers:
        headers.update(extra_headers)
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with opener.open(request, timeout=config["timeoutSeconds"]) as response:
            return (response.geturl(), response.headers.get("Content-Type") or "",
                    response.read(config["maxBytes"]))
    except urllib.error.HTTPError as error:
        raise ScriptError(_http_error_message(error, url))
    except urllib.error.URLError as error:
        raise ScriptError("请求失败: %s (%s)" % (url, error.reason))
    except (socket.timeout, TimeoutError):
        raise ScriptError("请求超时（%d 秒）: %s" % (config["timeoutSeconds"], url))


def _decode(body, content_type):
    """把响应体解码成文本。

    编码优先取 ``Content-Type`` 里的 charset，拿不到时退回 UTF-8 并容错替换——
    一个编码猜错不该让整个工具失败。
    """
    charset = None
    for part in (content_type or "").split(";"):
        part = part.strip()
        if part.lower().startswith("charset="):
            charset = part.split("=", 1)[1].strip().strip('"\'')
    try:
        return body.decode(charset or "utf-8", errors="replace")
    except LookupError:
        return body.decode("utf-8", errors="replace")


# ---------------------------------------------------------------- HTML 正文提取

#: 整块跳过的标签：它们的内容不是正文（脚本、样式、导航、页脚……）。
_SKIP_TAGS = frozenset((
    "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object",
    "head", "nav", "footer", "aside", "form", "button", "select", "option",
))

#: 遇到就补一个换行的块级标签：否则整页会挤成一行的长字符串。
_BLOCK_TAGS = frozenset((
    "p", "div", "br", "hr", "li", "ul", "ol", "tr", "td", "th", "table",
    "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "main", "blockquote", "pre",
))


class _TextExtractor(html.parser.HTMLParser):
    """把 HTML 压成阅读文本。

    **刻意不追求 readability 的质量**：真正的正文识别需要一整套启发式（密度、链接比、DOM 权重），
    那是 ``trafilatura`` / ``@mozilla/readability`` 的活。零依赖的版本只能做「去掉明显的非正文
    区块 + 保留块级结构」，因此它的输出会比那些库脏一些——这是**有意接受的取舍**，
    换来的是「拷过去就能用、不需要 pip install」。要更好的效果，把 ``extract_text`` 换掉即可。
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._skip_depth = 0
        self._parts = []
        self.title = None
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        if tag in _SKIP_TAGS:
            self._skip_depth += 1
            return
        if tag == "title":
            self._in_title = True
        if tag in _BLOCK_TAGS:
            self._parts.append("\n")

    def handle_endtag(self, tag):
        if tag in _SKIP_TAGS:
            if self._skip_depth > 0:
                self._skip_depth -= 1
            return
        if tag == "title":
            self._in_title = False
        if tag in _BLOCK_TAGS:
            self._parts.append("\n")

    def handle_data(self, data):
        if self._in_title:
            if self.title is None:
                self.title = data.strip()
            return
        if self._skip_depth == 0 and data.strip():
            self._parts.append(data)

    def text(self):
        """把收集到的片段压成干净的多行文本。"""
        joined = "".join(self._parts)
        lines = []
        for raw in joined.splitlines():
            line = " ".join(raw.split())
            if line:
                lines.append(line)
        return "\n".join(lines)


def extract_text(source):
    """从 HTML 里取出标题与正文文本。

    :return: ``(title, text)``；解析失败时标题为 ``None``、文本为空串（调用点退回原始文本）
    """
    parser = _TextExtractor()
    try:
        parser.feed(source)
        parser.close()
    except Exception:  # noqa: BLE001  解析器的容错不该把整次抓取带走
        return None, ""
    return parser.title, parser.text()


def _looks_like_html(content_type, source):
    """判断响应是不是 HTML。Content-Type 缺失时按内容开头猜——不少站点就是不给。"""
    if "html" in (content_type or "").lower():
        return True
    head = (source or "").lstrip()[:200].lower()
    return head.startswith("<!doctype html") or head.startswith("<html")


# ---------------------------------------------------------------- 搜索后端


def _with_domain_terms(query, domains):
    """把域名限制折进查询词。

    两个后端都没有「域」参数，只有 ``site:`` 语法，所以这一层是共用的。
    """
    if not domains:
        return query
    return query + " " + " ".join("site:%s" % domain for domain in domains)


def _search_searxng(query, count, recency, domains, config):
    """走 SearXNG 的 JSON 接口（自建，无需厂商 key）。"""
    if not config["endpoint"]:
        raise ScriptError("provider=searxng 时必须在 scripts.web.endpoint 里配端点地址")
    params = {"q": _with_domain_terms(query, domains), "format": "json", "safesearch": "0"}
    if recency:
        params["time_range"] = recency
    url = config["endpoint"].rstrip("/") + "/search?" + urllib.parse.urlencode(params)
    _, content_type, body = _http_request(
        url, config, trusted_origins=_trusted_origins(config["endpoint"]),
        accept="application/json")
    if "json" not in (content_type or "").lower() and not body.lstrip().startswith(b"{"):
        raise ScriptError("SearXNG 端点没有返回 JSON：请确认服务端开启了 json 格式（search.formats）")
    try:
        payload = json.loads(_decode(body, content_type))
    except ValueError as error:
        raise ScriptError("SearXNG 返回的不是合法 JSON: %s" % error)
    results = []
    for item in (payload.get("results") or [])[:count]:
        results.append({
            "title": _text(item.get("title")) or "(无标题)",
            "url": _text(item.get("url")) or "",
            "snippet": _text(item.get("content")) or "",
            "engine": _text(item.get("engine")),
            "publishedAt": _text(item.get("publishedDate")),
        })
    return results


def _search_brave(query, count, recency, domains, config):
    """走 Brave 的 Web Search API（需要 apiKey）。"""
    if not config["apiKey"]:
        raise ScriptError("provider=brave 时必须在 scripts.web.apiKey 里配密钥")
    params = {"q": query, "count": str(min(count, 20))}
    if recency:
        params["freshness"] = recency
    url = "https://api.search.brave.com/res/v1/web/search?" + urllib.parse.urlencode(params)
    _, content_type, body = _http_request(
        url, config, accept="application/json",
        extra_headers={"X-Subscription-Token": config["apiKey"]})
    try:
        payload = json.loads(_decode(body, content_type))
    except ValueError as error:
        raise ScriptError("Brave 返回的不是合法 JSON: %s" % error)
    results = []
    for item in ((payload.get("web") or {}).get("results") or [])[:count]:
        results.append({
            "title": _text(item.get("title")) or "(无标题)",
            "url": _text(item.get("url")) or "",
            "snippet": _text(item.get("description")) or "",
            "engine": "brave",
            "publishedAt": _text(item.get("age")),
        })
    return _filter_domains(results, domains)


#: Exa 的公开 MCP 端点是「按自然语言理解时间范围」的，没有结构化参数。
_EXA_RECENCY_TERMS = {"day": "past 24 hours", "week": "past week",
                      "month": "past month", "year": "past year"}


def _search_exa_mcp(query, count, recency, domains, config):
    """走 Exa 的公开 MCP 端点（``https://mcp.exa.ai/mcp``，**不需要任何 key**）。

    它是插件的零配置兜底，代价要说清楚：**查询会离开你的机器**，经过 Exa 的服务器。
    而且那是个引流性质的公开端点——额度、限速、可用性都没有承诺（429 就是它在限速）。
    因此它排在自建端点之后；一旦你配了 ``endpoint``，它就再也不会被用到。
    """
    parts = [_with_domain_terms(query, domains)]
    if recency in _EXA_RECENCY_TERMS:
        parts.append(_EXA_RECENCY_TERMS[recency])
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": EXA_MCP_TOOL,
                   "arguments": {"query": " ".join(parts), "numResults": count}},
    }
    base = config["exaMcpUrl"]
    separator = "&" if "?" in base else "?"
    url = base + separator + "tools=" + EXA_MCP_TOOL
    _, content_type, body = _http_request(
        url, config, trusted_origins=_trusted_origins(base),
        accept="application/json, text/event-stream",
        # Content-Type 必须写明：urllib 带 body 时会自己塞上
        # application/x-www-form-urlencoded，而 MCP 端点只收 application/json，
        # 后者会让它回 415（这是实测撞出来的，不是推测）
        extra_headers={"Content-Type": "application/json",
                       "x-exa-source": "jellyfish-web"},
        data=json.dumps(payload).encode("utf-8"))
    return _parse_exa_response(_decode(body, content_type))[:count]


def _parse_exa_response(text):
    """从 MCP 的响应体里解析出结果列表。

    端点回的是 **SSE**（``text/event-stream``）而不是裸 JSON：正文在某个 ``data:`` 行里。
    两种形态都试一次——只认一种，会在对方换个版本时突然全挂，而且报出来的是
    「没有结果」这种看不出原因的错。
    """
    rpc = None
    for line in text.splitlines():
        if not line.startswith("data:"):
            continue
        candidate = _json_object(line[5:].strip())
        if candidate is not None:
            rpc = candidate
            break
    if rpc is None:
        rpc = _json_object(text.strip())
    if rpc is None:
        raise ScriptError("Exa MCP 返回的内容无法解析（既不是 SSE 也不是 JSON）")
    error = rpc.get("error")
    if error:
        raise ScriptError("Exa MCP 报错: %s" % (error.get("message") or error))
    result = rpc.get("result") or {}
    body = ""
    for item in result.get("content") or []:
        if item.get("type") == "text" and _text(item.get("text")):
            body = item["text"]
            break
    if result.get("isError"):
        raise ScriptError("Exa MCP 返回错误: %s" % (" ".join(body.split())[:200] or "（没有说明）"))
    if not body:
        raise ScriptError("Exa MCP 没有返回内容")
    return _parse_exa_blocks(body)


def _json_object(text):
    """试着把一段文本解析成含 ``result``/``error`` 的 JSON 对象，失败返回 None。"""
    if not text:
        return None
    try:
        candidate = json.loads(text)
    except ValueError:
        return None
    return candidate if isinstance(candidate, dict) else None


def _parse_exa_blocks(text):
    """把 Exa 那段落格式化的文本切成一条条结果。

    它的形状是固定的纯文本（``Title:`` / ``URL:`` / 可选 ``Published:``、``Author:``，然后
    ``Highlights:`` 或 ``Text:`` 接着正文），块与块之间靠下一行的 ``Title: `` 分界。
    因为是**纯文本协议**，字段缺失是常态：拿不到正文也要把标题和链接留下，否则一条好结果
    会因为少了个 ``Highlights:`` 就被整个丢掉。
    """
    results = []
    for block in re.split(r"(?m)(?=^Title: )", text):
        block = block.strip()
        if not block:
            continue
        url = _block_field(block, "URL")
        if not url:
            continue
        results.append({
            "title": _block_field(block, "Title") or url,
            "url": url,
            "snippet": _block_body(block),
            "engine": "exa-mcp",
            "publishedAt": _block_field(block, "Published"),
        })
    return results


def _block_field(block, name):
    """取 ``Name: value`` 那一行的值。"""
    match = re.search(r"(?m)^%s:[ \t]*(.*)$" % re.escape(name), block)
    if not match:
        return None
    value = match.group(1).strip()
    return None if not value or value == "N/A" else value


def _block_body(block):
    """取正文：``Text:`` 与 ``Highlights:`` 两种形态都出现过，谁在就用谁。"""
    for marker in ("\nText:", "\nHighlights:"):
        index = block.find(marker)
        if index < 0:
            continue
        body = block[index + len(marker):]
        body = re.sub(r"(?m)^---\s*$", "", body)
        collapsed = " ".join(body.split())
        return collapsed[:800] + ("…" if len(collapsed) > 800 else "")
    return ""


def _filter_domains(results, domains):
    """按域名收窄结果（SearXNG 与 Exa 用查询词下推，Brave 只能在这里过滤）。"""
    if not domains:
        return results
    wanted = tuple(domain.lower() for domain in domains)
    kept = []
    for item in results:
        host = (urllib.parse.urlsplit(item.get("url") or "").hostname or "").lower()
        if any(host == domain or host.endswith("." + domain) for domain in wanted):
            kept.append(item)
    return kept


#: 支持的后端。新增一个后端 = 加一个这样的函数，不改其它任何地方。
_PROVIDERS = {
    "searxng": _search_searxng,
    "brave": _search_brave,
    "exa-mcp": _search_exa_mcp,
}


def _resolve_provider(config):
    """决定这次用哪个后端。

    ``auto`` 的顺序是「**自建的优先，其次是你主动配了 key 的，最后才落到免费的公开端点**」。
    「自建有就先用自己的」是有意的：那样查询不出你的机器，这正是这个插件相对
    pi-web-access 那类实现的全部意义所在。
    """
    explicit = config["provider"]
    if explicit and explicit != "auto":
        if explicit not in _PROVIDERS:
            raise ScriptError("未知的 provider: %r（支持: auto / %s）"
                              % (explicit, " / ".join(sorted(_PROVIDERS))))
        return explicit
    if config["endpoint"]:
        return "searxng"
    if config["apiKey"]:
        return "brave"
    return "exa-mcp"


def _search(query, count, recency, domains, config):
    """按配置选后端并搜索。"""
    provider = _resolve_provider(config)
    return _PROVIDERS[provider](query, count, recency, domains, config)


# ---------------------------------------------------------------- 工具


@tool(
    name="web_search",
    description="联网搜索，返回标题 / 链接 / 摘要列表。需要时效信息、或本地资料里没有答案时使用。",
    parameters={
        "query": {"type": "string", "description": "搜索词"},
        "count": {"type": "integer", "description": "最多返回多少条，缺省 5，上限 20"},
        "recency": {"type": "string", "description": "只看最近：day / week / month / year"},
        "domains": {"type": "array", "items": {"type": "string"},
                    "description": "只在这些域名下搜（可选）"},
    },
    required=["query"],
)
def web_search(args, ctx):
    """联网搜索。

    **工具的签名固定是 ``(args, ctx)``**：参数从 ``args`` 里取（而不是把它们列成函数参数）——
    工具的参数集是模型给的 JSON，可能缺字段、类型也可能不对，而 ``args`` 这个映射恰好把
    「缺了」与「写错了」分开，因此这里逐项做校验。
    """
    query = _text(args.get("query"))
    if not query:
        raise ScriptError("缺少参数 query")
    config = _config(ctx)
    limit = min(max(1, _positive_int(args.get("count"), 5)), 20)
    domains = [text for text in (_text(item) for item in (args.get("domains") or [])) if text]
    results = _search(query, limit, _text(args.get("recency")), domains, config)
    if not results:
        # 「没搜到」不是错误：返回一句明确的空结果，模型据此换关键词，而不是以为工具坏了
        return ToolResult("没有搜到结果（可换个说法，或去掉域名限制）", summary="0 条")
    lines = []
    for index, item in enumerate(results, start=1):
        lines.append("%d. %s" % (index, item["title"]))
        lines.append("   %s" % item["url"])
        if item["snippet"]:
            lines.append("   %s" % item["snippet"])
    return ToolResult("\n".join(lines),
                      summary="搜到 %d 条（%s）" % (len(results), _resolve_provider(config)))


@tool(
    name="web_fetch",
    description="抓取一个网页并返回正文文本（去掉脚本 / 样式 / 导航）。搜索之后想读全文时使用。",
    parameters={
        "url": {"type": "string", "description": "要抓取的 http/https 地址"},
        "max_chars": {"type": "integer", "description": "返回正文的字符上限，缺省 20000"},
    },
    required=["url"],
)
def web_fetch(args, ctx):
    """抓取网页正文。

    这里**刻意不传 ``trusted_origins``**：``url`` 是模型给的，无论配置里写了什么，
    它都不该获得「内网也放行」的待遇（唯一的口子是用户显式写下的 ``allowRanges``）。
    """
    url = _text(args.get("url"))
    if not url:
        raise ScriptError("缺少参数 url")
    config = _config(ctx)
    final_url, content_type, body = _http_request(url, config, accept="text/html,*/*")
    source = _decode(body, content_type)
    limit = _positive_int(args.get("max_chars"), config["maxChars"])
    if _looks_like_html(content_type, source):
        title, text = extract_text(source)
        content = text if text else source
        if title:
            content = "%s\n\n%s" % (title, content)
    else:
        # 非 HTML（纯文本 / JSON …）：原样给，不要试图「提取」它
        content = source
    truncated = len(content) > limit
    if truncated:
        content = content[:limit] + "\n[已截断：原文更长，可用更小的范围或直接访问该地址继续]"
    host = urllib.parse.urlsplit(final_url).hostname or final_url
    return ToolResult(content,
                      summary="%s → %d 字符%s" % (host, len(content), "（截断）" if truncated else ""))


# ---------------------------------------------------------------- 提示词贡献


@contributes("prompt")
def prompt(ctx):
    """告诉模型什么时候用这两个工具。

    ``auto`` 兜底到 Exa，因此默认总是有可用后端，这段提示默认就会注入。
    """
    try:
        _resolve_provider(_config(ctx))
    except ScriptError:
        # 配置写错要在「调用工具」时炸，而不是在「组装提示词」时把一个回合带走——
        # 后者报出来的位置离真正的原因太远，只会让人以为是模型或提示词出了问题
        return None
    return ("需要时效信息（新闻、版本号、当下状态）或本地资料没有答案时，用 web_search 联网搜索；"
            "要读某条结果的全文时用 web_fetch。搜索结果的摘要可能过时，涉及事实时以抓到的正文为准。")
