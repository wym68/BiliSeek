# 动态页 UP主 关注栏改造计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将动态页的 UP主 筛选 chips 改造为 B 站风格的"最常访问"头像栏 + "更多"按钮跳转关注列表页，并修复登录/关注/取关后关注列表不自动刷新的问题。

**Architecture:** 5 文件改动：ViewModel 添加刷新机制、DynamicFilterBar 重构 UI、新建关注列表页、AppShell 添加路由、MainActivity 绑定回调。

**Tech Stack:** Kotlin, Jetpack Compose, Coil, Material3

---

### Task 1: 修复关注列表刷新机制（AppViewModel.kt）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt:177-196, 773-794, 1619-1640`

- [ ] **Step 1: 修改 `initDynamic()` 增加 `forceRefresh` 参数**

在 `AppViewModel.kt:773-794`，修改 `initDynamic()` 方法：

```kotlin
/** 登录后首次进入动态 tab 时调用：拉关注列表 + 第一页动态。 */
fun initDynamic(forceRefresh: Boolean = false) {
    if (!session.value.isLoggedIn) return
    val cur = _dynamic.value
    if (cur.initState is DynamicInitState.Loading) return
    if (!forceRefresh && (cur.upChips.isNotEmpty() || cur.feed.items.isNotEmpty())) return
    viewModelScope.launch {
        _dynamic.value = cur.copy(initState = DynamicInitState.Loading)
        network.repository.followings()
            .onSuccess { ups ->
                _dynamic.value = _dynamic.value.copy(
                    upChips = ups,
                    initState = DynamicInitState.Idle,
                )
                loadDynamicPage(offset = "")
            }
            .onFailure {
                _dynamic.value = _dynamic.value.copy(
                    initState = DynamicInitState.Error(it.message ?: "加载失败"),
                )
            }
    }
}
```

- [ ] **Step 2: 新增 `refreshUpChips()` 方法**

在 `AppViewModel.kt` 动态部分（约第 815 行后）添加：

```kotlin
/** 强制刷新关注列表（无视缓存），关注/取关成功后调用。 */
private fun refreshUpChips() = viewModelScope.launch {
    network.repository.followings()
        .onSuccess { ups ->
            _dynamic.value = _dynamic.value.copy(upChips = ups)
        }
}
```

- [ ] **Step 3: 修改 `toggleRelation()` 成功后刷新关注列表**

在 `AppViewModel.kt:1619-1640`，在 `onSuccess` 块中添加 `refreshUpChips()` 调用：

```kotlin
network.repository.modifyRelation(mid, follow)
    .onSuccess {
        _upSpace.value = _upSpace.value.copy(followingLoading = false)
        emitMessage(if (follow) "已关注" else "已取消关注")
        // 关注/取关成功后刷新动态页的关注列表
        refreshUpChips()
    }
```

- [ ] **Step 4: 在 init 块添加登录状态监听**

在 `AppViewModel.kt` 的 init 块中添加（找到 `init {` 块，在末尾追加）：

```kotlin
// 监听登录状态，登录成功后自动刷新动态页关注列表
viewModelScope.launch {
    session.collect { s ->
        if (s.isLoggedIn) {
            initDynamic(forceRefresh = true)
        }
    }
}
```

**验证：** 编译通过，无语法错误。

---

### Task 2: 重构 DynamicFilterBar UI（DynamicScreen.kt）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:56-88, 232-276`

- [ ] **Step 1: 给 `DynamicScreen` 添加 `onOpenFollowList` 参数**

在 `DynamicScreen` 函数签名中（第 86 行附近），添加参数：

```kotlin
onOpenFollowList: () -> Unit = {},
```

- [ ] **Step 2: 将 `onOpenFollowList` 传递给 `DynamicFilterBar`**

在 `DynamicScreen` 内部调用 `DynamicFilterBar` 处（约第 143-149 行），添加参数：

```kotlin
DynamicFilterBar(
    typeFilter = state.typeFilter,
    upChips = state.upChips,
    selectedUpMid = state.selectedUpMid,
    onTypeFilterChange = onTypeFilterChange,
    onSelectUp = onSelectUp,
    onOpenFollowList = onOpenFollowList,  // 新增
)
```

- [ ] **Step 3: 重构 `DynamicFilterBar` 函数**

替换 `DynamicFilterBar` 函数（第 232-276 行）为：

```kotlin
@Composable
private fun DynamicFilterBar(
    typeFilter: String,
    upChips: List<FollowingUp>,
    selectedUpMid: Long?,
    onTypeFilterChange: (String) -> Unit,
    onSelectUp: (FollowingUp?) -> Unit,
    onOpenFollowList: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(
                selected = typeFilter == DynamicType.ALL,
                onClick = { onTypeFilterChange(DynamicType.ALL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("全部") }
            SegmentedButton(
                selected = typeFilter == DynamicType.VIDEO,
                onClick = { onTypeFilterChange(DynamicType.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("仅视频") }
        }

        if (upChips.isNotEmpty()) {
            // 标题行：最常访问 + 更多按钮
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最常访问",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenFollowList) {
                    Text("更多 >")
                }
            }

            // UP 头像横滑列表
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(upChips.take(6)) { up ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSelectUp(up) },
                    ) {
                        AsyncImage(
                            model = up.face,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            up.uname,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 修改下拉刷新为强制刷新**

在 `MainActivity.kt` 中 `DynamicScreen` 调用处，修改 `onRefresh` 回调：

```kotlin
onRefresh = { viewModel.initDynamic(forceRefresh = true) },
```

**验证：** 编译通过，动态页 UI 显示头像 + "最常访问" + "更多 >"。

---

### Task 3: 新建关注列表页面（FollowingListScreen.kt）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/ui/FollowingListScreen.kt`

- [ ] **Step 1: 创建 FollowingListScreen.kt**

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.biliseek.core.network.dto.FollowingUp

/**
 * 关注列表页。
 *
 * 显示用户关注的所有 UP 主，点击进入对应 UP 的空间页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingListScreen(
    followings: List<FollowingUp>,
    onSelectUp: (FollowingUp) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的关注") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(followings) { up ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectUp(up) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = up.face,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        up.uname,
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
```

**验证：** 编译通过，文件无语法错误。

---

### Task 4: 添加导航路由（AppShell.kt）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/AppShell.kt:34-45, 105-110, 197-236`

- [ ] **Step 1: 在 Destination 枚举中添加 FOLLOWING_LIST**

在 `AppShell.kt:34-45`，添加新路由：

```kotlin
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    DYNAMIC("dynamic", "动态"),
    LIBRARY("library", "列表"),
    SETTINGS("设置"),
    PLAYER("player", "播放器"),
    UP_SPACE("upSpace", "UP主页"),
    FOLLOWING_LIST("followingList", "关注列表"),
}
```

- [ ] **Step 2: 给 AppShell 添加 followingListContent 参数**

在 `AppShell` 函数签名中（约第 110 行），添加参数：

```kotlin
upSpaceContent: @Composable (onBack: () -> Unit) -> Unit = { },
followingListContent: @Composable (onBack: () -> Unit) -> Unit = { },
```

- [ ] **Step 3: 在 NavHost 中添加 FOLLOWING_LIST 路由**

在 `AppShell.kt` 的 `NavHost` 中（约第 235 行后），添加：

```kotlin
composable(Destination.FOLLOWING_LIST.route) {
    followingListContent({ navController.popBackStack() })
}
```

- [ ] **Step 4: 在底部导航栏中排除 FOLLOWING_LIST**

在 `AppShell.kt:176-177`，修改排除逻辑：

```kotlin
if (dest == Destination.PLAYER || dest == Destination.UP_SPACE || dest == Destination.FOLLOWING_LIST) return@forEach
```

**验证：** 编译通过，导航路由正确注册。

---

### Task 5: 绑定 MainActivity 回调（MainActivity.kt）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:160, 189, 226-255, 439-485`

- [ ] **Step 1: 声明 `openFollowingListRequests` 信号**

在 `MainActivity.kt` 中（约第 160 行，与 `openUpSpaceRequests` 声明处），添加：

```kotlin
val openFollowingListRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
```

- [ ] **Step 2: 将信号传递给 AppShell**

在 `AppShell` 调用处（约第 189 行），添加参数：

```kotlin
openFollowingListRequests = openFollowingListRequests,
```

- [ ] **Step 3: 给 DynamicScreen 传递 onOpenFollowList 回调**

在 `MainActivity.kt` 中 `DynamicScreen` 调用处（约第 226-255 行），添加回调：

```kotlin
DynamicScreen(
    // ... 现有参数 ...
    onOpenFollowList = {
        openFollowingListRequests.tryEmit(Unit)
    },
    // ... 其他参数 ...
)
```

- [ ] **Step 4: 给 AppShell 添加 followingListContent 回调**

在 `AppShell` 调用处（约第 183 行），添加参数：

```kotlin
AppShell(
    // ... 现有参数 ...
    followingListContent = { onBack ->
        BackHandler(onBack = { onBack() })
        FollowingListScreen(
            followings = dynamic.upChips,
            onSelectUp = { up ->
                viewModel.openUpSpaceFromFollowing(up)
                openUpSpaceRequests.tryEmit(Unit)
                onBack()
            },
            onBack = onBack,
        )
    },
    // ... 其他参数 ...
)
```

- [ ] **Step 5: 在 AppViewModel 中添加 `openUpSpaceFromFollowing()` 方法**

在 `AppViewModel.kt` 动态部分（约第 815 行后），添加：

```kotlin
/** 从关注列表页点击 UP 主 → 打开 UP 空间页。 */
fun openUpSpaceFromFollowing(up: FollowingUp) {
    openUpSpace(
        SearchUser(mid = up.mid, uname = up.uname, upic = up.face)
    )
}
```

**验证：** 编译通过，点击"更多 >"跳转关注列表页，点击 UP 主跳转空间页。

---

### Task 6: 最终验证

- [ ] **Step 1: 编译检查**

运行: `./gradlew assembleDebug`

预期: BUILD SUCCESSFUL

- [ ] **Step 2: UI 验证清单**

| 验证项 | 预期结果 |
|--------|----------|
| 登录后动态页 | 自动加载关注列表，显示头像栏 |
| "最常访问"标题 | 正确显示 |
| "更多 >"按钮 | 点击跳转关注列表页 |
| UP 头像显示 | 圆形头像，48dp |
| UP 头像点击 | 跳转 UP 空间页 |
| 关注列表页 | 显示全部关注的 UP |
| 关注列表页点击 | 跳转对应 UP 空间页 |
| 返回手势 | 关注列表页返回动态页 |
| 下拉刷新 | 强制刷新关注列表 + 动态 |
| 关注/取关 | 返回动态页后列表已更新 |
| 未关注时 | 隐藏"最常访问"栏 |

---

### 交互流程图

```
┌─────────────────────────────────────────────────────────────┐
│                        动态页                                │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  [√ 全部]  [仅视频]                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 最常访问                                        更多 > │   │
│  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐  │   │
│  │  │ 🖼️ │ │ 🖼️ │ │ 🖼️ │ │ 🖼️ │ │ 🖼️ │ │ 🖼️ │  │   │
│  │  │名字1│ │名字2│ │名字3│ │名字4│ │名字5│ │名字6│  │   │
│  │  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│  ──────────────────────────────────────────────────────── │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 章北海official · 3小时前                             │   │
│  │ [视频封面] 【改革开放史】1978                         │   │
│  │ 3.6万播放 · 2弹幕                                    │   │
│  │ 3 转发    29 评论    0 点赞                           │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 老猫鱼不吃鱼 · 5小时前                                │   │
│  │ 不是，都已经上升到"名讳"的高度了吗...                   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
         │                                      │
         │ 点击头像/名字                         │ 点击"更多 >"
         ▼                                      ▼
┌─────────────────────┐            ┌─────────────────────────┐
│     UP空间页         │            │      关注列表页          │
│  (现有 UpSpaceScreen) │            │  ┌─────────────────┐   │
│                       │            │  │ ← 我的关注       │   │
│                       │            │  ├─────────────────┤   │
│                       │            │  │ 🖼️ 小黛晨读     │   │
│                       │            │  │ 🖼️ 随风说       │   │
│                       │            │  │ 🖼️ IT咖啡馆     │   │
│                       │            │  │ 🖼️ 王哥我想玩户外 │   │
│                       │            │  │ 🖼️ ...          │   │
│                       │            │  └─────────────────┘   │
└─────────────────────┘            └─────────────────────────┘
                                          │
                                          │ 点击UP主
                                          ▼
                                ┌─────────────────────┐
                                │     UP空间页         │
                                └─────────────────────┘
```
