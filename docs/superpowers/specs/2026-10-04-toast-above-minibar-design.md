# 带 App 图标的提示：挪到迷你条上方，不挡迷你条

日期：2026-10-04
范围：`app` 的 `ui/AppShell.kt`（提示位）、`ui/components/AppToast.kt`（位置常量 + `LocalShowMessage`）、`ui/components/AppToastPolicy.kt`（新增）、`MainActivity`（普通提示改走 App 内提示）、`LoginScreen.kt`（两条失败提示）。

## 背景（用户原话）

> 检查一下所有的带 app 图标的浮框提醒。调整其位置在 minibar 上面一点，不要挡住 minibar。

## 现状（检查结果）

带 App 图标的提示有两种：

1. **App 自己画的白色胶囊**（`AppToast`）：
   - 主界面的提示位（带「撤销」的那些：队列变动、稍后再看 / 收藏夹 / 本地列表 / 已下载 / AI 总结的删除）：固定在系统导航栏上方 86dp，特意对齐系统 Toast——正好**压在迷你条上**。
   - 下拉刷新的提示（`RefreshStateSnackbar`）：画在页面内容区里，已经在迷你条上方 16dp，不挡。
   - 队列面板里的提示位：在面板里，面板盖住了迷你条，不涉及。
2. **系统 Toast**（Android 12 起也是白底胶囊 + App 图标）：所有不带按钮的普通提示（`transientMessage`：「已加入 N 项…」「链接已复制」「下载完成：…」等）、`showReplacingToast`（动态筛选、播放模式、请先登录）、登录面板里「未找到哔哩哔哩 App」两条、图片预览里「已保存到相册」。
   - 系统 Toast 的位置由系统决定（targetSdk ≥ 30 时 `setGravity` 对文字 Toast 无效），**挪不动**，同样压在迷你条附近。

## 方案

1. **主界面提示位跟着底部栏走**（`AppShell`）：
   - 量出 Scaffold 底部栏（迷你条 + 底部导航栏，含系统导航条）的实际高度，提示下沿放在它上方 16dp（`AppToastDefaults.GapAboveBottomBar`，与下拉刷新提示同距）。
   - 迷你条在：在迷你条上方 16dp；迷你条收成小横杠：在小横杠上方；没在播放 / 播放页上（没有迷你条）：在导航栏上方 16dp。高度变化时位置平滑过渡。
   - 提示位挪到全屏层之上（全屏时也看得到提示，贴底 24dp）；小窗模式下不画（小窗里看不到）。
2. **普通提示改成 App 内提示**（`MainActivity`）：`transientMessage` 和 `showReplacingToast` 合成一条管道，按 `resolveToastTarget` 决定显示在哪：
   - App 在前台：主界面提示位；队列面板开着时显示在面板里（面板是独立窗口，会挡住主界面的提示）。
   - App 在后台或小窗里：照旧系统 Toast（如后台下载完成），否则看不到。
   - 别的底部弹层 / 居中弹窗开着（主窗口没有焦点，`hasWindowFocus()`）：也用系统 Toast——它们是独立窗口，会挡住 App 内的提示（如评论弹层里点赞失败、AI 总结阅读面板里的提示）；这时迷你条本来就被盖住。
   - 新的普通提示替换旧的普通提示（同以前连点只留最新一条），停留 2 秒（同系统 Toast 的短提示）。
   - **带「撤销」的提示正在显示时，普通提示等它结束再弹**，不把撤销的机会顶掉（如删除后正好有下载完成）；带「撤销」的提示照旧会顶掉普通提示。
3. **登录面板的两条失败提示**：通过 `LocalShowMessage`（`MainActivity` 提供）走同一条管道；没提供时（预览、测试）退回系统 Toast。
4. **不改**：图片预览里的「已保存到相册」（预览铺满全屏、盖住了迷你条，系统 Toast 不挡什么，且 App 内提示会被预览盖住）；下拉刷新提示；队列面板里的提示位；全屏 / 小窗上的黑底 HUD。

## 测试与验证

- 先写 `AppToastPolicyTest`（前台 / 后台 / 小窗 / 队列面板开着 / 别的弹层盖着时各显示在哪）并确认编译失败，再实现。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；位置、过渡、与「撤销」提示的先后由用户真机看；不装机，等用户开口。
- 项目约定（CLAUDE.md「In-app transient messages」、docs/09 组件表与选型表）同步改成：前台一律 App 内提示、放在迷你条上方；系统 Toast 只用于后台 / 小窗 / 别的弹层盖着主界面，以及图片预览里的「已保存到相册」。
