# 视频封面右上角的充电角标（第一步：充电专属 + 调试日志）

日期：2026-10-04
范围：`core:network` 的 `dto/UserDto.kt`（`SpaceVideo`）、`dto/DynamicDto.kt`（`DynamicArchive`）、`dto/BiliDto.kt`（`WatchLaterItem`）、`DynamicFeed.kt`（`DynamicVideo`）、`BiliNetwork.kt`、`internal/ChargeDebug.kt`（新增）；`app` 的 `ui/CoverImage.kt`、`ui/ChargeBadgePolicy.kt`（新增）、`SearchScreen.kt`、`ui/UpSpaceScreen.kt`、`ui/WatchLaterScreen.kt`、`ui/DynamicScreen.kt`。

## 背景（用户原话）

> B 站的视频有几种类型：免费视频，充电专属视频，充电但是限免视频。如果是充电视频或充电但限免视频，我希望在视频条目的封面右上角添加一些标志，用来提示。同时还有一种情况是需要充电，并且我已经充电，也可以添加一个标志，为已充电视频。可以参考下 bilipai 如何实现。

## 调研

- bilipai 只做了一种「充电专属」标签，不区分限免与已充电：
  - 空间投稿看 `is_charging_arc` / `elec_arc_type == 1` / `ugc_pay`；
  - 动态里的视频优先用 `badge.text`，再看同一组字段；
  - 稍后再看看 `charging_pay`；
  - 视频详情有 `is_upower_exclusive` / `is_upower_play` / `is_upower_preview`。
- 公开文档（bilibili-API-collect）：
  - `elec_arc_type` 写着「1 为充电视频，可能还有其他情况」；
  - `elec_arc_badge` 没有文档；
  - 「限时免费」没有任何接口说明；
  - `is_upower_play` 没有释义，从名字看像「当前用户能否播放」。
- 现状：搜索结果已经按 `badgepay`（人工核实过的字段）在封面左上角显示粉色「充电」。

## 方案（用户已选）

- 数据：**先做文档确定的「充电专属」+ 调试日志**；用户在手机上打开几个已知例子（充电专属、限免、充过电的 UP 的），开发者只读手机日志、不发请求，对上号后再补「限免」「已充电」。
- 「已充电」判断：**播放页顺手记住**（第二步做，等日志确认 `is_upower_play` 的含义）。
- 角标样式：效果图选 **B 短字**——「充电」粉 `#FB7299`、「限免」绿 `#1DB46E`、「已充电」蓝 `#00AEEC`，白字，在封面**右上角**（右上、左下两个角圆 4dp）。
  - 封面窄于 80dp 时 9sp；80dp 及以上 10sp。
  - 效果图：`.superpowers/brainstorm/1751126-1791122364/content/charge-badge-style.html`，脚本 `/tmp/biliseek-fonts/build_charge_badge_mock.py`。

### 第一步做什么

1. **角标组件**：
   - `CoverThumbnail` 的 `badge` 改成 `CoverBadge(text, color)`，画在右上角；
   - 抽出 `CoverBadgeLabel` 给动态卡片的视频封面共用（那里不是 `CoverThumbnail`，右下角已有时长）；
   - 搜索结果原来的左上角「充电」随之挪到右上角。
2. **哪些列表显示「充电」**（接口里有文档字段的）：
   - 搜索结果：`badgepay`（已有）；
   - UP 空间投稿：`is_charging_arc` 或 `elec_arc_type == 1`；
   - 动态里的视频：`badge.text` 含「充电」，或 `is_charging_arc` / `elec_arc_type == 1`。新版动态的普通视频 `badge.text` 是「投稿视频」，所以只认含「充电」的文字。结果算进 `DynamicVideo.chargeExclusive`；
   - 稍后再看：`charging_pay` 不为空（bilipai 的判断）。
3. **判断放在纯函数**：
   - `app/ui/ChargeBadgePolicy.kt`：`chargeBadge(...)`，各列表一个重载，返回 `CoverBadge?`；
   - 动态的判断在 `core:network` 解析时算好（`isChargeExclusiveArchive`），因为 `DynamicArchive` 不出 `core:network`。
4. **调试日志**（只在 debug 包）：
   - 新的 OkHttp 拦截器只读不改（`peekBody`），加在解压拦截器外层，拿到明文 JSON；
   - 在 JSON 里找带 `bvid` 的对象，每个打一行，tag `BiliSeekCharge`：接口路径、bvid、标题前 16 个字，以及键名含 `charg / upower / elec / ugc_pay / pay / badge / free / preview / privilege / rights` 的字段；
   - 对象 / 数组值压成一行，最多 160 字。纯函数 `chargeDebugLines`（有单测）；
   - 现有的 `BiliSeekHttp` 整包日志太长会被 logcat 截断，所以单独打这种短行。

### 第二步（等日志）

- 「限免」：按日志里限免视频的真实字段补规则（预期在 `elec_arc_type`、`elec_arc_badge` 或动态 `badge.text` 里）。
- 「已充电」：播放页的视频详情若确认 `is_upower_play` 表示「我能看」，就记住这个 UP（不多发请求），列表里他的充电视频改标「已充电」。
- 收藏夹、榜单、合集若日志里有对应字段，一并接上；本地列表没存这类信息，不做。

## 不做

- 不为了角标额外请求视频详情。
- 播放页本身不加角标。

## 测试与验证

- 先写测试并确认编译失败，再实现：
  - `core:network`：`ChargeDebugLinesTest`；新字段解析（空间投稿、稍后再看、动态 → `DynamicVideo.chargeExclusive`）；
  - `app`：`ChargeBadgePolicyTest`。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
- 第二步的数据收集：用户装上 debug 包、在手机上打开例子后，开发者用 `adb logcat -s BiliSeekCharge` 读日志。

## 第二步进展（2026-10-04 真机日志）

- `is_upower_play` 已核实就是「当前账号能否观看」：BV1YT421y7bR（已充电）true，BV1u1a662Ewa（未充电）false；后者 `is_upower_preview=true`。
- 空间投稿：充电专属 `is_charging_arc=true`、`elec_arc_type=1`、`elec_arc_badge="充电专属"`；普通为 false / 0 / 空。
- 动态：`badge.text` 普通「投稿视频」、合作「合作视频」、充电「充电专属」；动态里的视频没有 `is_charging_arc` / `elec_arc_type`。
- 「已充电」名单按用户选择**存本地**（`ChargedUpStore`，DataStore `biliseek_charged_ups`），只在登录时更新。
- 待办：「限免」等样本；搜索结果 / 收藏夹 / 合集等日志。

## 第三步（2026-10-05）：限免、去掉已充电、搜索不标

- 用户反馈：只看过老猫鱼不吃鱼的限免视频，他的其他充电视频被标成「已充电」；动态、搜索标得不对不全。
- 真机日志（电脑端持续记录，`rights` 逐项展开）：
  - 限免 BV1jjHp6jEk7：UP 空间 `elec_arc_badge="限时免费"`，动态 `badge.text="限时免费"`；
  - 视频详情里限免和已充电一模一样（`is_upower_exclusive=true is_upower_play=true`，`rights` 无差别）→「已充电」无法可靠判断；
  - 搜索接口对充电专属、限免视频都不带任何充电字段（全 0）。
- 用户选定：**去掉「已充电」，搜索结果不标**。
- 现在的规则：粉色「充电」= 充电专属（UP 空间 `is_charging_arc` / `elec_arc_type == 1`，动态角标文字含「充电」，稍后再看 `charging_pay.level`）；绿色「限免」= UP 空间 `elec_arc_badge` 或动态角标文字是「限时免费」。收藏夹、合集等列表没有充电字段，不标。

