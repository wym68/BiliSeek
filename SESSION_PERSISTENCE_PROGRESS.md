# 播放会话持久化功能 - 开发进度

## 功能目标

1. **自动保存**：暂停、切歌、停止播放时自动保存当前播放队列和进度
2. **自动恢复**：打开 App 时自动恢复上次播放会话，minibar 显示（暂停状态）
3. **minibar 常驻**：点击播放不跳转播放页，minibar 始终显示，点击才展开

## 已完成的修改

### 1. 数据模型 (PlaybackModels.kt)
- 新增 `PersistedSession` 和 `PersistedTrack` 数据类
- `@Serializable` 注解用于 JSON 序列化
- `fromBiliRequest()` 和 `toBiliRequest()` 转换方法

### 2. SettingsStore (SettingsStore.kt)
- session 持久化从 DataStore 改为 SharedPreferences
- `saveLastSessionSync()` 使用 `commit()` 同步写入
- `getLastSessionSync()` 同步读取

### 3. PlayerConnection 修改
- `saveSession()` 改为 public 方法，供外部调用
- `restoreSession()` **只碰内存、不碰播放器**：恢复 queue/index/playMode/presentation +
  state 元数据 + `restoredPositionMs`，设 `hasRestoredQueue`。不再塞假 MediaItem、不 prepare。
- `togglePlayPause()` 恢复感知：`hasRestoredQueue` 时改走 `resumeFromRestoredPosition()` 取流续播
- `resumeFromRestoredPosition()` 用 `startPositionOverride = restoredPositionMs` 从上次位置续播
- `hasRestoredQueue` 标志：标记已恢复队列，用于 `pushState()` 中 `hasMedia` 判断
- `onEnterBackground()` 时强制 `saveSession()`
- `pushState()` 从 `queue[queueIndex]` 推导 title/bvid/cid/coverUrl

> 收尾时移除了早期为「假 prepare」擦屁股的时序 hack：`isInRestoreWindow()`（500ms 窗口）、
> `restoreCompletedAt`、`isRestoring`。根因（给 URI-less item 调 prepare）消失后它们不再需要。

### 4. AppViewModel 修改
- `sessionSaver` 和 `sessionRestorer` 改为同步版本
- `ProcessLifecycleOwner` 监听 `onStop`，进后台时强制保存
- `onCleared()` 中调用 `saveSession()` 作为兜底

### 5. AppShell 修改
- 移除 `playbackStarted` 的自动导航到 PLAYER route
- `showMiniPlayer` 逻辑：`playback.mode != IDLE && expansion != FULLSCREEN && currentRoute != PLAYER`
- minibar 常驻显示，点击才展开播放页

### 6. build.gradle.kts
- 添加 `androidx.lifecycle:lifecycle-process` 依赖

## 当前状态

### 可行
- 暂停 → 杀进程 → 重启 → minibar 显示正确曲目信息 ✅
- minibar 常驻，不自动跳转播放页 ✅
- 点击 minibar 展开播放页 ✅
- 恢复后点击播放：`togglePlayPause` 已接 `resumeFromRestoredPosition`，从上次位置取流续播 ✅（逻辑已通，取流需真人实测）

### 待验证（需真人，VM 被风控无法自动测）
- 恢复后点击播放实际取流并从上次位置续播
- 多 P/歌单恢复后「下一首/上一首」正常
- 取流失败（-352）时错误呈现与重试，不重复打请求

## 问题根因记录

### 问题：恢复后 mode 被重置为 IDLE
**现象**：restoreSession 成功后 showMiniPlayer=true，但紧接着 onIsPlayingChanged(false) 触发 saveSession 和 pushState，把 mode 重置为 IDLE

**根因**：
1. restoreSession() 调用 c.setMediaItems() + c.prepare() + c.playWhenReady=false
2. isRestoring 在 finally 中设为 false
3. 异步回调 onIsPlayingChanged(false) 触发时 isRestoring 已为 false
4. pushState() 从 controller 读取 player 状态，发现 STATE_IDLE，设置 mode=IDLE

**修复方案**：
- hasRestoredQueue 标志：标记已恢复队列
- isInRestoreWindow()：恢复后 500ms 窗口期，忽略 player 状态变化
- pushState() 用 hasRestoredQueue 判断 hasMedia

## 待修复

1. ~~**恢复后点击播放**~~ ✅ 已完成。最终设计不是「恢复时 prepare」，而是**恢复不碰播放器**：
   `togglePlayPause()` 在 `hasRestoredQueue` 时改走 `resumeFromRestoredPosition()`，
   由正常 `play()` 路径取流并 `startPositionOverride = restoredPositionMs` 续播；
   `play()` 开头清 `hasRestoredQueue`。minibar/播放页共用 `togglePlayPause` 这一入口，故无需改 UI。

2. **测试验证**：需要真人测试以下场景（VM 被风控无法自动测）：
   - 播放 → 暂停 → 杀进程 → 重启 → minibar 显示正确
   - 点击 minibar → 展开播放页
   - 点击播放 → 正常取流续播

自动化回归：`PersistedSessionTest`（序列化 + 转换往返），`./gradlew :core:player:test` 全绿。

## 相关文件

- `core/player/src/main/java/com/biliseek/core/player/PlaybackModels.kt` - 数据模型
- `core/player/src/main/java/com/biliseek/core/player/PlayerConnection.kt` - 核心逻辑
- `app/src/main/java/com/biliseek/app/data/SettingsStore.kt` - 持久化
- `app/src/main/java/com/biliseek/app/AppViewModel.kt` - 生命周期
- `app/src/main/java/com/biliseek/app/ui/AppShell.kt` - UI 显示
- `app/build.gradle.kts` - 依赖

## Git 提交记录

| Commit | 描述 |
|--------|------|
| 55acbc7 | fix: core/player 模块缺少 kotlinx-serialization 插件 |
| 2565318 | feat: 播放会话持久化修复 + minibar 常驻显示 |
| 113ddf5 | fix: minibar 常驻显示修复 + 进后台强制保存会话 |
| c7c66ce | fix: 恢复会话后不自动播放 + 进后台强制保存会话 |

---

最后更新：2026-09-03
