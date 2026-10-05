# 迷你条真正浮在页面上；设置里选深色 / 浅色 / 跟随系统

日期：2026-10-04
范围：`app` 的 `ui/AppShell.kt`（底部布局、提示位）、`ui/components/MiniBarInset.kt`（新增）、各内容页的滚动容器、`ui/RefreshStateSnackbar.kt`、`ui/PrivateChatScreen.kt`、`data/SettingsStore.kt`、`ui/ThemePolicy.kt`（新增）、`ui/SettingsScreen.kt`、`MainActivity`、`ui/PlayerScreen.kt`（封面取色）。

## 背景（用户原话）

> 现在当迷你播放器样式为附在底部时，迷你播放器有圆角，在底部，但并不是浮在底部。它的背景是实的，不能显示更底层的画面。
> 整个 APP 的颜色是深色还是浅色，还是跟随系统可以在设置中指定设置。

## 现状

- 迷你条和底部导航一起放在 Scaffold 的底栏里，页面内容只排到迷你条上沿：「贴底」的圆角外、「浮在底部」的卡片四周露出的都是 Scaffold 的实底色，页面在迷你条上沿被截断。
- 主题固定跟随系统（`BiliSeekTheme(darkTheme = isSystemInDarkTheme())`）；播放页封面取色也直接读系统深浅色。XML 主题固定浅色，所以系统深色时启动那一下本来就会先亮一下。

## 方案

### 1. 迷你条浮在页面上（用户在效果图里选了 A）

效果图：`.superpowers/brainstorm/1852443-1791142286/content/minibar-float.html`（脚本 `/tmp/biliseek-fonts/build_minibar_float_mock.py`）。

- **布局**：Scaffold 底栏只放底部导航；页面（NavHost）一直排到导航栏上沿，迷你条叠在页面最下面。「贴底」的圆角外、「浮在底部」卡片的左右和下边距都能看到下面的页面；迷你条本身仍是实心的。
- **迷你条高度**：量出迷你条当前高度（含「浮在底部」的 8dp 下边距；收起成小横杠时是小横杠的高度；没在播放、在播放页上时为 0），经 `LocalMiniBarInset` 传给各页面。
- **各页面**：
  - 列表 / 可滚动内容的底部留白加上这个高度：滑到底时最后一条停在迷你条上方，不被挡住。涉及搜索（结果、历史、首页）、动态、列表页（本地列表、收藏夹、合集、内页）、稍后再看、合集、已下载、AI 总结、听歌历史、榜单、UP 空间、关注列表、消息中心及回复 / 赞 / 粉丝、设置、AI 设置。
  - 固定在页面底部的东西整体抬到迷你条上方：私信输入框、下拉刷新的提示。
- **提示位**：带 App 图标的提示在「导航栏 + 迷你条」上方 16dp，同以前一样不挡迷你条。
- 播放页、全屏、各种弹层不受影响（那里没有迷你条或盖住了迷你条）。

### 2. 深色模式设置

- 设置 → 界面加一行「深色模式」：跟随系统（默认）/ 浅色 / 深色，选了立刻生效（`SettingsStore.themeMode`）。
- `MainActivity`：`resolveDarkTheme(mode, 系统是否深色)`（`ThemePolicy.kt`，有单测）的结果传给 `BiliSeekTheme(darkTheme = …)`；状态栏、导航条图标颜色跟着这个结果（`enableEdgeToEdge` 的 `detectDarkMode`）。
- 播放页封面取色按当前主题的实际深浅算（主题底色的亮度），不再直接读系统。
- 已知限制：冷启动的第一瞬间（系统启动画面、窗口底色）仍按系统颜色，和现在系统深色时一样。

## 测试与验证

- 先写 `ThemePolicyTest`（三种模式 × 系统深浅）并确认编译失败，再实现。
- 迷你条布局由用户真机看：各页面滑到底最后一条不被挡；私信输入框、刷新提示在迷你条上方；贴底圆角外、浮动卡片四周能看到页面。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
