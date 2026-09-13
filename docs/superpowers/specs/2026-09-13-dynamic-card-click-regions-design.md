# 动态卡片点击区域重设计

日期：2026-09-13
范围：`app` 模块的动态流卡片 `DynamicCard`（`DynamicScreen.kt`，被 `DynamicScreen` 与 `UpSpaceScreen` 共用）+ `AppViewModel` 的详情状态 + `core:network` 的 `DynamicEntry`。

## 背景

`DynamicCard` 当前的点击行为：

- **视频动态**：整卡（含文案、头像、空白区域）绑定 `onClick`，点哪里都直接播放视频。
- **图文动态**（无视频）：整卡绑定 `onDetailClick`，点击非图片区域会打开 `DynamicDetailSheet` 模态弹层——弹层背后是 `AppViewModel.openDynamicDetail()`，发起四级降级网络请求（web detail → opus/detail → desktop → rid）拉取完整正文，因为 feed 里的 `descText` 对 OPUS 类型往往只是被服务端截断的摘要。
- 图片点击（`DrawGrid` / 单图）已经有独立的 `onImageClick`，不受影响。
- 头像/昵称当前不可点击；`DynamicEntry` 里也没有 `authorMid` 字段——DTO 层的 `DynamicAuthor.mid` 已解析但未映射进来。

这与预期体验不符：视频动态点文字会误触发播放；图文动态点空白区域弹出一个模态层，割裂了浏览节奏；头像/昵称无法进入 UP 主页。

## 一、点击区域拆分

删除 `DynamicCard` 顶层 `Column` 上的整卡 `Modifier.clickable`（`DynamicScreen.kt:282-286`）。改为三块独立可点区域：

1. **视频行**（封面缩略图 + 时长角标 + 标题 + 播放/弹幕数，`DynamicScreen.kt:330-387` 的 `Row`）：仅当 `entry.video != null` 时渲染；整行绑定 `onClick`（播放），点击范围不含上方文案、头像、下方互动栏。
2. **头像/昵称区**（`DynamicScreen.kt:288-307` 的 `Row`）：新增 `onAuthorClick`，点击进入该 UP 的空间页。视频动态和图文动态都适用。
3. **正文文案**（`descText`）：不再触发导航，改为原地展开/收起（见下节）。

卡片空白区域（padding、无图无文的区域）点击无响应。图片点击、底部转发/评论/点赞按钮保持现状不变。

## 二、正文文案：原地展开/收起

替换 `descText` 现有的静态 `maxLines = 3` 截断（`DynamicScreen.kt:309-317`），只对这一段文案生效（`opusTitle` 不受影响，它本来就不限制行数、通常较短）。

**折叠态**：最多 3 行；若文本确实溢出（用两段式布局：先用 `onTextLayout` 测量 `hasVisualOverflow`，溢出时再按第 3 行可用宽度截断正文并拼接后缀，重新渲染），第 3 行末尾以内联可点击的 `…展开` 结尾。不溢出则不显示任何按钮，行为等同现状。

**点击"展开"**：
- 若该动态（按 `idStr`）尚未拉取过完整正文，触发与现有 `DynamicDetailSheet` 相同的网络请求 `BiliRepository.dynamicDetail(idStr, seed = entry)`（四级降级、仓储层已有 LRU 缓存），文案区域显示小型 loading 态。
- 成功后原地渲染完整正文，不限制行数，末尾以内联 `…收起` 结尾。
- 失败则内联显示错误提示 + 可重试。

**点击"收起"**：仅切换回 3 行折叠视图；已拉取的完整正文保留在内存，重新展开不重复请求。

**多卡片独立展开**：列表中允许同时有多张卡片处于展开态，互不影响（按 `idStr` 维护状态，而非单一"当前弹层"模型）。

## 三、头像/昵称 → 跳转 UP 主页

- `core/network/DynamicFeed.kt`：`DynamicEntry` 新增 `authorMid: Long = 0`，在 `DynamicFeed.kt:116` 附近的映射处补 `authorMid = author.mid`（`DynamicAuthor.mid` 已存在于 DTO，仅需补映射）。
- `DynamicCard` 新增 `onAuthorClick: () -> Unit`，包在头像 + 昵称/时间的 `Row` 外层。
- `DynamicScreen` / `UpSpaceScreen` 新增 `onAuthorClick: (DynamicEntry) -> Unit` 并透传给 `DynamicCard`。
- `MainActivity.kt` 两处 `DynamicCard`/`DynamicScreen`/`UpSpaceScreen` 调用点，`onAuthorClick` 接到：
  ```kotlin
  { entry -> viewModel.openUpSpace(SearchUser(mid = entry.authorMid, uname = entry.authorName, upic = entry.authorFace)) }
  ```
  复用现有 `AppViewModel.openUpSpace(user: SearchUser)`（与搜索结果里 `onUserClick = viewModel::openUpSpace` 是同一套机制）。
- `UpSpaceScreen` 自身的动态列表里点头像，效果是重新打开（等价于停留在）当前 UP 的空间页——不特殊处理，行为简单一致即可。

## 四、状态模型改动（`AppViewModel`）

**删除**（完全被本设计取代）：
- `_dynamicDetail: MutableStateFlow<DynamicDetailState?>` / `dynamicDetail` / `openDynamicDetail()` / `dismissDynamicDetail()`
- `DynamicDetailSheet.kt` 组件本身
- `MainActivity.kt:524-531` 里对 `DynamicDetailSheet` 的弹层接入代码

**新增**：
- 按 `idStr` 维护的展开状态 Map，例如：
  ```kotlin
  data class DynamicExpandState(
      val expanded: Boolean = false,
      val entry: DynamicEntry? = null,   // 拉取到的完整正文，null = 未拉取/加载中
      val error: String? = null,
  )
  private val _dynamicExpand = MutableStateFlow<Map<String, DynamicExpandState>>(emptyMap())
  val dynamicExpand: StateFlow<Map<String, DynamicExpandState>> = _dynamicExpand.asStateFlow()

  fun toggleDynamicExpand(entry: DynamicEntry) { ... }
  ```
- `toggleDynamicExpand`：折叠→展开时，若 Map 里没有该 `idStr` 的缓存正文则发起 `dynamicDetail()` 请求并写入 loading/成功/失败态；展开→折叠只翻转 `expanded`，不清空已缓存正文。

## 五、数据流穿透

- `DynamicCard` 新增参数：`expandState: DynamicExpandState?`、`onToggleExpand: () -> Unit`、`onAuthorClick: () -> Unit`；移除 `onDetailClick`。
- `DynamicScreen` / `UpSpaceScreen`：新增 `dynamicExpand: Map<String, DynamicExpandState>` 状态入参 + `onToggleExpand: (DynamicEntry) -> Unit` + `onAuthorClick: (DynamicEntry) -> Unit`，在 `items(...)` 循环里按 `entry.idStr` 从 Map 取值传给 `DynamicCard`。
- `MainActivity.kt`：接上 `viewModel.dynamicExpand` 状态、`viewModel::toggleDynamicExpand`，并按上节接上 `onAuthorClick`；删除 `dynamicDetail`/`onOpenDetail`/`DynamicDetailSheet` 相关接线。

## 不受影响

图片预览点击（`DrawGrid`、单图）、底部转发/评论/点赞按钮、`DynamicFilterBar`、下拉刷新、分页加载——均维持现状。
