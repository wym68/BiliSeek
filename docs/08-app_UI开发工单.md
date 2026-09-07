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
  - **清空之后再滑一次** → 走 Activity 默认 pop：此时搜索框为空、不自动聚焦、不弹键盘，让系统决定退到桌面
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

| 状态 | 提示位置 | 文案 |
| --- | --- | --- |
| 顶部加载下一页失败 | 顶部红条 | 复用现有 `errorMessage` |
| 整页失败（首屏） | 全屏 ErrorPanel | 同上 |
| 触发 `SearchError.Blocked` | 顶部红条 | 「请求太频繁，B 站暂时拦截了搜索」+ 提示「过几分钟再试」 |
| 返回清空后无历史 | 聚焦弹层中央 | 现有「暂无搜索历史」 |

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

> 备注：当前 `onKeywordChange("")` 把 `SearchUiState` 整体重置（`AppViewModel.kt:979`），等于 keyword/results/errors/pages 全清。如果你希望「清空 keyword 后保留上次搜索结果」，需要在 ViewModel 拆出只清 keyword 的入口（例：`fun clearKeywordOnly()`）。请确认。

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

