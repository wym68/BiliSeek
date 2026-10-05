# 首页标题栏小改：艺术标题、新用户文案、未登录点头像提示

日期：2026-10-02
范围：`app` 模块的首页标题栏（`SearchScreen.kt`）、首页空状态（`ui/HomeScreen.kt`）、`MainActivity` 的头像接线；新增字体资源与 `NOTICE` 声明。
快捷入口换成榜单（每周必看 / 入站必刷 / 热歌榜）另立设计，不在本文范围。

## 背景

真机首次安装后的首页（用户反馈）：

- 标题「BiliSeek」是系统字体加粗：规整，但太正式，和圆角粗线条的小电视 Logo 不是一个气质。
- 新用户 slogan 的中文行「搜索全 B 站，默认只听。想看画面时一键切换。」不理想，想改成「探索你的 B 站」。
- 未登录时头像是个空圆，点了没有任何反应（头像挂的是 `clickable(enabled = session.isLoggedIn)`），希望点了有提示。

## 方案

### 1. 标题：文艺衬线

在浏览器里对比了四个候选（Fredoka / Shrikhand / Pacifico / DM Serif Display），用户选定 **DM Serif Display**：「Bili」正体，「Seek」斜体加强调色。

- 新增 `BiliSeekWordmark`（`app/ui/components/BiliSeekWordmark.kt`）：单个 `Text` + `AnnotatedString`。
  「Bili」用 DM Serif Display 正体；「Seek」用斜体，颜色取 `MaterialTheme.colorScheme.primary`，
  所以换壁纸（莫奈取色）、深色模式、封面取色强调色都会跟着变。
  初值 34sp / 行高 40sp / 字距 -0.5sp，装机后按实际观感微调。读屏软件仍读作「BiliSeek」。
- `SearchScreen.kt` 标题栏里的 `Text("BiliSeek", …)` 换成 `BiliSeekWordmark()`。标题栏折叠按测量高度算，不受字号变化影响。
- `MiniPlayer.kt` / `PlayerScreen.kt` 里的 `ifBlank { "BiliSeek" }` 是没有曲名时的兜底文字，不是品牌标题，不动。
- 字体文件：`app/src/main/res/font/dm_serif_display_regular.ttf`、`dm_serif_display_italic.ttf`，
  取自 Google Fonts 仓库 `ofl/dmserifdisplay/` 的原版文件，**不裁剪、不改动**（OFL 对改动版本有保留字体名等限制，原样随附最省事），
  两个文件共约 148KB，只用在这个标题上。
- 授权：SIL OFL 1.1。`NOTICE` 与 `app/src/main/assets/NOTICE.txt`（设置页「开源许可」展示的那份，两份须保持一致）
  各在「免责声明」前加一条「9. DM Serif Display」：设计方 Colophon Foundry，随 APK 分发正体与斜体两个原版文件，未做修改。
  字体文件自带的版权串（name 表 ID 0）与 Google Fonts 仓库 OFL.txt 抬头一致，写的是 Adobe「Source」加 Google LLC
  （上游元数据如此），NOTICE 按原文引用，并附 OFL 链接。

### 2. 新用户文案

- `ui/HomeScreen.kt` 新用户引导的中文行 →「探索你的 B 站」（「B」与「站」之间留空格，与项目其他文案一致）。
  英文行「Seek and you shall find.」保留。
- 这块引导只在「无搜索历史且无播放热力数据」时显示，用一阵就不会再出现。

### 3. 头像点击

- `SearchScreen.kt`：头像去掉 `enabled = session.isLoggedIn`，始终可点。
- `MainActivity.kt`：`onAvatarClick` 由 `openOwnSpace` 改为
  `if (session.isLoggedIn) openOwnSpace() else <弹提示>`。`openOwnSpace` 本身不加守卫
  （设置页账号卡片也用它，那边自带登录判断）。
- 提示用系统 `Toast`「请在设置中登录」，`LENGTH_SHORT`。Android 12+ 的系统 Toast 就是白色胶囊加 App 图标，
  与下载提示同款；不带按钮的纯文字提示按项目约定可以用系统 Toast，不必走 `AppToast`。
  用 `MainActivity` 的一个成员字段保存引用（与 `videoSurface` 等字段同一写法），弹新提示前 `cancel()` 上一个，避免连点时排队弹多次。
  不复用 `viewModel.notifyMessage`：那条管道逐条弹、不取消上一条，连点会排队。
- 不抽 `*Policy`：整个分支就是一个 `if`，抽出来只会得到一个复述 `if` 的测试。

## 不做

- 不动问候语、搜索框、热力图等其他首页元素。
- 不改已登录时头像的显示与行为。
- 未登录点头像只弹提示，不跳转设置页（用户要求只提示）。

## 测试与验证

- 自动化：没有可抽出的判断逻辑，不新增单测。跑 `./gradlew test`（全离线）确认现有测试不受影响，`assembleDebug` 编译通过。
  现有测试里没有钉死标题或 slogan 文字的用例。
- 真机（由用户）：
  - 首页标题为 DM Serif 样式，「Seek」斜体并取主题强调色；深色模式、换壁纸后配色正常。
  - 新用户首页第二行为「探索你的 B 站」。
  - 未登录点头像弹「请在设置中登录」，连点不排队；已登录点头像仍进个人空间。
  - 设置 → 开源许可里能看到 DM Serif Display 条目。

## 文档

- `docs/05-功能优化篇.md` §13：新增 13.27，总览表加一行。
- `docs/BiliSeek-手动测试指南.md`：补一步首页标题栏的真机检查。
