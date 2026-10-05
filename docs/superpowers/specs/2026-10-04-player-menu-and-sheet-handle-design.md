# 播放页三点菜单精简、底部弹层统一窄横杠、「复制链接」等叫法统一

日期：2026-10-04
范围：`app` 的 `ui/PlayerSheets.kt`、`ui/PlayerScreen.kt`、`ui/FullscreenPlayer.kt`、`ui/ActionListSheet.kt`、`ui/components/CompactDragHandle.kt`（新增，从 `ActionListSheet.kt` 挪出）、`ui/components/SettingsComponents.kt`、`ui/VideoActionBar.kt`、`ui/ReplyAtScreen.kt`、`ui/SummaryStyleSheet.kt`、`ui/components/DynamicCommentDialog.kt`。

## 背景（用户原话，附两张截图）

> 现在播放页的定时关闭和倍速点了之后会弹出选择框。但是这个选择框的上方的横杠占据的空白空间太大了。不好看。
> 播放页的三点按钮点击后，其中的倍速定时关闭的功能按钮可以取消掉，替换为查看作者和添加到列表。这样下方就只有画质音质分享三个功能条。同时看一下弹出的弹框高度是否刚好显示这三个条目。
> 此外，这里是分享，但其他的地方是复制链接。他们的实际作用应该是一样的，也改为复制链接。

## 现状与原因

1. **横杠留白**：M3 默认拖动条上下各留 22dp，加 4dp 的条共 48dp。倍速 / 定时 / 画质 / 音质弹层的内容又多一层 16dp 的上边距，标题离弹层顶边约 64dp。
   - 这是用户第三次提：AI 总结验收时嫌动作菜单「拖动条占高」，当时给 `ActionListSheet` 做了窄拖动条 `CompactDragHandle`（8dp + 4dp + 8dp）；播放队列验收时嫌「拖动条留白大」，队列面板去掉了拖动条。
   - 2026-10-02 统一弹窗风格时特意没统一拖动条高度，其他弹层一直是默认高度。
2. **三点菜单被截断**：3 个方块 + 5 行列表约 449dp，超过半屏（真机 360×792dp 的一半是 396dp）。`ModalBottomSheet` 默认先停在半屏，最后一行「分享」被切掉。
3. **叫法不一致**：
   - 播放页的「分享」实际是复制当前视频链接并提示「链接已复制」，和别处的「复制链接」完全一样；
   - 「添加到列表」别处都叫「加入列表」（点开的对话框标题也是「加入列表」）；
   - 「查看作者」别处都叫「查看UP主」。

## 方案（用户已选：全部底部弹层统一窄横杠；三个叫法一起统一）

1. **窄横杠统一**：
   - `CompactDragHandle` 从 `ActionListSheet.kt` 挪到 `ui/components/CompactDragHandle.kt`，所有用 `ModalBottomSheet` 的弹层都改用它：
     - 播放页：三点菜单、倍速、定时、画质、音质；
     - `SettingsSheet`（设置页的各个选择弹层、下载对话框、简介、已下载页的弹层）；
     - 投币、回复、AI 总结样式、动态评论；
     - 动作菜单 `ActionListSheet` 本来就用它。
   - 播放队列面板保持没有拖动条（之前按用户反馈专门去掉的）。
   - 内容从横杠下方 8dp 开始，标题离弹层顶边约 28dp，和动作菜单一致（原来 48 ～ 64dp）：
     - 倍速 / 定时 / 画质 / 音质的内容上边距 16dp 改 8dp；
     - `SettingsSheet` 的标题、投币、回复、AI 总结样式的内容加 8dp 上边距；
     - 动态评论的标题行带 48dp 高的关闭按钮，本身有余量，不再加。
2. **三点菜单**（`PlayerMoreMenuSheet`）：
   - 顶部方块：「查看UP主 / 加入列表 / 下载」；
   - 下方列表：「画质」（有可选画质时）/「音质」/「复制链接」（复制图标）；
   - 一打开就完整展开（`skipPartiallyExpanded`），底部边距 32dp 改 16dp。
   - 高度约 20（横杠）+ 8 + 84（方块）+ 17（分隔线）+ 3 × 48（列表）+ 16（间距）+ 16（底边）≈ 305dp，加上系统导航条，不到半屏，刚好显示到「复制链接」。
   - 倍速、定时只从播放页上的胶囊进；菜单的 `onSetSpeed` / `onSetSleepTimer` 参数和两个弹层状态随之删除。
3. **全屏「更多」面板**：同样改叫「加入列表」「复制链接」「查看UP主」；「定时关闭」保留（全屏页面上没有定时胶囊）。

## 不做

- 播放页互动栏里的分享图标不改：它显示的是 B 站的分享数，点了也是复制链接。
- 代码里的回调名（`onShare`、`onViewAuthor` 等）不改，只改界面文字和图标。
- 播放队列面板不加拖动条。

## 测试与验证

- `PlayerUiTest`：「点菜单后能进入倍速」改为「三点菜单只有查看UP主 / 加入列表 / 下载与音质 / 复制链接，没有倍速、定时关闭」。
- `FullscreenPlayerUiTest`：「更多面板可达」补查三个新叫法。
- 两个都只编译核对（真机跑 androidTest 会重装 App、清数据）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
