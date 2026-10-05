# 搜索框：短链展开成活动页时列出视频，并收紧 av 号识别

日期：2026-10-02
范围：`:core:network`（`parseSearchInput`、活动页取回）、`:app`（`SearchController` 接线、榜单框架泛化到任意活动页）。
承接 [home-podcast](2026-10-02-home-podcast-design.md)：那份里写的解析器通用，这次把它接到搜索框。

## 背景

在搜索框里搜一条展开后是活动页的 b23 短链（例如热门播客那条 `b23.tv/1smoWpr`），现在的行为是：
展开短链 → 对落点再解析 → 既没有 BV 号也没有 av 号 → 退回，**拿链接原文当关键词去搜索**。用户看到的是搜索结果页或「没有找到」。
用户希望像热门播客入口那样，把活动页里的视频抽出来列出来。

同时，`parseSearchInput` 的 av 号识别太松：条件是「输入里含 `av` 字样，或整段都是数字」，再用
`(?:^|/|av)(\d{1,12})(?:$|/|\?)` 找数字。后果：

- 搜「2024」「1989」这类**纯数字**会直接去播 av2024、av1989，而不是搜索；
- 任何带 `av` 字样（如 `favlist`、`nav`）又带 `/数字/` 的链接，例如 UP 的收藏夹
  `https://space.bilibili.com/12345/favlist`，会被当成 av12345 起播。

## 方案

### 1. 活动页：展开后列出视频

- `SearchIntent` 新增 `EraPage(url)`。`parseSearchInput` 在 b23 短链之后、BV 号之前识别活动页网址：
  `bilibili.com/blackboard/era/<id>.html`（`www.` / `m.` 可有可无，`http` 也认），
  **规整成 `https://www.bilibili.com/blackboard/era/<id>.html`**：丢掉 `?…` 分享参数（里面有分享会话 id、时间戳），也统一成 https。
  放在 BV 号之前，是因为活动页网址的参数里可能带 BV 号字样，仍应按活动页处理。
- 短链展开后二次解析得到 `EraPage` 时，不再退回关键词搜索，而是打开榜单页（`ChartController.openEraPage(url)`）。
  直接把活动页网址（不是短链）粘进搜索框，走同一条路。
- 打开榜单页时**清空搜索框**（回到首页状态）：链接已经「用掉」；也避免 `MainActivity` 里「搜索关键词非空就关榜单页」
  的保护把它关掉。返回后回到首页。
- 榜单页：沿用热门播客那套——解析标签页和 BV 号，批量补标题封面；有多个标签页显示期数选择条，只有一组时不显示；
  顶栏标题用页面的 `<title>`（取不到时「活动页」）。
- **解析不出视频时**（页面没有视频、或结构认不出来）：榜单页显示「这个活动页没能解析：里面没有找到视频，或者页面结构认不出来」，
  可返回，不再退回「拿链接文字去搜」。
- 展开后既不是视频也不是活动页的链接，**本文写成时行为不变**（仍退回关键词搜索）；后来改为提示「这个链接不是视频」，见 docs/05 §13.31。

### 2. 泛化榜单框架

- `:core:network`：`EraPageContent(title, groups)`；`eraPage(url, emptyMessage)` 取页面、解析标题与分组，没有分组时抛 `emptyMessage`；
  `podcastPage()` = `eraPage(PODCAST_ERA_URL, "热门播客页面结构变了，没能解析")`，替换掉原来的 `podcastIssues()`（还没提交过）。
  标题由新增的纯函数 `parseEraPageTitle(html)` 取（HTML 的 `<title>`，折叠空白，没有时空串）。
- `:app`：`ChartKind` 加 `ERA_PAGE("活动页")` 和 `isEraPage`（`PODCAST`、`ERA_PAGE` 为 true）；`ChartState` 加 `title`（默认 `kind.title`，
  活动页用页面自己的标题，热门播客仍显示「热门播客」）；`ChartController` 里的 `podcast*` 改名 `era*`，新增 `openEraPage(url)`，
  `selectIssue` 对所有活动页类榜单生效；只有一个分组时不显示期数选择条。
- 这条路径上活动页会被取两次：展开短链时 App 本来就把落点页面下载了一遍但没保留，随后榜单页走自己的取页面（限速、冷却、HTTP 412 处理）再取一次。
  只发生在「短链落到活动页」这条少见的路径上；若要省掉，得改展开接口去保留页面内容，这次不动。

### 3. 收紧 av 号

`parseSearchInput` 只认两种：

- 整段输入就是 `av170001`（不分大小写）：`^av(\d{1,12})$`；
- 文本里带视频页网址 `…/video/av170001`，后面可以跟 `/`、`?`、`.html`：`/video/av(\d{1,12})(?![0-9A-Za-z])`。

数字必须大于 0。**不再认**：纯数字、`av` 后面带空格或字母、链接里别的 `/数字/`、`aid=`。这些都按关键词搜索。
这是行为变化：搜纯数字不再播 av 号（占位提示里本来也没写过「纯数字」）。b23 短链 `b23.tv/av170001` 不受影响（短链优先匹配）。

## 不做

- 展开后既不是视频也不是活动页的链接的「这个链接不是视频」提示（本文写成时待用户定，后来用户选了做，见 docs/05 §13.31）。
- 展开接口保留页面内容以省一次请求。
- 非 `blackboard/era` 的活动页（如 `blackboard/activity-xxx.html`）：结构不同，不认，按关键词搜索（同现状）。
- 搜索框占位提示文案不改。

## 风险

- 活动页解析只对 `__BILIACT_EVAPAGEDATA__` + `EvaTabs.Panel` 这种搭建器结构有把握；别的 era 页面如果结构不同，会走「没能解析」的错误提示。
  期数选择条的标签文字、面板划分依赖页面里的标签组件。
- 活动页里的 BV 可能含已下架视频，补卡片时会被跳过，列表可能比页面上少几条。

## 测试

- `:core:network`（离线）：
  - `SearchInputTest`：`AV170001`、视频页网址里的 av（`?p=2`、`/`、`.html`）仍直达；纯数字、`favlist` 链接、`cv` 链接、`avatar2024`、`av170001abc`、`av0`、`av 170001`
    都是关键词；活动页网址（含分享参数、`m.` 站、`http`、混在分享文本里）规整成干净的 https 网址；
    `activity-xxx` 页、别的域名下的 `/blackboard/era/`、`bilibili.com.evil.com` 仿冒域名都不算活动页。
  - `EraPagePolicyTest`：`parseEraPageTitle`（真实页面样例的标题「热门播客榜单」；折叠空白；没有 `<title>` 时空串）。
- `./gradlew test assembleDebug` 通过。
- 真机（由用户）：搜 `https://b23.tv/1smoWpr` 打开榜单页（标题「热门播客榜单」、期数选择条、列表有标题封面）；
  直接粘贴活动页网址同样；搜「2024」是正常搜索；UP 收藏夹链接不再起播；搜索框里搜普通短链、BV、`av170001` 行为不变。

## 文档

- `docs/05-功能优化篇.md`：§13.30 + 总览表一行。
- `docs/BiliSeek-手动测试指南.md`：步骤 15 加检查点。
- `docs/03-架构设计篇.md`：首页描述里的「直达」补上活动页。
