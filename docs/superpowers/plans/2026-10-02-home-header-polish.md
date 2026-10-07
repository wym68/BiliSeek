# 首页标题栏小改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页标题换成 DM Serif Display 衬线艺术字，新用户引导文案改为「探索你的 B 站」，未登录点头像弹「请在设置中登录」。

**Architecture:** 标题做成 `:app` 里的一个小组件 `BiliSeekWordmark`（单个 `Text` + `AnnotatedString`，字体来自 `res/font`），在 `SearchScreen` 标题栏里替换原来的 `Text`。头像始终可点，登录与否的分支放在 `MainActivity` 接线处（一个 `if`，不抽 Policy）。文案是 `HomeScreen.kt` 里的一行字符串。

**Tech Stack:** Kotlin、Jetpack Compose（Material 3）、Android 资源字体（`res/font`）、Gradle。

设计稿：[2026-10-02-home-header-polish-design.md](../specs/2026-10-02-home-header-polish-design.md)

## Global Constraints

以下逐条抄自设计稿，每个任务都隐含遵守：

- 字体：DM Serif Display，原版 TTF，**不裁剪、不改动**；两个文件 `dm_serif_display_regular.ttf`、`dm_serif_display_italic.ttf` 放 `app/src/main/res/font/`，共约 148KB，只用在这个标题上。
- 标题：初值 34sp / 行高 40sp / 字距 -0.5sp；「Bili」正体，「Seek」斜体并取 `MaterialTheme.colorScheme.primary`；单个 `Text`，读屏仍读作「BiliSeek」。
- `MiniPlayer.kt` / `PlayerScreen.kt` 里的 `ifBlank { "BiliSeek" }` 是兜底文字，**不动**。
- 授权：SIL OFL 1.1。`NOTICE` 与 `app/src/main/assets/NOTICE.txt` **必须保持一致**；「9. DM Serif Display」加在「免责声明」前。
- 文案：中文行改为「探索你的 B 站」（「B」与「站」之间留空格）；英文行「Seek and you shall find.」保留。
- 头像：始终可点；已登录 `openOwnSpace()`（行为不变），未登录弹系统 `Toast`「请在设置中登录」（`LENGTH_SHORT`），弹新提示前 `cancel()` 上一个。`openOwnSpace` 本身不加守卫。
- 不抽 `*Policy`，不新增单测（整个分支就是一个 `if`）。
- 不动问候语、搜索框、热力图等其他首页元素。

执行层面的约束：

- **不执行 `git commit` / `git push`**：用户尚未同意提交，改完留在工作区。
- **不启动 App、不访问 Bilibili**（CLAUDE.md 安全规则）。只编译、跑离线单测；真机效果由用户看。
- 工作区里 `docs/01-开发环境搭建.md` 的未提交修改不是本次改动，**不要动、不要纳入**。
- 不要用 `cd` 改变工作目录，命令都在仓库根目录（`./`）下执行。
- 这是纯界面 / 文案改动，没有可单测的逻辑，所以各任务的「验证」是编译 + grep + diff，不是红绿测试。

---

### Task 1: 衬线标题 `BiliSeekWordmark`（字体、组件、接入、NOTICE）

**Files:**
- Create: `app/src/main/res/font/dm_serif_display_regular.ttf`
- Create: `app/src/main/res/font/dm_serif_display_italic.ttf`
- Create: `app/src/main/java/com/biliseek/app/ui/components/BiliSeekWordmark.kt`
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt`（加 1 行 import；标题栏里的 `Text("BiliSeek", …)`；删掉随之无用的 `FontWeight` import）
- Modify: `NOTICE`、`app/src/main/assets/NOTICE.txt`（同样的改动，两份必须一致）

**Interfaces:**
- Produces: `@Composable fun BiliSeekWordmark(modifier: Modifier = Modifier)`，包 `com.biliseek.app.ui.components`。无参即可调用；Task 2、3 不依赖它。

- [ ] **Step 1: 取字体文件**

```bash
mkdir -p app/src/main/res/font
gh api -H "Accept: application/vnd.github.raw" repos/google/fonts/contents/ofl/dmserifdisplay/DMSerifDisplay-Regular.ttf > app/src/main/res/font/dm_serif_display_regular.ttf
gh api -H "Accept: application/vnd.github.raw" repos/google/fonts/contents/ofl/dmserifdisplay/DMSerifDisplay-Italic.ttf > app/src/main/res/font/dm_serif_display_italic.ttf
ls -l app/src/main/res/font
```

Expected：两个文件，大小分别为 `76580` 与 `71208` 字节。

- [ ] **Step 2: 核对字体文件确实是正体和斜体**

```bash
python3 -c "
from fontTools.ttLib import TTFont
for fn in ('dm_serif_display_regular', 'dm_serif_display_italic'):
    n = TTFont(f'app/src/main/res/font/{fn}.ttf')['name']
    print(fn, '|', n.getName(1, 3, 1, 0x409), '|', n.getName(2, 3, 1, 0x409))
"
```

Expected：
```
dm_serif_display_regular | DM Serif Display | Regular
dm_serif_display_italic | DM Serif Display | Italic
```

- [ ] **Step 3: 新建组件**

创建 `app/src/main/java/com/biliseek/app/ui/components/BiliSeekWordmark.kt`：

```kotlin
package com.biliseek.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.biliseek.app.R

private val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.dm_serif_display_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * 首页标题「BiliSeek」（设计稿：docs/superpowers/specs/2026-10-02-home-header-polish-design.md）。
 *
 * DM Serif Display：「Bili」正体，「Seek」斜体并取主题强调色，
 * 所以莫奈取色、深色模式、封面强调色都会带着它一起变。单个 Text，读屏仍读作「BiliSeek」。
 */
@Composable
fun BiliSeekWordmark(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append("Bili")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = accent)) { append("Seek") }
        },
        modifier = modifier,
        style = TextStyle(
            fontFamily = DmSerifDisplay,
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.5).sp,
        ),
    )
}
```

- [ ] **Step 4: 接入 `SearchScreen.kt`**

三处修改（按内容匹配，不要按行号，行号在改动过程中会变）：

(a) 加 import。把

```kotlin
import com.biliseek.designsystem.theme.BsShapes
```

改成

```kotlin
import com.biliseek.designsystem.theme.BsShapes
import com.biliseek.app.ui.components.BiliSeekWordmark
```

(b) 把标题栏里的

```kotlin
                        Text(
                            "BiliSeek",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
```

整段换成

```kotlin
                        BiliSeekWordmark()
```

(c) 删掉现在已无人使用的这一行 import（先 `grep -n FontWeight app/src/main/java/com/biliseek/app/SearchScreen.kt`，确认只剩这一行 import 才删）：

```kotlin
import androidx.compose.ui.text.font.FontWeight
```

- [ ] **Step 5: `NOTICE` 两份同步加「9. DM Serif Display」**

两份文件换行符都是 LF、分隔线宽 80。用同一段脚本改两份，保证内容一致，分隔线直接沿用文件里已有的那条：

```bash
python3 - <<'EOF'
anchor = "AI 总结阅读页的 Markdown 渲染。"
for path in ("NOTICE", "app/src/main/assets/NOTICE.txt"):
    text = open(path, encoding="utf-8").read()
    assert text.count(anchor) == 1, path
    sep = next(l for l in text.splitlines() if l and set(l) == {"-"})
    block = "\n".join([
        anchor, "", sep,
        "9. DM Serif Display  —  https://fonts.google.com/specimen/DM+Serif+Display  （SIL OFL 1.1）",
        sep, "",
        "首页标题「BiliSeek」所用字体（设计：Colophon Foundry）。随 APK 分发正体与斜体两个",
        "原版字体文件，未做修改。字体文件自带的版权声明：",
        "",
        "  Copyright 2014 - 2017 Adobe Systems Incorporated (http://www.adobe.com/),",
        "  with Reserved Font Name 'Source'. Copyright 2019 Google LLC.",
        "",
        "许可证：SIL Open Font License 1.1 —— https://openfontlicense.org",
    ])
    open(path, "w", encoding="utf-8").write(text.replace(anchor, block))
print("ok")
EOF
```

Expected：输出 `ok`。

- [ ] **Step 6: 验证**

```bash
diff NOTICE app/src/main/assets/NOTICE.txt && echo "NOTICE 两份一致"
grep -n "9. DM Serif Display" NOTICE app/src/main/assets/NOTICE.txt
git status --short app/src/main/res/font
./gradlew :app:compileDebugKotlin --console=plain
```

Expected：
- `NOTICE 两份一致`
- 两个文件各命中 1 行 `9. DM Serif Display …`
- `git status` 显示 `?? app/src/main/res/font/`（没被忽略）
- Gradle 末尾 `BUILD SUCCESSFUL`（没有 `Unresolved reference: font` 之类错误；`FontWeight` 无用导入警告也不应再出现在 `SearchScreen.kt`）

---

### Task 2: 新用户文案

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`（新用户引导里的中文行）

**Interfaces:** 无（纯字符串）。

- [ ] **Step 1: 改文案**

把

```kotlin
                Text(
                    "搜索全 B 站，默认只听。想看画面时一键切换。",
                    style = MaterialTheme.typography.bodySmall,
                )
```

改成

```kotlin
                Text(
                    "探索你的 B 站",
                    style = MaterialTheme.typography.bodySmall,
                )
```

- [ ] **Step 2: 验证**

```bash
grep -rn "搜索全 B 站" app/src || echo "旧文案已无残留"
grep -n "探索你的 B 站" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt
grep -n "Seek and you shall find" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt
```

Expected：
- `旧文案已无残留`
- 第二条命中 1 行（`探索你的 B 站`）
- 第三条命中 1 行（英文行仍在）

---

### Task 3: 未登录点头像弹提示

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt`（头像去掉 `enabled`；`onAvatarClick` 的 KDoc）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`onAvatarClick` 接线；新增一个字段和 `showLoginHint()`）

**Interfaces:**
- Consumes: `SearchScreen` 的参数 `onAvatarClick: () -> Unit`（已存在）；`MainActivity` 里已有的 `openOwnSpace: () -> Unit` 和 `session`（`session.isLoggedIn`）。
- Produces: `MainActivity.showLoginHint()`（private，无参，弹「请在设置中登录」）。

- [ ] **Step 1: `SearchScreen.kt`：头像始终可点**

把

```kotlin
                            .clickable(enabled = session.isLoggedIn) { onAvatarClick() }
```

改成

```kotlin
                            .clickable { onAvatarClick() }
```

再把参数的 KDoc

```kotlin
    /** 头像点击（跳转登录账户 UP 主页）。 */
```

改成

```kotlin
    /** 头像点击。头像始终可点：已登录进自己的 UP 主页，未登录提示去设置登录（均由调用方处理）。 */
```

- [ ] **Step 2: `MainActivity.kt`：接线**

(a) 把

```kotlin
                            onAvatarClick = openOwnSpace,
```

改成

```kotlin
                            onAvatarClick = { if (session.isLoggedIn) openOwnSpace() else showLoginHint() },
```

(b) 在类顶部字段区，`pendingStorageDownload` 之后加一个字段。把

```kotlin
    private var pendingStorageDownload: List<com.biliseek.app.download.DownloadRequest>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
```

改成

```kotlin
    private var pendingStorageDownload: List<com.biliseek.app.download.DownloadRequest>? = null

    /** 未登录点头像的提示。留着引用，弹新的之前先取消上一个，连点时不会排队弹多次。 */
    private var loginHintToast: android.widget.Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
```

(c) 在 `copyToClipboard` 之后加 `showLoginHint()`。把

```kotlin
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BiliSeek", text))
    }
```

改成

```kotlin
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BiliSeek", text))
    }

    private fun showLoginHint() {
        loginHintToast?.cancel()
        loginHintToast = android.widget.Toast
            .makeText(this, "请在设置中登录", android.widget.Toast.LENGTH_SHORT)
            .also { it.show() }
    }
```

- [ ] **Step 3: 验证**

```bash
grep -n "enabled = session.isLoggedIn" app/src/main/java/com/biliseek/app/SearchScreen.kt || echo "头像不再按登录态禁用"
grep -n "clickable { onAvatarClick() }" app/src/main/java/com/biliseek/app/SearchScreen.kt
grep -nE "showLoginHint|loginHintToast|openOwnSpace" app/src/main/java/com/biliseek/app/MainActivity.kt
./gradlew :app:compileDebugKotlin --console=plain
```

Expected：
- `头像不再按登录态禁用`
- 第二条命中 1 行
- MainActivity 的 grep 共 7 行：`openOwnSpace` 定义 1 行；头像接线 1 行（已是 `if (session.isLoggedIn) openOwnSpace() else showLoginHint()`）；设置页账号卡片的原有用法 `onOpenOwnSpace = openOwnSpace` 1 行；`loginHintToast` 3 行（字段声明、`?.cancel()`、赋值）；`private fun showLoginHint` 定义 1 行
- Gradle 末尾 `BUILD SUCCESSFUL`

---

### Task 4: 全量验证与文档同步

**Files:**
- Modify: `docs/05-功能优化篇.md`（§13.1 总览表加一行；末尾追加 §13.27）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 1 两处、步骤 21 一行）
- Modify: `docs/superpowers/specs/2026-10-02-home-header-polish-design.md`（一句话：Toast 引用的保存位置）

**Interfaces:** 无。

- [ ] **Step 1: 全量验证（先于写文档，文档里要写验证结果）**

```bash
./gradlew test assembleDebug --console=plain > /tmp/biliseek-home-header-verify.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-home-header-verify.log
```

这一步耗时较长，放后台跑并轮询日志末尾。Expected：`exit=0`，日志末尾 `BUILD SUCCESSFUL`。有测试失败就停下来如实报告，不继续写「通过」。

- [ ] **Step 2: `docs/05-功能优化篇.md` 总览表加一行**

把

```markdown
| 13.26 | 下载补充：已下载页分段、下载对话框改底部弹层、只下了音频时点「看」改为联网播放 | ✅ 已实现（规则离线单测）；真机待用户验证 |
```

改成

```markdown
| 13.26 | 下载补充：已下载页分段、下载对话框改底部弹层、只下了音频时点「看」改为联网播放 | ✅ 已实现（规则离线单测）；真机待用户验证 |
| 13.27 | 首页标题栏：标题改衬线艺术字、新用户文案改「探索你的 B 站」、未登录点头像弹提示 | ✅ 已实现；真机待用户验证 |
```

- [ ] **Step 3: `docs/05-功能优化篇.md` 末尾追加 §13.27**

在文件最后一条（13.26 的「未验证」那行）之后追加。把

```markdown
- **未验证**：真机上的分段列表、底部弹层、离线条目切看。
```

改成

```markdown
- **未验证**：真机上的分段列表、底部弹层、离线条目切看。

### 13.27 首页标题栏小改（2026-10-02）

设计稿：[home-header-polish](superpowers/specs/2026-10-02-home-header-polish-design.md)。

- **标题**：「BiliSeek」由系统字体加粗改为 DM Serif Display：「Bili」正体、「Seek」斜体并取主题强调色（`colorScheme.primary`，跟着莫奈取色 / 深色模式 / 封面强调色变）。在浏览器里对比了圆线粗体、复古招牌、手写连笔、文艺衬线四个方向后选定衬线。新增 `ui/components/BiliSeekWordmark.kt`；字体用 Google Fonts 的原版 TTF（正体 + 斜体共约 148KB，放 `res/font/`，不裁剪），授权 SIL OFL 1.1，在 `NOTICE` / `assets/NOTICE.txt` 加「9. DM Serif Display」。
- **文案**：新用户首页第二行「搜索全 B 站，默认只听。想看画面时一键切换。」→「探索你的 B 站」；英文行「Seek and you shall find.」保留。这块只在无搜索历史、无播放记录时显示。
- **头像**：未登录时头像原本被禁用点击，点了没反应。改为始终可点：已登录仍进自己的 UP 空间，未登录弹系统浮框「请在设置中登录」，连点时先取消上一个、不排队（不复用 `viewModel.notifyMessage`：那条管道逐条弹、不取消）。
- **验证**：界面与文案改动，没有可抽出的判断逻辑，未新增单测；`./gradlew test assembleDebug` 通过。**未验证**：真机上标题字体的实际观感与字号（初值 34sp，待微调）、深色模式与换壁纸后的强调色、头像提示、设置 → 开源许可里的新条目。
```

- [ ] **Step 4: `docs/BiliSeek-手动测试指南.md` 步骤 1 与步骤 21**

(a) 步骤 1 的「首屏」行。把

```markdown
| 首屏   | 显示「Seek and you shall find.」+ 一行说明，**不是空白页** |
```

改成

```markdown
| 首屏   | 显示「Seek and you shall find.」+ 一行说明「探索你的 B 站」，**不是空白页**（这块只在无搜索历史、无播放记录时出现） |
```

(b) 步骤 1 的「标题栏」行，拆成两行。把

```markdown
| 标题栏 | 顶部显示「BiliSeek」+ 账号名（未登录显示「未登录」），右侧头像圆圈 |
```

改成

```markdown
| 标题栏 | 顶部显示「BiliSeek」（衬线字体：「Bili」正体、「Seek」斜体并取主题强调色）+ 账号名（未登录显示「未登录」），右侧头像圆圈 |
| 点头像 | 未登录：弹浮框「请在设置中登录」，连点不排队；已登录：进入自己的 UP 空间 |
```

(c) 步骤 21 表格里「点「退出登录」」之后加一行。把

```markdown
| 点「退出登录」 | 先弹确认框，确认后才登出 |
```

改成

```markdown
| 点「退出登录」 | 先弹确认框，确认后才登出 |
| 点「开源许可」 | 底部弹层显示全文，「第三方项目致谢」里有「9. DM Serif Display」（首页标题字体，SIL OFL 1.1） |
```

- [ ] **Step 5: 设计稿里 Toast 引用的写法对齐实现**

在 `docs/superpowers/specs/2026-10-02-home-header-polish-design.md` 里，把

```markdown
用一个 `remember` 的引用在弹新提示前 `cancel()` 上一个，避免连点时排队弹多次。
```

改成

```markdown
用 `MainActivity` 的一个成员字段保存引用（与 `videoSurface` 等字段同一写法），弹新提示前 `cancel()` 上一个，避免连点时排队弹多次。
  不复用 `viewModel.notifyMessage`：那条管道逐条弹、不取消上一条，连点会排队。
```

- [ ] **Step 6: 收尾检查**

```bash
git status --short
```

Expected（不含任何提交）：

```
 M "docs/01-\345\274\200\345\217\221\347\216\257\345\242\203\346\220\255\345\273\272.md"   ← 既有的，不是本次改的
 M NOTICE
 M app/src/main/assets/NOTICE.txt
 M app/src/main/java/com/biliseek/app/MainActivity.kt
 M app/src/main/java/com/biliseek/app/SearchScreen.kt
 M app/src/main/java/com/biliseek/app/ui/HomeScreen.kt
 M docs/05-功能优化篇.md
 M docs/BiliSeek-手动测试指南.md
?? app/src/main/java/com/biliseek/app/ui/components/BiliSeekWordmark.kt
?? app/src/main/res/font/
?? docs/superpowers/plans/2026-10-02-home-header-polish.md
?? docs/superpowers/specs/2026-10-02-home-header-polish-design.md
```

然后向用户汇报：改了什么、验证了什么（`./gradlew test assembleDebug` 结果）、哪些没验证（真机观感），并问是否现在装到手机（装机前提醒先点亮并解锁手机，安装时要在手机上点确认）。**不提交。**
