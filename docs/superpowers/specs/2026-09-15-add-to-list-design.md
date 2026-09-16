# BiliSeek「加入列表」功能改造 — 实施计划

> **版本**: v1.0  
> **日期**: 2026-09-15  
> **状态**: 待审核

---

## 1. 目标

将搜索结果三点菜单中的「加入歌单」改名为「加入列表」，并扩展功能使其既能加入本地歌单，也能加入 B 站远程收藏夹。在选择器和 Library 页面提供收藏夹的创建/编辑/删除管理能力。

## 2. 现状

### 2.1 当前流程
```
三点菜单 → "加入歌单" → PlaylistPickerDialog → 选本地歌单 → library.addToPlaylist()
```

### 2.2 关键文件
| 文件 | 作用 |
|------|------|
| `app/.../SearchScreen.kt:181` | 菜单项文案 "加入歌单" |
| `app/.../ui/PlaylistPickerDialog.kt` | 本地歌单选择器 |
| `app/.../AppViewModel.kt:1118-1136` | confirmAddToPlaylist / createPlaylistAndAdd |
| `app/.../ui/SelectionToolbar.kt:62` | 批量操作按钮 "加入歌单" |
| `app/.../ui/MultiPagePickerScreen.kt:116` | 多P选择页标题 |
| `app/.../ui/UpSpaceScreen.kt:112` | UP空间菜单 |
| `app/.../MainActivity.kt:279,296,300,501` | 回调绑定 |
| `core/network/.../BiliApi.kt:340-343` | dealFavorite API (已有) |
| `core/network/.../BiliRepository.kt:798-807` | dealFavorite 方法 (已有) |
| `app/.../data/Entities.kt:85-96` | FavoriteFolderEntity (已有) |
| `app/.../ui/LibraryScreen.kt:368-448` | FAVORITES tab (已有) |

### 2.3 已有 B 站 API
| 端点 | 方法 | 状态 |
|------|------|------|
| `x/v3/fav/folder/created/list-all` | GET | ✅ 已实现 |
| `x/v3/fav/resource/list` | GET | ✅ 已实现 |
| `x/v3/fav/resource/deal` | POST | ✅ 已实现 (dealFavorite) |
| `x/v3/fav/folder/add` | POST | ❌ 需新增 |
| `x/v3/fav/folder/edit` | POST | ❌ 需新增 |
| `x/v3/fav/folder/del` | POST | ❌ 需新增 |

---

## 3. 待确认事项

### 3.1 `dealFavorite` 的 rid 参数问题

当前 `dealFavorite(aid: Long, addIds, delIds)` 的 `rid` 参数需要视频的 `aid`，但搜索结果 `SearchVideo` 只有 `bvid`。

**选项 A**：检查搜索 API 返回数据是否包含 aid
- 如果搜索 API 返回的 `SearchVideo` 中有 aid 字段 → 直接使用
- 需要检查 `BiliDto.kt` 中 `SearchData` 的结构

**选项 B**：通过 bvid 调用 `x/web-interface/view` 获取 aid
- 增加一次网络请求
- 可用缓存减少重复请求

**选项 C**：修改 `dealFavorite` 参数支持 bvid
- B 站 API `x/v3/fav/resource/deal` 的 `rid` 参数实际接受 aid
- 不支持 bvid

**建议**：先确认搜索 API 是否返回 aid（选项 A），如果不返回则选选项 B。

---

## 4. 分步实施计划

### Phase 1：B 站 API 层

#### Step 1.1 — BiliApi.kt 新增 3 个接口

**文件**: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`

在 `// ── 收藏夹（M11）` 区块末尾（line 209 之后）新增：

```kotlin
/** 新建收藏夹。返回收藏夹元数据。 */
@FormUrlEncoded
@POST("x/v3/fav/folder/add")
suspend fun addFavoriteFolder(
    @FieldMap fields: Map<String, String>,
): BiliResponse<FavoriteFolderData>

/** 修改收藏夹。 */
@FormUrlEncoded
@POST("x/v3/fav/folder/edit")
suspend fun editFavoriteFolder(
    @FieldMap fields: Map<String, String>,
): BiliResponse<JsonElement>

/** 删除收藏夹。media_ids 逗号分隔。 */
@FormUrlEncoded
@POST("x/v3/fav/folder/del")
suspend fun deleteFavoriteFolder(
    @FieldMap fields: Map<String, String>,
): BiliResponse<JsonElement>
```

**验证**: 编译通过。

#### Step 1.2 — FavoriteDto.kt 新增 DTO

**文件**: `core/network/src/main/java/com/biliseek/core/network/dto/FavoriteDto.kt`

在文件末尾新增：

```kotlin
/** `x/v3/fav/folder/add` 返回的收藏夹元数据。 */
@Serializable
data class FavoriteFolderData(
    val id: Long = 0,
    val fid: Long = 0,
    val mid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
    @SerialName("ctime") val ctime: Long = 0,
    @SerialName("mtime") val mtime: Long = 0,
)
```

**验证**: 编译通过。

#### Step 1.3 — BiliRepository.kt 新增 3 个方法

**文件**: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`

在 `dealFavorite()` 方法（line 807）之后新增：

```kotlin
/** 新建收藏夹，返回新建收藏夹的 media_id。 */
suspend fun addFavoriteFolder(
    title: String,
    intro: String = "",
    privacy: Int = 0,
): Result<Long> = writeRequest(
    payload = buildMap {
        put("title", title)
        if (intro.isNotBlank()) put("intro", intro)
        put("privacy", privacy.toString())
    },
) { f, _ -> api.addFavoriteFolder(f) }
    .map { it.data.id }

/** 修改收藏夹标题和简介。 */
suspend fun editFavoriteFolder(
    mediaId: Long,
    title: String,
    intro: String = "",
    privacy: Int = 0,
): Result<Unit> = writeRequest(
    payload = buildMap {
        put("media_id", mediaId.toString())
        put("title", title)
        put("intro", intro)
        put("privacy", privacy.toString())
    },
) { f, _ -> api.editFavoriteFolder(f) }
    .map { }

/** 删除收藏夹。 */
suspend fun deleteFavoriteFolder(mediaIds: List<Long>): Result<Unit> =
    writeRequest(
        payload = mapOf("media_ids" to mediaIds.joinToString(",")),
    ) { f, _ -> api.deleteFavoriteFolder(f) }
        .map { }
```

**验证**: 编译通过。

---

### Phase 2：ViewModel 层

#### Step 2.1 — AppViewModel.kt 新增收藏夹操作方法

**文件**: `app/src/main/java/com/biliseek/app/AppViewModel.kt`

在 `createPlaylistAndAdd()` 方法（line 1136）之后新增：

```kotlin
/** 加入到已有 B 站收藏夹。 */
fun confirmAddToFavoriteFolder(mediaId: Long) {
    val tracks = _pendingBatchAdd.value ?: return
    _pendingBatchAdd.value = null
    viewModelScope.launch {
        // TODO: 确认 aid 获取方式后实现
        // 需要将 tracks 中的 bvid 转换为 aid
        // 然后调用 network.repository.dealFavorite(aid, listOf(mediaId), emptyList())
        emitMessage("已加入 ${tracks.size} 项到收藏夹")
    }
}

/** 新建 B 站收藏夹并加入视频。 */
fun createFavoriteFolderAndAdd(title: String) {
    val tracks = _pendingBatchAdd.value ?: return
    _pendingBatchAdd.value = null
    if (title.isBlank()) return
    viewModelScope.launch {
        val result = network.repository.addFavoriteFolder(title)
        result.onSuccess { mediaId ->
            // 同样需要 aid 转换
            emitMessage("已加入 ${tracks.size} 项到收藏夹")
            syncFavoriteFolders()
        }.onFailure {
            emitMessage("创建收藏夹失败：${it.message}")
        }
    }
}

/** 编辑 B 站收藏夹名称。 */
fun editFavoriteFolder(mediaId: Long, newTitle: String) {
    viewModelScope.launch {
        network.repository.editFavoriteFolder(mediaId, newTitle)
            .onSuccess { syncFavoriteFolders() }
            .onFailure { emitMessage("编辑失败：${it.message}") }
    }
}

/** 删除 B 站收藏夹。 */
fun deleteFavoriteFolder(mediaId: Long) {
    viewModelScope.launch {
        network.repository.deleteFavoriteFolder(listOf(mediaId))
            .onSuccess { syncFavoriteFolders() }
            .onFailure { emitMessage("删除失败：${it.message}") }
    }
}
```

同时需要确认 `syncFavoriteFolders()` 是否可从外部调用。查看现有代码：

```kotlin
// 现有方法（line 414 附近）
private fun syncFavoriteFolders() { ... }
```

需改为 `fun syncFavoriteFolders()` 或提供 public 包装方法。

**验证**: 编译通过。

---

### Phase 3：UI 选择器重构

#### Step 3.1 — 重命名文件

将 `app/.../ui/PlaylistPickerDialog.kt` 重命名为 `app/.../ui/AddToListPickerDialog.kt`。

```bash
git mv app/src/main/java/com/biliseek/app/ui/PlaylistPickerDialog.kt \
       app/src/main/java/com/biliseek/app/ui/AddToListPickerDialog.kt
```

#### Step 3.2 — 重构选择器 Composable

**文件**: `app/.../ui/AddToListPickerDialog.kt`

完全重写内容：

```kotlin
package com.biliseek.app.ui

/**
 * 加入列表选择器：本地歌单 + B站收藏夹合并展示。
 *
 * 未登录时仅展示本地歌单区域。
 * 已登录时展示两个区域，各有独立的新建按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListPickerDialog(
    playlists: List<PlaylistEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    isLoggedIn: Boolean,
    onPickPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onPickFavorite: (Long) -> Unit,
    onCreateFavorite: (title: String) -> Unit,
    onEditFavorite: (mediaId: Long, newTitle: String) -> Unit,
    onDeleteFavorite: (mediaId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var creatingPlaylist by remember { mutableStateOf(playlists.isEmpty() && !isLoggedIn) }
    var creatingFavorite by remember { mutableStateOf(false) }
    var editingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deletingFavorite by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var favoriteTitle by remember { mutableStateOf("") }
    var favoriteIntro by remember { mutableStateOf("") }

    // 主选择器对话框
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入列表") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                // ── 本地歌单区域 ──
                Text("本地歌单", style = MaterialTheme.typography.labelLarge,
                    Modifier.padding(bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPickPlaylist(playlist.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(playlist.name, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { /* TODO: 显示编辑菜单 */ }) {
                                Text("⋮", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { creatingPlaylist = true }) {
                    Text("＋ 新建歌单")
                }

                // ── B站收藏夹区域 ──
                if (isLoggedIn) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("B站收藏夹", style = MaterialTheme.typography.labelLarge,
                        Modifier.padding(bottom = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(favoriteFolders, key = { it.mediaId }) { folder ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPickFavorite(folder.mediaId) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(folder.title, style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${folder.mediaCount} 个",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = {
                                    editingFavorite = folder.mediaId to folder.title
                                }) {
                                    Text("⋮", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    TextButton(onClick = { creatingFavorite = true }) {
                        Text("＋ 新建收藏夹")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // ── 子对话框 ──

    // 新建歌单
    if (creatingPlaylist) {
        AlertDialog(
            onDismissRequest = { creatingPlaylist = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(playlistName, { playlistName = it },
                    singleLine = true, label = { Text("名称") })
            },
            confirmButton = {
                TextButton(onClick = { onCreatePlaylist(playlistName) },
                    enabled = playlistName.isNotBlank()) { Text("创建并加入") }
            },
            dismissButton = { TextButton(onClick = { creatingPlaylist = false }) { Text("取消") } },
        )
    }

    // 新建收藏夹
    if (creatingFavorite) {
        AlertDialog(
            onDismissRequest = { creatingFavorite = false },
            title = { Text("新建收藏夹") },
            text = {
                Column {
                    OutlinedTextField(favoriteTitle, { favoriteTitle = it },
                        singleLine = true, label = { Text("标题") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(favoriteIntro, { favoriteIntro = it },
                        label = { Text("简介（可选）") })
                }
            },
            confirmButton = {
                TextButton(onClick = { onCreateFavorite(favoriteTitle) },
                    enabled = favoriteTitle.isNotBlank()) { Text("创建并加入") }
            },
            dismissButton = { TextButton(onClick = { creatingFavorite = false }) { Text("取消") } },
        )
    }

    // 编辑收藏夹
    editingFavorite?.let { (mediaId, currentTitle) ->
        var editTitle by remember { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { editingFavorite = null },
            title = { Text("编辑收藏夹") },
            text = {
                OutlinedTextField(editTitle, { editTitle = it },
                    singleLine = true, label = { Text("标题") })
            },
            confirmButton = {
                TextButton(onClick = { onEditFavorite(mediaId, editTitle) },
                    enabled = editTitle.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("取消") } },
        )
    }

    // 删除收藏夹确认
    deletingFavorite?.let { (mediaId, title) ->
        AlertDialog(
            onDismissRequest = { deletingFavorite = null },
            title = { Text("删除收藏夹") },
            text = { Text("确定删除「$title」？收藏夹内的视频不会被删除。") },
            confirmButton = {
                TextButton(onClick = { onDeleteFavorite(mediaId) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingFavorite = null }) { Text("取消") } },
        )
    }
}
```

**验证**: UI 渲染正确，分组展示。

---

### Phase 4：文案修改

#### Step 4.1 — 搜索结果菜单
**文件**: `app/.../SearchScreen.kt`

```
行 181:  "加入歌单" → "加入列表"
行 865:  "加入歌单" → "加入列表"
```

#### Step 4.2 — 批量选择工具栏
**文件**: `app/.../ui/SelectionToolbar.kt`

```
行 62:  "加入歌单" → "加入列表"
```

#### Step 4.3 — 多P选择页
**文件**: `app/.../ui/MultiPagePickerScreen.kt`

```
行 116:  "加入歌单" → "加入列表"
```

#### Step 4.4 — UP空间菜单
**文件**: `app/.../ui/UpSpaceScreen.kt`

```
行 112:  "加入歌单" → "加入列表"
```

**验证**: 全局搜索 `加入歌单` 确认无遗漏。

---

### Phase 5：回调绑定更新

#### Step 5.1 — MainActivity.kt 更新 Dialog 调用

**文件**: `app/.../MainActivity.kt`

找到 `PlaylistPickerDialog` 的调用（line 501 附近），替换为：

```kotlin
AddToListPickerDialog(
    playlists = playlists,
    favoriteFolders = favoriteFolders,
    isLoggedIn = session.isLoggedIn,
    onPickPlaylist = viewModel::confirmAddToPlaylist,
    onCreatePlaylist = viewModel::createPlaylistAndAdd,
    onPickFavorite = viewModel::confirmAddToFavoriteFolder,
    onCreateFavorite = viewModel::createFavoriteFolderAndAdd,
    onEditFavorite = viewModel::editFavoriteFolder,
    onDeleteFavorite = viewModel::deleteFavoriteFolder,
    onDismiss = viewModel::dismissBatchAdd,
)
```

同时移除旧的 `PlaylistPickerDialog` import，替换为 `AddToListPickerDialog` 的 import。

**验证**: 对话框正常弹出。

---

### Phase 6：Library 页面管理

#### Step 6.1 — FAVORITES tab 增加管理功能

**文件**: `app/.../ui/LibraryScreen.kt`

在 FAVORITES tab 的每个收藏夹项上增加长按菜单：

```kotlin
// 在收藏夹 item 上增加 combinedClickable 或 pointerInput 处理长按
items(favoriteFolders, key = { "favorite-${it.mediaId}" }) { folder ->
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpenFavorite(folder.mediaId, folder.title) },
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ... 现有内容保持不变 ...
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = { showMenu = false; /* 触发编辑回调 */ },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { showMenu = false; /* 触发删除回调 */ },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
```

需要在 `LibraryScreen` 的参数中增加编辑/删除回调：

```kotlin
fun LibraryScreen(
    // ... 现有参数 ...
    onEditFavoriteFolder: (Long, String) -> Unit = { _, _ -> },
    onDeleteFavoriteFolder: (Long) -> Unit = {},
)
```

#### Step 6.2 — 新建收藏夹按钮

在 FAVORITES tab 列表顶部增加新建按钮：

```kotlin
item(key = "favorite-create") {
    TextButton(
        onClick = { /* 弹出新建收藏夹对话框 */ },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text("＋ 新建收藏夹")
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
```

**验证**: 长按菜单正常，编辑/删除后列表刷新。

---

## 5. 数据流图

```
用户点击三点菜单 → "加入列表"
        │
        ▼
  AddToListPickerDialog
   ┌────────┴────────┐
   │                 │
选择本地歌单      选择B站收藏夹
   │                 │
   ▼                 ▼
onPickPlaylist    onPickFavorite
   │                 │
   ▼                 ▼
confirmAddTo      confirmAddTo
Playlist()        FavoriteFolder()
   │                 │
   ▼                 ▼
library.          network.repository.
addToPlaylist()   dealFavorite(aid, mediaIds)
   │                 │
   ▼                 ▼
Room DB           B站 API
```

---

## 6. 修改文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `core/network/.../BiliApi.kt` | 新增 | 3 个 API 接口 |
| `core/network/.../dto/FavoriteDto.kt` | 新增 | FavoriteFolderData DTO |
| `core/network/.../BiliRepository.kt` | 新增 | 3 个 repository 方法 |
| `app/.../AppViewModel.kt` | 新增 | 收藏夹 CRUD + 添加视频方法 |
| `app/.../ui/PlaylistPickerDialog.kt` | 重命名+重写 | → AddToListPickerDialog.kt |
| `app/.../SearchScreen.kt` | 修改 | 文案 "加入歌单" → "加入列表" |
| `app/.../ui/SelectionToolbar.kt` | 修改 | 文案 "加入歌单" → "加入列表" |
| `app/.../ui/MultiPagePickerScreen.kt` | 修改 | 文案 "加入歌单" → "加入列表" |
| `app/.../ui/UpSpaceScreen.kt` | 修改 | 文案 "加入歌单" → "加入列表" |
| `app/.../MainActivity.kt` | 修改 | Dialog 调用替换 |
| `app/.../ui/LibraryScreen.kt` | 修改 | FAVORITES tab 增加管理 |

---

## 7. 预估工作量

| Phase | 内容 | 预估 |
|-------|------|------|
| Phase 1 | API 层 | 30 min |
| Phase 2 | ViewModel | 45 min |
| Phase 3 | 选择器重构 | 60 min |
| Phase 4 | 文案修改 | 10 min |
| Phase 5 | 回调绑定 | 20 min |
| Phase 6 | Library 管理 | 40 min |
| 测试 | 验证 | 30 min |
| **合计** | | **~3.5 h** |

---

## 8. 风险与注意事项

1. **收藏夹数量上限**: B 站普通用户最多 100 个收藏夹，创建时若接近上限需提示
2. **aid 获取**: dealFavorite 需要 aid，需确认搜索 API 返回数据中是否包含
3. **网络错误处理**: 所有收藏夹操作失败需友好提示 toast
4. **本地歌单删除**: 已有 CASCADE 外键，删除歌单自动清理关联
5. **刷新时机**: 创建/编辑/删除收藏夹后调用 syncFavoriteFolders() 刷新
