# stock / stockpanel 设计说明

> 这份文件只写**结论与理由**，不写用法（用法看 `README.md` 与 `/stock help`）。
> 里面的实测数据是 2026-10 在本机（Python 3.9.6 + akshare 1.18.88）跑出来的；
> 换网络、换 akshare 版本后应当重新验证——「怎么验」在最后一节。

## 一、为什么是两个脚本、两个进程

```
scripts/python/
├── stock/        取数：5 个工具 + /stock 命令族 + prompt 贡献（import akshare，会联网）
└── stockpanel/   面板：只读缓存文件（零第三方依赖，永不联网）
```

合起来写会更好维护，但**内核的两条约束让它必须分开**：

1. **面板处理器在界面渲染线程上同步执行，且没有任何超时**。`UiContributions` 对面板处理器
   只做 try/catch（抛错记一条 WARN、跳过该插件），没有截止时间。于是一面板 handler 里的一次
   网络请求就会冻住整个界面——连 `Esc` 都按不动（它也在渲染线程上）。
2. **脚本 worker 同时只有一个在途请求，其余排队**。回合运行中 TUI **每秒**补一次面板失效
   （`TuiApp` 的 `LIVE_REFRESH_MILLIS`），若面板与取数共用一个 worker，那每秒一次的面板拉取
   会排在某个 3~13 秒的取数请求后面，界面持续卡死，最坏触发 `invokeTimeoutSeconds` 杀掉 worker、
   连带熔断掉整个脚本。

拆开之后，面板进程只做「读一个小 JSON + 拼字符串」，毫秒级，永远不会卡界面。

## 二、数据源矩阵（实测）

**东方财富的推流域名在部分网络下会被远端直接断开**（`RemoteDisconnected`，不是超时），
且表现为**时通时断**——同一个函数连续调用会出现「一次成功、三次失败」。因此凡是东财口径
都必须配一个非东财的备选，否则插件会时好时坏，而偶发失败是最难归因的一类故障。

| 数据 | 主口径 | 兜底 | 实测 |
| --- | --- | --- | --- |
| 实时快照 | 腾讯 `qt.gtimg.cn`（裸 HTTP + GBK，一次可查多只） | akshare 腾讯全市场 → akshare 新浪全市场 | 主 0.1s ✓✓✓；中 5s；末 24s |
| 日线 | `stock_zh_a_daily`（新浪） | `stock_zh_a_hist`（东财） | 主 0.8~3.2s ✓✓✓ |
| 公司概况 | `stock_profile_cninfo`（巨潮） | — | 0.1s ✓ |
| 关键财务 | `stock_financial_abstract`（新浪） | — | 0.7~1.1s ✓✓ |
| 个股资金流 | `stock_individual_fund_flow`（东财） | `stock_fund_flow_individual`（同花顺） | 主 **1/6** ⚠；兜底 8~13s ✓✓ |

**已实测确认不可用**（不要试图捡回来）：

- 东财 `push2`（实时）：`stock_bid_ask_em` / `stock_individual_info_em` / `stock_zh_a_hist_min_em` /
  `stock_board_industry_name_em` / `stock_zh_a_spot_em` —— 全部 `RemoteDisconnected`。
- 雪球口径：`stock_individual_spot_xq` / `stock_individual_basic_info_xq` 要登录态，返回
  `400016 遇到错误，请刷新页面…`。
- 新浪 `hq.sinajs.cn` 裸接口：403（需要 Referer）。
- `stock_a_indicator_lg`（乐咕乐股）：当前版本 akshare 里已不存在。
- 同花顺没有「个股资金流的单只」接口，只有全市场排行（这就是兜底慢的原因：为了 1 只股票拉 5213 行）。

**上一轮刻意没做的**（用户决定）：行业板块与概念板块（`stock_board_*`、`stock_sector_*`）。
数据本身取得到（同花顺行业一览 90 行、新浪板块成分股 49 个细分行业都实测可用），
但两个来源的板块命名不同源、口径不可比，做进工具反而会让模型拿两份对不上的维度做对比。

## 三、单位口径（改渲染前必须先核这几条）

三个数据源的同一件事用不同单位，实测钉死如下：

| 量 | 腾讯快照 | 新浪日线 | 东财日线 |
| --- | --- | --- | --- |
| 成交量 | 手（字段 36） | **股**（÷100 才是手） | 手 |
| 成交额 | **万元**（字段 37，×10000 才是元） | 元 | 元 |
| 换手率 | 百分比（字段 38） | **比例**（×100 才是百分比） | 百分比 |
| 市值 | 亿元（字段 44/45） | — | — |

`stock_render.amount_yi` 收的是**元**，`amount_yi_plain` 收的是**亿元**——名字像，单位不同，
混用会让市值差四个数量级。

涨跌幅与振幅**不用数据源给的**（新浪根本没有），一律按相邻收盘价自己算。代价是前复权序列
在**除权日**不连续，那天会有一点偏差；这是口径本身的性质，不是 bug。

## 四、面板的设计约束

面板的尺寸与形状**全部由外壳决定**，插件只能给内容：

| 约束 | 值 | 来源 |
| --- | --- | --- |
| 侧栏内容宽度 | 18 列（侧栏起点 20 列 − 边框 2） | `ChatLayout.SIDEBAR_MIN_WIDTH` |
| 单面板行数上限 | 8 行 | `ChatLayout.PANEL_MAX_ROWS` |
| 终端窄于 | 80 列整块隐藏 | `ChatLayout.SIDEBAR_MIN_TERMINAL_WIDTH` |
| 一块区域 | 同时只显示一个面板 | `UiPlacement`（`sparkline` / `pet` 也在抢右栏） |

因此面板按「**一行一只股票、18 显示列**」设计：名称 10 列 + 空格 + 涨跌幅 7 列。宽度必须用
Unicode 东亚宽度口径算（`east_asian_width` 的 W/F 算 2 列）——股票名称全是汉字，用 `len()`
会让「贵州茅台」和「XD安徽凤」看起来一样宽，涨跌幅再也对齐不了。

`region: RIGHT` 只是**软建议**。同区域已有 `sparkline` / `pet` 时本面板可能不显示，由用户
`/ui` 切换；插件不能假设自己一定显示。

## 五、刷新时机（面板为什么不实时）

脚本**无法主动推 `INVALIDATED`**（SDK 里没有 `present` 那类出口），因此面板的刷新完全挂在外壳
已有的失效触发源上：

| 触发 | 效果 |
| --- | --- |
| `/stock refresh`、`/stock add`、`/stock del` | 命令执行后外壳置脏 → 下一帧重收 |
| `stock_watch(refresh/add/remove)` 工具 | 回合运行中每秒补失效；回合收敛时再失效一次 |
| `stock_quote` 命中自选股 | 顺手合并进缓存（不发额外请求） |

**面板从不主动联网**。所以它显示的永远是「上次刷新的快照」，刷新时刻直接写在标题上
（同日 `自选 21:40`，跨天 `自选 09-30`）——没有时刻的话用户会把它当实时行情。

## 六、已知边界

- **资金流的兜底口径信息量小**：东财逐日明细拿不到时退到同花顺，只有一个累计净额，
  没有主力/超大单/大单的五档拆分，而且档位只有「即时 / 3 / 5 / 10 / 20 日」这种粗粒度。
  结果里会带一句 `note` 说明，不假装数据是全的。
- **兜底接口慢（8~13 秒）**：同花顺那个接口要拉全市场 5213 行。所以配置里把
  `invokeTimeoutSeconds` 放宽到 90——默认的 30 秒会和它擦边。
- **`stock_quote` 里指数不适用 `PE`/`PB`**：腾讯对指数返回 0，表格会显示 `0.00` 而不是 `-`。
  纯观感问题，没做特殊处理（个股的 `PB = 0` 是真实可能的，不能一律当缺值）。
- **没有 MA 之外的指标**：刻意不做。用户要分析时由模型拿到原始数据后自己算，工具一旦内置了
  MACD/RSI 这类判断，口径就再也没法在不改代码的前提下换。
- **自选股是用户级的，不是会话级**：存在 `~/.jellyfish/stock`，所以 `/stock` 是
  `session_required=False`——在首页就能用，也不会为了它建一个会话。
- **不存在的代码照样能加进自选股，但会被如实报告**：腾讯接口对查不到的代码「连一行都不返回」，
  而请求失败时同样一行都不返回——**两者不可区分**，因此不能凭「取不到」就拒绝用户的操作
  （接口一抖动用户就什么都加不了）。选择是「加入 + 明确报告哪几只没取到」，
  否则面板会永远少一只而没人知道为什么。报告文案由 `_missing_note` **一处**产生，
  `add` / `refresh` / 工具侧三个入口共用——分开写会漂移出「有的地方报、有的地方不报」。

## 七、怎么重新验证

`stock_sources.py` 的所有结论都能用一段脚本复现（**不要靠记忆改这些口径**）：

```bash
# 1) 快照：主口径应当 0.1 秒内返回，三条口径的耗时差一个数量级
python3 -c "
import sys; sys.path.insert(0, './stock')
import stock_sources as s
print(s.spot(['600519','000858','sh000001']))"

# 2) 日线：核对 volume 是不是「股」、turnover 是不是「比例」
python3 -c "
import sys; sys.path.insert(0, './stock')
import stock_sources as s
d = s.history('600519', days=3, ma_windows=[])
for r in d['rows']: print(r['date'], r['volumeLots'], r['turnover'], r['changePct'])"

# 3) 东财到底通不通：连打 5 次，看成功率（本机实测 1/6）
python3 -c "
import akshare as ak
for i in range(5):
    try: print('OK', len(ak.stock_individual_fund_flow(stock='600519', market='sh')))
    except Exception as e: print('FAIL', type(e).__name__)"
```

改了 `manifest.json` 里的工具签名之后，记得同步清单（`--check` 只比名字，改了描述必须 `--write`）：

```bash
python3 ~/.jellyfish/gateway/python/<digest>/script/dump_manifest.py ./stock --write
python3 ~/.jellyfish/gateway/python/<digest>/script/dump_manifest.py ./stockpanel --write
```
