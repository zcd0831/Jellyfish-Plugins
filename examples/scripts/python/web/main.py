"""联网搜索与网页抓取（Python 脚本插件，零第三方依赖）。

它演示的是一个**真实形态**的脚本插件：有自己的配置段、有外部 HTTP 调用、有自己的错误语义，
并且只依赖标准库——因此拷进 ``scripts/python/`` 就能用，不需要 ``pip install`` 任何东西。

三件事值得照着看：

1. **密钥与端点从 ``ctx.configuration`` 读**，而不是从环境变量。脚本进程的环境是严格白名单
   （不携带 JVM 的密钥），配置段是脚本拿密钥的唯一通道，而且 ``${ENV}`` 由内核插值；
2. **失败要抛 ``ScriptError``**，不要返回 ``{"error": ...}``——后者会被当成正常输出，
   模型会以为「一切正常，结果就是这几个字」；
3. **摘要走 ``ToolResult``**：正文给模型，``summary`` 给人（外壳接在工具名后面显示）。

配置段（``plugins.configurations.jellyfish-plugin-python.scripts.web``）::

    {
      "provider": "searxng",              // searxng | brave
      "endpoint": "https://searx.example.org",
      "apiKey": "",                       // provider=brave 时填
      "timeoutSeconds": 15,
      "maxBytes": 200000,
      "allowPrivateAddresses": false      // 自建内网 SearXNG 时置 true
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
import socket
import urllib.error
import urllib.parse
import urllib.request

from jellyfish_sdk import ScriptError, ToolResult, contributes, tool

#: 自己的目录要用 __file__ 定位：脚本可能从任何工作目录被拉起
HERE = os.path.dirname(os.path.abspath(__file__))

#: 缺省值。与配置段里能写的键一一对应。
DEFAULT_PROVIDER = "searxng"
DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_MAX_BYTES = 200000
DEFAULT_MAX_CHARS = 20000

#: 缺省 User-Agent：不少站点会拒绝没有 UA 的请求（403），而内置的默认 UA 恰好是那种。
DEFAULT_USER_AGENT = "jellyfish-web/1.0 (+https://github.com/zcd/jellyfish)"


# ---------------------------------------------------------------- 配置


def _config(ctx):
    """把配置段读成一个带缺省值的字典。

    每次都从 ``ctx.configuration`` 现读，而不是在模块顶层缓存：``/reload`` 之后配置段变了，
    缓存住的那份会静默地继续用旧值——那正是「改了配置没生效」最难查的一种。
    """
    raw = ctx.configuration or {}
    return {
        "provider": str(raw.get("provider") or DEFAULT_PROVIDER).strip().lower(),
        "endpoint": _text(raw.get("endpoint")),
        "apiKey": _text(raw.get("apiKey")),
        "timeoutSeconds": _positive_int(raw.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS),
        "maxBytes": _positive_int(raw.get("maxBytes"), DEFAULT_MAX_BYTES),
        "maxChars": _positive_int(raw.get("maxChars"), DEFAULT_MAX_CHARS),
        "allowPrivateAddresses": bool(raw.get("allowPrivateAddresses")),
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


def _assert_url_allowed(url, allow_private):
    """校验一个 URL 可以访问，返回解析结果。

    **这是一道真实的防线，不是形式**：``web_fetch`` 的输入来自模型，而模型可能被网页内容
    诱导去访问 ``http://169.254.169.254/``（云元数据）或 ``http://localhost:8080``（本机服务）。
    没有它，这个工具就是一台内网探测器。

    做法是「把主机名解析成 IP，再逐个判定」——只看字面量会漏掉 ``localtest.me`` 这类
    解析到 127.0.0.1 的域名。DNS 重绑定（解析之后再改）不在覆盖范围内，见文档。
    """
    if not url or not str(url).strip():
        raise ScriptError("url 不能为空")
    parsed = urllib.parse.urlsplit(str(url).strip())
    if parsed.scheme not in ("http", "https"):
        raise ScriptError("只支持 http/https，实际是 %r" % (parsed.scheme or "(空)"))
    host = parsed.hostname
    if not host:
        raise ScriptError("url 里没有主机名: %s" % url)
    if allow_private:
        return parsed
    lowered = host.lower()
    if lowered == "localhost" or lowered.endswith(".localhost") or lowered.endswith(".local"):
        raise ScriptError("内网地址不可访问: %s" % host)
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
        if _is_blocked_address(ip):
            raise ScriptError("内网地址不可访问: %s → %s" % (host, address))
    return parsed


def _is_blocked_address(ip):
    """判断一个 IP 是不是「不该被工具访问」的地址。"""
    return (ip.is_private or ip.is_loopback or ip.is_link_local
            or ip.is_reserved or ip.is_multicast or ip.is_unspecified)


class _GuardedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """重定向逐跳校验。

    **必须逐跳查，只在入口查一次是不够的**：``https://evil.example/`` 完全可以 302 到
    ``http://169.254.169.254/``，而 ``urllib`` 默认会自己跟过去。
    """

    def __init__(self, allow_private):
        self.allow_private = allow_private

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _assert_url_allowed(newurl, self.allow_private)
        return urllib.request.HTTPRedirectHandler.redirect_request(
            self, req, fp, code, msg, headers, newurl)


# ---------------------------------------------------------------- HTTP


def _http_get(url, config, accept=None):
    """发一次 GET，返回 ``(最终 URL, content-type, bytes)``。

    只在这一处做网络调用，因此超时、UA、重定向校验、字节上限都只有一份实现。
    """
    _assert_url_allowed(url, config["allowPrivateAddresses"])
    opener = urllib.request.build_opener(
        _GuardedRedirectHandler(config["allowPrivateAddresses"]))
    headers = {"User-Agent": config["userAgent"], "Accept-Encoding": "identity"}
    if accept:
        headers["Accept"] = accept
    request = urllib.request.Request(url, headers=headers)
    try:
        with opener.open(request, timeout=config["timeoutSeconds"]) as response:
            content_type = response.headers.get("Content-Type") or ""
            body = response.read(config["maxBytes"])
            return response.geturl(), content_type, body
    except urllib.error.HTTPError as error:
        raise ScriptError("HTTP %d: %s" % (error.code, url))
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


def _search_searxng(query, count, recency, domains, config):
    """走 SearXNG 的 JSON 接口（自建，无需厂商 key）。"""
    if not config["endpoint"]:
        raise ScriptError("provider=searxng 时必须在 scripts.web.endpoint 里配端点地址")
    params = {"q": query, "format": "json", "safesearch": "0"}
    if recency:
        params["time_range"] = recency
    if domains:
        params["q"] = query + " " + " ".join("site:%s" % domain for domain in domains)
    url = config["endpoint"].rstrip("/") + "/search?" + urllib.parse.urlencode(params)
    _, content_type, body = _http_get(url, config, accept="application/json")
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
    headers_url, content_type, body = _brave_get(url, config)
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


def _brave_get(url, config):
    """Brave 需要自定义请求头，因此这里不复用 ``_http_get`` 的默认头。"""
    _assert_url_allowed(url, config["allowPrivateAddresses"])
    opener = urllib.request.build_opener(
        _GuardedRedirectHandler(config["allowPrivateAddresses"]))
    request = urllib.request.Request(url, headers={
        "User-Agent": config["userAgent"],
        "Accept": "application/json",
        "X-Subscription-Token": config["apiKey"],
    })
    try:
        with opener.open(request, timeout=config["timeoutSeconds"]) as response:
            return response.geturl(), response.headers.get("Content-Type") or "", response.read(config["maxBytes"])
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise ScriptError("Brave 拒绝了这个密钥（401），请检查 scripts.web.apiKey")
        raise ScriptError("Brave 请求失败 HTTP %d" % error.code)
    except urllib.error.URLError as error:
        raise ScriptError("Brave 请求失败: %s" % error.reason)


def _filter_domains(results, domains):
    """按域名收窄结果（SearXNG 用 ``site:`` 查询下推，Brave 只能在这里过滤）。"""
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
_PROVIDERS = {"searxng": _search_searxng, "brave": _search_brave}


def _search(query, count, recency, domains, config):
    """按配置选后端并搜索。"""
    provider = _text(config.get("provider")) or DEFAULT_PROVIDER
    handler = _PROVIDERS.get(provider)
    if handler is None:
        raise ScriptError("未知的 provider: %r（支持: %s）"
                          % (provider, " / ".join(sorted(_PROVIDERS))))
    return handler(query, count, recency, domains, config)


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
    return ToolResult("\n".join(lines), summary="搜到 %d 条" % len(results))


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
    """抓取网页正文。"""
    url = _text(args.get("url"))
    if not url:
        raise ScriptError("缺少参数 url")
    config = _config(ctx)
    final_url, content_type, body = _http_get(url, config, accept="text/html,*/*")
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

    只在配好了后端时才注入：工具不可用还教模型去用，只会让它把回合浪费在必然失败的调用上。
    """
    config = _config(ctx)
    if not config["endpoint"] and not config["apiKey"]:
        return None
    return ("需要时效信息（新闻、版本号、当下状态）或本地资料没有答案时，用 web_search 联网搜索；"
            "要读某条结果的全文时用 web_fetch。搜索结果的摘要可能过时，涉及事实时以抓到的正文为准。")
