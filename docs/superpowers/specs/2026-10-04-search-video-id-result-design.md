# 搜索 BV 号 / av 号 / 视频链接：先显示结果，不直接播放

日期：2026-10-04
范围：`app` 的 `search/SearchController.kt`、`ui/VideoMenuPolicy.kt`（加一个映射）；`core:network` 的 `dto/BiliDto.kt`（`VideoViewInfo` 加 `pic`）。

## 背景（用户原话）

> 我搜索 BV 号的时候，它就直接起播了。我希望搜索 BV 号的时候，先展示搜索结果，正常的展示视频名等信息。

## 现状

- 搜索框里认出 BV 号、av 号（本地换算成 BV）、视频网址、展开后是视频的 b23 短链，都直接起播，标题先显示成 BV 号。
- 从别的 App 分享过来的文字（`handleSharedText`）走同一条路，也直接起播。

## 方案（用户已选）

- **范围**：搜索框里的 BV 号、av 号、视频链接、短链（展开后是视频）**都先显示一条结果**。
- **外部分享仍然直接播放**：分享过来就是想听这个。只有在搜索框里自己输入或粘贴时才先显示结果。
- **做法**：
  - 认出视频后请求一次视频详情（`videoView`，就是播放页本来要拉的那个公开接口）。
  - 映射成一条 `SearchVideo`（`VideoViewInfo.toSearchVideo()`，放在 `VideoMenuPolicy.kt` 现有的「条目 → 搜索结果模型」一节），放进搜索结果，只有这一条、没有下一页。
  - 标题、UP 主、时长、播放量、发布日期都显示，充电专属视频带粉色「充电」角标（`badgePay` 取 `is_upower_exclusive`）。
  - 点这一条和普通结果一样：单 P 起播，多 P 先挑 P；三点菜单照常。
  - 在「UP 主」搜索模式下也切回「视频」，结果才看得到。
- **拉详情失败时**（断网、视频不存在）退回把原输入当关键词搜索，和短链展不开时一样；断网时显示「网络不可用」可重试。
- 搜索历史不记 BV 号（和以前一样只记关键词）。
- 活动页、「这个链接不是视频」的处理不变。

## 测试与验证

- 先写测试并确认编译失败再实现：
  - `VideoMenuPolicyTest` 加 `VideoViewInfo.toSearchVideo()` 的映射；
  - 详情解析加 `pic`。
- 搜索流程没有控制器单测，由用户在真机验证；手动测试指南步骤 15 按新行为改，步骤 16（外部分享）不变。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
