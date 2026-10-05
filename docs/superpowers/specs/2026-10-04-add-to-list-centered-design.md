# 加入列表与输入名字的弹窗改回屏幕中间

日期：2026-10-04
范围：`app` 模块的 `ui/AddToListPickerDialog.kt`、`ui/LibraryScreen.kt`、`ui/components/`（新增 `CenterDialog.kt`，`InputSheet.kt` 改为 `InputDialog.kt`）、`ui/components/SettingsComponents.kt`，以及 androidTest `DialogsUiTest`。
承接 [dialog-style-unify](2026-10-02-dialog-style-unify-design.md)：10-02 把这两类弹窗改成了底部弹层。

## 背景

10-02 用户提了两点：「加入列表」去掉每行的数量；它和「新建歌单」弹窗要与整个系统 UI 适配。当时把两者都改成了从底部弹出。
真机试用后用户反馈：加入列表「应该还是和之前一样是屏幕中间弹出」。

- **范围**（用户已选）：加入列表和 7 处输入名字的弹窗回到屏幕中间。「删除下载」和总结阅读页的菜单仍是底部弹层。
- **样子**（用户在浏览器效果图里选了 B）：居中弹窗，里面沿用现在底部弹层的排法，原样挪到中间。不恢复 10-02 之前的排法：行间分割线、每个分区限 3 行、分区里单独滚动。

效果图按真机画：PJD110，360×792dp，字体缩放 1.0，用真机上的 Roboto 和 OPPO Sans 4.0 SC。
原来的居中弹窗在真机上量到宽 320dp，底色 `#262a34`。

## 方案

### 1. 居中弹窗外壳 `CenterDialog`（`ui/components/CenterDialog.kt`，新增）

- 外观与 `BsConfirmDialog`（Material 的 `AlertDialog`）一致：
  - 同一个弹窗窗口宽度，真机上是 320dp
  - `AlertDialogDefaults` 的圆角（28dp）和底色
  - `headlineSmall` 大标题
  - 四周 24dp 内边距
- 和 `AlertDialog` 不同的地方：
  - 内容区横向铺满，列表行可以整行可点，按下的波纹到边。左右边距由内容自己留：分区标题用 `SettingsSheetSection`，和底部弹层是同一套边距。
  - 内容过长时只滚中间，标题和底部按钮不跟着滚。
- 底部按钮行由调用方放，右对齐，按钮间距 12dp。
- 键盘弹出时弹窗挪到键盘上方，这是弹窗窗口的默认行为，10-02 之前的输入框也是这样。所以不用再加 `imePadding`。

### 2. 加入列表

- 外壳换成 `CenterDialog(title = "加入列表")`，里面不变：
  - 分区标题用 `SettingsSheetSection`：主色小字，右端「＋ 新建」
  - 每行（`PickerRow`）只显示名字，右侧三点；整行可点，行高 48dp
  - 没有分割线，不限行数，整体滚动
- 右下角加回「取消」文字按钮。点弹窗外面、按返回键也能关，和原来的 `AlertDialog` 一样。
- 子弹窗：
  - 新建、编辑用 `InputDialog`
  - 三点菜单仍是 `ActionListSheet`，从底部弹出、叠在居中弹窗上，和 10-02 之前一样
  - 删除确认仍是 `BsConfirmDialog`

### 3. 输入弹窗 `InputDialog`（由 `InputSheet` 改名并换外壳）

- 参数不变：`title`、`confirmLabel`、`confirmEnabled`、`onConfirm`、`onDismiss`、`fields(FocusRequester)`。7 处调用点只改名。
- 外壳是 `CenterDialog`。输入框左右留 24dp，多个输入框之间隔 12dp。
- 底部并排两个按钮，各占一半、间距 12dp，和下载弹层的按钮一样：
  - 「取消」用 `OutlinedButton`
  - 确认键用 `Button`，可不可点由 `confirmEnabled` 控制
- 弹出时自动聚焦第一个输入框，做法和 `InputSheet` 相同。

### 4. 收尾

- `SettingsSheet` 去掉 10-02 为输入弹层加的 `imePadding()`，因为现在已经没有底部弹层里放输入框了。`SettingsSheetSection` 的 `action` 槽保留，加入列表还在用。
- 删除 `InputSheet.kt`。

## 不做

- 不动「删除下载」、总结阅读页的菜单、下载弹层和确认框。
- 不恢复 10-02 之前的列表排法。
- 数量继续不显示，这是 10-02 的要求。

## 风险

- 弹窗宽度沿用平台默认值。其他机型上可能不是 320dp，但始终和确认框一样宽。
- 收藏夹很多时，弹窗会长到接近满屏，靠中间部分滚动。
- 弹窗刚出来时就请求焦点，键盘能不能自动弹出还没在真机上验证。弹不出来的话，点一下输入框就行。

## 测试与验证

- 这次是纯界面改动，没有可以抽出来的判断逻辑，所以不加 JVM 单测。
- `DialogsUiTest`：把 `InputSheet` 改成 `InputDialog`，并新增一例「加入列表点「取消」会关闭」。只做编译核对，不在设备上跑，因为跑测试会卸载重装 debug 包。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin` 要通过。不装机：用户要求攒一批修改再装。

## 文档

- `docs/09-UI规范与重构.md`：
  - 共用组件表里 `InputSheet` 改成 `InputDialog`，并加上 `CenterDialog`
  - 「弹窗选型」：加入列表用居中弹窗，输入文字用 `InputDialog`
- `docs/05-功能优化篇.md`：新增 §13.38，总览表加一行；§13.36 加一句指向 §13.38。
- `docs/BiliSeek-手动测试指南.md`：步骤 13 里的加入列表和歌单改名、收藏夹的新建 / 编辑，检查点改成居中弹窗。
