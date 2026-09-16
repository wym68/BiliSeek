# BiliSeek「消息中心」功能 — 设计文档

> **版本**: v1.0
> **日期**: 2026-09-16
> **状态**: 已确认

---

## 1. 目标

为 BiliSeek 添加**消息中心**功能。登录账号后，用户可：

1. 在**动态页**顶部「全部 / 仅视频」chip 行右侧看到一个消息入口图标（含未读小红点），点击进入消息中心。
2. 消息中心上半部是三个 Tab：
   - **回复与@**：他人对用户作品的回复 + @ 用户的提及。点击条目可在站内**直接写文字回复评论**（弹底部输入对话框）；点击视频/动态正文跳转到对应视频详情。
   - **收到喜欢**：他人给用户作品的点赞。点击条目跳转到对应视频。
   - **新增粉丝**：新增的关注。点击条目跳转到该用户空间。
3. 消息中心下半部是**私信会话列表**（多列）：点击进入聊天详情，可**向上滚动加载更早消息**，底部可**发送文字消息**。

不引入本地 Room 缓存，所有数据按需拉取；不增加新依赖。

---

## 2. 现状与约束

### 2.1 项目结构

- 单 Android app（`app/` + `core/network/` + `core/player/` + `design-system/`）
- UI：Jetpack Compose + Material3 + `navigation-compose`，**无 DI**，手工装配
- 网络：Retrofit 3 + OkHttp 5 + kotlinx.serialization
- 存储：Room + DataStore Preferences

### 2.2 已有可复用通道

| 能力 | 现有实现 |
|---|---|
| 登录态 Cookie 注入 | `BiliCookieJar`（`core/network/.../internal/Interceptors.kt`）：每个请求自动带 `SESSDATA`/`bili_jct`/`DedeUserID`+风控三件套 |
| 风控凭据 & WBI 密钥 | `AuthGate.awaitReady()`（`core/network/.../AuthGate.kt`）：buvid3/4 + bili_ticket + WBI imgKey/subKey 已就绪 |
| WBI 签名 GET | `BiliRepository.wbiRequest(params) { api.xxx(it) }`（`BiliRepository.kt`） |
| CSRF 写请求 | `BiliRepository.writeRequest(payload) { fields, referer -> api.xxx(f, r) }`（`BiliRepository.kt:630`）— 自动注入 `csrf=bili_jct` 并触发 cookie 刷新重试 |
| 风控/限速 | `RiskCooldown`（-352 全局退避）+ `RequestThrottle`（SEARCH/FEED/WRITE 三桶）+ `RetryPolicy`（resolveRetryAction 分流 -352/-412） |
| 跨域 `@Url` 调用 | 已有先例：passport 登录接口在 `BiliApi.kt` 中用 `@Url` 写完整 URL |
| 登录态远程列表通用状态 | `RemoteListState<T>`（`app/src/main/java/com/biliseek/app/data/RemoteListState.kt`），未登录复用 `LoginPanel` |
| 模板 Screen | `WatchLaterScreen.kt`、`FollowingListScreen.kt`、`UpSpaceScreen.kt`、`DynamicScreen.kt` |
| push 路由 | `AppViewModel.openXxxRequests: MutableSharedFlow` + `AppShell` 的 `LaunchedEffect` 收集后 `navController.navigate`（现成模板：`openUpSpaceRequests`、`openFollowingListRequests`） |

### 2.3 与现有项目代码风格的一致点

- DTO 全部用 `BiliResponse<T>(code, message, ttl, data)` 外壳（`core/network/.../dto/BiliDto.kt`）
- `@SerialName` / `@Serializable` 命名走 snake_case → camelCase
- 写接口走 `writeRequest`，读接口优先 `wbiRequest`（私信域接口在 B 站实测**不要求 WBI**，参考 bilipai 的 `MessageApi` 实现：只带 Cookie + 必要的 query 字段即可）
- UI 渲染走 `RemoteListState` 四态（loading/error+retry/empty/list）+ `PullToRefreshBox`
- 不引入 Hilt/Koin，复用现有 `BiliNetwork.get(context)` 装配
- 严禁主动 POST 表单时不走 `writeRequest`（防 -111）

### 2.4 关键参考实现

`ref/bilipai/app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt:2400-2640` 的 `MessageApi` 是已上线的端到端实现。本 spec 的端点路径、字段命名、query 字段、csrf 用法均以它为准（仅去掉本项目不需要的项：set_top、remove_session、intercept、batch_update_dustbin_ack、batch_rm_dustbin、msg_dnd、push_ss）。

---

## 3. 端点清单（最小必要集）

### 3.1 互动消息（域：`https://api.bilibili.com`）

| 用途 | 方法 | 完整 URL | 备注 |
|---|---|---|---|
| 互动未读 | GET | `https://api.bilibili.com/x/msgfeed/unread` | 用于入口小红点 |
| 回复与@ | GET | `https://api.bilibili.com/x/msgfeed/reply` | 回复+@合一 tab（参考 bilipai `getReplyFeed`） |
| 收到喜欢 | GET | `https://api.bilibili.com/x/msgfeed/like` | |
| 新增粉丝 | GET | `https://api.bilibili.com/x/relation/followings/new`（**待真人验证**：标准 `x/relation/followings` 不区分"新增"，需选另一端点；详见 §3.5） | |
| 删除互动项 | POST | `https://api.bilibili.com/x/msgfeed/del` | 不在本期 |
| 评论回复 | POST | `https://api.bilibili.com/x/v2/reply/add` | 回复与@ 站内回复用 |

### 3.2 私信（域：`https://api.vc.bilibili.com`）

| 用途 | 方法 | 完整 URL | 备注 |
|---|---|---|---|
| 私信未读数 | GET | `https://api.vc.bilibili.com/session_svr/v1/session_svr/single_unread` | 用于入口小红点（与互动未读求和） |
| 会话列表 | GET | `https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions` | |
| 历史消息 | GET | `https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs` | `begin_seqno` 游标分页 |
| 发送私信 | POST | `https://api.vc.bilibili.com/web_im/v1/web_im/send_msg` | 走 `writeRequest` |
| 标记已读 | POST | `https://api.vc.bilibili.com/session_svr/v1/session_svr/update_ack` | 走 `writeRequest` |

### 3.3 系统通知（域：`https://message.bilibili.com`）

不纳入本期（截图里没有这个 tab）。后续要加时只需新增一个 Tab 与一个端点：`https://message.bilibili.com/x/sys-msg/query_notify_list`。

### 3.4 字段与参数（最小集）

**`get_sessions`** query: `session_type=4&group_fold=1&unfollow_fold=0&sort_rule=2&size=20&build=0&mobi_app=web&web_location=333.999&pn=1&end_ts=0`

**`fetch_session_msgs`** query: `talker_id&session_type=1&size=20&begin_seqno&end_seqno=0&mobi_app=web`

**`send_msg`** body：BiliApi 方法 `@FormUrlEncoded` `@POST` + `@Field` 全字段显式声明。**`csrf` 由 `writeRequest` 自动注入**；`csrf_token` **必须显式声明**为 `@Field("csrf_token")`（B 站该端点同时校验两个，参考 `ref/bilipai/ApiClient.kt:2547`）。调用点从 `session.biliJct` 读取 `csrf_token`（**不参与** `writeRequest` 的 cookie 刷新路径）。
- `msg[sender_uid]`: Long（当前登录 mid）
- `msg[receiver_id]`: Long（对方 mid）
- `msg[receiver_type]`: Int=1
- `msg[msg_type]`: Int=1（纯文本）
- `msg[content]`: String（JSON：`{"content":"hello"}`，参考 B 站 Web 客户端）
- `msg[timestamp]`: Long（秒）
- `msg[dev_id]`: String（风控 devId，从 `BiliCredentials` 取，缺失时 fallback UUID）
- `msg[new_face_version]`: Int=1

**`update_ack`** body：`talker_id` + `session_type` + `ack_seqno` + `csrf_token`（显式 `@Field`，同 `send_msg`）；`csrf` 由 `writeRequest` 注入。

**`x/v2/reply/add`** body：复用 `BiliApi.addReply(@FieldMap)`（`BiliApi.kt:389`，已存在）。`csrf` 由 `writeRequest` 注入；**此接口不需要 `csrf_token`**（与 `send_msg`/`update_ack` 的反伪造方案不同）。

### 3.5 「新增粉丝」端点的歧义与处置

B 站没有公开"新增粉丝"专用接口。可行路径：

1. **方案 X**：用 `x/relation/followings?order=desc` 拉关注列表按关注时间倒序，取前 N 条作为"最近新增粉丝"。缺点：数据本身是关注列表，不是粉丝列表，方向相反。
2. **方案 Y**：用 `x/relation/followers?order=desc` 拉**粉丝列表**按关注时间倒序，作为"新增粉丝" tab 内容。优点：方向对，是真正的粉丝。
3. **方案 Z**：本期不展示"新增粉丝"具体用户名单，只在该 tab 里展示「粉丝 +N」摘要文本。

**决策（§4 设计会确认）**：采用**方案 Y**（`x/relation/followers`）。字段名沿用 `Follower`（不是新增独立类型）；tab 标题保持「新增粉丝」以匹配用户截图。**真机验证阶段由测试人员确认排序参数能否稳定反映"最近关注的人"。**

---

## 4. 架构与模块

### 4.1 `core/network` 改动

| 文件 | 改动 |
|---|---|
| `dto/MessageDto.kt` | **新增**。最小字段集 DTO：`Session`、`Message`、`Follower`、`ReplyAtItem`、`LikeItem`、`Unread`、`SendMessageResult`、`MessageHub`。 |
| `BiliApi.kt` | **新增方法**（10 个，全部 `@Url` 全 URL；`@GET`/`@POST` 注解照抄 §3）：`getMsgfeedUnread`、`getMsgfeedReply`、`getMsgfeedLike`、`getFollowers`（新增 `@GET("x/relation/followers")`）、`getSessionUnread`、`getSessions`、`fetchSessionMsgs`、`postSendMsg`（POST `@FormUrlEncoded`，所有 `msg[*]` + `csrf_token` 显式 `@Field`；`csrf` 由 `writeRequest` 注入）、`postUpdateAck`（同 `postSendMsg`）、`postReplyAdd`（**复用现有** `addReply(@FieldMap)`，不需要 `csrf_token`） |
| `BiliRepository.kt` | **新增方法**：`messageHub(): MessageHub`（并发 4 块：reply + like + followers + sessions）、`loadMoreSessionMessages(talkerId, beginSeqno)`、`sendPrivateMessage(...)`、`markSessionRead(talkerId, ackSeqno)`、`postCommentReply(oid, type, root, parent, content)`、`messageUnread(): Int` |
| `internal/RequestThrottle.kt` | **新增桶** `MESSAGE`（8 req / 10 s）。`messageHub()`/`messageUnread()` 进此桶；`sendPrivateMessage`/`postCommentReply` 复用 `WRITE` 桶（已是写操作） |
| `internal/WriteRequest.kt` | **不修改**。仅注入 `csrf` 字段。**`csrf_token` 必须由调用方在 payload 里显式写入**（`send_msg`/`update_ack` 需要；`reply/add` 不需要）。 |

### 4.2 `app` 改动

| 文件 | 改动 |
|---|---|
| `data/RemoteListState.kt` | **新增** 4 个 `TrackListId`：`MESSAGE_REPLY`、`MESSAGE_LIKE`、`MESSAGE_FOLLOWER`、`PRIVATE_SESSION` |
| `AppViewModel.kt` | **新增**状态：`MessageHubState(reply, like, follower, sessions)`（一个 state 含 4 个 `RemoteListState`）、`PrivateChatState(talkerId, list, draft, sending, canLoadMore)`、`unreadCount: StateFlow<Int>`；**新增方法**：`openMessages()`、`openPrivateChat(talkerId, peerInfo)`、`loadMessageHub()`、`sendChatMessage(...)`、`submitCommentReply(...)`、`refreshMessageUnread()`；**新增信号**：`openMessagesRequests: MutableSharedFlow<Unit>`、`openPrivateChatRequests: SharedFlow<PrivateChatNav>`（带 talkerId + peerName + peerFace） |
| `ui/AppShell.kt` | **新增** `Destination.MESSAGES`（push）、`Destination.PRIVATE_CHAT`（push，参数化 `talkerId`/`peerName`/`peerFace`）；新增两个 `LaunchedEffect` 收集上述两个 SharedFlow 信号并 navigate |
| `ui/MessageCenterScreen.kt` | **新增**（仿 `WatchLaterScreen` + 内嵌 TabRow） |
| `ui/PrivateChatScreen.kt` | **新增**（仿已有聊天 UI 风格） |
| `ui/DynamicScreen.kt` | **修改**顶部 chip 行：在 Row 末尾加 `IconButton(Icon=MessageBell)`，带 `Badge`（未读数 > 0 时显示，点击 → `viewModel.openMessages()`） |
| `MainActivity.kt` | **修改**`libraryContent` 等 lambda 模式，新增 `messagesContent` 与 `privateChatContent(talkerId, peerName, peerFace)`；**新增** `ProcessLifecycleOwner.LifecycleEventObserver`（在 `ON_RESUME` 时调 `viewModel.refreshMessageUnread()`） |
| 测试 | **新增** `MessageDtoParseTest`（用 vendored JSON 验证字段映射）；`RequestThrottleMessageBucketTest`（验证 MESSAGE 桶限速） |

### 4.3 数据流

```
User 动态页点 🔔
   └─ AppViewModel.openMessages()
        └─ _openMessagesRequests.emit(Unit)
             └─ AppShell.LaunchedEffect → navController.navigate("messages")

User 进入消息中心页
   └─ MessageCenterScreen.LaunchedEffect → viewModel.loadMessageHub()
        └─ BiliRepository.messageHub()
             ├─ api.getMsgfeedReply()   → RemoteListState<ReplyAtItem>
             ├─ api.getMsgfeedLike()    → RemoteListState<LikeItem>
             ├─ api.getFollowers()      → RemoteListState<Follower>
             ├─ api.getSessions()       → RemoteListState<Session>
             └─ 任意一块失败不影响其他

User 切换 Tab（不重发请求）
   └─ MessageCenterScreen 直接渲染 MessageHubState.reply / like / follower

User 点会话
   └─ AppViewModel.openPrivateChat(talkerId, peerName, peerFace)
        └─ navigate("chat/$talkerId/$peerName/$peerFace")

User 进入聊天页
   └─ PrivateChatScreen.LaunchedEffect → viewModel.openChat(talkerId)
        ├─ BiliRepository.fetchSessionMsgs(talkerId, beginSeqno=0) → MessageHub
        └─ BiliRepository.markSessionRead(talkerId, ackSeqno=maxSeq)

User 上滑
   └─ LazyColumn.detectLoadMore → viewModel.loadMoreMsgs(minSeqno)
        └─ BiliRepository.fetchSessionMsgs(talkerId, beginSeqno=minSeqno-1)

User 点发送
   └─ viewModel.sendChatMessage(content)
        └─ BiliRepository.sendPrivateMessage(...)
             ├─ 成功：本地乐观插入消息，draft 清空
             └─ 失败：toast + draft 保留

User 点 "回复与@" 条目
   └─ 弹 BottomSheet 输入框 → 提交 → viewModel.submitCommentReply(oid, root, parent, content)
        └─ BiliRepository.postCommentReply(...)
             ├─ 成功：toast「已回复」，关闭 sheet
             └─ 失败：toast + 内容保留

未读小红点刷新
   ├─ AppViewModel.init → viewModelScope.launch { refreshMessageUnread() }
   └─ MainActivity 注册 LifecycleObserver → ON_RESUME → refreshMessageUnread()
        └─ BiliRepository.messageUnread(): Int
             ├─ api.getMsgfeedUnread() (api.bilibili.com)
             ├─ api.getSessionUnread() (api.vc.bilibili.com)
             └─ 返回 sum
```

---

## 5. UI 行为

### 5.1 动态页（`DynamicScreen.kt`）

修改顶部筛选行：

```
┌──────────────────────────────────────────┐
│ [全部] [仅视频]                    [🔔❶] │ ← 新增 IconButton + Badge
└──────────────────────────────────────────┘
```

- `IconButton`：`Icons.Filled.Notifications` 或 `Icons.Outlined.Notifications`，点击 → `openMessages()`。
- `Badge`：未读数 > 0 时显示，文本 = min(unread, 99+)。
- 仅在登录态显示（未登录态整行 chip 都不显示，已有的登录态守护）。

### 5.2 消息中心页（`MessageCenterScreen.kt`）

```
┌──────────────────────────────────────────┐
│  消息                          [下拉刷新] │
├──────────────────────────────────────────┤
│ [回复与@]  [收到喜欢]  [新增粉丝]         │ ← TabRow
├──────────────────────────────────────────┤
│ [头像] 用户A                             │
│        「视频标题」 评论：「写得真好」     │
│        3 小时前                           │
│  …                                       │
│  ──────────── 私信 ────────────          │ ← 分隔（弱化文字）
│  [头像] 用户B                            │
│         最后一条消息…        1 天前       │
│  [头像] 用户C                            │
│         最后一条消息…        2 天前       │
└──────────────────────────────────────────┘
```

- 三个 Tab 切换：复用 `MessageHubState` 已有数据，不重发请求。
- `PullToRefreshBox`：下拉触发 `loadMessageHub()` 全量重拉（4 块并发）。
- 每条互动项：列表项底部带一条分隔线，点击 → 跳详情或弹回复框。
- 进入页面：触发一次 `loadMessageHub()`（不依赖远程已读——首次加载全量即可）。
- 进入页面 / Tab 切换 / 点击会话：自动触发对应端点的 `updateAck` 或本地标记，避免下次仍显示未读（**简化**：本期内只对"私信会话列表"和"互动消息列表进入"时通过 API 标记已读，对单个 Tab 切换不做标记；小红点 = 服务端未读 - 本地已标记，避免漏算）。

### 5.3 私信聊天页（`PrivateChatScreen.kt`）

```
┌──────────────────────────────────────────┐
│ ← 用户B (头像)                            │
├──────────────────────────────────────────┤
│         [9:01] Hello                      │
│  [Hi 9:00]                               │
│  [今天去爬山吗 9:05]    [好啊 9:06]       │
│  …(向上滚触发 loadMore)                   │
├──────────────────────────────────────────┤
│ [输入消息…                       ] [➤]   │
└──────────────────────────────────────────┘
```

- `LazyColumn(reverseLayout=true)`：自己消息靠右、对方靠左，按时间倒序（最新在底）。
- 首次进入：`begin_seqno=0` 拉首屏 20 条 → 拿到 `max_seqno` → 调 `markSessionRead(talkerId, ackSeqno=maxSeqno)`。
- 上滑到底部触发 `loadMore`：用 `minSeqno-1` 作为下一次 `begin_seqno`。
- 输入栏：`OutlinedTextField(draft)` + `IconButton(send)`，draft 非空 + 未在发送中 → 可点击。
- 发送：`writeRequest` → 成功后乐观插入到列表尾部（更新本地列表），draft 清空；失败 toast + draft 保留。
- 离开页面：不轮询。

### 5.4 顶部导航与返回

- 入口在动态页，**不新增底部 tab**（按用户确认）。
- 消息中心页顶部导航 `← 返回` 回到动态页。
- 聊天页 `← 返回` 回到消息中心页。

---

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| 未登录（-101 / 缺 SESSDATA） | `BiliRepository.requireLoggedInMid()` 抛 `NotLoggedInException` → UI 显示 `LoginPanel`（复用） |
| 风控（-352） | `RiskCooldown` 全局退避；UI 转 `RemoteListState.Error` 文案「触发风控，请稍后再试」 |
| CSRF（-111） | `writeRequest` 自动 cookie 刷新重试；若仍失败转 `Error` |
| 私信限速 / 超频 | `RequestThrottle.MESSAGE` 桶自动排队 / 拒请求，UI 转「操作太频繁」 |
| 发送失败 | toast 文案「发送失败：{message}」+ draft 保留 |
| 网络超时 / 5xx | 现有 `RetryPolicy` 不重试 GET；UI 转 `Error` 文案 + 重试按钮 |

---

## 7. 测试

### 7.1 单元测试（自机可跑）

- `MessageDtoParseTest`：用 vendored JSON（从 `ref/bilipai/.../response/...` 取样复制到 `app/src/test/resources/message/`）验证 DTO 反序列化。
- `RequestThrottleMessageBucketTest`：注入虚拟时钟，验证 8 req / 10s 限速。
- `SendMessageFieldBuildTest`：验证 `send_msg` 字段嵌套格式 `msg[sender_uid]` / `msg[content]` 正确序列化（与 B 站 Web 端一致）。

### 7.2 集成测试（Mock 服务器）

- 在 `:app` 模块用 `MockWebServer` 拦截私信/互动请求，验证 `BiliRepository.messageHub()` 编排、错误局部化、CSRF 注入。

### 7.3 真人测试（**强制项，按 AGENTS.md 第 2.3 节**）

由测试人员用真实账号手动执行：

| 场景 | 验证点 |
|---|---|
| 扫码登录 | 登录后入口图标出现，未登录时入口隐藏 |
| 未读小红点 | 触发真实未读（让其他账号发一条消息 / 评论）后回到 App，红点显示 |
| 回复与@ tab | 显示真实条目，能成功发送回复评论，对方能收到 |
| 收到喜欢 tab | 显示真实条目，点击跳转到正确视频 |
| 新增粉丝 tab | 显示真实粉丝，点击跳转到正确用户空间 |
| 私信会话列表 | 头像/昵称/最后一条消息/时间正确显示 |
| 私信聊天 | 消息按时间正确排列；上滑加载更早；发送后立即看到自己消息，对方收到 |
| 标记已读 | 进入聊天后未读数 -1 |
| 风控测试 | 短时间内高频请求，确认 -352 被识别且全局退避生效 |
| -111 csrf 测试 | 模拟 cookie 过期，确认 `writeRequest` 自动刷新重试 |

所有上述用例必须由真人执行后才标记为通过。**自动化测试不得替代真人验证。**

---

## 8. 验收标准（Definition of Done）

- [ ] `:core:network:compileDebugKotlin` 通过
- [ ] `:app:compileDebugKotlin` 通过
- [ ] `:app:testDebugUnitTest` 中新增的 `MessageDtoParseTest`、`RequestThrottleMessageBucketTest`、`SendMessageFieldBuildTest` 全部通过
- [ ] lint 无新增 warning
- [ ] 真人测试 7.3 全部用例通过
- [ ] 动态页入口小红点正确显示/隐藏
- [ ] 三个 Tab 切换流畅、不重发请求
- [ ] 私信聊天首屏 + 向上加载更多 + 发送消息闭环
- [ ] 不引入新依赖、不修改 `core/player`、`design-system` 模块

---

## 9. 影响面与风险

| 影响 | 评估 |
|---|---|
| 网络层 | 仅新增 DTO + 10 个 endpoint 方法 + 1 个桶；零侵入 |
| ViewModel | 集中在 `AppViewModel` 末尾，仿 `watchLater` 块；不影响既有功能 |
| UI | 仿 `WatchLaterScreen`；动态页 chip 行加图标，1 个文件小改 |
| 风控 | 新增 `MESSAGE` 桶严格限速；`RiskCooldown`/`RetryPolicy` 复用 |
| 依赖 | 无 |
| 行为兼容 | `BiliNetwork` 单例装配零修改；现有 OkHttp/Retrofit 实例复用 |
| 用户体验风险 | 私信接口对风控敏感，需先在测试账号小流量验证；如触发风控立即收口（不上线） |

---

## 10. 实施步骤（实现阶段会进入 writing-plans 拆任务）

1. `core/network/dto/MessageDto.kt` 新增最小字段集 DTO
2. `core/network/BiliApi.kt` 新增 10 个端点方法（`@Url` 全 URL）
3. `core/network/BiliRepository.kt` 新增 `messageHub` / `loadMoreSessionMessages` / `sendPrivateMessage` / `markSessionRead` / `postCommentReply` / `messageUnread`
4. `core/network/internal/RequestThrottle.kt` 新增 `MESSAGE` 桶（8/10s）
5. `app/.../data/RemoteListState.kt` 新增 4 个 `TrackListId`
6. `app/.../AppViewModel.kt` 新增 `MessageHubState` / `PrivateChatState` / `unreadCount` + 方法 + 两个 SharedFlow
7. `app/.../ui/AppShell.kt` 新增 `Destination.MESSAGES` / `PRIVATE_CHAT` + LaunchedEffect
8. `app/.../ui/MessageCenterScreen.kt` 新增
9. `app/.../ui/PrivateChatScreen.kt` 新增
10. `app/.../ui/DynamicScreen.kt` 顶部 chip 行加 IconButton + Badge
11. `app/.../MainActivity.kt` 接线两个新 Screen + LifecycleObserver 触发未读刷新
12. 单测 + 集成测试 + 真人测试（**强制**）

---

## 11. 范围之外（YAGNI）

- 私信发送图片 / 表情 / 撤回
- 系统通知 tab（`message.bilibili.com`）
- 私信置顶 / 删除 / 屏蔽
- 互动消息删除 / 设置不再提醒
- 未读数本地持久化
- 消息搜索
- 实时推送 / WebSocket
- 群聊（`session_type=2`）

以上待后续版本评估。