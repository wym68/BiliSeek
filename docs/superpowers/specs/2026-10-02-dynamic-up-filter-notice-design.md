# 动态页按 UP 筛选：选中 / 取消时的下方提示

日期：2026-10-02
范围：`app` 模块的 `DynamicFeedController`（`selectDynamicUp`）、新增纯函数 `dynamic/DynamicFilterPolicy.kt`、`MainActivity` 的提示接线。

## 背景

动态页「最常访问」里点 UP 头像可以只看这个 UP 的动态，再点一次取消筛选，但现在只有头像上一圈主题色环，没有任何文字提示。
用户要求选中和取消的时候，在下方用浮框提示。

## 现状

- `DynamicFeedController.selectDynamicUp(up)`：再点已选中的 UP = 取消筛选；筛选会重置游标、重拉第 1 页，并给这个 UP 计一次访问。
- 全局消息管道（`emitMessage` → `transientMessage` → 系统 Toast）是逐条排队的：连点几个头像，提示会一条条慢慢弹，滞后于实际筛选。
  未登录点头像的提示（`showLoginHint`）就是因为这个，改成了「先取消上一条再弹」。

## 方案

### 提示文字（用户选定）

- 选中某个 UP（包括从 A 直接切到 B）：「只看「X」的动态」。
- 取消筛选：「已取消筛选」。
- 筛选没有变化（本来就没筛选、又要回关注流）：不提示。

### 名字太长要截断（用户要求）

UP 主名字太长时，截断并用省略号代替放不下的部分。系统 Toast 自己不会给省略号（放不下就换行或被系统切掉），所以在拼提示文字时就截好：

- 按显示宽度算：全角字符（中日韩、全角符号、emoji，码点 ≥ U+2E80）算 2，其余算 1。
- 名字宽度超过 20（约 10 个汉字）：保留宽度 ≤ 19 的最长前缀，再接「…」（算 1），总宽仍不超过 20。
- 按码点切，不会把 emoji 从中间切坏。首尾空白先去掉；名字是空白时不拼名字：「只看这位 UP 主的动态」。

### 通道

- 不走全局消息管道（它排队）。`DynamicFeedController` 自己暴露 `upFilterNotice: SharedFlow<String>`（缓冲 1、满了丢最旧的）。
- `MainActivity` 收集后用「先取消上一条再弹」的系统 Toast，连点只留最新一条。这个写法抽成 `showReplacingToast(text)`，与未登录点头像的提示（`showLoginHint`）共用一个「上一条」引用。
- 系统 Toast 在下方，Android 12 起是白底胶囊 + App 图标；不带按钮的普通提示按项目约定就用它（`AppToast` 是给要带按钮的提示用的）。

### 纯函数（`dynamic/DynamicFilterPolicy.kt`，离线单测）

- `resolveUpFilterChange(selectedMid, up)`：点头像后筛选怎么变，结果是 `Select(up)` / `Clear` / `null`（没变化）。
  规则就是 `selectDynamicUp` 里原来的写法，原样搬出来：再点已选中的 = 取消；`up == null` 且本来有筛选 = 取消；`up == null` 且本来没筛选 = 没变化。
- `upFilterNotice(change)`：提示文字（含上面的截断）。

`selectDynamicUp` 改用 `resolveUpFilterChange`（行为不变），在状态变化之后发提示。

## 不做

- 不改筛选逻辑本身、头像栏界面、全局消息管道。
- 不给「全部 / 仅视频」切换加提示。
- 不用 `AppToast` 自绘（提示没有按钮）。

## 风险

- 系统 Toast 的实际宽度、是否换行取决于系统版本和字号。宽度上限 20 是按「一行大约放 17 个汉字、其中固定的「只看「」的动态」占 7 个」估的，没有在真机上量过；不合适调 `MAX_NOTICE_NAME_WIDTH` 即可。
- App 在后台时系统不显示 Toast，不影响筛选本身。

## 测试

- `DynamicFilterPolicyTest`（离线）：选中 / 再点取消 / A 切 B / `up == null` 有无筛选；提示文字（正常名、空白名、超长中文名、超长英文名、恰好在上限、中英混排、emoji 不被切坏、首尾空白）。
- `./gradlew test assembleDebug` 通过；Toast 的显示靠真机。

## 文档

- `docs/05-功能优化篇.md`：新增 §13.35，总览表一行。
- `docs/BiliSeek-手动测试指南.md`：动态步骤补检查点。
