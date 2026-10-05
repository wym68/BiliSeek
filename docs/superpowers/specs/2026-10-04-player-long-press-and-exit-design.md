# 播放页看模式：长按画面 2 倍速；退出时画面和整页一起淡出

日期：2026-10-04
范围：`app` 的 `ui/PlayerControls.kt`（`CoverArea`）、`ui/PlayerScreen.kt`、`ui/AppShell.kt`（返回处理、`leavePlayerThen`）、`ui/LongPressSpeedBoost.kt`（新增）、`ui/VideoFrameCapture.kt`（新增）、`ui/FullscreenGestures.kt` / `FullscreenGesturePolicy.kt` / `FullscreenPlayer.kt`。

## 背景（用户原话）

> 播放页在看时会在小框中播放视频。正在播放内容时，我希望实现长按小框中的内容时能够以二倍速播放，松手时原速播放。且在看模式下退出播放页会其他内容先消失，然后播放视频的窗口再消失。你优化一下退出播放页的一致性。

## 调查

- **长按**：全屏已有「长按临时加速、松手恢复」（`resolveLongPressSpeed`：2×，在放无损时 1.5×，免得重新取流卡两下）；播放页小框没有任何长按。
- **退出不一致的原因**：
  - 播放页「在看就先切成听、撤掉画面，再退出」（`leavePlayerThen`）的返回处理注册在 AppShell 顶上；NavHost 内部也注册了返回处理（`PredictiveBackHandler`，反编译 navigation-compose 2.9.8 核实），而**后注册的优先**——系统返回手势被 NavHost 直接弹出，没先切成听。
  - NavHost 默认退场是 700ms 淡出；视频画面是 SurfaceView，单独一层，不跟着页面变透明。于是其他内容先淡没，视频窗口要等这一页销毁才消失。
  - 收起箭头、点底部页面、查看UP主走的是 `leavePlayerThen`，没有这个问题；全屏层的返回处理是后注册的，所以全屏时返回仍先退出全屏。

## 方案（用户选 A：画面定格后整页淡出）

1. **长按 2 倍速**（播放页小框，看模式）：
   - 只在播放中生效；按住约半秒（系统长按时间）震一下，变 2×（在放无损时 1.5×）；画面中间显示黑底「2.0× 加速中」（同全屏的 `GestureHudBubble`）；松手恢复原来的速度。已经不慢于目标速度时长按不变。
   - 抽出 `LongPressSpeedBoost`（全屏、小框共用）：松手、或手势被打断（页面销毁、被别的手势抢走）都在 `finally` 里恢复原速，不会卡在加速上。
   - 规则 `resolveLongPressSpeed` 加 `isPlaying`（暂停时返回 null）；全屏同样补上「只在播放中」和开始加速时的震动，两处一致。
2. **返回手势也走「先切成听」**：播放页的 `BackHandler` 挪进 PLAYER 路由里（注册在 NavHost 之后、优先于它）；全屏时禁用，交给全屏层先退出全屏。
3. **画面定格后整页淡出**（`leavePlayerThen`，所有离开方式共用）：
   - 撤画面前先用 `PixelCopy` 截下 SurfaceView 当前这一帧（`captureVideoFrame`，最多等 250ms），再切成听；`CoverArea` 在听且有定格帧时显示这一帧（同一位置、同样 8dp 圆角），所以画面看起来没变。
   - 切成听后再弹出播放页：整页连同这一帧一起淡出，不会先跳成封面、也不会最后才消失。
   - 截不到（还没出画面、Surface 无效、超时）就退回封面，同以前。
   - 播放页销毁时清掉定格帧；截帧中又点了一次返回只换成最新的去向，不重复截。

## 不做

- 听模式（封面）不响应长按（用户说的是看模式下的小框）。
- 不改 NavHost 的转场（仍是 700ms 淡入淡出）；进播放页的动画不变。

## 测试与验证

- 先写 `FullscreenGesturePolicyTest`「暂停时长按不加速」并确认编译失败，再实现。
- `PlayerUiTest`：看模式播放中长按画面 → 先 2× 后恢复 1×；暂停时长按不改速度（只编译，不在用户手机上跑）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；长按手感、截帧淡出、各种离开方式由用户真机看；不装机，等用户开口。
