# 搜索页头像点击跳转 UP 主页

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点击搜索页标题栏的用户头像，跳转到该用户的应用内 UP 主页（`UpSpaceScreen`）。

**Architecture:** 2 文件改动：`SearchScreen` 新增回调参数并给头像加 `clickable`；`MainActivity` 接线回调，用 `session` 中的用户信息构造 `SearchUser` 调用已有的 `openUpSpace()` 流程。

**Tech Stack:** Kotlin, Jetpack Compose

---

### Task 1: SearchScreen 新增 onAvatarClick 回调

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt:148-149`（函数签名）
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt:301-307`（头像 AsyncImage）

- [ ] **Step 1: 添加回调参数**

在 `SearchScreen` 函数签名中，`session` 参数之前新增：

```kotlin
onAvatarClick: () -> Unit = {},
```

完整签名片段（约 line 147-149）：

```kotlin
    onClearHistory: () -> Unit = {},
    /** 头像点击（跳转登录账户 UP 主页）。 */
    onAvatarClick: () -> Unit = {},
    /** 登录态（标题栏显示账号名与头像，BBPlayer 主页风格）。 */
    session: BiliSession = BiliSession.EMPTY,
```

- [ ] **Step 2: 给头像加 clickable**

找到标题栏中的 `AsyncImage`（约 line 301-307），在 `Modifier` 链中添加 `.clickable`：

```kotlin
                    AsyncImage(
                        model = if (session.isLoggedIn) normalizeCoverUrl(session.face) else null,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = session.isLoggedIn) { onAvatarClick() }
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
```

需要确认 `import androidx.compose.foundation.clickable` 已存在（文件头部检查）。

- [ ] **Step 3: 验证编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/biliseek/app/SearchScreen.kt
git commit -m "feat(search): add onAvatarClick callback to SearchScreen

Prepares avatar click handler for navigating to user's UP space."
```

---

### Task 2: MainActivity 接线头像点击

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:340`（searchContent lambda）

- [ ] **Step 1: 传入 onAvatarClick 回调**

在 `MainActivity.kt` 的 `searchContent` lambda 中，找到 `SearchScreen` 调用（约 line 306-341），在 `session = session` 之前新增 `onAvatarClick` 参数：

```kotlin
                            onAvatarClick = {
                                viewModel.openUpSpace(
                                    SearchUser(
                                        mid = session.dedeUserId.toLongOrNull() ?: 0L,
                                        uname = session.uname,
                                        upic = session.face,
                                    )
                                )
                                openUpSpaceRequests.tryEmit(Unit)
                            },
                            session = session,
```

需要确认 `import com.biliseek.core.network.dto.SearchUser` 已存在。

- [ ] **Step 2: 验证编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/MainActivity.kt
git commit -m "feat(search): wire avatar click to open logged-in user's UP space

Clicking the avatar on the search page now navigates to the user's
in-app UP space page, consistent with dynamic/search-result author clicks."
```

---

### Task 3: 真机验证

**Files:** None（手动验证）

- [ ] **Step 1: 构建安装**

Run: `./gradlew installDebug`

- [ ] **Step 2: 未登录状态验证**

预期：头像不显示（`model = null`），点击无反应。

- [ ] **Step 3: 已登录状态验证**

预期：
1. 标题栏显示用户头像和昵称
2. 点击头像 → 跳转 `UpSpaceScreen`，显示当前账户的投稿和动态
3. 返回手势正常回到搜索页

- [ ] **Step 4: 回归验证**

预期：
1. 动态页点击作者头像 → 仍正常跳转对应 UP 主页
2. 搜索结果点击用户 → 仍正常跳转对应 UP 主页
3. 搜索功能本身不受影响

---

## Summary

| Task | Files Changed | Risk |
|------|--------------|------|
| 1. SearchScreen 回调 | `SearchScreen.kt` | Low - 新增可选参数，默认空实现 |
| 2. MainActivity 接线 | `MainActivity.kt` | Low - 复用已有 openUpSpace 流程 |
| 3. 真机验证 | None | Manual verification |

Total: 2 files modified.
