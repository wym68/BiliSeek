# 全屏播放优化：方向、自动转向、控件隐藏、播放列表、全屏连播

日期：2026-10-04
范围：
- `app`：`AndroidManifest.xml`，`ui/AppShell.kt`，`ui/FullscreenPlayer.kt`，`MainActivity`，新增 `ui/AutoRotatePolicy.kt`
- `:core:player`：`internal/PlaybackModePolicy.kt`、`PlayerConnection.kt`（只改内部，不加公开 API）

## 背景（用户原话）

> 全屏播放页优化，横屏 竖屏，自动转向等。现在进入全屏显示时，可隐藏的组件停留时间有点长。全屏播放中没有进入播放列表的按钮。且在全屏播放时，点击下一首播放，会以听的形式起播，我希望直接下一个全屏播放。

用户选定：
- **方向**：除全屏外的页面锁定竖屏；在播放页、而且是看模式时，把手机横过来就自动进全屏，竖回来自动退出，跟随系统「自动旋转」开关。不加手动旋转按钮。
- **下一首**：只有全屏继续看；在播放页内嵌看时下一首仍回到听，保持「默认只听」的取向。播完自动接下一首也一样。
- **自动隐藏**：3 秒（原来 4 秒）。
- **播放列表**：全屏侧边面板，样式同倍速、画质面板。

## 现状

- 应用没有锁方向：列表等页面横过手机也会转成横屏，这些页面并不是为横屏设计的。
- 全屏按视频比例锁方向（竖屏视频 `SENSOR_PORTRAIT`，其余 `SENSOR_LANDSCAPE`），退出时还原。播放页上横过手机不会进全屏。
- 控件 `AUTO_HIDE_DELAY_MS = 4000`。
- 全屏底栏有：弹幕、上一首 / 播放 / 下一首、倍速、画质、音质。没有播放列表。
- 播放核心 `resolvePresentationOnTrackChange` 的规则是「看模式下切下一首回到听」，只有 PiP 例外。全屏换内容也回到听，`MainActivity` 随之把全屏退掉。

## 方案

### 1. 其他页面锁定竖屏

清单里 `MainActivity` 加 `android:screenOrientation="portrait"`。全屏层照旧按视频比例设 `SENSOR_LANDSCAPE` 或 `SENSOR_PORTRAIT`，退出时还原成竖屏（它本来就记着原方向）。

### 2. 横过手机自动进 / 退全屏（`ui/AutoRotatePolicy.kt` + `AppShell`）

- 页面锁了竖屏，系统不会再因为转手机而改界面方向，所以用 `OrientationEventListener` 读手机实际朝向。
- `deviceTilt(degrees)` 把角度换成竖或横：
  - 0° ±30° 算竖
  - 60–120° 或 240–300° 算横
  - 其余角度和平放（-1）不做判断
  
  两个区间之间留了空档，手机在临界角度附近晃不会来回跳。倒着拿手机（180° 附近）不算竖。
- `resolveAutoRotate(previous, current, fullscreen, canAutoEnter, systemAutoRotate)` 只认**变化**：
  - 系统「自动旋转」关着：什么都不做。
  - 从竖变横、不在全屏、可以自动进：进全屏。「可以自动进」指在播放页、正在内嵌看、不在画中画、视频是横的或比例还不知道。竖屏视频不自动进，它的全屏本来就是竖着的。
  - 从横变竖、在横屏全屏里：退出全屏。竖屏视频的全屏本来就是竖着的，不会因为转手机退出。
  - 只认变化的好处：手动点全屏时手机还竖着，不会马上被「竖着」退出；横着手动退出全屏，也不会马上又被拉进全屏。
- 监听只在需要时开：在播放页且正在内嵌看，或者在全屏里。系统「自动旋转」开关在要动作的那一刻才读（`Settings.System.ACCELEROMETER_ROTATION`），不常驻监听。

### 3. 全屏换内容继续全屏（`:core:player`）

- `resolvePresentationOnTrackChange(current, sameContent, nextHasVideo)`：
  - 全屏、下一条有画面：保持 `FULLSCREEN`。视频轨保持开启、画面不撤，下一条直接在全屏里播。
  - 下一条没有画面（只下了音频的本地文件）：回到听，全屏层按原有规则退出（`shouldLeaveFullscreen`）。
  - 内嵌看：仍回到听（不变）。PiP 不变。同一条内容重新取流不变。
- `PlayerConnection.load` 把「下一条有没有画面」传进去：在线播放有画面；本地文件看有没有视频轨。
- 换内容时不重新走移动网络询问：用户已经在全屏里看着。
- 下一条是竖屏视频时，全屏方向按原规则跟着变成竖屏。

### 4. 控件 3 秒自动隐藏

`AUTO_HIDE_DELAY_MS` 改为 3000。进全屏和每次点出控件后都按 3 秒算，判断条件（暂停时不藏、拖进度条时不藏、面板开着时不藏）不变。

### 5. 全屏播放列表

- 底栏加「播放列表」图标按钮，图标与播放页一致。横屏放在倍速 / 画质 / 音质右边；竖屏放在第一行右端。
- 点开 `FullscreenPanelType.QUEUE`（标题「播放列表」）：
  - 按队列顺序列出序号 + 标题，当前这一首用主色并带「正在播放」图标，打开时自动滚到当前这一首。
  - 点一项：收起面板、跳到那一首（`queueSkipTo`），按第 3 条继续全屏。
  - 点当前这一首只收起面板。
- 删除、清空、撤销这些管理操作仍在播放页的队列面板里，全屏面板只负责「看和跳」。
- 接线：`FullscreenPlayerOverlay` 新增 `onSkipTo`，经 `AppShell` 新参数 `onQueueSkipTo`，由 `MainActivity` 传入 `viewModel::queueSkipTo`。

## 不做

- 不加手动旋转按钮。
- 内嵌看时的下一首仍回到听。
- 全屏面板里不做队列管理。

## 风险

- 自动转向靠传感器角度，不同手机手感可能有差异；区间可以调。系统「自动旋转」关着时完全不转，这是用户要求的。
- 全屏连播在移动网络下会继续消耗流量，因为用户已经在看。
- 清单锁竖屏只对手机有效：大屏设备上，Android 16 起系统可能忽略方向限制。

## 测试与验证

- 先写测试，确认失败再实现：
  - `AutoRotatePolicyTest`：角度换算、只认变化、系统开关、竖屏视频、手动进退不被立刻反转
  - `PlaybackModePolicyTest`：全屏换内容继续全屏、下一条没画面回到听、内嵌仍回到听，「换内容与进后台规则一致」那条测试改为只有全屏例外
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`。方向、传感器和全屏界面要在真机上看。
- 不装机：用户要求攒一批修改再装。
