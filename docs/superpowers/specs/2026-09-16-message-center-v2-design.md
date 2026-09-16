# BiliSeek「消息中心」v2 设计（导航结构 + DTO 修正）

> **版本**: v2.0
> **日期**: 2026-09-16
> **状态**: 已确认
> **关系**: 本文档**取代** `2026-09-16-message-center-design.md`（v1）的 §4~§5（架构与 UI 行为部分），v1 的 §1~§3（目标、端点清单）与 §6~§11 仍然有效，未在本文提到的内容以 v1 为准。

---

## 1. 背景

v1 已实现并上线（commit `6a535c9`~`eda8749`），真机验证暴露两类问题：

1. **导航死循环**（已修复，见下方"已修复问题"）：`MessageCenterScreen` 的 `onInit` 误接到兼具"加载 + 发导航信号"两个职责的 `openMessages()`，导致每次进页面都重新触发一次导航信号，页面被反复销毁重建。已改为接到纯加载的 `retryMessageHub()`，问题消失。
2. **交互形态和真实 B 站不一致**：现在是"三个 tab 内嵌切换，内容在同一页面里换"；真实 B 站是"三个入口图标常驻不换，点击后各自 push 到独立页面，消息中心页面下方直接是私信会话列表"。
3. **两个真机验证中发现、待修的 bug**：
   - `MessageHubState.isLoading`（聚合层）在请求成功分支没有被重置为 `false`，导致下拉刷新指示器永久转圈。
   - `ReplyAtItem`/`LikeItem` DTO 假设了错误的扁平 JSON 结构；真实 `x/msgfeed/reply`、`x/msgfeed/like` 响应把业务字段（`content`、`business_id`、`source_id` 等）嵌在一层 `item` 子对象里，且用户信息字段名是 `nickname`/`avatar` 而非 `name`/`face`（已用 `ref/bilipai/.../MessageResponse.kt` 交叉核对确认）。评论正文、跳转目标全部读到默认值/空字符串。

本次改动一次性解决 2 和 3（3 是 2 的重构过程中顺路修，不是新增范围）。

---

## 2. 架构改动

### 2.1 导航：三个入口图标各自变成 push 页面

新增 3 个 `Destination`（`AppShell.kt`），接线方式与现有 `UP_SPACE`/`FOLLOWING_LIST` 完全一致（各自一个 `MutableSharedFlow<Unit>` 信号 + `AppShell` 里一个 `LaunchedEffect` 订阅 `navigate()`）：

```kotlin
MESSAGE_REPLY("messageReply", "回复与@")
MESSAGE_LIKE("messageLike", "收到喜欢")
MESSAGE_FOLLOWER("messageFollower", "新增粉丝")
```

`MessageCenterScreen`（`messages` route）职责收缩为：顶部栏「消息」+ 三个入口图标（横向排列，前两个带未读角标）+ 下方直接是私信会话列表（**去掉**现有的"私信"分隔线标题和 TabRow）。点图标 → emit 对应的 `openXxxFeedRequests` → push 到子页面；子页面顶部栏「← 回复与@/收到喜欢/新增粉丝」，返回键回消息中心页。

**关键设计约束（吸取上次教训）**：三个子页面的 `onInit` 只能接"纯加载"函数（`loadReplyFeed()` / `loadLikeFeed()` / `loadFollowerFeed()`），不能接同时"发导航信号"的入口函数——三个图标各自的点击回调（`openReplyFeed()` 等）和子页面自己的 `onInit` 必须是两个不同的函数，避免重蹈 v1 的导航死循环。

### 2.2 数据加载：懒加载（方案 B）

- 进 `MessageCenterScreen`：只拉 ① 未读聚合（结构化，见 2.3）② 私信会话列表（`retrySessionList()`，复用现有 `messageHub()` 里 `sessions` 那一块的调用，或者直接单独调 `api.getSessions()`）。**不再**一次性拉 reply/like/follower 三块。
- 进任意一个子页面：该页面自己触发对应端点，独立的 `RemoteListState`，独立的 loading/error/重试，互不影响。
- 子页面列表**拉取成功后**，顺带刷新一次未读聚合（`refreshUnreadSummary()`，best-effort，失败不提示）——B 站 `msgfeed` 系列接口是"查看即服务端标记已读"，不需要显式 ack 端点，这样退回消息中心/动态页时角标能正确消掉。这是把 v1 §5.2"进入互动消息列表时标记已读"的规则从"进消息中心"平移到"进各自子页面"，语义不变。
- 私信会话列表沿用 v1 已有的"进 `PrivateChatScreen` 后显式调 `markSessionRead`"逻辑，不受本次改动影响。

### 2.3 未读角标数据源

`BiliRepository.messageUnread(): Int` 改成返回结构化类型：

```kotlin
data class MessageUnreadSummary(
    val reply: Int = 0,
    val at: Int = 0,
    val like: Int = 0,
    val sessionUnread: Int = 0,
) {
    val total: Int get() = reply + at + like + sessionUnread
    /** "回复与@"图标角标：两类通知合并显示一个数字。 */
    val replyAtBadge: Int get() = reply + at
}
```

内部实现不变（仍是 `api.getMsgfeedUnread()` + `api.getSessionUnread()` 两次调用，只是不在内部就地求和，把结构体原样交给调用方），**不增加新请求**。

- 动态页铃铛角标：用 `.total`（不变）。
- 消息中心"回复与@"图标角标：`.replyAtBadge`。
- 消息中心"收到喜欢"图标角标：`.like`。
- "新增粉丝"图标**不带角标**——B 站"新增粉丝"没有"已读/未读"概念，`x/msgfeed/unread` 也不提供这个数字，真实 App 该图标本身也不带数字角标。

`AppViewModel.unreadCount: StateFlow<Int>` 相应改名/改型为 `unreadSummary: StateFlow<MessageUnreadSummary>`；`DynamicScreen` 的 `unreadCount: Int` 参数改传 `unreadSummary.total`。

### 2.4 DTO 修正（`core/network/dto/MessageDto.kt`）

对照 `ref/bilipai/.../MessageResponse.kt` 的真实结构重写：

```kotlin
@Serializable
data class MsgfeedUser(
    @SerialName("mid") val mid: Long = 0,
    @SerialName("nickname") val name: String = "",   // 真实字段名 nickname，本地属性名保持 name 减少下游改动
    @SerialName("avatar") val face: String = "",      // 真实字段名 avatar
)

@Serializable
data class MsgfeedCursor(
    @SerialName("id") val id: Long = 0,
    @SerialName("time") val time: Long = 0,
    @SerialName("is_end") val isEnd: Boolean = false,
)

@Serializable
data class ReplyAtContent(
    @SerialName("root_id") val rootId: Long = 0,
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("root_reply_content") val rootReplyContent: String = "",
    @SerialName("target_reply_content") val targetReplyContent: String = "",
)

@Serializable
data class ReplyAtItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("user") val user: MsgfeedUser? = null,
    @SerialName("item") val item: ReplyAtContent? = null,
    @SerialName("reply_time") val replyTime: Long = 0,
) {
    /** 优先取楼中楼回复内容，取不到时退回一级回复内容（对齐参考实现 firstNonBlank 逻辑）。 */
    val content: String get() =
        item?.targetReplyContent?.takeIf { it.isNotBlank() } ?: item?.rootReplyContent.orEmpty()
    val businessId: Int get() = item?.businessId ?: 0
    val sourceId: Long get() = item?.sourceId ?: 0
    val sourceContent: String get() = item?.sourceContent.orEmpty()
    val rootId: Long get() = item?.rootId ?: 0
}

@Serializable
data class ReplyFeedData(
    @SerialName("items") val items: List<ReplyAtItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
) {
    val hasMore: Boolean get() = cursor?.isEnd == false
}

@Serializable
data class LikeContent(
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
    @SerialName("title") val title: String = "",
)

@Serializable
data class LikeItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("users") val users: List<MsgfeedUser> = emptyList(),   // 复数：可能多人点赞合并展示
    @SerialName("item") val item: LikeContent? = null,
    @SerialName("like_time") val likeTime: Long = 0,
) {
    val firstUser: MsgfeedUser? get() = users.firstOrNull()
    val title: String get() = item?.title.orEmpty()
    val sourceId: Long get() = item?.sourceId ?: 0
    val businessId: Int get() = item?.businessId ?: 0
}

@Serializable
data class LikeFeedBucket(
    @SerialName("items") val items: List<LikeItem> = emptyList(),
    @SerialName("cursor") val cursor: MsgfeedCursor? = null,
)

@Serializable
data class LikeFeedData(
    @SerialName("latest") val latest: LikeFeedBucket? = null,
    // 真实响应还有一个 "total" 桶（历史累计），本期不需要，交给
    // Json { ignoreUnknownKeys = true }（BiliNetwork 已配置）自动丢弃，不声明字段。
) {
    val items: List<LikeItem> get() = latest?.items.orEmpty()
    val hasMore: Boolean get() = latest?.cursor?.isEnd == false
}
```

`FollowerEntry`/`FollowersData`（`x/relation/followers`）**不改动**——这一族关系类接口（`x/relation/followings`、`x/relation/followers`）历史上都是扁平结构，项目里已有的 `FollowingData`/`FollowingUp`（`DynamicDto.kt:167`）就是同款扁平结构且已跑通，可以类比确认现有 `FollowerEntry` 结构大概率正确。**仍需真机验证**这一条，不因为"大概率对"就跳过 §4 的真人测试清单。

`RemoteListState` 消费方（`AppViewModel`/`MessageCenterScreen` 及新增的三个子页面）里所有 `item.content`/`item.businessId`/`item.sourceId` 的读取点不变——因为改成了计算属性（`val content: String get() = ...`），调用方语法不用改，只是取值方式从"直接字段"变成"派生自嵌套 item"。

### 2.5 `AppViewModel` 状态改动

移除 `MessageHubState`（连带其卡死的顶层 `isLoading`，问题随结构拆分自动消失），拆成：

```kotlin
val privateSessions: StateFlow<RemoteListState<Session>>   // 消息中心主体
val replyFeed: StateFlow<RemoteListState<ReplyAtItem>>       // 回复与@ 子页面
val likeFeed: StateFlow<RemoteListState<LikeItem>>           // 收到喜欢 子页面
val followerFeed: StateFlow<RemoteListState<FollowerEntry>>  // 新增粉丝 子页面
val unreadSummary: StateFlow<MessageUnreadSummary>
```

每个都是独立的 `loadXxx()`（`onInit`/`onRetry` 共用同一个函数，和这次修复导航死循环用的模式一致）+ 独立的 `openXxxFeed()`（只 emit 导航信号，不做加载）。

`BiliRepository.messageHub()`（并发拉 4 块）不再被 `AppViewModel` 调用，予以删除；替换成 4 个独立的单块拉取方法（`replyFeed()`/`likeFeed()`/`followers()`/`sessions()`，内部直接是对应 `api.getXxx()` 的简单包装，不需要 `safeCall` 局部隔离——反正现在每块本来就是独立请求，失败只影响自己）。

---

## 3. UI 改动

### 3.1 `MessageCenterScreen.kt`（重写）

```
┌──────────────────────────────────────────┐
│  消息                              关闭   │
├──────────────────────────────────────────┤
│  [💬]        [👍]        [👤]            │  ← 3 个 IconButton，前两个 BadgedBox
│ 回复与@     收到喜欢     新增粉丝         │
├──────────────────────────────────────────┤
│ [头像] 用户B                             │  ← 私信会话列表（PullToRefreshBox）
│        最后一条消息…        1 天前        │     直接是页面主体，不再有"私信"标题
│  …                                       │
└──────────────────────────────────────────┘
```

未登录态仍复用 `LoginPanel`（不变）。

### 3.2 三个新子页面

`ReplyAtScreen.kt` / `LikeScreen.kt` / `FollowerScreen.kt`（三个独立文件，跟随项目"模板 Screen 复制修改"的既有惯例，不引入跨这三者共享的新抽象——`MessageCenterScreen.kt` 里原有的 `MessageItemRow`/loading-error-empty 分支渲染代码按 tab 拆开，分别落到三个文件里）：

```
┌──────────────────────────────────────────┐
│ ← 回复与@                                 │
├──────────────────────────────────────────┤
│ [头像] 用户A                             │  ← PullToRefreshBox + LazyColumn
│        「视频标题」 评论：「写得真好」     │     4 态：loading/error+重试/empty/list
│        3 小时前                           │
└──────────────────────────────────────────┘
```

- `ReplyAtScreen`：长按条目弹出回复输入 `ModalBottomSheet`（原样从 `MessageCenterScreen.kt` 搬过来，交互不变）；点击条目跳转对应视频。
- `LikeScreen`：点击条目跳转对应视频；用户头像/昵称取 `item.firstUser`。
- `FollowerScreen`：点击条目跳转对应用户空间。

---

## 4. 测试

- `MessageDtoParseTest.kt` 的 `回复与@ 列表解析`/`收到喜欢列表解析` 两个用例改用新的嵌套 JSON fixture（手写，标注参考 `ref/bilipai/.../MessageResponse.kt` 的真实结构），断言从嵌套 `item` 里取值。
- 新增一个 `MessageUnreadSummary` 计算属性的单测（`replyAtBadge = reply + at`、`total` 求和）。
- 真机测试清单（v1 §7.3）追加：
  - 三个图标点击后 push 到独立页面，返回键回消息中心；
  - 回复与@/收到喜欢图标角标数字正确显示/点开后消失，新增粉丝图标确认没有角标；
  - 回复与@ 页面内容正确显示（评论正文非空、点击能跳到正确视频）；
  - 收到喜欢页面用户名/头像正确显示（验证 `users` 数组取首个是否符合预期，多人点赞场景如有需要另行确认展示逻辑）；
  - 新增粉丝页面数据结构确认（§2.4 提到的"大概率正确但需验证"）。

---

## 5. 影响面

| 文件 | 改动类型 |
|---|---|
| `core/network/dto/MessageDto.kt` | 修改：`MsgfeedUser`/`ReplyAtItem`/`LikeItem`/`ReplyFeedData`/`LikeFeedData` 结构重写，新增 `ReplyAtContent`/`LikeContent`/`LikeFeedBucket`/`MsgfeedCursor` |
| `core/network/BiliRepository.kt` | 修改：删除 `messageHub()`，`messageUnread()` 返回类型改为 `MessageUnreadSummary`，新增 4 个单块拉取方法 |
| `app/.../AppViewModel.kt` | 修改：删除 `MessageHubState`/`openMessages()`/`loadMessageHub()`/`retryMessageHub()`，新增 4 组独立状态 + load/open 函数对，`unreadCount`→`unreadSummary` |
| `app/.../ui/AppShell.kt` | 新增：3 个 `Destination` + 3 个 `LaunchedEffect` + 3 个 `composable{}` |
| `app/.../ui/MessageCenterScreen.kt` | 重写：去掉 TabRow/MessageHubState，改成图标行 + 私信列表 |
| `app/.../ui/ReplyAtScreen.kt`、`LikeScreen.kt`、`FollowerScreen.kt` | 新增 |
| `app/.../ui/DynamicScreen.kt` | 修改：`unreadCount: Int` 参数改读 `unreadSummary.total`（1 行） |
| `app/.../MainActivity.kt` | 修改：接线 3 个新 Screen + 更新 messagesContent 的回调签名 |
| `core/network/src/test/.../MessageDtoParseTest.kt` | 修改：两个 fixture 改嵌套结构 |

不新增依赖，不涉及 `core/player`、`design-system`。

---

## 6. 范围之外（YAGNI，沿用 v1 §11 + 本次新增）

- `LikeFeedData` 的 `total` 桶（历史累计点赞）不解析，只用 `latest`。
- 收到喜欢多人点赞的完整用户列表展示（本期只取 `users.first()`，不做"用户A、用户B等3人赞了你"这种聚合文案）。
- 新增粉丝角标（无数据源，明确不做）。
