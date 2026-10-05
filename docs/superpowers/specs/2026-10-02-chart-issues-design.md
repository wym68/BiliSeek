# 每周必看、热歌榜：期数选择条

日期：2026-10-02
范围：`:core:network`（期数整理、仓库取数）、`:app`（`ChartController` 的期数机制）。
承接 [home-charts](2026-10-02-home-charts-design.md)（当时「往期切换」列在不做里）和 [home-podcast](2026-10-02-home-podcast-design.md)（期数选择条的做法）。

## 背景

每周必看有 300 多期，热歌榜也有上百期，现在只能看最新一期。热门播客已经有期数选择条，用户要求这两个榜单也能像它那样切换期数。

## 可行性（只查了现有代码与社区文档，没碰 B 站服务器）

期数列表本来就在现有的第一次请求里，只是被丢掉了：

- 每周必看 `series/list` 返回**全部期数**（`number / subject / name`），现在只取了最大的 `number`；
- 热歌榜 `all_period` 返回**全部期**（按年份分组的 `ID / priod / publish_time`），现在只取了发布时间最晚的一期；
- 取某一期内容的接口也不用换：`series/one?number=N`、`music_list?list_id=ID`，现有代码已经在用，只是总传最新的那一期。

所以**不需要新接口**。打开时仍是 2 次请求，和现在一样；切到没看过的期多 1 次。

## 方案

### 界面（沿用热门播客）

顶栏下一排期数选择条（`ChartScreen` 里的 `IssueChips`，已经是通用的）：最新一期在最左、默认选中；只有一期或没有期数列表时不显示。
标签：每周必看「第279期」、热歌榜「第48期」（接口没给期数时退成「往期」）；完整期名（日期范围、主题）仍在页头。
选择条是横向滑动的，每周必看有 300 多个标签，往回翻到很久以前要滑很久。用户选了「和热门播客一样」，**不做「全部期数」弹层**。

### 数据（`:core:network`）

- 新增公开类型 `ChartIssue(id: Long, number: Int)`：`id` 是取这一期内容用的编号（每周必看是期数，热歌榜是 `list_id`），`number` 显示成「第 N 期」，`label` 由它派生。
  与 `ChartItem` / `ChartPage` / `EraGroup` 一样，`:app` 要直接用，所以公开。
- 仓库：`weeklyMustWatch()` / `hotSongs()` 各拆成两个——
  - `weeklyIssues()` / `hotSongIssues()`：取全部期数，最新在前，1 次请求；
  - `weeklyIssue(issue)` / `hotSongIssue(issue)`：取某一期的内容，1 次请求。

  都走 `chartRead`（`accountRead` 风控分流 + FEED 限速桶），**不加重试**。`weeklyMustWatch()` / `hotSongs()` 只有 `ChartController` 在用，随之删除。
- 纯函数（`internal/ChartPolicy.kt`，离线单测）：
  - `resolveWeeklyIssues(entries)`：期数 > 0 的、去重、期数大的在前（不依赖接口返回的顺序）；
  - `resolveMusicIssues(byYear)`：各年份合并，`id` > 0 的、按 `id` 去重，发布时间晚的在前（相同取期数大的）。

  取代 `resolveLatestWeeklyNumber` / `resolveLatestMusicPeriod`（「最新」就是列表第一项；没有别处在用，一并删除）。
  顺带比旧写法稳一点：旧的先取最晚一期再检查 `id`，最晚一期的 `id` 无效就整张榜单空了；新的先过滤再排序。
- `MusicListData.toChartPage` 的参数由 `MusicPeriod` 改成期数 `Int`（页头「第 N 期」只用到期数）。

### 控制器（`:app`）

`ChartController` 里活动页分组切换的那套（`eraCache` / `loadEraGroup` / `failEra`）改成**三类榜单共用的「期数」机制**，热门播客与搜索框活动页的行为不变：

- 取数分三路：入站必刷（没有期数）、每周必看与热歌榜（`fetchIssued`：先取期数列表，再取要看的那一期）、活动页类（`fetchEra`：先取页面，再按组补卡片）。
- `issueCache`（下标 → `ChartPage`）三类共用；取第 N 期内容的方法按榜单种类分发（`fetchIssue`）；`selectIssue` 不再限定活动页，有选择条就能切。
  取过的期直接用缓存，没取过的发一次请求；等待期间又切走了，结果丢弃（和原来一样）。
- 下拉刷新 / 失败重试：重取期数列表和当前这一期，缓存清掉。期数列表是空的（接口给了空）显示「榜单暂时是空的」，不当错误。
- 不做请求取消：连续点多个期数会各发一次请求（经 FEED 限速桶排队，间隔 0.6–0.9 秒），旧的结果回来后被丢弃，与热门播客一致。
  `runCatching` 会把协程取消吞成失败，要加取消得连带改仓库里所有 `runCatching`，不值得。

## 不做

- 「全部期数」弹层、按日期跳转、记住上次看到哪一期。
- 热歌榜的原创榜（`list_type=2`）；入站必刷本来就没有期数。
- 刷新后按期数编号保持选中：现在按下标，和热门播客一致，只有刷新的瞬间正好发布了新一期才会错位一格。
- 不落库：期数列表和各期内容只在内存里。

## 风险

- 接口都只对照过社区文档、**没对照过真实响应**：`series/list` 是否一次给全部、`all_period` 的 `priod` 是否全局连续、老期数用 `series/one?number=` / `music_list?list_id=` 能否取到，真机上要验证。
- 最新一期官方若还没发布，第一个标签可能是空榜单（旧写法也有这个问题，现在往前切一期即可）。
- 选择条很长（每周必看 300 多个标签）。

## 测试与验证

- `:core:network`（离线）`ChartPolicyTest`：期数乱序、无效、重复；热歌榜跨年份合并与排序、`id` 无效与重复、空列表；`ChartIssue.label`；页头「第 N 期」随参数变化。
  替换掉旧的 5 个「取最新一期」用例。
- `./gradlew test assembleDebug` 通过。控制器是有状态的胶水代码（依赖具体的 `BiliRepository`），和之前的榜单一样不单测；判断逻辑都在上面的纯函数里。
- 真机（由用户）：见手动测试指南步骤 14。

## 文档

- `docs/05-功能优化篇.md`：新增 §13.33，总览表一行；`home-charts` 设计稿「不做」里的「往期切换」加指向。
- `docs/BiliSeek-手动测试指南.md`：步骤 14 加每周必看、热歌榜的期数选择条检查点。
- `docs/03-架构设计篇.md`：`ChartController` 的描述若写到了分组切换，补上期数。
