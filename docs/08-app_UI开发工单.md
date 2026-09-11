# BiliSeek UI 开发工单

## 背景

- `docs/06-写操作接入-实现工单.md` 与 `docs/07-风控加固-实现工单.md` 的目标主要已落在 `:core:network` 层，BiliRepository 已具备写操作与统一错误分流能力。
- 但当前 `:app` 主要提供搜索、播放、收藏夹/稍后再看、动态等浏览能力，缺少评论列表、评论输入/点赞、视频点赞/投币/收藏/三连等可操作入口，因此无法在真机触发接口进行验收。
- 此外，部分已有页面（播放页、音乐库等）的操作能力与错误提示仍有补齐空间，需要按页面逐个梳理与扩展。
- 本工单聚焦 **前端 UI 层**：为每个需要扩展能力的页面给出目标效果、状态模型、调用的 Repository 方法、错误/登录/风控分支、交互草图，并随讨论逐页追加章节。

## 范围

- 做：按页面逐个讨论并设计 UI 方案，覆盖：视频详情/操作区、评论、播放页操作补齐、音乐库操作补齐、动态页操作、个人中心等。
- 不做：核心网络层（已在 `06`/`07` 工单落地）；新的网络协议；后端相关。
- 约束：严格不访问 B 站服务；验证以离线测试/Mock 为主；真机验收只写给真人执行，Agent 不发起真实请求。

## 设计原则

- UI 只调 `BiliNetwork.get(app).repository`，不直接碰 Retrofit。
- 写操作走 `BiliRepository.writeRequest` 统一入口，错误按 `WriteError` 分流；评论读按现有 `BiliRepository.comments` 流程，含 Phase B 的 `-111` 降级。
- 状态用 `StateFlow` 暴露在 `AppViewModel`，Composable 用 `collectAsState` 订阅；本地瞬时态（输入框、点赞动画）保留在 Composable。
- 任何写操作前检查登录，未登录时引导到登录页或提示。
- 错误展示统一通过 toast/Snackbar/内嵌提示区分：登录失效、网络错误、风控(`-352`)、csrf(`-111`)、未知。
- 重复操作防抖（如点赞按钮的 loading 态），避免快速连发被风控。

## 讨论流程

- 每页按以下结构产出（草图、状态、调用、错误提示、待办清单），由用户逐页确认：
  1. 目标效果
  2. 适用入口与触发动作
  3. 状态模型
  4. 调用的 `BiliRepository` 方法（已存在 / 需新增）
  5. 错误/登录/风控分支与提示
  6. 交互草图（ASCII）
  7. 待开发 / 复用项清单
- 每页讨论满意后再开启下一页。
- 各页面内容在下方「页面设计」章节依次追加。

## 页面设计

> 逐页追加。每页包含：目标效果、入口、状态、调用、错误分支、交互草图、待办清单。

---

## 1. 搜索页（SearchScreen + Search 路由）

> 本节重点：边缘滑动手势的返回行为改造（M11 之后遗留问题：搜索结果页会直接退 App）。

### 1.1 目标效果

- **搜索结果页**（已有关键词且已搜出结果）下，系统边缘滑动手势/虚拟返回键：
  - 若有子页面（多 P 面板、UP 空间页、日历史页等）打开 → **先收子页面**，回到搜索结果页
  - 没有子页面 → **清空搜索框 + 自动聚焦 + 弹起软键盘**（模拟「撤销这次搜索」的感觉）
  - **清空之后再滑一次** → 走 Activity 默认 pop：此时搜索框为空、不自动聚焦、不弹键盘，回到首页状态
- **首页空状态**（无关键词）保持现状：返回手势直接退到桌面
- **长按搜索结果弹出的 `ResultActionSheet`**：`ModalBottomSheet` 自带返回消费，不在此节改造
- 全屏播放、UP 空间页、稍后再看、合集等页面的返回处理沿用既有 `BackHandler`（在 `LibraryScreen.kt` / `MainActivity.kt` 已覆盖）

### 1.2 适用入口与触发动作

- 入口：底部导航 `Destination.SEARCH` 起点（默认打开即搜索页）
- 触发动作：
  - `onKeywordChange("")` 已经在 `AppViewModel.onKeywordChange` 走「清空 + 重置 `SearchUiState`」路径
  - 新增 `onBack()` 回调：`SearchScreen` 内部判断「结果页 / 子页 / 空状态」分别派发
  - 新增 `onFocusSearch()`：清空 keyword 后请求 `LocalFocusManager` + `LocalSoftwareKeyboardController`

### 1.3 状态模型

- `SearchUiState` 不变（`AppViewModel.kt:125`）
- 新增 UI 局部状态 `var pendingBackFocus by remember { mutableStateOf(false) }`：标记「返回手势触发过首次清空」，下一次返回不再自动聚焦
- 新增回调入参（顶层函数 `SearchScreen`）：
  - `onBack: () -> Unit = {}`：由 `MainActivity` 注入「先收子页，否则 onKeywordChange(\"\") + 自动聚焦」
  - `onFocusSearch: () -> Unit = {}`：暴露给「清空 keyword 后自动聚焦并弹键盘」的副作用

### 1.4 调用的 `BiliRepository` 方法

- 本节**不涉及新接口**。所有写操作（暂无）和读操作沿用现有：
  - `BiliRepository.search(keyword, page, order, duration)`（已有）
  - `BiliRepository.searchUsers(keyword, page)`（已有）
  - `BiliRepository.pages(bvid)`（已有，给多 P 用）
  - `BiliRepository.expandShortLink(url)`（已有，给短链/BV 直达用）
- 复用的 VM 方法：`onKeywordChange` / `searchKeyword` / `dismissPages` / `closeUpSpace` / `closeDayHistory`

### 1.5 错误/登录/风控分支

- 保持现有 `SearchError` / `errorMessage` / `ErrorPanel`：
  - `SearchError.Blocked` → 红条提示 + 不给「重试」按钮
  - `SearchError.CredentialsInvalid` → 显示「设备凭据已失效，已重新种植」
  - `SearchError.NotActivated` / `Network` / `Empty` / `Unknown` → 各自文案
- 返回手势改造**不引入新的错误分支**；只是消费 `BackHandler`，没有网络副作用

### 1.6 交互草图（ASCII）

```
            ┌──────────────────────────────┐
            │  BiliSeek                     │
            │  晚上好，xxx                  │   ← 标题栏
            │  ┌──────────────────────┐  ✕  │
            │  │ keyword [  ]         │     │   ← 搜索框（聚焦态）
            │  └──────────────────────┘     │
            │  [视频] [UP主]                 │   ← 模式切换
            │  排序: …  时长: …              │   ← 筛选
            │  ─────────────────────────── │
            │  约 235 个结果 · 第 1/12 页   │
            │  ┌─────┐                       │
            │  │ 封面│ 标题（2 行省略）       │   ← 搜索结果行
            │  └─────┘  UP · 时长 · 播放数   │
            │  ─────────────────────────── │
            │  … (懒加载更多)                │
            └──────────────────────────────┘

返回手势（第 1 次，无子页打开）：
  onKeywordChange("") ─► SearchUiState() ─► 关键字变空
  pendingBackFocus = true
  focusRequester.requestFocus() + 弹键盘
  → 回到「无关键词 / 聚焦」分支：弹出搜索历史参考

返回手势（第 2 次，已清空）：
  pendingBackFocus = false（不再聚焦、不弹键盘）
  Activity 正常退栈
```

子页面打开时的返回栈优先级（同一手势只收一层）：

```
┌──────────────┐
│  UP 空间页    │  ─BackHandler(closeUpSpace)──►  搜索结果页（结果已搜出）
└──────────────┘
┌──────────────┐
│  多 P 面板     │  ─BackHandler(onDismissPages)─►  搜索结果页
└──────────────┘
┌──────────────┐
│  日历史页     │  ─BackHandler(closeDayHistory)►  搜索结果页
└──────────────┘
┌──────────────┐
│ 搜索结果页     │  ─本节新增 BackHandler────────►  清空 + 聚焦 + 弹键盘
└──────────────┘
┌──────────────┐
│ 首页空状态     │  不拦截 ─►  退到桌面
└──────────────┘
```

### 1.7 错误分支在 UI 上的映射

| 状态                       | 提示位置          | 文案                             |
| ------------------------ | ------------- | ------------------------------ |
| 顶部加载下一页失败                | 顶部红条          | 复用现有 `errorMessage`            |
| 整页失败（首屏）                 | 全屏 ErrorPanel | 同上                             |
| 触发 `SearchError.Blocked` | 顶部红条          | 「请求太频繁，B 站暂时拦截了搜索」+ 提示「过几分钟再试」 |
| 返回清空后无历史                 | 聚焦弹层中央        | 现有「暂无搜索历史」                     |

### 1.8 待开发 / 复用项清单

- 复用：
  - `SearchUiState`、`onKeywordChange`、`dismissPages`、`closeUpSpace`、`closeDayHistory`
  - `LocalFocusManager` / `LocalSoftwareKeyboardController`（Compose）
  - 现有 `BackHandler` 模式（`SearchScreen.kt:331`、`MainActivity.kt:219/228/293`）
- 新增 / 改动：
  - `SearchScreen`：
    - 新增 `onBack`、`onFocusSearch` 入参；新增 `pendingBackFocus` 局部状态；新增 `FocusRequester`
    - 在「有子页打开」分支之前判断「是否需要先收子页」：以 `state.pages.isNotEmpty()` / `dayHistoryDate != null` / `upSpace.up != null` / `ResultActionSheet` 是否打开为条件，逐层 `BackHandler` 拦截（**已有 BackHandler 优先于本节新增的全局拦截**，因 Compose BackHandler 是后注册者优先——所以这里其实只要在「所有子页都已关闭」这一支注册一次即可）
  - `MainActivity`：
    - 注入 `onBack` / `onFocusSearch` 实现：
      - `onBack` = 一级「清空 keyword + onFocusSearch」
      - `onFocusSearch` = 触发 `FocusRequester.requestFocus()` + `keyboard.show()`
  - `AppViewModel`：**不动**（已有 `onKeywordChange` 重置 `SearchUiState()` 的逻辑）
- 行为约束：
  - `pendingBackFocus` 只在「返回清空后」置 true；下次进入搜索页或重新输入关键词时重置
  - 避免在空状态时弹键盘（与 `M11 真机反馈` 注释「未聚焦首页不直接显示历史」一致）
  - 真机体验需真人按《手动测试指南》跑一遍：
    - 搜索结果页 → 滑一次 → 搜索框被清空、聚焦、键盘弹起、显示历史
    - 再滑一次 → 退到桌面
    - 打开多 P 面板 → 滑一次 → 关闭多 P，搜索结果保留
    - 打开 UP 空间 → 滑一次 → 关闭空间，搜索结果保留

### 1.9 验证

- 离线：`./gradlew :app:assembleDebug` 编译通过
- 真机（仅真人）：
  - 三种返回手势场景的页面流转（见 1.6 草图）
  - 滑回搜索结果页时，搜索结果是否仍在；不期望被清掉（仅清空 keyword，不清空 results —— 若你要的是「清空全部结果」请在工单里加备注）
  - 滑回空状态时，再次返回是否真的退到桌面而不是「先关键盘」

> 备注（定案）：结果页滑返回触发「清空」时**全清** `SearchUiState`（keyword/results/errors/pages），回到空状态并显示搜索历史——沿用现有 `onKeywordChange("")`（`AppViewModel.kt:979`），本节零改动。**不**拆 `clearKeywordOnly()`：保留结果又清关键词会与「聚焦时显示历史」冲突（框空着却挂着上次列表）。

---

## 2. 搜索页：结果区滚动与统计行（定稿方案 A3）

> 用户反馈：搜索结果页向上滑动查看更多结果时，标题栏下方的筛选区（视频/UP主 + 排序 + 时长）始终不滚动，可视结果区太小；并要求去掉「约 N 个结果 · 第 X/Y 页」一行。
> 
> 截图来源：`/home/minifive/Desktop/Snipaste_2026-09-07_15-29-07.png`（已查看）
> 
> **定稿方案 A3**：顶栏（`BiliSeek` 标题 + 问候语 + 头像）+ 搜索框保持固定不动；模式切换、排序/时长筛选、错误条、多 P 面板、搜索结果列表、加载更多指示器全部进入单一 `LazyColumn` 一起参与滚动。统计行**直接删除**，不替代显示。

### 2.1 截图与代码核对

红框区域与 `app/src/main/java/com/biliseek/app/SearchScreen.kt` 中现有实现严格对应：

1. **模式切换** `视频` / `UP 主` —— `SingleChoiceSegmentedButtonRow`（`SearchScreen.kt:227`）
2. **排序 chip 行** —— 第一个 `FlowRow`（`SearchScreen.kt:405`），3 个 `FilterChip`「最多播放 / 最新 / 最多弹幕」
3. **时长 chip 行** —— 第二个 `FlowRow`（`SearchScreen.kt:415`），4 个 `FilterChip`「10 分内 / 10-30 分 / 30-60 分 / 60 分+」
4. 紧接红框下方的统计行：「约 1000 个结果 · 第 2/50 页」—— `SearchScreen.kt:281-288` 的 `Text(...)`

### 2.2 现象根因（按代码定位）

```kotlin
Column(modifier = modifier.fillMaxSize()) {
    Row(...) { /* 标题栏 */ }            // 不滚
    OutlinedTextField(...)              // 不滚
    SingleChoiceSegmentedButtonRow(...) // 不滚（红框上沿）
    if (...) SearchFilterBar(...)       // 不滚（红框主体，2 行 chip）
    if (...) { /* UP 主分支 */ return@Column }
    if (state.items.isNotEmpty()) { /* 错误条 */ }
    if (state.totalResults > 0) { Text("约 N 个结果 ...") }   // 不滚 → 后续删除
    if (state.pages.isNotEmpty()) { /* 多 P */ }
    when {
        ...
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) { ... }
    }
}
```

- 整个 `Column` 里没有 `weight(1f)` 子项
- 红框部分 + 统计行按自然高度累加
- 最后一项 `LazyColumn(Modifier.fillMaxSize())` 在 `Column` 里只能拿到「前面吃剩的高度」，对应用户看到的「结果区小、上半固定」
- `LazyColumn` 滚动只滚动列表内部，无法带动 `Column` 同级元素

### 2.3 目标效果（A3）

- 顶栏与搜索框**始终钉在屏幕顶部**
- 模式切换、筛选 chip、错误条、多 P 面板、搜索结果、加载更多指示器**全部进入 `LazyColumn`**，随结果一起滚走
- 统计行「约 N 个结果 · 第 X/Y 页」**直接删除**，不显示页码、不显示已加载数、不显示结果总数
- 滚动加载下一页 `shouldLoadMore` 行为不变
- UP 主分支（`state.searchMode == SearchMode.USER`）与首页空状态分支（`state.keyword.isBlank()`）**不进** `LazyColumn`，继续走外层 `Column`

### 2.4 状态模型

- `SearchUiState` **不动**：仍承载 `items` / `page` / `totalResults` / `totalPages` / `isLoading` / `isLoadingMore` / `error` / `pages` 等数据
- `SearchScreen` 内部：
  - `listState` 复用 `rememberLazyListState()`，传给新的 `LazyColumn`
  - `shouldLoadMore` 的 `derivedStateOf` 不变，仅依赖 `listState` 与 `state.items.size`
  - 新增 `actionsFor: SearchVideo?` 等局部状态保持现状
- `AppViewModel` **不动**

### 2.5 调用的 `BiliRepository` 方法

- 本节**不涉及新接口**：`search` / `searchUsers` / `pages` / `expandShortLink` 复用第 1 节列表
- 仅渲染层结构调整，无网络调用变化

### 2.6 错误/登录/风控分支

- 错误条（`Card` 包 `Text`，`SearchScreen.kt:262-281`）从 `Column` 内子项转为 `LazyColumn` 的一个 `item`，随滚动一起进出
- `SearchError.Blocked` 红条、`ErrorPanel` 文案、`SearchError.CredentialsInvalid` / `NotActivated` / `Network` / `Empty` / `Unknown` 全部不变
- 统计行删除后**不再展示**结果数量、页码

### 2.7 交互草图（ASCII）

**A3 滚动前**：

```
 ┌──────────────────────────────┐
 │ BiliSeek                     │   ← 标题栏（不滚）
 │ 下午好，bili_xxx              │
 │ ┌──────────────┐ ✕           │   ← 搜索框（不滚）
 │ │ 邓紫棋       │             │
 │ └──────────────┘             │
 ├──────────────────────────────┤ ← 外层 Column 与 LazyColumn 分界
 │ [视频][UP主]                 │   ← 以下全在 LazyColumn 内
 │ 最多播放 最新 最多弹幕       │
 │ 10分内 10-30 30-60 60+       │
 │ ┌──┐ 标题 1                   │
 │ │封│ UP · 时长 · 播放数        │
 │ └──┘ 2023年10月…             │
 │ ┌──┐ 标题 2                   │
 │ │封│ UP · 时长 · 播放数        │
 │ └──┘ …                       │
 │   …                          │
 └──────────────────────────────┘
```

**A3 滚动后**：

```
 ┌──────────────────────────────┐
 │ BiliSeek                     │   ← 标题栏仍钉在顶部
 │ 下午好，bili_xxx              │
 │ ┌──────────────┐ ✕           │   ← 搜索框仍钉在顶部
 │ │ 邓紫棋       │             │
 │ └──────────────┘             │
 ├──────────────────────────────┤
 │ ┌──┐ 后续 result…             │   ← 筛选 + 上方结果已滚走
 │ │封│ 后续 result…             │
 │ └──┘ 后续 result…             │
 │ ┌──┐ 后续 result…             │
 │ │封│ …                       │
 │ └──┘ …                       │
 │   …                          │
 └──────────────────────────────┘
```

### 2.8 待开发 / 复用项清单

- 复用：
  - `SearchScreen` 全部子组件（`SearchFilterBar` / `UserResults` / `ResultRow` / `ErrorPanel` / `MultiPagePanel` / `ResultActionSheet`）
  - `shouldLoadMore` 的 `derivedStateOf` 滚动加载逻辑
  - `SearchUiState` 字段（`items` / `page` / `isLoadingMore` / `error` / `pages` 等）
  - `listState`（`rememberLazyListState()`）
- 改动（仅 `SearchScreen.kt`）：
  - **拆分外层 `Column`**：保留 `Row` 标题栏 + `OutlinedTextField` 搜索框 两个不滚子项；剩余部分（模式切换、筛选、错误条、多 P、结果、加载更多）迁入 `LazyColumn`
  - **`LazyColumn`**：`state = listState, modifier = Modifier.weight(1f).fillMaxSize()`（在 `Column` 内以 `weight(1f)` 抢占剩余高度）
  - **`item { }` 子项顺序**：
    1. `item(key = "mode") { SingleChoiceSegmentedButtonRow(...) }`
    2. `if (state.searchMode == SearchMode.VIDEO) item(key = "filters") { SearchFilterBar(...) }`
    3. `if (state.items.isNotEmpty() && state.error != null) item(key = "error-${state.error}") { Card(...) }`（错误条随结果滚动）
    4. `if (state.pages.isNotEmpty()) { BackHandler(...); item(key = "pages") { MultiPagePanel(...) } }`
    5. `items(state.items, key = { it.bvid }) { video -> ResultRow(...) }`
    6. `if (state.isLoadingMore) item(key = "loadingMore") { Box(...) { CircularProgressIndicator() } }`
  - **删除** `SearchScreen.kt:281-288` 的统计行 `Text("约 ${state.totalResults} 个结果 · 第 ${state.page}/${state.totalPages} 页", ...)`
  - **UP 主分支 / 首页空状态分支**：保持在 `Column` 内，`return@Column` 行为不变
  - **多 P 面板的 `BackHandler`**：从 `Column` 内的 `if (state.pages.isNotEmpty())` 移入 `LazyColumn` 的同一个 `if` 内，逻辑不变（`BackHandler` 注册位置与所在容器无关，只跟当前 Composition 有关）
  - **`shouldLoadMore` 与加载下一页**：保留 `LaunchedEffect(shouldLoadMore, itemCount) { if (shouldLoadMore) onLoadMore() }`；`derivedStateOf` 只依赖 `listState` 与 `state.items.size`，与是否在 `LazyColumn` 内无关，无需改动
- **不改**：`AppViewModel` / `MainActivity` / `BiliRepository` / `AppShell` / 任何 Repository 接口
- 行为约束：
  - `LazyColumn` 内子项 `key` 必须稳定，避免重组导致列表状态错位
  - 模式切换与筛选的 `key` 固定字符串（仅两个实例）；错误条用 `state.error` 的 hashCode 或 `SearchError` 的 `data object` 引用
  - 滚动加载下一页 `shouldLoadMore` 仍要求 `state.items` 非空，避免空列表时误触发

### 2.9 验证

- 离线：`./gradlew :app:assembleDebug` 编译通过
- 真机（仅真人）：
  - 搜索结果页向上滑 → 模式切换 + 排序 chip + 时长 chip 一起滚走，标题栏与搜索框保持不动
  - 滚到底自动加载下一页正常（LoadingMore 指示器在 `LazyColumn` 末尾可见）
  - UI 中不再出现「约 N 个结果 · 第 X/Y 页」字样
  - 模式切换（视频↔UP 主）、筛选切换（排序/时长）时滚动位置不丢
  - 长按搜索结果弹 `ResultActionSheet`，系统返回手势优先收起 sheet
  - 多 P 面板打开 → 系统返回手势收起面板，回到搜索结果页
  - UP 主分支不受影响（继续走外层 `Column`）
  - 首页空状态分支不受影响（继续走外层 `Column`）

---

## 3. 搜索页：复选模式与批量操作

> 本章覆盖三个交织的改动：搜索结果行右侧加三点按钮（`MoreVert`）、长按进入复选模式、多 P 视频拆解为多选分 P 组件。所有功能点都以选中集为单位执行（加入歌单 / 加入播放列表 / 下一首播放 / 下载）。

### 3.1 目标效果

- **三点按钮（`MoreVert`）**
  
  - 在每个 `ResultRow` 右侧（在封面与文字列之外）加一列；点击弹**单条**功能框，与原长按 `ResultActionSheet` 内容一致
  - 复选模式下三点按钮**隐藏**，行尾改为复选框
  - 复选框/三点按钮二者互斥，同一行不同时显示

- **长按进入复选模式**
  
  - 长按任一结果 → 整页进入「管理态」：**顶栏换成工具栏**、**搜索框隐藏**，每行首...........................部出现复选框
  - 长按后该行被勾选；再点行 = 切换勾选态
  - 工具栏：`全选 / 全不选 / 加入歌单 / 加入播放列表 / 退出复选`
  - 「全选」范围 = 当前可见 `state.items`（分页拉满之前已有的那些）
  - 系统返回键 = 退出复选模式并**清空勾选**，回到搜索结果页
  - 复选模式下点击单条视频 = **不播放**，仅切换勾选态

- **多 P 视频拆解（分两种场景，定案）**
  
  - **批量场景**（复选模式下对选中集执行动作）：**直接取每条视频的全部分 P**、按 `pages` 自然顺序执行，**不弹选择页**（多选是批量快捷操作，反复弹选择页打断体验）。搜索页封面**不加 P 数角标**（`SearchVideo` DTO 无分 P 字段，逐条预取 `pages()` 会高频命中服务器，违反安全铁律）——用户在触发单条动作时才由选择页自然看到 P 数。
  - **单条场景**（正常态点三点按钮对单条 BV 执行动作）：单条 BV（无论单 P/多 P）执行「加入歌单 / 加入播放列表 / 下一首播放 / 下载」时：
    - **单 P** → 跳过选择页，直接执行
    - **多 P** → 进入**新的多选分 P 组件 `MultiPagePickerScreen`**，顶部常驻工具栏：`加入歌单 / 加入播放列表 / 下一首播放 / 下载 / 返回结果`
    - 选中若干分 P 后点击工具栏动作 → 对选中 P 批量执行（不直接播放）
  - 多选分 P 组件不复用现有 `MultiPagePanel`（语义不同：MultiPagePanel 是「挑一首播」，这里是「挑若干执行批量操作」）

- **加入播放列表（新增能力）**
  
  - 把选中的视频（多 P 已拆为 P1/P2/…/PN）按列表顺序**追加到 ExoPlayer 队列末尾**，不动 `queueIndex`
  - 与「下一首播放」（`enqueueNext`）语义分开
  - 走统一成功 toast，不显示「已加入 3/5」

### 3.2 适用入口与触发动作

- 入口：搜索结果页（`Destination.SEARCH` → `SearchScreen`）的 `LazyColumn` 内
- 单条触发：
  - `onResultClick(video)` —— **复选模式下禁用**（改为切换勾选）；正常态走原逻辑
  - `onResultLongClick(video)` —— 进入复选态，并自动勾选该项
  - `onMoreClick(video)` —— 点 `MoreVert` 弹单条 `ResultActionSheet`
- 批量触发（`AppViewModel` 新增）：
  - `enterSelectionMode(initialBvid: String)` —— 打开管理态
  - `exitSelectionMode()` —— 退出并清空勾选
  - `toggleSelection(bvid: String)` —— 切换勾选
  - `selectAllVisible()` —— 勾选 `state.items` 全部 bvid
  - `clearSelection()` —— 清空勾选（不退出）
  - `addSelectionToPlaylist()` —— 弹 `PlaylistPickerDialog` → 入库
  - `appendSelectionToQueue()` —— 追加到 ExoPlayer 队列
  - `enqueueSelectionNext()` —— `enqueueNext` 按列表顺序逐条入队
  - `downloadSelection()` —— `enqueueDownload` 按列表顺序逐条入队
  - `pickMultiPagesAnd(action)` —— **单条场景专用**（正常态三点按钮）：给多 P BV 进入 `MultiPagePickerScreen`，收到选中 P 后执行 `action`；批量场景不经此路径

### 3.3 状态模型

- `AppViewModel` 新增 `MutableStateFlow<Set<String>> _selectedBvids`，`selectedBvids: StateFlow<Set<String>>`
- 新增 `MutableStateFlow<SelectionMode> _selectionMode = SelectionMode.Off`：
  - `data object Off : SelectionMode`
  - `data class On(val initialBvid: String) : SelectionMode`
- 新增 `MutableStateFlow<MultiPagePickerState?> _multiPagePicker`：
  - `data class MultiPagePickerState(val bvid: String, val pages: List<PageEntry>, val pendingAction: PendingAction)`
  - `sealed interface PendingAction { data object AddToPlaylist; data object AppendToQueue; data object EnqueueNext; data object Download }`
- `SearchUiState` 不动；多选 UI 状态只在 ViewModel 持有
- `SearchScreen` 局部状态：
  - `actionsFor: SearchVideo?` 保留（单条功能框）
  - 新增 `selectionVisible: Boolean = selectionMode != Off`，根据 VM 切换 UI
- `ResultRow` 视觉态：
  - 选中：行背景高亮（主题 `surfaceVariant` 透明度）+ 行尾复选框打勾
  - 未选中：行尾复选框空
  - 复选态下 `MoreVert` 不渲染

### 3.4 调用的 `BiliRepository` 方法

- 复用：
  - `pages(bvid)` —— 单条 BV 触发动作前先查分 P，多 P 时跳 `MultiPagePickerScreen`
  - `search` / `searchUsers` —— 复用既有结果
  - `expandShortLink` —— 复用
- 新增（`:core:player`）：
  - `PlayerConnection.appendToQueue(request: PlaybackRequest)` —— 追加到 ExoPlayer 队列末尾，不动 `queueIndex`，不自动播放
    - 实现要点（基于 `PlayerConnection.kt:72-73, 482-511`）：
      
      ```kotlin
      fun appendToQueue(request: PlaybackRequest) {
          if (request !is PlaybackRequest.Bili) return
          queue = queue + request
          // 不动 queueIndex：appendToQueue 不影响当前播放位
          persistQueue()  // 复用 playQueue 已有的会话持久化路径
      }
      ```
- `AppViewModel`：
  - 现有 `enqueueNext` / `requestAddToPlaylist` / `enqueueDownload` 全部复用
  - 新增 `appendSelectionToQueue()`：按 `state.items` 顺序，把选中的 BV 拆解为 `PlaybackRequest.Bili` 列表，依次调 `connection.appendToQueue`

### 3.5 错误/登录/风控分支

- 加入歌单（弹 `PlaylistPickerDialog`）：
  - 用户取消 → 静默，不弹 toast
  - 单条入库失败（数据库写入）→ 沿用 `LibraryRepository.addToPlaylist` 现有错误处理（无显式错误反馈，记录日志；本次工单不引入新错误分支）
- 加入播放列表：
  - 多 P 拉取 `pages(bvid)` 失败 → 复用现有 `SearchError.Network` / `Unknown` 红条
  - `appendToQueue` 失败 → 静默（队列是内存结构，无外部依赖）
- 下一首播放：
  - 与现有 `enqueueNext` 一致；失败抛 `Exception` 由 ViewModel 捕获并写入 `_pendingError`（沿用现有）
- 下载：
  - 复用 `enqueueDownload` 与现有 `DownloadManager` 任务状态；多 P 时每个 P 走一次 `enqueueDownload`
  - 公共媒体库权限链路（`MainActivity.kt:55-66`）继续生效
- 复选模式：
  - 不引入新的错误分支；纯 UI 态
- 统一成功 toast：动作执行完（批量取全 P、或单条走选择页选完 P 后）弹一个 `Snackbar`（或 `Toast`）「已加入 N 项到 X」，N = 实际入队/入库的 P 总数，X = 动作名（歌单名 / 播放队列 / 下一首播放 / 下载队列）

### 3.6 交互草图（ASCII）

**正常态 / 复选态切换**：

```
正常态                              复选态（管理态）
 ┌────────────────────────────┐     ┌────────────────────────────┐
 │ BiliSeek                   │     │ [全选] [全不选] [歌单] [队] │
 │ 下午好，bili_xxx            │     │ 已选 3 项        [退出]     │
 │ ┌──────────────┐ ✕         │     ├────────────────────────────┤
 │ │ 邓紫棋       │           │     │ ☐ ┌──┐ 标题 1          ⋮  │
 │ └──────────────┘           │     │ ☑ ┌──┐ 标题 2              │
 ├────────────────────────────┤     │ ☑ ┌──┐ 标题 3              │
 │ [视频][UP主]               │     │ ☐ ┌──┐ 标题 4              │
 │ 最多播放 最新 最多弹幕     │     │ …                          │
 │ 10分内 10-30 30-60 60+     │     └────────────────────────────┘
 │ ┌──┐ 标题 1           ⋮   │       ↑ 搜索框已隐藏
 │ │封│ UP · 时长 · 播放数    │       ↑ 顶栏换工具栏
 │ └──┘ …                     │       ↑ ⋮ 按钮隐藏，行尾变复选框
 │ ┌──┐ 标题 2           ⋮   │
 │ │封│ …                     │
 │ └──┘ …                     │
 │ …                          │
 └────────────────────────────┘
```

**多 P 拆解 → `MultiPagePickerScreen`**：

```
点单条 BV 的「加入播放列表」（三点按钮 / 长按后批量工具栏）
  ↓
BiliRepository.pages(bvid)
  ├─ 单 P（size == 1）→ 直接 appendToQueue(第一个 P) → 成功 toast
  └─ 多 P（size > 1）→ 打开 MultiPagePickerScreen(bvid, pages, action)
        ┌────────────────────────────┐
        │ [歌单][队列][下一首][下载]  [返回]
        ├────────────────────────────┤
        │ ☐ P1  标题 1   时长          │
        │ ☑ P2  标题 2   时长          │
        │ ☑ P3  标题 3   时长          │
        │ ☐ P4  标题 4   时长          │
        │ …                            │
        └────────────────────────────┘
        点击工具栏动作 → 对 ☑ 选中项批量执行 → 成功 toast → 返回搜索页
```

### 3.7 待开发 / 复用项清单

#### 3.7.1 改动（仅前端）

- `SearchScreen.kt`：
  - `ResultRow` 签名扩展：新增 `trailing: @Composable () -> Unit`（行尾插槽，根据复选态渲染 `MoreVert` 或 `Checkbox`）
  - `ResultActionSheet`（`SearchScreen.kt:471-501`）保留，内容不变
  - 复选态工具栏 `SelectionToolbar`（新增）：
    - 全选 / 全不选 / 加入歌单 / 加入播放列表 / 退出
    - 显示已选数（`已选 N 项`）
  - `SearchScreen` 顶部根据 `selectionMode` 切换：
    - `Off` → 现有 `Row`(标题) + `OutlinedTextField`
    - `On` → `SelectionToolbar`
  - 行点击行为：根据 `selectionMode` 切换 `onResultClick` 与 `toggleSelection`
- `MainActivity.kt`：
  - 注入复选态回调：`enterSelectionMode` / `exitSelectionMode` / `toggleSelection` / `selectAllVisible` / `clearSelection` / `addSelectionToPlaylist` / `appendSelectionToQueue` / `enqueueSelectionNext` / `downloadSelection`
  - 监听 `_multiPagePicker`，非 null 时渲染 `MultiPagePickerScreen`
- 新建 `MultiPagePickerScreen.kt`：
  - 入参：`bvid: String`、`pages: List<PageEntry>`、`onAction: (PendingAction, selected: List<PageEntry>) -> Unit`、`onDismiss: () -> Unit`
  - 顶部工具栏：`加入歌单 / 加入播放列表 / 下一首播放 / 下载 / 返回结果`
  - 每行复选框，`mutableStateOf<Set<Long>>` 选中 cid
  - 返回手势：`BackHandler(onBack = onDismiss)`（点「返回结果」或系统返回都关掉面板）
- 新建 `MultiPagePickerRoutes.kt`（如有需要，命名占位）：
  - 仅声明 `PendingAction` 与状态容器
- `LibraryRepository`：
  - 复用 `addToPlaylist(playlistId: Long, items: List<PlaylistTrack>)` —— 若现有方法只接受 `PlaylistTrack`，需扩展支持从 `PageEntry` 构造

#### 3.7.2 改动（核心播放器）

- `PlayerConnection.kt`：
  - 新增 `fun appendToQueue(request: PlaybackRequest)`：
    - 仅处理 `PlaybackRequest.Bili`
    - `queue = queue + request`
    - 不动 `queueIndex`
    - 复用现有 `persistQueue()` 路径（与 `playQueue` 一致，见 `PlayerConnection.kt:482-511`）

#### 3.7.3 改动（ViewModel）

- `AppViewModel.kt`：
  - 新增 `_selectedBvids: MutableStateFlow<Set<String>>`、`selectedBvids: StateFlow<Set<String>>`
  - 新增 `_selectionMode: MutableStateFlow<SelectionMode>`、`selectionMode: StateFlow<SelectionMode>`
  - 新增 `_multiPagePicker: MutableStateFlow<MultiPagePickerState?>`
  - 新增函数：`enterSelectionMode(initialBvid)` / `exitSelectionMode()` / `toggleSelection(bvid)` / `selectAllVisible()` / `clearSelection()`
  - 新增批量动作（定案：批量一律取全 P、不弹选择页；对每条选中 BV 先取 `pages`，按自然顺序把全部 P 拆成 `PlaybackRequest.Bili` 执行）：
    - `addSelectionToPlaylist()` → 弹一次 `PlaylistPickerDialog`，把所有选中 BV 的全部 P 入选中歌单
    - `appendSelectionToQueue()` → 所有选中 BV 的全部 P 依次 `connection.appendToQueue`
    - `enqueueSelectionNext()` → 所有选中 BV 的全部 P 依次 `connection.enqueueNext`
    - `downloadSelection()` → 所有选中 BV 的全部 P 依次 `enqueueDownload`
  - 私有辅助 `private fun openMultiPagePicker(bvid, action)`：**仅单条场景（三点按钮）用**——拉 `pages(bvid)`，多 P 时写入 `_multiPagePicker` 弹选择页、单 P 直接执行
  - 私有辅助 `private fun executeOnSelectedPages(action, pages: List<PageEntry>)`：按 `pages` 顺序对每个 P 执行 `action`
  - `executeOnSelectedPages` 内部对每个 P 构造 `PlaybackRequest.Bili(bvid, cid=page.cid, ...)` 并调对应 API
- `MultiPagePickerScreen` 回调：
  - `onDismiss` → `_multiPagePicker.value = null`
  - `onAction(action, selected)` → ViewModel 内部按 `action` 派发；执行完一个 toast 并关掉面板

#### 3.7.4 不改

- `BiliApi` / `BiliRepository` 接口不变（仅复用现有）
- `AppShell` 不变（不引入新的 nav destination）
- `MultiPagePanel` 保留现状，继续走 `playPage` 路径
- `ResultActionSheet` 保留现状，三点按钮复用同一份

#### 3.7.5 行为约束

- 复选模式下系统返回键：清空勾选并退出，回到搜索结果页（不弹 `Dialog` 二次确认）
- 工具栏按钮：
  - 已选数 = 0 时「加入歌单 / 加入播放列表 / 下一首播放 / 下载」**禁用**
  - 已选数 = `state.items.size` 时「全选」**禁用**
  - 已选数 < `state.items.size` 时「全不选」**启用**
- 多 P 选择页（**仅单条场景**，正常态三点按钮触发；批量场景不弹此页）：
  - 单 P BV（`pages.size == 1`）→ 跳过选择页，直接执行动作
  - 多 P BV → 进入选择页，选中若干 → 工具栏动作 → 批量执行
  - 选择页内「加入歌单 / 加入播放列表 / 下一首播放 / 下载」按选中 cid 列表逐条执行
- `appendToQueue` 不阻塞当前播放、不重排 `queueIndex`
- 多选动作全部走统一成功 toast，不显示「已加入 3/5」分步进度

### 3.8 验证

- 离线：
  - `./gradlew :app:assembleDebug` 编译通过
  - `./gradlew :core:player:test` 新增 `AppendToQueueTest`（用假 `PlayerConnection` 验证 `appendToQueue` 不动 `queueIndex` 且队列末尾追加正确）
- 真机（仅真人）：
  - 长按搜索结果 → 进入复选态，标题栏换成工具栏，搜索框隐藏
  - 工具栏点「全选」→ 当前可见全部勾选；「全不选」→ 清空
  - 点工具栏「加入播放列表」 → 批量取每条选中 BV 的全部 P、按自然顺序追加队列 + toast（**不弹选择页**）
  - 正常态点单条三点按钮「加入播放列表」 → 单 P 直接追加；多 P 跳 `MultiPagePickerScreen` 挑 P → toast → 返回搜索结果页
  - 复选态下系统返回键 → 退出复选、勾选清空、回到搜索结果页
  - 三点按钮点击 → 弹单条 `ResultActionSheet`，与原长按内容一致
  - 复选态下三点按钮不可见，行尾为复选框
  - 「下一首播放」批量：选 3 个 BV → 加入到当前播放位的下一首（按列表顺序逐条入队）
  - 「下载」批量：选 3 个 BV → `DownloadManager` 任务列表新增 3 条

### 3.9 已决 / 实现注意

- **`PlaybackRequest.Bili` 构造（已确认）**：`PlaybackRequest.Bili` 已带 `cid: Long? = null`（`PlaybackModels.kt:54`），按 `PageEntry.cid` 构造 `PlaybackRequest.Bili(bvid, cid=page.cid, ...)` 直接可行，**无需扩展**。
- **多 P 入队顺序（定案）**：按 `pages` **自然顺序**（P1→P2→…）执行，不按勾选先后。
- **复选态跨页行为（定案）**：`selectAllVisible` 只覆盖**点「全选」当时已加载的可见项**；之后翻页拉出的新项不自动纳入。
- **未登录禁用策略（定案）**：未登录时「加入歌单 / 下载 / 关注」**禁用**（无账号无意义）；「加入播放列表 / 下一首播放」是内存队列，**不禁用**。
- **三点按钮与现有长按冲突（实现注意）**：现有 `combinedClickable(onClick, onLongClick)`（`SearchScreen.kt:531`）保留，三点按钮点击区域需避免与行的长按手势重叠。

---

## 4. UP 空间页与动态页：动态互动 + 图文动态 + 关注 + 投稿批量

> 本章覆盖四处变更：
> 
> - **UP 空间页**（`UpSpaceScreen`）：投稿视图复用第 3 章的三点按钮 / 复选模式 + 关注/已关注按钮
> - **动态 tab / UP 空间·动态视图**（`DynamicScreen` / `DynamicCard`）：图文动态多图 + desc bug修复、图片全屏预览、点赞/评论/转发功能落地
> - **基础设施**：`jsonWriteRequest` 补齐（对齐07 工单 B6）；新增 `likeDynamic` / `addDynamicReply` 接口
> - **分享/评论目标**：复用 `BiliPai DynamicInteractionPolicy` 的 oid / type 分流（DRAW→type=11，其他→type=17）

### 4.1 目标效果

#### 4.1.1 UP 空间页（`UpSpaceScreen`）

- **关注按钮**：UP 头部卡右侧加「关注 / 已关注」按钮（BiliPai 同款位置）
  - 未登录：按钮显示「关注」，点击跳登录页
  - 未关注：按钮显示「关注」，点击 → `modifyRelation(mid, true)` → 乐观更新为「已关注」+ 切换样式
  - 已关注：按钮显示「已关注」+ 「✓」图标，再次点击 → `modifyRelation(mid, false)` → 乐观更新为「关注」
  - 请求失败：回滚本地状态，弹 toast「关注失败：code」
- **投稿视图**：复用第 3 章的三点按钮 / 复选模式
  - `PostRow` 末尾加 `trailing` 插槽：复选态显示 `Checkbox`；正常态显示 `MoreVert`
  - 多 P 投稿 → 复用第 3 章 `MultiPagePickerScreen`，批量动作集合一致（加入歌单 / 加入播放列表 / 下一首播放 / 下载）
  - 工具栏 `SelectionToolbar` 与搜索页同款

#### 4.1.2 图文动态多图 + desc bug

- **DRAW / OPUS / ARTICLE**：
  - `parseDynamicItem` 的 desc 来源统一为「`module_dynamic.desc.text` ∪ `opus.summary.text` ∪ `article.summary`」
  - 多图：`DynamicEntry.pics: List<String>` 收集 DRAW / OPUS / ARTICLE 全部图片
- **`DrawGrid` 组件**（新建）：DRAW/OPUS 多图时使用
  - 1 张图：按原图 `width/height` 算 `aspectRatio`，单图占位（最大宽度 240dp）
  - 2-9 张：9 宫格布局，每格 `aspectRatio = 1f`，间距 4dp，圆角 4dp
  - ≥10 张：展示前 9 张 + 右上 `+N` 角标（参考 BiliPai `DrawGrid.kt:138-152`）
  - GIF：URL 以 `.gif` 结尾时用专用 `ImageLoader`；其他走默认 `Coil`
  - 点击任一图 → 全屏预览
- **单图兜底**：图文动态 `pics.size == 1` 且不属于 DRAW/OPUS 时用现有「首图占位」逻辑
- **缩略图策略**：使用 `coil3` 的 `ContentScale.Crop`，等距加载到 200×200 缩略图避免大图占内存

#### 4.1.3 图片全屏预览（`ImagePreviewDialog`）

新建 `ImagePreviewDialog.kt`：

- `HorizontalPager` 滑动切换图片，双指缩放、双击复位/放大、`Modifier.pointerInput` 处理手势
- 顶部：当前索引 / 总数 + 关闭按钮
- 底部：「保存到相册」按钮（可选，未来实现）
- 暗黑背景，状态栏沉浸
- 进入动画：从 `Rect`（小图位置）放大到全屏（参考 BiliPai `ImagePreviewDialog.kt`）
- 出动画：反向
- 支持在预览页双击空白处关闭

#### 4.1.4 动态底部互动栏（`ActionButton`）

替换 `DynamicActionText`（只读）为 `ActionButton`：

- 三个按钮：`转发` / `评论` / `点赞`
- **转发**：
  - 点击 → 弹 `RepostDialog`（输入框 + 转发按钮，参考 BiliPai `RepostDialog.kt`）
  - 提交后调 `AppViewModel.repostDynamic(idStr, content)`，乐观更新 `forward.count += 1`
- **评论**：
  - 点击 → 弹 `DynamicCommentDialog`（新建）：
    - 半屏 sheet，输入框 + 历史评论预览（拉评论列表，参考 BiliPai `DynamicCommentSheet.kt`）
    - 提交走 `AppViewModel.addDynamicReply(target.oid, target.type, message)`，乐观更新 `comment.count += 1`
  - 评论 oid/type 分流：`resolveDynamicCommentTarget(entry)`：
    - DRAW：`oid = major.draw.id`，`type = 11`
    - 其他（OPUS / 转发 / 普通）：`oid = id_str`，`type = 17`
    - 参考 BiliPai `DynamicInteractionPolicy.resolveDynamicCommentTarget`
- **点赞**：
  - 点击 → `AppViewModel.likeDynamic(idStr, like = !stat.like.status)`，乐观更新：
    - `stat.like.status = !status`
    - `stat.like.count = (status ? +1 : -1)`
  - 防抖：500ms 内只发一次请求
- 视觉：
  - 已点赞：拇指图标填充、主题色高亮
  - 未点赞：线性图标、主题色 onSurfaceVariant
  - 计数变化：`AnimatedContent` 动画（参考 BiliPai `ActionButton.kt:155-181`）

### 4.2 适用入口与触发动作

#### 4.2.1 UP 空间页

- **关注按钮**：`onFollowClick(mid, follow)` → `AppViewModel.toggleRelation(mid, follow)`
- **三点按钮**：`onPostMore(video)` → 弹 `ResultActionSheet`（复用搜索页）
- **长按投稿**：与第 3 章一致

#### 4.2.2 动态页

- **点赞按钮**：`onLikeClick(dynamicId)` → `AppViewModel.likeDynamic(idStr, like)`
- **评论按钮**：`onCommentClick(dynamicId)` → 弹 `DynamicCommentDialog`
- **转发按钮**：`onRepostClick(dynamicId)` → 弹 `RepostDialog`
- **点击图片**：`onImageClick(index, rect)` → 打开 `ImagePreviewDialog`

### 4.3 状态模型

#### 4.3.1 `AppViewModel` 新增

- `_followingMid: MutableStateFlow<Long?>`（当前空间页 UP 是否已关注）
- `_likeRequestGate: Map<String, Long>`（防抖，500ms）
- `_repostDialog: MutableStateFlow<RepostDialogState?>`（`{ idStr, content, isPosting }`）
- `_commentDialog: MutableStateFlow<CommentDialogState?>`（`{ idStr, oid, type, content, isPosting, replies: List<ReplyItem> }`）
- `_previewImage: MutableStateFlow<ImagePreviewState?>`（`{ pics: List<String>, initialIndex, sourceRect }`）
- 现有 `DynamicEntry` 新增 `pics: List<String>` 字段

#### 4.3.2 `BiliRepository` 关注

- `toggleRelation(mid, follow): Result<Unit>` 调用 `modifyRelation(mid, follow)`

#### 4.3.3 `DynamicFeed.parseDynamicItem` 修复

- DRAW / OPUS / ARTICLE 分支：
  
  ```kotlin
  val descText = listOf(
      content?.desc?.text.orEmpty(),
      major?.opus?.summary?.text.orEmpty(),
      major?.article?.summary.orEmpty(),
  ).firstOrNull { it.isNotBlank() } ?: ""
  base.copy(
      descText = descText,
      pics = when {
          major?.draw?.items?.isNotEmpty() == true ->
              major.draw.items.mapNotNull { if (it.src.isNotBlank()) normalizeCoverUrl(it.src) else null }
          major?.opus?.pics?.isNotEmpty() == true ->
              major.opus.pics.mapNotNull { if (it.url.isNotBlank()) normalizeCoverUrl(it.url) else null }
          major?.article?.covers?.isNotEmpty() == true ->
              major.article.covers.map { normalizeCoverUrl(it) }
          else -> emptyList()
      },
      coverUrl = pics.firstOrNull() ?: coverUrl,
  )
  ```

### 4.4 调用的 `BiliRepository` 方法

#### 4.4.1 复用已有

- `modifyRelation(mid, follow)` —— 关注/取关
- `dynamicFeed(type, offset)` —— 关注动态流
- `dynamicSpace(hostMid, offset)` —— UP 空间动态
- `pages(bvid)` —— 投稿页多 P 检测（复用第 3 章 `MultiPagePickerScreen`）

#### 4.4.2 新增

- `BiliApi.likeDynamic` → `BiliRepository.likeDynamic(idStr, like)` → `x/dynamic/feed/dyn/thumb`（**走 jsonWriteRequest**）
- `BiliApi.addDynamicReply` → `BiliRepository.addDynamicReply(oid, type, message)` → `x/v2/reply/add`（走 writeRequest）
- `BiliApi.repostDynamic` → `BiliRepository.repostDynamic(idStr, content)` → `x/dynamic/feed/create/dyn`（**走 jsonWriteRequest**，JSON body `dyn_req`）
- `BiliApi.relation(fid)` → `BiliRepository.relation(mid): Result<RelationStat>` → `x/relation?fid=<mid>`（**读接口**，非写；进 UP 空间页时单发一次，取返回的 `relation`/`attribute` 判断是否已关注）
- `BiliRepository.resolveDynamicCommentTarget(entry)` 返回 `(oid, type)`，参照 BiliPai 规则

#### 4.4.3 基础设施新增

- `:core:network` 新增 `internal/JsonWriteRequest.kt`（对齐07 工单 B6）：
  
  ```kotlin
  internal suspend fun <T> jsonWriteRequest(
      payloadJson: String,
      idempotentCodes: = emptySet(),
      block: suspend (csrf: String, body: RequestBody) -> BiliResponse<T>,
  ): Result<T?>
  ```
  - csrf 注入 query（不是 body）
  - 错误分流复用 `WriteOutcome` / `WriteError`
  - 走风控三件套 `authGate.awaitReady()` + 冷却闸门
- `BiliApi.repostDynamic(@Query("csrf"), @Body body: RequestBody)` + `BiliApi.likeDynamic(@Query("csrf"), @Body body: RequestBody)`
- 新增 DTO：`DynamicThumbRequest(dyn_id_str, up: Int)`、`DynamicRepostRequest(dyn_id_str, content, dyn_req_str?)`、`RelationStat(relation/attribute)`（`x/relation` 返回，判断关注态）

### 4.5 错误/登录/风控分支

- **关注/取关**：
  - 未登录：按钮跳登录页
  - `WriteError.NeedLogin` → 跳登录
  - `WriteError.Blocked (-352)` → toast「操作太频繁，请稍后再试」
  - `WriteError.CsrfInvalid (-111)` → 一次性刷新 cookie 重试，失败提示重登
  - `WriteError.Unknown(code)` → toast「关注失败：code」
- **点赞**：
  - 防抖 500ms
  - `WriteError.NeedLogin` → 提示登录
  - 其他错误：回滚乐观更新 + toast
- **评论**：
  - 提交时检查登录
  - `WriteError.CsrfInvalid` 走一次刷新重试
  - 提交成功后刷新评论列表（拉最近 5 条）
- **转发**：
  - 同评论
  - 乐观更新 forward.count，失败回滚
- **图片预览**：
  - 加载失败：保留当前页 + 显示「加载失败」小图标
  - GIF：失败回落静态图
- **未验证标注**：
  - `likeDynamic` 接口未经字节级对齐线上 JS，**上线前需真机抓包**验证 `up` 参数语义（`up=1` 点赞、`up=2` 取消）
  - `addDynamicReply` 用 type=17/11 需真机抓包确认 type 码

### 4.6 交互草图（ASCII）

**UP 空间页**：

```
 ┌──────────────────────────────┐
 │ [头像] UP名                  │
 │        签名        [关注]    │ ← 头部右侧关注按钮
 ├──────────────────────────────┤
 │ [投稿] [动态]                 │ ← 视图切换
 │ ─────────────────────────── │
 │ ┌──┐ 标题 1           ⋮    │ ← 三点按钮（复选态替换为复选框）
 │ │封│ UP · 时长               │
 │ └──┘ …                       │
 │ …                            │
 └──────────────────────────────┘
```

**动态页 - 图文动态**：

```
 ┌──────────────────────────────┐
 │ [头像] 老猫鱼不吃鱼          │
 │        8小时前                │
 │                              │
 │ 老郭在武汉的结果出来了        │ ← desc 修复后
 │ ┌──┐┌──┐┌──┐                 │
 │ │  ││  ││  │                 │ ← DrawGrid 9 宫格
 │ └──┘└──┘└──┘                 │
 │ ┌──┐┌──┐┌──┐                 │
 │ │  ││  ││+5│                 │
 │ └──┘└──┘└──┘                 │
 │ ─────────────────────────── │
 │ [↗转发 3]  [💬评论 352]  [♥点赞 605] │ ← ActionButton（点击响应）
 └──────────────────────────────┘
```

**图片全屏预览**：

```
 ┌──────────────────────────────┐
 │         1/9          [✕]    │
 ├──────────────────────────────┤
 │                              │
 │         [图片，双指缩放]      │ ← HorizontalPager + ZoomableImage
 │                              │
 ├──────────────────────────────┤
 │         [💾保存到相册]        │
 └──────────────────────────────┘
```

**转发对话框**：

```
 ┌──────────────────────────────┐
 │  ↗ 转发动态               [✕]│
 ├──────────────────────────────┤
 │ ┌──────────────────────────┐ │
 │ │ 说点什么吧…(可选)         │ │
 │ │                           │ │
 │ │                           │ │
 │ └──────────────────────────┘ │
 ├──────────────────────────────┤
 │                     [取消][转发] │
 └──────────────────────────────┘
```

**评论对话框**：

```
 ┌──────────────────────────────┐
 │  💬 评论                 [✕]│
 ├──────────────────────────────┤
 │ 昵称: 666                     │ ← 评论列表（最近 10 条）
 │ 时间: 1小时前                 │
 │ 评论内容…                     │
 │ ─────────────────────────── │
 │ 昵称: 333                     │
 │ …                             │
 ├──────────────────────────────┤
 │ ┌──────────────────────┐    │
 │ │ 写评论…                │    │ ← 输入框
 │ └──────────────────────┘    │
 │                     [发送]    │
 └──────────────────────────────┘
```

### 4.7 待开发 / 复用项清单

#### 4.7.1 新增文件

- `:app/src/main/java/com/biliseek/app/ui/components/DrawGrid.kt` —— 9 宫格
- `:app/src/main/java/com/biliseek/app/ui/components/ZoomableImage.kt` —— 单图缩放
- `:app/src/main/java/com/biliseek/app/ui/components/ImagePreviewDialog.kt` —— 全屏预览
- `:app/src/main/java/com/biliseek/app/ui/components/ActionButton.kt` —— 动态底栏按钮
- `:app/src/main/java/com/biliseek/app/ui/components/RepostDialog.kt` —— 转发输入框
- `:app/src/main/java/com/biliseek/app/ui/components/DynamicCommentDialog.kt` —— 评论 sheet
- `:core:network/src/main/java/com/biliseek/core/network/dto/DynamicDto.kt` 追加 `DynamicThumbRequest`、`DynamicRepostRequest`
- `:core:network/src/main/java/com/biliseek/core/network/internal/JsonWriteRequest.kt`（对齐07 工单 B6）
- `:core:network/src/main/java/com/biliseek/core/network/internal/JsonWriteRequestTest.kt`

#### 4.7.2 改动文件

- `:core:network/src/main/java/com/biliseek/core/network/BiliApi.kt`：
  - 追加 `likeDynamic` / `addDynamicReply` / `repostDynamic` 接口
  - `repostDynamic` 改 JSON body
- `:core:network/src/main/java/com/biliseek/core/network/BiliRepository.kt`：
  - 追加 `likeDynamic` / `addDynamicReply` / `repostDynamic` / `resolveDynamicCommentTarget` / `toggleRelation`
- `:core:network/src/main/java/com/biliseek/core/network/DynamicFeed.kt`：
  - `DynamicEntry` 加 `pics: List<String>`
  - `parseDynamicItem` DRAW/OPUS/ARTICLE 分支修复 desc 来源 + pics 列表
- `:core:network/src/main/java/com/biliseek/core/network/dto/DynamicDto.kt`：
  - `DynamicOpus.pics` 已存在，补充字段映射
- `:app/src/main/java/com/biliseek/app/AppViewModel.kt`：
  - 新增 `toggleRelation` / `likeDynamic` / `repostDynamic` / `addDynamicReply` / `openRepostDialog` / `openCommentDialog` / `openImagePreview` + 关闭函数
  - 新增 `_followingMid` / `_repostDialog` / `_commentDialog` / `_previewImage`
- `:app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt`：
  - 头部加关注按钮
  - `PostRow` 加 `trailing` 插槽 + 三点按钮 + 复选态
  - 投稿列表接入 `SelectionToolbar`（复用第 3 章）
  - 多 P 投稿 → `MultiPagePickerScreen`（复用第 3 章）
- `:app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt`：
  - `DynamicCard` 改用 `DrawGrid`（多图）+ `ImagePreviewDialog`（预览）
  - `DynamicActionText` 改 `ActionButton`（转发 / 评论 / 点赞回调）
- `:app/src/main/java/com/biliseek/app/MainActivity.kt`：
  - 注入动态相关回调
  - 关注按钮回调

#### 4.7.3 复用

- 第 3 章 `SelectionToolbar` / `MultiPagePickerScreen`
- 第 3 章 `MultiPagePickerScreen` 的 `PendingAction` 枚举
- BiliPai `DynamicInteractionPolicy.resolveDynamicCommentTarget` 逻辑（复写为 Kotlin）
- BiliPai `DrawGridV2` 的布局策略（参考但不复制源代码）

#### 4.7.4 行为约束

- 关注/点赞/转发/评论前必须登录（复用 `session.value.isLoggedIn`）
- 点赞防抖：500ms 内重复点击只发一次请求
- 图片预览：暗黑背景 + 沉浸式状态栏
- 多图懒加载：`HorizontalPager` 邻近页预加载 1 页
- 关注按钮乐观更新失败必须回滚本地状态
- `jsonWriteRequest` 错误码与 `writeRequest` 一致（`-101/-111/-352/-412`）

### 4.8 验证

#### 4.8.1 离线

- `./gradlew :app:assembleDebug` 编译通过
- `./gradlew :core:network:test` 新增：
  - `JsonWriteRequestTest` —— csrf 注入、错误分流、冷却闸门
  - `DynamicFeedParseTest` —— DRAW/OPUS/ARTICLE 多图 + desc 来源验证
  - `DynamicCommentTargetTest` —— oid/type 分流规则

#### 4.8.2 真机（仅真人）

- **图文动态 desc**：刷动态 → DRAW 动态能看到文案 + 9 宫格图片
- **图片预览**：点图 → 全屏可缩放、可滑动切换、双击复位
- **多图角标**：≥10 张图片动态 → 第 9 张显示 `+N`
- **关注按钮**：未关注 → 点击 → 关注成功；再点 → 取关成功
- **点赞**：点击 → 计数 +1、图标填充；再点 → 计数 -1
- **点赞防抖**：连点 5 次 → 只发 1 次请求
- **评论**：打开评论 sheet → 输入评论 → 提交 → 评论数 +1
- **评论目标**：DRAW 动态评论走 type=11；OPUS 动态评论走 type=17
- **转发**：点转发 → 输入内容 → 提交 → 转发数 +1
- **未登录场景**：点赞/评论/转发/关注 → 跳登录页
- **风控**：触发 `-352` → toast「操作太频繁」，按钮回滚
- **jsonWriteRequest 错误分流**：mock `-111` → 刷新 cookie 重试；mock `-412` → 触发凭据重种

### 4.9 风险与未决

1. **`likeDynamic` 接口 `up` 参数语义**：BiliPai 用 `up=1` 点赞、`up=2` 取消，B 站部分端点恰好相反。**真机抓包验证前不强制 trust**
2. **`addDynamicReply` type=17/11 适用边界**：参考 BiliPai 但需真机验证 DRAW 动态 type=11 是否被服务端接受
3. **转发 JSON body 结构**：`dyn_req_str` 字段由 `JSON.stringify({...})` 生成，结构复杂易变，**以当日抓包为准**
4. **`jsonWriteRequest` 与 `writeRequest` 的 csrf 位置差异**：csrf 必须在 query 而非 body，否则服务端 `-111`
5. **图片预览的进入动画**：`Rect` 转 `offset` 需 `LookaheadScope` 或测量后延迟，复杂度高，本工单做简化版本（直接全屏切换）
6. **评论列表懒加载**：BiliPai 用 `Pager` + 分页，本工单只做首次拉 10 条 + 提交后局部追加，不做翻页
7. **未登录点赞/评论/转发**：B 站服务端可能直接拒绝（不报错但无效），需前端拦截
8. **关注按钮状态来源（定案）**：`dynamicSpace` 返回**不含**关系字段，`followings()` 列表分页/有上限、判断不可靠。定为**进 UP 空间页时单发一次 `x/relation?fid=<mid>`**（读接口）取关注态，新增 `BiliApi.relation` + `RelationStat` DTO。用户主动进空间页才触发，不违反高频铁律。
9. **多图加载性能**：9 张图同时加载可能 OOM，需用 Coil 的 `crossfade(false)` + 缩略图占位（200×200），避免大图阻塞
10. **DRAW 多图与 BiliPai OPUS 详情富文本渲染冲突**：BiliPai 详情页用 `OpusContentBlock` 解析，列表页用九宫格。本工单只做列表页九宫格，详情页暂不做（不在本章范围）

---

后续章节按用户页面讨论顺序继续追加。

---

## 5. MiniPlayer 改造：可拖动进度条 + 播放列表按钮 + 风格切换

> 本章覆盖三处变更：
> 
> - **minibar 与展开态的进度条均可拖动**（当前 minibar 用 `LinearProgressIndicator` 不可拖）
> - **minibar 右侧新增播放列表按钮**，弹出底部 sheet 显示 ExoPlayer 队列
> - **minibar 样式两种风格可切换**：FLOAT（仿 BBPlayer，浮在底部上）+ BOTTOM（贴底）
> - 对齐 BBPlayer `NowPlayingBar.tsx` 的交互范式

### 5.1 目标效果

#### 5.1.1 进度条可拖动

- minibar（COLLAPSED 态）与展开态（PLAYER route）共用一个 `DraggableProgressBar` 组件：
  - `Slider` 实现：拖动期间用本地 `dragValue` 状态，不下发 seek
  - `onValueChangeFinished` 时下发 `onSeek(dragValue.toLong())`
  - 高度：minibar 2dp / 展开态 16dp（沿用 `PlayerScreen.kt:ProgressBar`）
  - 拖动时悬浮显示当前时刻（沿用 `ProgressBar` 已有逻辑）
- 行为约束：
  - minibar 拖动时不触发「上滑跳播放页」手势（panGesture 互斥）
  - minibar 拖动时不触发水平 pan「上一首 / 下一首」手势
- **不引入新的 PlayerConnection API**（`seekTo` 已存在，见 `PlayerConnection.kt` 调用链）

#### 5.1.2 播放列表按钮

- minibar 右侧加 `IconButton(icon = 'format-list-bulleted')`，位置在「播放/暂停」按钮**右侧**：
  
  ```
  ┌──────────────────────────────────────────┐
  │ ┌──┐ 标题                          ▶  ☰ │
  │ │封│ 作者 · 时长                        │
  │ └──┘                                       │
  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │
  └──────────────────────────────────────────┘
  ```
- 点击 → 弹出底部 sheet `PlayerQueueSheet`
- 展开态（PLAYER route）已有「队列面板」，**不重复**显示按钮（沿用 `PlayerScreen.kt` 的 `QueuePanel`）

#### 5.1.3 `PlayerQueueSheet`（新建）

- 底部 sheet，`ModalBottomSheet` 实现
- 顶部标题：「播放队列 · N 首」
- 列表条目（极简）：
  - 序号（01 / 02 / ...）
  - 标题（单行省略）
  - 时长（右对齐）
  - **当前播放高亮**：序号与标题用主题色 `colorScheme.primary`，前缀「✓ 当前播放」标记
- 点击条目 → `onQueueSkipTo(index)` → 跳到该 P
- 底部「清空队列」按钮（**本轮做**）：点击 → `onClearQueue()` → **保留当前播放、清掉其余**，`queueIndex` 归 0；清空后 sheet 显示「只有当前这一首」，当前播放不打断
- **空队列**显示「队列为空」+「从搜索结果加入」提示
- **列表为空 vs 队列为空**：
  - 当前播放为空（`state.mode == IDLE`）：sheet 不弹出
  - 队列为空（`queue.isEmpty()` 但 `state.mode != IDLE`）：sheet 显示「只有当前这一首」

#### 5.1.4 样式设置项（FLOAT / BOTTOM）

- `AppSettings.miniBarStyle: MiniBarStyle`（枚举）：
  - `FLOAT`（默认）：圆角 24dp、左右 margin 16dp、阴影 elevation=7、高度约 70dp（仿 BBPlayer）
  - `BOTTOM`：贴底、圆角顶 24dp、阴影 elevation=0、高度约 70dp（保留 BiliSeek 现状）
- FLOAT 样式示意：
  
  ```
  ┌──────────────────────────────────────────┐
  │ ┌──┐ 标题                          ▶  ☰ │ ← 圆角 24dp / elevation=7
  │ │封│ 作者 · 时长                        │
  │ └──┘                                       │
  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │
  └──────────────────────────────────────────┘
        ↑ 距底 16dp margin / 左右 16dp margin
  ```
- BOTTOM 样式示意（与现状一致）：
  
  ```
  ┌──────────────────────────────────────────┐
  │ ┌──┐ 标题                          ▶  ☰ │ ← 贴底，圆角顶 24dp
  │ │封│ 作者 · 时长                        │
  │ └──┘                                       │
  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │
  └──────────────────────────────────────────┘
  ```
- 设置页 `SettingsScreen` 加切换项（SegmentedButton 二选一）

### 5.2 适用入口与触发动作

- **minibar 进度条拖动**：
  - `onSeek(positionMs)` 回调 → `AppViewModel.seekTo(positionMs)`（已存在，见 `PlayerConnection.seekTo`）
- **播放列表按钮点击**：
  - `onOpenQueue()` 回调 → `AppViewModel.openQueueSheet()`
- **队列条目点击**：
  - `onQueueSkipTo(index)` 回调 → `AppViewModel.queueSkipTo(index)`（已存在）
- **样式切换**：
  - 设置页 `SettingsScreen` `onMiniBarStyleChange` → `AppViewModel.setMiniBarStyle(style)`

### 5.3 状态模型

#### 5.3.1 `AppViewModel` 新增

- `_queueSheetOpen: MutableStateFlow<Boolean>`（是否显示播放列表 sheet）
- `queueSheetOpen: StateFlow<Boolean>`
- `openQueueSheet()` / `closeQueueSheet()`
- `clearQueue()`（透传 `connection.clearQueue()`）

#### 5.3.2 `AppSettings` 新增字段

- `miniBarStyle: MiniBarStyle`（默认 `FLOAT`）
- `MiniBarStyle { FLOAT, BOTTOM }`

#### 5.3.3 `SettingsStore` 持久化

- DataStore Preferences key：`"mini_bar_style"`（字符串：FLOAT / BOTTOM）
- 已有 `SettingsStore.state: StateFlow<AppSettings>`，增字段后兼容旧数据（默认 FLOAT）

#### 5.3.4 `BiliSeekDatabase` 迁移

- 数据库 schema v4→v5：
  - `settings` 表加 `mini_bar_style TEXT NOT NULL DEFAULT 'FLOAT'`
  - `MIGRATION_4_5`：`ALTER TABLE settings ADD COLUMN mini_bar_style TEXT NOT NULL DEFAULT 'FLOAT'`
  - `schemas/com.biliseek.app.data.BiliSeekDatabase/5.json` 同步更新

### 5.4 调用的 `BiliRepository` 方法

- 不涉及新**网络**接口；新增一个播放器本地 API：
  - `PlayerConnection.seekTo(positionMs)`（已存在）
  - `PlayerConnection.queueSkipTo(index)`（已存在）
  - `PlayerConnection.queue` / `queueIndex`（已暴露在 `PlaybackState.queue`）
  - `PlayerConnection.clearQueue()`（**新增**：保留当前播放、清其余，`queueIndex` 归 0）

### 5.5 错误/登录/风控分支

- 本节**不涉及网络请求**；纯本地播放控制 + UI 状态
- 队列条目点击 `queueSkipTo(index)`：
  - `index < 0` 或 `index >= queue.size` → 静默忽略，记录日志
  - `index == queueIndex`（已播放） → 静默忽略
- `seekTo(positionMs)`：
  - `positionMs < 0` → 钳制为 0
  - `positionMs > durationMs` → 钳制为 `durationMs`
  - `durationMs == 0`（未加载完成） → 静默忽略

### 5.6 交互草图（ASCII）

**minibar FLOAT 样式**：

```
 ┌──────────────────────────────────────────┐
 │  列表 tab                                  │
 │  …                                          │
 │  ┌──────────────────────────────────────┐ │
 │  │ ┌──┐ 标题                    ▶  ☰ │ │ ← 圆角 24dp、阴影 elevation=7
 │  │ │封│ 作者 · 03:24 / 17:14 · 听      │ │ ← 左 16dp margin、右 16dp margin
 │  │ └──┘                                   │ │
 │  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │ │ ← Slider 可拖动
 │  └──────────────────────────────────────┘ │
 │  ↑ 距底 16dp                                 │
 │  [搜]  [动]  [列]  [设]                      │
 └──────────────────────────────────────────┘
```

**minibar BOTTOM 样式**：

```
 ┌──────────────────────────────────────────┐
 │  列表 tab                                  │
 │  …                                          │
 │  ┌──────────────────────────────────────┐ │
 │  │ ┌──┐ 标题                    ▶  ☰ │ │ ← 贴底、圆角顶 24dp
 │  │ │封│ 作者 · 03:24 / 17:14 · 听      │ │
 │  │ └──┘                                   │ │
 │  │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │ │
 │  └──────────────────────────────────────┘ │
 │  [搜]  [动]  [列]  [设]                      │
 └──────────────────────────────────────────┘
```

**`PlayerQueueSheet`**：

```
 ┌──────────────────────────────────────────┐
 │  播放队列 · 5 首                [✕]      │ ← 标题 + 关闭按钮
 ├──────────────────────────────────────────┤
 │ ✓ 01  邓紫棋歌曲120首精选     17:14     │ ← 当前播放高亮（主色）
 │    02  Hi-Res无损GEM歌曲       06:42    │
 │    03  在百万豪宅录音棚听       01:59    │
 │    04  …                                │
 │    05  …                                │
 ├──────────────────────────────────────────┤
 │  [清空队列]                                │ ← 本轮做：保留当前播放、清其余
 └──────────────────────────────────────────┘
```

**进度条拖动状态**：

```
 ┌──────────────────────────────────────────┐
 │ ┌──┐ 标题                    ▶  ☰ │
 │ │封│ 作者 · 07:42 / 17:14 · 听       │ ← 拖动期间显示「当前位置」
 │ └──┘                                   │
 │ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 2dp │ ← Slider 中间滑块
 │ ┃                                      │
 │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━      │ ← 背景轨道
 └──────────────────────────────────────────┘
```

### 5.7 待开发 / 复用项清单

#### 5.7.1 新增文件

- `:app/src/main/java/com/biliseek/app/ui/components/DraggableProgressBar.kt`
  - 入参：`positionMs: Long`, `durationMs: Long`, `onSeek: (Long) -> Unit`, `height: Dp = 2.dp`
  - 内部用 `Slider`，拖动期间本地 `dragValue` 状态
  - `onValueChangeFinished` 时调 `onSeek`
- `:app/src/main/java/com/biliseek/app/ui/components/PlayerQueueSheet.kt`
  - 入参：`queue: List<PlaybackRequest>`, `queueIndex: Int`, `onSkipTo: (Int) -> Unit`, `onClearQueue: () -> Unit`, `onDismiss: () -> Unit`
  - 内部用 `ModalBottomSheet` + `LazyColumn`
- `:app/src/main/java/com/biliseek/app/ui/MiniBarStyle.kt`（enum）

#### 5.7.2 改动文件

- `:app/src/main/java/com/biliseek/app/data/AppSettings.kt`：
  - 加 `miniBarStyle: MiniBarStyle = FLOAT`
- `:app/src/main/java/com/biliseek/app/data/SettingsStore.kt`：
  - `load()` / `save()` 加 `mini_bar_style` 字段
  - 默认 FLOAT
- `:app/src/main/java/com/biliseek/app/data/BiliSeekDatabase.kt`：
  - schema 版本 4 → 5
  - 加 `MIGRATION_4_5`：`ALTER TABLE settings ADD COLUMN mini_bar_style TEXT NOT NULL DEFAULT 'FLOAT'`
- `:core:player/src/main/java/com/biliseek/core/player/PlayerConnection.kt`：
  - 新增 `fun clearQueue()`：保留 `queue[queueIndex]`（当前播放）、移除其余，`queueIndex` 归 0，复用 `persistQueue()`；不打断当前播放。空队列 / 单首时为 no-op
- `:app/src/main/java/com/biliseek/app/AppViewModel.kt`：
  - 新增 `_queueSheetOpen: MutableStateFlow<Boolean>`、`queueSheetOpen: StateFlow<Boolean>`
  - 新增 `openQueueSheet()` / `closeQueueSheet()`
  - 新增 `clearQueue()`（透传 `connection.clearQueue()`）
  - 新增 `setMiniBarStyle(style: MiniBarStyle)` 持久化设置
- `:app/src/main/java/com/biliseek/app/ui/PlayerContainer.kt`（MiniPlayer 部分）：
  - 替换 `LinearProgressIndicator` 为 `DraggableProgressBar`
  - 在 `IconButton(onClick = onTogglePlayPause)` 后增加 `IconButton(onClick = onOpenQueue)`（`Icons.Default.QueueMusic`）
  - 引入 `miniBarStyle` 参数，根据 `FLOAT` / `BOTTOM` 切换外层 `Surface` 样式：
    - FLOAT：`Modifier.padding(horizontal = 16.dp).shadow(elevation = 7.dp, shape = RoundedCornerShape(24.dp))`，margin bottom = 16dp
    - BOTTOM：贴底、圆角顶 24dp、无 shadow
  - `AppShell` 注入 `miniBarStyle` 与 `onOpenQueue`
- `:app/src/main/java/com/biliseek/app/ui/PlayerContainer.kt`（PlayerScreen 部分）：
  - `ProgressBar` 替换为 `DraggableProgressBar`（复用同一组件，height=16.dp）
- `:app/src/main/java/com/biliseek/app/ui/AppShell.kt`：
  - 注入 `onOpenQueue: () -> Unit` 到 `PlayerContainer`
  - 状态订阅：`queueSheetOpen` 为 true 时渲染 `PlayerQueueSheet`
- `:app/src/main/java/com/biliseek/app/ui/SettingsScreen.kt`：
  - 加 `MiniBarStyle` 切换（SegmentedButton 二选一：「浮在底部」/「贴底」）
  - `onMiniBarStyleChange: (MiniBarStyle) -> Unit` 回调
- `:app/src/main/java/com/biliseek/app/MainActivity.kt`：
  - 注入 `onOpenQueue` / `onCloseQueue` 到 `AppShell`
  - 注入 `onMiniBarStyleChange` 到 `SettingsScreen`
  - 状态订阅：`viewModel.queueSheetOpen` + `connection.state.queue` 渲染 `PlayerQueueSheet`

#### 5.7.3 复用

- `Slider`（Compose Material3）实现可拖动进度条
- `ModalBottomSheet`（Compose Material3）实现底部 sheet
- `connection.state.queue` / `queueIndex`（`PlaybackState` 已有字段）
- `connection.seekTo` / `connection.queueSkipTo`（已存在）

#### 5.7.4 行为约束

- minibar 拖动时不触发「上滑跳播放页」手势（现有 `detectVerticalDragGestures` 加 `activeOffsetY` 阈值或 `pointerInput` 分区）
- 播放列表按钮点击时不跳播放页（仅弹 sheet）
- FLOAT 样式下阴影在暗黑主题需调整颜色（`shadowColor = Color.Black.copy(alpha = 0.3f)`）
- 队列为空时 sheet 显示「队列为空」占位文案
- 当前播放高亮条目点击时**不**重新 seek（同位置），仅关闭 sheet
- `miniBarStyle` 切换实时生效（不需重启）

### 5.8 验证

#### 5.8.1 离线

- `./gradlew :app:assembleDebug` 编译通过
- 数据库迁移测试：v4 → v5 不丢旧数据
- `MiniBarStyle` 枚举单测（FLOAT / BOTTOM 默认值）
- `./gradlew :core:player:test` 新增 `ClearQueueTest`（用假 `PlayerConnection`：多首队列 `clearQueue()` 后只剩当前播放、`queueIndex == 0`；空队列/单首为 no-op）

#### 5.8.2 真机（仅真人）

- **进度条可拖**：
  - minibar 拖动 → 跳转到对应位置（不是整首跳完）
  - 展开态拖动 → 同上
  - 拖动期间本地状态正确显示，松开后才下发 seek
- **播放列表按钮**：
  - minibar 右侧 ☰ 按钮可见 → 点击 → 弹出底部 sheet
  - sheet 显示当前队列（序号 + 标题 + 时长）
  - 当前播放条目 ✓ + 主色高亮
  - 点击非当前条目 → 跳到该 P
  - 点击当前条目 → 仅关闭 sheet
  - 点「清空队列」→ 当前这首继续放，其余移除，sheet 显示「只有当前这一首」
  - sheet 关闭手势：下滑 / 点空白 / 系统返回键
- **样式切换**：
  - 设置页切换 FLOAT → minibar 浮在底部上、左右 margin、阴影
  - 设置页切换 BOTTOM → minibar 贴底、无阴影
  - 切换实时生效（不需重启）
- **手势互斥**：
  - minibar 拖动进度条时不跳播放页
  - minibar 横向滑动仍可触发「上一首 / 下一首」（如果队列有）
- **未登录场景**：未登录时队列为空，sheet 显示「队列为空」

### 5.9 风险与未决

1. **minibar 拖动手势与上滑跳播放页冲突**：`detectVerticalDragGestures` 与 `Slider` 拖动手势可能重叠，需用 `pointerInput` 分区或 `Modifier.pointerInteropFilter`
2. **FLOAT 阴影在 iOS-like 暗黑主题下可能过暗**：需根据 `isSystemInDarkTheme()` 调整 `shadowColor` 透明度
3. **数据库迁移兼容**：老用户（数据库版本 ≤4）升级后默认 FLOAT，与新用户一致；可接受
4. **队列为空时 sheet 不应弹出**：需在 `MiniPlayer` 加 `_queueSheetOpen` 状态检查，避免空队列时弹出空 sheet
5. **`PlayerQueueSheet` 的 `PlaybackRequest.Bili` 字段展示**：当前 `PlaybackRequest`（`PlaybackModels.kt`）包含 `bvid` / `cid` / `title` / `coverUrl` / `author` / `durationSec`；展示用 `title` + `durationSec`（`formatDuration`）足够
6. **`Slider` 在 minibar 2dp 高度下的拖动手感**：2dp 高度触摸区可能偏小，需用 `Modifier.height(2.dp)` + `wrapContentSize` + 内部 24dp 触摸区扩大
7. **miniBarStyle 切换的实时生效**：`AppSettings` 是 `StateFlow`，`SettingsStore.state` 是 hot flow；`AppShell` 订阅 `settings.miniBarStyle` 即可实时切换（无需手动通知）
8. **当前播放条目在 sheet 内的自动滚动**：sheet 弹出时若当前播放不在可视范围，自动 scrollTo；可选功能

---

后续章节按用户页面讨论顺序继续追加。
