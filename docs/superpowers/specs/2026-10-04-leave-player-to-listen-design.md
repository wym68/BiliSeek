# 离开播放页回到迷你播放条：收起播放页、看自动切成听

日期：2026-10-04
范围：`app` 模块的 `ui/AppShell.kt`、`ui/PlayerPagePolicy.kt`（新增）、`AppViewModel`、`MainActivity`、设置页「起播模式」的说明文字。

## 背景（用户原话）

> 现在进入播放页面后，再从底部四个主页面（搜索，动态，列表，设置）切换时，播放页面不会返回为 mini 播放条。我希望切换时收回播放页面。同时回到 minibar 播放时，如果是在看的模式，自动切换到听模式。

## 现状与原因

播放页是导航里的一个目的地（`Destination.PLAYER`），离开它有几条路，以前各走各的：

| 离开方式 | 以前 |
| --- | --- |
| 播放页左上「收起」箭头 | 在看就先切成听，等呈现态变成 `NONE` 再退（避免 SurfaceView 在退场动画里盖住整屏）。✓ |
| 系统返回手势 | 由 NavController 直接退出这一页。**不切成听**：画面没了，视频轨还开着，违反「视频轨启用 ⟺ 存在可见 surface」 |
| 点底部四个页面 | 用「保存 / 恢复各页面的返回栈」导航，播放页跟着它所在页面的返回栈一起存起来，**不收起**；切回那个页面时又被恢复出来，而且仍在看 |
| 从播放页跳去 UP 空间 | 压在播放页上面，迷你播放条出现，**仍在看** |

另外，设置「起播模式：记住上次」（上次在看，下次起播直接进看）在起播时就请求看。起播早已不再自动打开播放页，于是画面没有地方显示，视频却在解码。

## 方案（用户已选：「记住上次」改在打开播放页时生效）

1. **离开播放页走同一条路**（`AppShell.leavePlayerThen`）：
   - 适用于：收起箭头、系统返回（新增播放页上的 `BackHandler`，全屏时仍先退全屏）、点底部页面、程序化切 tab（`tabSwitchRequests`）、跳去 UP 空间（`openUpSpaceRequests`）。
   - 在看（`INLINE` / `FULLSCREEN`）就先切成听，等呈现态真的变成 `NONE` 再执行跳转；已经在听就直接跳。
2. **底部页面先关掉播放页再切**：
   - 先 `popBackStack()` 退掉播放页，再按原来的方式切页面，播放页不会再被存进页面的返回栈。
   - 点的就是播放页下面那个页面时，退掉播放页就到了，再切一次是 no-op。
3. **兜底**：迷你播放条在显示（不在播放页、不在全屏、不在画中画）却还在内嵌看时，自动切成听（`shouldForceListen`）。漏掉的跳转路径也照样生效。
4. **自动切成听不算「上次」**：`AppViewModel.leaveToListen()` 只撤画面（`connection.setPresentation(NONE)`），不写 `lastPresentationWasVideo`。只有用户在播放页上亲手点「听 / 看」（`requestPresentation` → `applyPresentation`）才写。
5. **「记住上次」在打开播放页时生效**：
   - 播放页目的地进入组合时调 `onPlayerPageShown()`（打开播放页、从 UP 空间返回都会触发）。
   - 「记住上次」开着、上次在看、有内容、现在在听（`shouldAutoWatchOnPlayerPage`），就按自动请求进看（`requestPresentation(INLINE, automatic = true)`）。计量网络闸门、只下了音频的本地文件等规则照旧。
   - 起播时不再自动进看：删掉 `MainActivity` 里起播信号的处理和 `shouldAutoEnterVideo`。
   - 设置页说明文字改为「上次离开播放页时在看，下次打开播放页直接进看模式」。

判断都在 `ui/PlayerPagePolicy.kt` 的纯函数里：`mustLeaveToListen`、`shouldForceListen`、`shouldAutoWatchOnPlayerPage`。

## 不做

- 不改画中画：按 Home 进画中画仍是「看」，那是系统窗口，不是回到迷你播放条。
- 不改全屏：全屏时的返回先退回播放页（仍在看）。
- 不动「下一首」时的呈现态复位规则（`resolvePresentationOnTrackChange`）。

## 风险

- 离开时要等呈现态变成 `NONE` 才跳。如果切换没生效（播放器还没连上），这次点击就不会跳，再点一次就能退出，和原来的收起箭头一样。
- 从 UP 空间返回播放页时，「记住上次」会再判断一次；如果那时在计量网络上，可能再弹一次移动网络询问。

## 测试与验证

- 先写 `PlayerPagePolicyTest`（6 例），确认编译失败再实现。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。导航与界面接线没有 UI 测试，由用户在真机上验证。
- 不装机：用户要求攒一批修改再装。
