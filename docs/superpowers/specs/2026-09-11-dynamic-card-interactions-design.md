# 动态卡片互动按钮（转发/评论/点赞）设计

日期：2026-09-11
范围：`app` 模块的动态流卡片（`DynamicScreen.kt` / `UpSpaceScreen.kt`）+ `core:network` 的评论/点赞/转发相关 API。

## 背景

动态卡片底部的"转发 N / 评论 N / 点赞 N"数字已经能正确显示（读取 `DynamicStatModule`），但点击行为与预期不符：

- **点赞**：`toggleDynamicLike` 本身实现完整（乐观更新 + 失败回滚 + 500ms 防抖 + 未登录拦截），但两处调用点把"目标状态"传反了，导致取消点赞不生效。
- **转发**：点击后打开的是"转发到自己动态"弹窗，背后的 `forwardDynamic` 写接口 body 结构是未经真机验证的猜测实现（代码注释自己标注"⚠️ 未验证"），大概率打不通。
- **评论**：点击后打开的 `DynamicCommentDialog` 只是发评论用的占位输入框，注释明确写着"评论列表（占位，未实装）"——没有任何评论浏览功能。

## 一、点赞：修复方向反转的 bug

`AppViewModel.toggleDynamicLike(idStr, like: Boolean)` 的 `like` 参数语义是"点击后期望达到的目标状态"。但两处调用点：

```kotlin
// DynamicScreen.kt:183，UpSpaceScreen.kt:390
onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status == true) }
```

直接把"当前是否已点赞"当作目标状态传入，导致已点赞状态下点击不会取消。

**修复**：两处改为 `entry.stat?.like?.status != true`（取当前状态的反）。不改 `toggleDynamicLike` 本身。

## 二、转发：替换为复制链接，删除未验证的写接口链路

放弃"转发到自己动态"功能（未验证、有风控风险、且不符合常见"转发图标=复制链接/分享"的预期），改为纯本地复制链接，零网络请求。

**删除**：
- `AppViewModel.kt`：`RepostDialogState`、`openRepostDialog`、`updateRepostContent`、`dismissRepostDialog`、`submitRepost`
- `BiliRepository.kt`：`forwardDynamic`、`buildForwardBody`
- `RepostDialog` Composable 及其在 `MainActivity.kt` 里的弹窗接入代码

**新增**：
- `AppViewModel.dynamicUrl(idStr: String) = "https://t.bilibili.com/$idStr"`（仿照已有的 `videoUrl`）

**改动**：
- `DynamicScreen.kt` / `UpSpaceScreen.kt` 的 `onRepostClick` 回调直接触发 `copyToClipboard(viewModel.dynamicUrl(entry.idStr))` + `emitMessage("链接已复制")`，走既有 Toast 管道。

## 三、评论：新建评论浏览页

### 承载方式

不新增 NavHost route。把现有 `DynamicCommentDialog`（`ModalBottomSheet`）从"占位输入框"升级为真正的评论列表 Sheet，参考了 `ref/bilipai` 的 `DynamicCommentSheet` 但大幅简化（去掉置顶/删除/举报/miuix 定制 UI），只做：列表 + 楼中楼展开 + 评论点赞 + 回复某条评论。

### API 层改动（`core/network`）

1. **`BiliRepository.comments`**：签名从 `comments(aid: Long, page, sort)`（硬编码 `type="1"`）改为 `comments(oid: Long, type: Int, page: Int = 1, sort: Int = 1)`，让动态评论（`type=11/17`）和视频评论（`type=1`）共用一套读接口。当前代码里没有任何调用点，改签名不破坏现有功能。
2. **`BiliRepository.replyAction`**：签名从 `replyAction(aid: Long, rpid: Long, like: Boolean)`（硬编码 `type="1"`）改为 `replyAction(oid: Long, type: Int, rpid: Long, like: Boolean)`。
3. **新增 `BiliRepository.subReplies(oid: Long, type: Int, root: Long, page: Int): Result<CommentPage>`**：包装已存在但无人调用的 `BiliApi.replyReply`，用于"查看全部回复"翻页加载楼中楼。
4. **`BiliRepository.addDynamicReply`**：签名从 `addDynamicReply(oid: Long, type: Int, message: String)` 扩展为 `addDynamicReply(oid: Long, type: Int, message: String, root: Long = 0, parent: Long = 0)`，对应 B 站 `x/v2/reply/add` 的 `root`/`parent` 字段（0 = 发表新的一级评论；非 0 = 回复某条评论/楼中楼）。

### DTO 改动（`ReplyDto.kt`）

`ReplyItem` 补充三个字段（B 站评论接口的标准字段，非猜测，但仍需真机验证）：

```kotlin
data class ReplyItem(
    // ...已有字段（rpid/oid/mid/ctime/like/content/member）保持不变...
    val rcount: Int = 0,                    // 楼中楼总数
    val replies: List<ReplyItem>? = null,   // 服务端随主评论内嵌返回的前几条楼中楼
    val action: Int = 0,                    // 当前用户是否已点赞该评论（1=已赞）
)
```

### ViewModel 层改动（`AppViewModel.kt`）

`CommentDialogState` 从"单条草稿"升级为承载列表+分页+楼中楼+回复目标的完整状态：

```kotlin
data class CommentDialogState(
    val idStr: String,
    val oid: Long,
    val type: Int,
    val content: String = "",
    val isPosting: Boolean = false,
    val comments: RemoteListState<ReplyItem> = RemoteListState.empty(),
    val sort: Int = 1,                              // 1=热度 0=时间
    val expandedSubReplies: Map<Long, RemoteListState<ReplyItem>> = emptyMap(), // key=root rpid
    val replyTarget: ReplyItem? = null,              // 非 null=正在回复某条评论
)
```

复用已有的 `RemoteListState<T>`（`app/.../data/RemoteListState.kt`），与 `DynamicScreen.kt` 的动态流分页保持同一套形状。

新增方法（均遵循项目里"未登录拦截 → 乐观更新 → 失败回滚 → `emitMessage` 反馈"的既有模式，参考 `toggleDynamicLike`）：

- `openCommentDialog(entry)`：打开后立即触发首页评论加载（原逻辑保留，只是不再是"发完才知道内容"）
- `loadComments(reset: Boolean)`：首次加载 / 切换排序时 reset=true
- `loadMoreComments()`：翻页，供列表末尾 sentinel item 触发
- `setCommentSort(sort: Int)`：切换热度/时间，reset 列表重新加载
- `toggleCommentLike(rpid: Long, like: Boolean)`：调 `replyAction`，乐观更新 `ReplyItem.like`/`action`，失败回滚
- `startReplyTo(reply: ReplyItem)` / `clearReplyTarget()`：设置/清除回复目标
- `expandSubReplies(rootRpid: Long)` / `loadMoreSubReplies(rootRpid: Long)`：加载楼中楼，写入 `expandedSubReplies[rootRpid]`
- `submitComment()`：改造为同时支持"发新评论"（`replyTarget == null`）和"回复某条评论"（`root`/`parent` 取自 `replyTarget`），提交成功后清空 `replyTarget` 和输入框，但不再关闭整个 Sheet（列表仍打开，插入或刷新对应位置）

### UI 改动（`DynamicCommentDialog.kt`）

- 顶部：标题 + 评论总数 + 热度/时间排序 Tab + 关闭按钮
- 中间：`LazyColumn` 渐染评论列表：
  - 每条：头像/昵称/时间/内容/点赞图标+数（点击调 `toggleCommentLike`）/"回复"文字按钮（点击调 `startReplyTo`）
  - 若 `rcount > 0`：内嵌渲染 `replies` 预览 + "查看全部回复(N)" 按钮，点击后调 `expandSubReplies`，替换预览为完整分页列表
  - 列表末尾 sentinel item + `LaunchedEffect` 触发 `loadMoreComments()`（复用 `DynamicScreen.kt` 已有的 `feed.hasMore` footer 模式，保持风格一致，不引入 BiliPai 式的滚动位置探测）
  - 空态 / 加载态 / 错误态复用 `RemoteListState.errorMessage` 的既有展示约定
- 底部：输入框 + 发送按钮；若 `replyTarget != null` 显示"回复 @xxx"提示条 + 取消按钮

### 登录与风控

- 点赞评论 / 发评论 / 回复评论：进入方法体第一件事检查 `session.value.isLoggedIn`，未登录 `emitMessage("请先登录后再…")` 并 return，不发请求（沿用 `openCommentDialog`/`toggleDynamicLike` 现有模式）。
- 所有写操作（点赞评论、发评论、回复评论）继续走 `BiliRepository` 内的 `writeRequest`/`runWriteRequest` 统一管道（CSRF 注入、`-101` 刷新重试一次、`-352` 冷却拦截不重试），不新增绕过这条管道的调用路径。
- 评论列表读取沿用 `comments()` 已有的 WBI 主链路 + 降级兼容链路。

## 四、涉及文件清单

| 文件 | 改动类型 |
|---|---|
| `core/network/.../BiliApi.kt` | 无需新增（复用已有的 `replyMainWbi`/`replyMain`/`replyReply`/`replyAction`/`addReply`） |
| `core/network/.../BiliRepository.kt` | 改 `comments`/`replyAction` 签名；扩展 `addDynamicReply`；新增 `subReplies`；删除 `forwardDynamic`/`buildForwardBody` |
| `core/network/.../dto/ReplyDto.kt` | `ReplyItem` 新增 `rcount`/`replies`/`action` 字段 |
| `app/.../AppViewModel.kt` | 修点赞取反 bug；新增 `dynamicUrl`；删除转发弹窗状态/方法；`CommentDialogState` 升级；新增评论列表/分页/楼中楼/回复相关方法 |
| `app/.../ui/components/DynamicCommentDialog.kt` | 从占位输入框升级为列表 Sheet |
| `app/.../ui/DynamicScreen.kt` | 点赞取反修复；`onRepostClick` 改接复制链接 |
| `app/.../ui/UpSpaceScreen.kt` | 同上 |
| `app/.../MainActivity.kt` | 移除转发弹窗接入 |

## 五、风险与需要人工真机验证的点（CLAUDE.md 安全规则：禁止自动化测试打真实服务器）

- `ReplyItem` 新增的 `rcount`/`replies`/`action` 字段是否与 B 站当前接口返回结构一致，需人工登录真机验证一次评论列表加载。
- `comments()`/`subReplies()` 用于动态时的 `oid`/`type`（11=图文 DRAW，17=其他）参数是否被 `x/v2/reply/(wbi/)main` 和 `x/v2/reply/reply` 接受，此前只有"发评论"（`addDynamicReply`）验证过这套 oid/type 分流，读接口未验证过。
- `addDynamicReply` 新增的 `root`/`parent` 参数拼装是否与服务端预期一致，需人工验证"回复某条评论"实际生效（而不是又发了一条一级评论）。
- 复制链接 / 点赞取反修复本身零网络结构变更，无需额外验证。

## 六、不做的事（明确排除的范围）

- 不做评论删除 / 举报 / 置顶（这些需要作者权限判断，且用户选定的范围未包含）。
- 不新增 NavHost route，不做独立全屏评论页。
- 不引入 BiliPai 的滚动位置自动探测分页，统一用项目已有的 footer sentinel 模式。
