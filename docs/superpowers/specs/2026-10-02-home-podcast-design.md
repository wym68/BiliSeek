# 首页「热门播客」入口：解析活动页

日期：2026-10-02
范围：`:core:network`（活动页 HTML 取回与解析、批量取视频卡片）、`:app`（榜单框架加第四种、期数选择条）。
承接 [home-charts](2026-10-02-home-charts-design.md) 里「待定：热门播客」。

## 背景

官方 App 热门页的「热门播客」入口，分享出来是运营搭建的 H5 活动页
`https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html`（`blackboard/era`）。
App 端对应的是 gRPC `Popular/Index` + `entrance_id`，不走；网页端没有现成的列表接口。

用户在浏览器里查看源码，确认 HTML 里直接带了很多 BV 号。再用 App 同款 UA 与 Referer、不带 cookie 的 `curl`
存下真实页面核实（113KB，全程只读本地文件）：

- 数据在内嵌的 `window.__BILIACT_EVAPAGEDATA__ = {…};`（页面搭建器的 `layerTree`）。
- 其中一个 `EvaTabs` 组件下有 63 个 `EvaTabs.Panel`，标签文字在 `props.tabItem.tabItemProps.textContent.content`，
  形如「第63期」…「第1期」，**最新在前**；每个面板里有一个 `NativePickResource`，`props.ids[]` 是 `{"bvid":"BV…"}`，
  每期 10–21 个，共 858 条（838 个不重复，20 条跨期重复）。
- **只有 BV 号，没有标题和封面**（`_asyncData` 为空，标题是 B 站客户端自己再取的）。
- 补标题封面用社区文档里的批量接口 `GET x/article/cards?ids=BV…,BV…`
  （[docs/article/card.md](https://github.com/SessionHu/bilibili-API-collect/blob/master/docs/article/card.md)）：
  可传 BV，只要 `Referer` 在 `bilibili.com` 下，不要 WBI；返回以每个 id 为键的对象，含 `bvid` / `title` / `pic` /
  `duration` / `owner.name` / `stat.view`。该接口只来自文档，**没对照过真实响应**。

## 方案

### 1. 首页

「快捷入口」那一行由三张卡片变四张：每周必看 / 入站必刷 / 热歌榜 / **热门播客**（`Icons.Outlined.Podcasts`），一行排开，
不需要登录。`ChartKind` 新增 `PODCAST("热门播客")`。

### 2. 榜单页

沿用榜单页（`ChartScreen`，覆盖在搜索 tab 里，返回回首页）。热门播客比其他榜单多一排**期数选择条**：
顶栏下一行横向滚动的 `FilterChip`（「第63期」「第62期」…），默认选最新一期，点哪期看哪期。
列表行、下拉刷新、加载中 / 失败重试 / 空列表、点条目整期作临时队列起播，都与其他榜单一致。
顶栏副标题显示「N 个视频」。

### 3. 数据获取

- 打开 = 取页面 HTML 1 次 + 取当期视频卡片 1 次。切换到没看过的期只多 1 次卡片请求；
  看过的期缓存在 `ChartController` 的内存里，关闭榜单页或下拉刷新时清掉。下拉刷新 = 重取页面 + 重取当期。
- 页面请求走 `BiliApi.eraPage(@Url)`，限定 `bilibili.com` 域内（`isTrustedBiliHost`）；套 `FEED` 限速桶，
  冷却期内本地快速失败；**HTTP 412 当作被拦**，记入全局冷却且**不重试**；其他非 2xx 报「页面加载失败：HTTP N」。
- 卡片请求走 `chartRead`（即 `accountRead` 的风控出口 + `FEED` 桶）。一期最多取 30 个（现在每期最多 21 个）。
- 解析在 `Dispatchers.Default` 上做，不占主线程。

### 4. 解析规则（`core/network/internal/EraPagePolicy.kt`，纯函数，有单测）

**不写死层级路径**，页面搭建器的布局改了也尽量能认：

1. 在 HTML 里找 `window.__BILIACT_EVAPAGEDATA__ =`，用「认字符串与转义的括号配对」取出后面那个 JSON 对象
   （不靠 `;` 或下一个变量名定界），再解析。
2. 在 JSON 里找所有 `name == "EvaTabs.Panel"` 的节点（找到就不再往里钻）。每个面板：
   标签 = `props.tabItem.tabItemProps.textContent.content`，缺失时用「第N组」；
   BV = 面板内所有键名为 `bvid`、值是合法 BV 号（`BV` + 10 位字母数字）的字符串，按出现顺序去重。没有 BV 的面板丢掉。
3. 页面里没有任何标签面板时，退化成一组「全部」：整份 JSON 里所有合法 `bvid` 去重。
4. 取不到数据变量、JSON 解析失败、或最终一个分组都没有，都返回空列表；
   仓库层把空列表变成明确的错误「热门播客页面结构变了，没能解析」。

### 5. 对外类型与仓库方法

- 新增 `EraGroup(label, bvids)`（`ChartModels.kt`，对外公开）。
- 仓库：`podcastIssues(): Result<List<EraGroup>>`（固定网址写在仓库里）、
  `videoCards(bvids): Result<List<ChartItem>>`（按传入顺序返回；卡片里取不到或已不可用的跳过）。
- 卡片按返回对象里的 `bvid` 字段对应，不依赖 map 键的写法；响应 DTO 复用 `PopularVideo`。
- 解析器与 `videoCards` 都是通用的，之后「b23 短链展开成活动页时把视频列出来」可以直接复用，本次不接。

### 6. `:app` 结构

- `ChartState` 加 `issues: List<String>`（期数标签，其他榜单为空）与 `selectedIssue: Int`。
- `ChartController` 加 `selectIssue(index)`，并在内部保存页面分组与「已取过卡片的期」缓存；
  所有异步结果回来时，榜单页已关闭、换榜、或用户又切到别的期，就丢弃。
- `ChartScreen` 加 `onIssueSelect` 与期数选择条（`issues` 为空时不显示）。

## 不做

- 下载、长按菜单、「b23 短链展开成活动页时列视频」（解析器通用，之后接很小，等用户说）。
- 不动搜索框对非视频链接的处理（另有待决的改动，不在本文）。

## 风险

- 只绑定这一个活动页：运营换页（网址变）或改版（组件结构变）就会失效，此时显示明确的错误，不白屏。
- `x/article/cards` 只来自社区文档，真实响应与限制没验证；失败时显示错误并可重试，不退化成只有 BV 号的列表。

## 测试

- `:core:network`（离线）：
  - `EraPagePolicyTest`：用从真实页面裁剪的样例（`src/test/resources/era_podcast_sample.html`，3 期 × 3 个 BV，
    保留真实的嵌套结构）验证按期分组与顺序；另用内联 JSON 验证括号配对（字符串里的花括号和转义引号）、
    无标签页退化、标签缺失、面板内去重、面板外的 `bvid` 不算、非法 BV 忽略、没有数据变量。
  - `ChartDtoTest`：`x/article/cards` 响应解析（含 `null` 值与未知字段）。
  - `ChartPolicyTest`：`resolveCardItems`（按请求顺序、缺失跳过、按 `bvid` 对应）。
- `./gradlew test assembleDebug` 通过。
- 真机（由用户）：「热门播客」卡片能打开；期数选择条默认最新一期，切换后列表更新；标题封面正常；点条目能播；
  下拉刷新；断网显示失败并可重试；`x/article/cards` 的真实返回是否如文档所说。

## 文档

- `docs/05-功能优化篇.md`：§13.29 + 总览表一行；§10.0 待办里去掉「热门播客入口」。
- `docs/BiliSeek-手动测试指南.md`：步骤 14 加热门播客的检查点。
- `docs/03-架构设计篇.md`：控制器表与首页描述补上热门播客。
