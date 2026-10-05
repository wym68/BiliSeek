# 搜索框：短链展开成活动页时列出视频 + 收紧 av 号识别 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搜索框里的 b23 短链展开成（或直接粘贴的网址就是）活动页时，打开榜单页列出页面里的视频；同时收紧 av 号识别，纯数字和链接里别的数字不再当 av 号。

**Architecture:** `parseSearchInput` 新增 `SearchIntent.EraPage`（并把 av 识别收紧成两种写法）；仓库把热门播客的「取页面 + 解析」泛化成 `eraPage(url)`；`ChartController` 把 `podcast*` 泛化成 `era*`，新增 `openEraPage(url)`；`SearchController` 认出活动页时清空搜索框并交给榜单页。

**Tech Stack:** Kotlin、kotlinx.serialization、Retrofit、Jetpack Compose、JUnit4。

设计稿：[2026-10-02-search-era-and-av-design.md](../specs/2026-10-02-search-era-and-av-design.md)

## Global Constraints

以下抄自设计稿，每个任务都隐含遵守：

- 活动页网址识别：`bilibili.com/blackboard/era/<id>.html`（`www.` / `m.` 可有可无，`http` 也认；id 为 `[0-9A-Za-z_-]+`），**规整成 `https://www.bilibili.com/blackboard/era/<id>.html`**，丢掉 `?…` 分享参数。优先级：b23 短链 → 活动页 → BV 号 → av 号 → 关键词。
- av 号只认两种：整段输入就是 `av<数字>`（不分大小写，`^av(\d{1,12})$`）；文本里带 `/video/av<数字>`，后面不能紧跟字母数字（`/video/av(\d{1,12})(?![0-9A-Za-z])`）。数字必须大于 0。纯数字、`av` 后带空格或字母、链接里别的 `/数字/`、`aid=` 都按关键词。
- 展开短链得到活动页时：**清空搜索框**（`SearchUiState()`），打开榜单页（`openEraPage(url)`）；不再退回关键词搜索。直接粘贴的活动页网址同样处理。展开后既不是视频也不是活动页的链接，行为不变。
- 榜单页：标题用页面的 `<title>`（折叠空白，取不到用「活动页」）；有多个标签页才显示期数选择条，只有一组时不显示；热门播客标题仍是「热门播客」。解析不出视频时显示 `emptyMessage`，可返回。
- 仓库：`eraPage(url, emptyMessage)` 取页面（`eraPageRead`：限定 `bilibili.com` 域、`FEED` 桶、冷却快速失败、HTTP 412 当被拦不重试）、在 `Dispatchers.Default` 上解析；`podcastPage()` = `eraPage(PODCAST_ERA_URL, "热门播客页面结构变了，没能解析")`，**替换掉** `podcastIssues()`。
- 对外新增的类型仅 `EraPageContent(title, groups)`、`SearchIntent.EraPage(url)`。
- 不做：展开接口保留页面内容、「这个链接不是视频」的提示、非 era 的活动页、占位提示文案。

执行层面的约束：

- **不执行 `git commit` / `git push`**：用户尚未同意提交，改完留在工作区。
- **不访问 Bilibili 服务器**（CLAUDE.md 安全规则）：不 `curl` B 站、不运行 App。测试全部离线。
- 工作区里 `docs/01-开发环境搭建.md` 的未提交修改不是本次改动，**不要动、不要纳入**；前面几步（标题栏小改、三个榜单、热门播客）的未提交改动也保持原样。
- 不要用 `cd` 改变工作目录，命令都在仓库根目录 `/home/minifive/own_work/BiliSeek` 下执行。
- Gradle 命令放后台跑并轮询日志末尾，不要靠退出码一个信号下结论。

---

### Task 1: `parseSearchInput` 与活动页标题（`:core:network`，测试先行）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/SearchInput.kt`（`SearchIntent.EraPage`；av 识别；活动页识别）
- Modify: `core/network/src/main/java/com/biliseek/core/network/internal/EraPagePolicy.kt`（加 `parseEraPageTitle`）
- Modify: `core/network/src/test/resources/era_podcast_sample.html`（`<title>` 改成真实页面的标题）
- Test: `core/network/src/test/java/com/biliseek/core/network/SearchInputTest.kt`（追加）
- Test: `core/network/src/test/java/com/biliseek/core/network/EraPagePolicyTest.kt`（追加）

**Interfaces:**
- Produces（Task 2、3 依赖）：
  - `SearchIntent.EraPage(val url: String)`
  - `internal fun parseEraPageTitle(html: String): String`

- [ ] **Step 1: 把样例页的 `<title>` 改成真实标题**

真实页面的 `<title>` 是「热门播客榜单」，样例里原来写的是占位的 `sample`：

```bash
sed -i 's#<title>sample</title>#<title>热门播客榜单</title>#' core/network/src/test/resources/era_podcast_sample.html
grep -o '<title>[^<]*</title>' core/network/src/test/resources/era_podcast_sample.html
```

Expected：输出 `<title>热门播客榜单</title>`。

- [ ] **Step 2: 在 `SearchInputTest` 追加测试**

把

```kotlin
    @Test
    fun `av 号直达`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("av170001"))
    }
```

改成

```kotlin
    @Test
    fun `av 号直达`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("av170001"))
    }

    @Test
    fun `av 号不分大小写`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("AV170001"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("  Av170001  "))
    }

    @Test
    fun `视频页网址里的 av 号直达，后面可以跟斜杠、问号、html`() {
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("https://www.bilibili.com/video/av170001?p=2"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("看这个 https://www.bilibili.com/video/av170001/ 好看"))
        assertEquals(SearchIntent.VideoAid(170001), parseSearchInput("https://m.bilibili.com/video/av170001.html"))
    }

    @Test
    fun `纯数字是关键词，不再当 av 号`() {
        assertEquals(SearchIntent.Keyword("2024"), parseSearchInput("2024"))
        assertEquals(SearchIntent.Keyword("170001"), parseSearchInput("170001"))
    }

    @Test
    fun `链接里别的数字不算 av 号`() {
        val favlist = "https://space.bilibili.com/12345/favlist?fid=1"
        assertEquals(SearchIntent.Keyword(favlist), parseSearchInput(favlist))
        val column = "https://www.bilibili.com/read/cv12345/"
        assertEquals(SearchIntent.Keyword(column), parseSearchInput(column))
    }

    @Test
    fun `av 后面不是纯数字、或数字是 0，都不算`() {
        assertEquals(SearchIntent.Keyword("avatar2024"), parseSearchInput("avatar2024"))
        assertEquals(SearchIntent.Keyword("av170001abc"), parseSearchInput("av170001abc"))
        assertEquals(SearchIntent.Keyword("av0"), parseSearchInput("av0"))
        assertEquals(SearchIntent.Keyword("av 170001"), parseSearchInput("av 170001"))
    }

    @Test
    fun `活动页网址规整成不带分享参数的 https 地址`() {
        val raw = "https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html" +
            "?bili_only=1&share_session_id=abc&timestamp=1"
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html"),
            parseSearchInput(raw),
        )
    }

    @Test
    fun `m 站和 http 的活动页网址也认，并规整成 https 的 www 站`() {
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/abc-1_x.html"),
            parseSearchInput("http://m.bilibili.com/blackboard/era/abc-1_x.html"),
        )
    }

    @Test
    fun `分享文本里能挑出活动页网址`() {
        assertEquals(
            SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/abc.html"),
            parseSearchInput("【热门播客榜单】 https://www.bilibili.com/blackboard/era/abc.html?x=1 快来看"),
        )
    }

    @Test
    fun `不是 era 的活动页、别的域名下的 era 路径、仿冒域名都不算活动页`() {
        val other = "https://www.bilibili.com/blackboard/activity-xyz.html"
        assertEquals(SearchIntent.Keyword(other), parseSearchInput(other))
        val evil = "https://evil.com/blackboard/era/abc.html"
        assertEquals(SearchIntent.Keyword(evil), parseSearchInput(evil))
        val spoof = "https://bilibili.com.evil.com/blackboard/era/abc.html"
        assertEquals(SearchIntent.Keyword(spoof), parseSearchInput(spoof))
    }
```

- [ ] **Step 3: 在 `EraPagePolicyTest` 追加标题测试**

(a) 加 import。把

```kotlin
import com.biliseek.core.network.internal.parseEraGroups
```

改成

```kotlin
import com.biliseek.core.network.internal.parseEraGroups
import com.biliseek.core.network.internal.parseEraPageTitle
```

(b) 在文件末尾追加。把

```kotlin
        assertNull(extractJsonObject("{}", 5))
    }
}
```

改成

```kotlin
        assertNull(extractJsonObject("{}", 5))
    }

    @Test
    fun `活动页标题取自 title 标签`() {
        assertEquals("热门播客榜单", parseEraPageTitle(sample()))
    }

    @Test
    fun `标题里的换行和多余空白折成一个空格，没有 title 标签时是空串`() {
        assertEquals("a b c", parseEraPageTitle("<head><TITLE>\n  a   b\n c </TITLE></head>"))
        assertEquals("", parseEraPageTitle("<html>no title</html>"))
    }
}
```

- [ ] **Step 4: 跑测试，确认因为 `EraPage` 和 `parseEraPageTitle` 还不存在而编译失败**

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.SearchInputTest" --tests "com.biliseek.core.network.EraPagePolicyTest" --console=plain > /tmp/biliseek-eraav-t1-red.log 2>&1; echo "exit=$?"; grep -nE '^e: ' /tmp/biliseek-eraav-t1-red.log | cut -c1-200 | head -n 6
```

Expected：`exit=1`，日志里有 `e: ... Unresolved reference: EraPage`（或 `parseEraPageTitle`）。

- [ ] **Step 5: 改 `SearchInput.kt`：加 `EraPage`、收紧 av、认活动页**

(a) 加枚举变体。把

```kotlin
    /** b23.tv 短链，需展开后再解析 */
    data class ShortLink(val url: String) : SearchIntent
}
```

改成

```kotlin
    /** b23.tv 短链，需展开后再解析 */
    data class ShortLink(val url: String) : SearchIntent

    /**
     * B 站活动页（`blackboard/era`）：列出页面里的视频。
     * 网址已规整成不带分享参数的 `https://www.bilibili.com/blackboard/era/<id>.html`。
     */
    data class EraPage(val url: String) : SearchIntent
}
```

(b) 换正则。把

```kotlin
private val BVID = Regex("BV[0-9A-Za-z]{10}")
private val AVID = Regex("(?:^|/|av)(\\d{1,12})(?:$|/|\\?)", RegexOption.IGNORE_CASE)
private val B23 = Regex("https?://b23\\.tv/[0-9A-Za-z]+")
```

改成

```kotlin
private val BVID = Regex("BV[0-9A-Za-z]{10}")

/** 整段输入就是 av 号：`av170001`（不分大小写）。纯数字、别的写法都不算。 */
private val AV_ONLY = Regex("^av(\\d{1,12})$", RegexOption.IGNORE_CASE)

/** 视频页网址里的 av 号：`…/video/av170001`，后面可以跟 `/`、`?`、`.html`。 */
private val AV_IN_URL = Regex("/video/av(\\d{1,12})(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)

private val B23 = Regex("https?://b23\\.tv/[0-9A-Za-z]+")

/** B 站活动页：`bilibili.com/blackboard/era/<id>.html`。分享参数（`?…`）不要，规整成 https 的 www 站。 */
private val ERA_PAGE = Regex(
    "https?://(?:www\\.|m\\.)?bilibili\\.com/blackboard/era/([0-9A-Za-z_-]+)\\.html",
    RegexOption.IGNORE_CASE,
)
```

(c) 换解析函数。把

```kotlin
/**
 * 解析搜索框输入。
 *
 * 「搜索即入口」意味着用户会往里粘链接。支持 BV / AV / b23.tv 短链直达，
 * 否则退回关键词搜索。
 */
fun parseSearchInput(raw: String): SearchIntent {
    val input = raw.trim()
    if (input.isEmpty()) return SearchIntent.Keyword("")

    B23.find(input)?.let { return SearchIntent.ShortLink(it.value) }
    BVID.find(input)?.let { return SearchIntent.Video(it.value) }

    // av 号：形如 av123456 或含 /av123456/ 的链接
    if (input.contains("av", ignoreCase = true) || input.all { it.isDigit() }) {
        AVID.find(input)?.let { match ->
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let {
                return SearchIntent.VideoAid(it)
            }
        }
    }

    return SearchIntent.Keyword(input)
}
```

改成

```kotlin
/**
 * 解析搜索框输入。
 *
 * 「搜索即入口」意味着用户会往里粘链接。支持 BV / AV / b23.tv 短链 / 活动页网址直达，
 * 否则退回关键词搜索。优先级：b23 短链 → 活动页 → BV 号 → av 号 → 关键词。
 * 活动页放在 BV 号前面：活动页网址的参数里可能带 BV 号字样，仍应按活动页处理。
 */
fun parseSearchInput(raw: String): SearchIntent {
    val input = raw.trim()
    if (input.isEmpty()) return SearchIntent.Keyword("")

    B23.find(input)?.let { return SearchIntent.ShortLink(it.value) }
    ERA_PAGE.find(input)?.let {
        return SearchIntent.EraPage("https://www.bilibili.com/blackboard/era/${it.groupValues[1]}.html")
    }
    BVID.find(input)?.let { return SearchIntent.Video(it.value) }

    // av 号：只认整段输入就是 av170001，或视频页网址里的 /video/av170001。
    // 纯数字、链接里别的 /数字/ 都不算——搜「2024」不该去播 av2024，UP 的 …/12345/favlist 也不是 av12345。
    (AV_ONLY.find(input) ?: AV_IN_URL.find(input))
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        ?.let { return SearchIntent.VideoAid(it) }

    return SearchIntent.Keyword(input)
}
```

- [ ] **Step 6: 在 `EraPagePolicy.kt` 末尾加 `parseEraPageTitle`**

在文件末尾追加：

```kotlin

private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val WHITESPACE = Regex("\\s+")

/** 活动页的标题：HTML 的 `<title>`，换行和多余空白折成一个空格；没有时返回空串。 */
internal fun parseEraPageTitle(html: String): String =
    TITLE_TAG.find(html)?.groupValues?.get(1)?.replace(WHITESPACE, " ")?.trim().orEmpty()
```

- [ ] **Step 7: 跑测试，确认通过；再跑整个模块确认没有误伤**

```bash
./gradlew :core:network:testDebugUnitTest --console=plain > /tmp/biliseek-eraav-t1-green.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-eraav-t1-green.log; grep -nE '^e: ' /tmp/biliseek-eraav-t1-green.log | cut -c1-220 | head -n 6
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in glob.glob("core/network/build/test-results/testDebugUnitTest/*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    for tc in r.iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            print("FAIL:", r.get("name"), tc.get("name"))
print("core:network 全部单测 tests=%d failures=%d errors=%d skipped=%d" % (t, f, e, s))
EOF
```

Expected：`exit=0`，`BUILD SUCCESSFUL`；总数 337（改前 326 + `SearchInputTest` 新增 9 + `EraPagePolicyTest` 新增 2），`failures=0 errors=0`。有失败就停下来如实报告：先判断是实现错了还是期望写错了，不要为了变绿去改期望。

---

### Task 2: 仓库把「取页面 + 解析」泛化成 `eraPage`（`:core:network`）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`（加 `EraPageContent`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（import；`podcastIssues` → `eraPage` + `podcastPage`）

**Interfaces:**
- Consumes（Task 1）：`parseEraPageTitle`、已有的 `parseEraGroups`、`EraGroup`。
- Produces（Task 3 依赖）：
  - `data class EraPageContent(val title: String, val groups: List<EraGroup>)`
  - `BiliRepository.eraPage(url: String, emptyMessage: String = …): Result<EraPageContent>`
  - `BiliRepository.podcastPage(): Result<EraPageContent>`（替换 `podcastIssues()`）

仓库在现有测试里不会被构造，这一步没有新单测，验证靠编译加整个模块既有测试不被破坏。`podcastIssues()` 被 `ChartController` 引用，Task 3 之前 `:app` 会编译失败，属预期。

- [ ] **Step 1: `ChartModels.kt` 加 `EraPageContent`**

在文件末尾追加：

```kotlin

/** 活动页内容：页面标题（HTML 的 `<title>`，没有时为空串）+ 按标签页分组的 BV 号。 */
data class EraPageContent(
    val title: String,
    val groups: List<EraGroup>,
)
```

- [ ] **Step 2: `BiliRepository.kt` 加 import**

把

```kotlin
import com.biliseek.core.network.internal.parseEraGroups
```

改成

```kotlin
import com.biliseek.core.network.internal.parseEraGroups
import com.biliseek.core.network.internal.parseEraPageTitle
```

- [ ] **Step 3: 把 `podcastIssues` 换成 `eraPage` 与 `podcastPage`**

把

```kotlin
    /**
     * 热门播客：取活动页 HTML，按页面里的标签页（第 N 期）分组返回 BV 号，最新一期在前。
     * 页面只给 BV 号，标题封面要再经 [videoCards] 补。**绑定这一个活动页**：运营换页或改版就解析不出，
     * 此时抛出明确的错误，而不是返回空列表。
     */
    suspend fun podcastIssues(): Result<List<EraGroup>> = runCatching {
        val html = eraPageRead(PODCAST_ERA_URL)
        val groups = withContext(Dispatchers.Default) { parseEraGroups(html) }
        if (groups.isEmpty()) {
            throw SearchException(SearchError.Unknown("era page: no groups"), "热门播客页面结构变了，没能解析")
        }
        groups
    }
```

改成

```kotlin
    /**
     * 取一个活动页（`blackboard/era`）：标题 + 按页面里的标签页分组的 BV 号。公开页面，不需要登录。
     * 页面只给 BV 号，标题封面要再经 [videoCards] 补。页面里认不出任何视频时抛 [emptyMessage]，
     * 而不是返回空列表。热门播客与搜索框里的活动页都走这里。
     */
    suspend fun eraPage(
        url: String,
        emptyMessage: String = "这个活动页没能解析：里面没有找到视频，或者页面结构认不出来",
    ): Result<EraPageContent> = runCatching {
        val html = eraPageRead(url)
        val content = withContext(Dispatchers.Default) {
            EraPageContent(title = parseEraPageTitle(html), groups = parseEraGroups(html))
        }
        if (content.groups.isEmpty()) {
            throw SearchException(SearchError.Unknown("era page: no groups"), emptyMessage)
        }
        content
    }

    /**
     * 热门播客：固定网址的活动页，按期（标签页）分组，最新一期在前。
     * **绑定这一个活动页**：运营换页或改版就解析不出，此时抛出明确的错误。
     */
    suspend fun podcastPage(): Result<EraPageContent> =
        eraPage(PODCAST_ERA_URL, emptyMessage = "热门播客页面结构变了，没能解析")
```

- [ ] **Step 4: 编译 `:core:network` 并跑整个模块的单测**

```bash
./gradlew :core:network:testDebugUnitTest --console=plain > /tmp/biliseek-eraav-t2.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-eraav-t2.log; echo "--- 错误 / 相关警告 ---"; grep -nE '^(e|w): .*(BiliRepository|ChartModels|EraPage)' /tmp/biliseek-eraav-t2.log | cut -c1-220 | head -n 8; echo "(以上为空 = 无)"
```

Expected：`exit=0`，`BUILD SUCCESSFUL`，没有相关新警告或错误；测试总数仍是 337。

---

### Task 3: 榜单控制器泛化、搜索框接线、榜单页标题（`:app`）

**Files:**
- Modify (整文件重写): `app/src/main/java/com/biliseek/app/chart/ChartController.kt`
- Modify: `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`（标题用 `state.title`）
- Modify: `app/src/main/java/com/biliseek/app/search/SearchController.kt`（构造参数、`submitSearch`、`expandAndOpen`、`showEraPage`）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（`SearchController` 构造加 `openEraPage`）

**Interfaces:**
- Consumes（Task 1、2）：`SearchIntent.EraPage`、`BiliRepository.eraPage()` / `podcastPage()`、`EraPageContent`。
- Produces：
  - `ChartKind(title, isEraPage)`，新增 `ERA_PAGE("活动页")`；`PODCAST` 的 `isEraPage = true`
  - `ChartState.title: String = kind.title`
  - `ChartController.openEraPage(url: String)`
  - `SearchController(…, openEraPage: (String) -> Unit = {})`

控制器是薄胶水，这一步没有新单测，验证靠编译；路由决策在 `parseSearchInput`（已有单测）。

- [ ] **Step 1: 重写 `ChartController.kt`**

用下面的内容整文件覆盖 `app/src/main/java/com/biliseek/app/chart/ChartController.kt`：

```kotlin
package com.biliseek.app.chart

import com.biliseek.app.remote.ListPlayer
import com.biliseek.core.network.BiliRepository
import com.biliseek.core.network.ChartItem
import com.biliseek.core.network.EraGroup
import com.biliseek.core.player.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 榜单页的种类：首页快捷入口里的四个榜单，加上搜索框里认出来的活动页。 */
enum class ChartKind(val title: String, val isEraPage: Boolean = false) {
    WEEKLY("每周必看"),
    PRECIOUS("入站必刷"),
    HOT_SONGS("热歌榜"),
    PODCAST("热门播客", isEraPage = true),

    /** 搜索框里认出来的活动页（b23 短链展开，或直接粘贴的网址）；标题用页面自己的 `<title>`。 */
    ERA_PAGE("活动页", isEraPage = true),
}

/** 榜单页状态。只在内存里，不落库。 */
data class ChartState(
    val kind: ChartKind,
    /** 顶栏标题。活动页用页面自己的标题，其余用 [ChartKind.title]。 */
    val title: String = kind.title,
    /** 页头一行说明（期名 · 主题 / 官方说明 / 第 N 期 / N 个视频）。 */
    val subtitle: String = "",
    val items: List<ChartItem> = emptyList(),
    /** 活动页类榜单的分组标签（热门播客是期数，最新在前）；其他榜单、或只有一组时为空，页面不显示选择条。 */
    val issues: List<String> = emptyList(),
    /** [issues] 里当前选中的下标。 */
    val selectedIssue: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 首页榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）与搜索框里的活动页。公开接口，不需要登录。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.charts` 持有。每次打开或下拉刷新重新取，不落库
 * （设计稿：docs/superpowers/specs/ 下的 home-charts、home-podcast、search-era-and-av）。
 * 风控由 [BiliRepository] 的读请求出口负责；这里不重试、不循环。
 * 点条目起播经 [playList] 回到宿主，整张榜单（活动页类是当前这一组）作为临时队列（docs/10）。
 *
 * 活动页类（热门播客、搜索框里的活动页）是页面里按标签页分组的 BV 号：先取页面得到各组的 BV 号，
 * 再按组批量取标题封面。取过卡片的组缓存在内存里，关闭榜单页或下拉刷新时清掉。
 */
class ChartController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
) {

    private val _opened = MutableStateFlow<ChartState?>(null)

    /** 当前打开的榜单页。null = 未打开。 */
    val opened: StateFlow<ChartState?> = _opened.asStateFlow()

    /** [ChartKind.ERA_PAGE] 的网址（热门播客的网址在仓库里）。 */
    private var eraUrl: String = ""

    /** 活动页取到的各组，以及已取过卡片的组（下标 → 条目）。 */
    private var eraGroups: List<EraGroup> = emptyList()
    private val eraCache = mutableMapOf<Int, List<ChartItem>>()

    fun open(kind: ChartKind) {
        resetEra()
        _opened.value = ChartState(kind, isLoading = true)
        fetch(kind)
    }

    /** 打开搜索框里认出来的活动页。[url] 已规整成 `https://www.bilibili.com/blackboard/era/<id>.html`。 */
    fun openEraPage(url: String) {
        resetEra()
        eraUrl = url
        _opened.value = ChartState(ChartKind.ERA_PAGE, isLoading = true)
        fetch(ChartKind.ERA_PAGE)
    }

    fun close() {
        _opened.value = null
    }

    /** 下拉刷新 / 失败后重试。已在加载时忽略。活动页类会重取页面，并重取当前这一组。 */
    fun retry() {
        val state = _opened.value ?: return
        if (state.isLoading) return
        _opened.value = state.copy(isLoading = true, errorMessage = null)
        fetch(state.kind, issue = state.selectedIssue)
    }

    /** 活动页类：切到第 [index] 组。取过卡片的组直接用缓存，没取过的发一次卡片请求。 */
    fun selectIssue(index: Int) {
        val state = _opened.value?.takeIf { it.kind.isEraPage } ?: return
        if (index == state.selectedIssue || index !in eraGroups.indices) return
        val cached = eraCache[index]
        _opened.value = if (cached != null) {
            state.copy(
                selectedIssue = index, items = cached, subtitle = eraSubtitle(cached),
                isLoading = false, errorMessage = null,
            )
        } else {
            state.copy(
                selectedIssue = index, items = emptyList(), subtitle = "",
                isLoading = true, errorMessage = null,
            )
        }
        if (cached == null) scope.launch { loadEraGroup(state.kind, index) }
    }

    private fun resetEra() {
        eraUrl = ""
        eraGroups = emptyList()
        eraCache.clear()
    }

    private fun fetch(kind: ChartKind, issue: Int = 0) {
        scope.launch {
            if (kind.isEraPage) fetchEra(kind, issue) else fetchChart(kind)
        }
    }

    private suspend fun fetchChart(kind: ChartKind) {
        val result = when (kind) {
            ChartKind.WEEKLY -> repository.weeklyMustWatch()
            ChartKind.PRECIOUS -> repository.preciousVideos()
            ChartKind.HOT_SONGS -> repository.hotSongs()
            ChartKind.PODCAST, ChartKind.ERA_PAGE -> error("活动页类榜单走 fetchEra")
        }
        update(kind) { current ->
            result.fold(
                onSuccess = { page ->
                    current.copy(
                        subtitle = page.subtitle,
                        items = page.items,
                        isLoading = false,
                        errorMessage = null,
                    )
                },
                onFailure = { current.copy(isLoading = false, errorMessage = it.message ?: "加载失败") },
            )
        }
    }

    /** 活动页类：先取页面里的各组（刷新时重取），再取要看的那一组的卡片。 */
    private suspend fun fetchEra(kind: ChartKind, issue: Int) {
        eraCache.clear()
        val page = when (kind) {
            ChartKind.PODCAST -> repository.podcastPage()
            else -> repository.eraPage(eraUrl)
        }.getOrElse { error ->
            failEra(kind, index = null, error)
            return
        }
        eraGroups = page.groups
        val index = issue.coerceIn(0, page.groups.lastIndex)
        // 先把标题和分组选择条显示出来，再等这一组的卡片；只有一组时不需要选择条
        update(kind) {
            it.copy(
                title = if (kind == ChartKind.ERA_PAGE) page.title.ifBlank { kind.title } else it.title,
                issues = if (page.groups.size > 1) page.groups.map { g -> g.label } else emptyList(),
                selectedIssue = index,
            )
        }
        loadEraGroup(kind, index)
    }

    private suspend fun loadEraGroup(kind: ChartKind, index: Int) {
        val group = eraGroups.getOrNull(index) ?: return
        val items = eraCache[index]
            ?: repository.videoCards(group.bvids).getOrElse { error ->
                failEra(kind, index, error)
                return
            }.also { eraCache[index] = it }
        // 等待期间用户又切到了别的组：丢弃
        update(kind) { current ->
            if (current.selectedIssue != index) {
                current
            } else {
                current.copy(
                    items = items, subtitle = eraSubtitle(items),
                    isLoading = false, errorMessage = null,
                )
            }
        }
    }

    /** [index] 为 null 表示页面本身没取到；否则只在用户还停在这一组时才显示错误。 */
    private fun failEra(kind: ChartKind, index: Int?, error: Throwable) {
        update(kind) { current ->
            if (index != null && current.selectedIssue != index) {
                current
            } else {
                current.copy(isLoading = false, errorMessage = error.message ?: "加载失败")
            }
        }
    }

    private fun eraSubtitle(items: List<ChartItem>) = "${items.size} 个视频"

    /** 只在榜单页还开着、且还是 [kind] 这一榜时改状态；关了或换榜了就丢弃（异步结果回来得晚）。 */
    private fun update(kind: ChartKind, transform: (ChartState) -> ChartState) {
        val current = _opened.value?.takeIf { it.kind == kind } ?: return
        _opened.value = transform(current)
    }

    /**
     * 点榜单条目：整张榜单作为临时队列，从这一条开始播，不覆盖用户加入的队列（docs/10）。
     * 榜单接口一次取全，不需要续页。
     */
    fun play(item: ChartItem) {
        val items = _opened.value?.items.orEmpty()
        val index = items.indexOf(item).takeIf { it >= 0 } ?: return
        playList.play(items.map { it.toRequest() }, index, loadMore = null)
    }

    // 榜单条目 → 播放请求。cid 留空：轮到播放时取分 P，多 P 原地展开（与稍后再看一致）
    private fun ChartItem.toRequest() = PlaybackRequest.Bili(
        bvid = bvid,
        title = title,
        coverUrl = coverUrl,
        author = author,
        durationSec = durationSec,
    )
}
```

- [ ] **Step 2: `ChartScreen.kt` 标题用 `state.title`**

把

```kotlin
        BsTopBar(title = state.kind.title, subtitle = state.subtitle, onNavigate = onClose)
```

改成

```kotlin
        BsTopBar(title = state.title, subtitle = state.subtitle, onNavigate = onClose)
```

再把

```kotlin
            successText = "${state.kind.title}已刷新",
```

改成

```kotlin
            successText = "${state.title}已刷新",
```

- [ ] **Step 3: `SearchController.kt` 接线**

(a) 类 KDoc 和构造参数。把

```kotlin
 * 搜索：关键词输入与提交（BV / AV / b23 短链直达）、外部分享文本、视频搜索与 UP 主搜索、
```

改成

```kotlin
 * 搜索：关键词输入与提交（BV / AV / b23 短链 / 活动页直达）、外部分享文本、视频搜索与 UP 主搜索、
```

再把

```kotlin
    private val onNewSearch: () -> Unit = {},
) {
```

改成

```kotlin
    private val onNewSearch: () -> Unit = {},
    /** 搜索框里认出活动页（b23 短链展开成活动页、或直接粘贴的网址）：交给榜单页列出里面的视频。 */
    private val openEraPage: (String) -> Unit = {},
) {
```

(b) `submitSearch` 加分支。把

```kotlin
            is SearchIntent.ShortLink -> expandAndOpen(intent.url, fallbackKeyword = raw)

            is SearchIntent.Keyword -> {
```

改成

```kotlin
            is SearchIntent.ShortLink -> expandAndOpen(intent.url, fallbackKeyword = raw)

            is SearchIntent.EraPage -> showEraPage(intent.url)

            is SearchIntent.Keyword -> {
```

(c) `expandAndOpen` 加分支和 KDoc。把

```kotlin
    /** 展开 b23 短链后二次解析。展不开或落点不可信时，退回关键词搜索。 */
```

改成

```kotlin
    /**
     * 展开 b23 短链后二次解析：落点是视频就起播，是活动页就列出页面里的视频；
     * 展不开、落点不可信、或既不是视频也不是活动页时，退回关键词搜索。
     */
```

再把

```kotlin
                        else -> doSearch(fallbackKeyword, page = 1)
                    }
                }
```

改成

```kotlin
                        is SearchIntent.EraPage -> showEraPage(resolved.url)
                        else -> doSearch(fallbackKeyword, page = 1)
                    }
                }
```

(d) 新增 `showEraPage`。把

```kotlin
    /**
     * 接住外部分享。
```

改成

```kotlin
    /** 活动页：链接已经「用掉」，清空搜索框回到首页，由榜单页列出页面里的视频。 */
    private fun showEraPage(url: String) {
        _search.value = SearchUiState()
        openEraPage(url)
    }

    /**
     * 接住外部分享。
```

- [ ] **Step 4: `AppViewModel.kt` 传 `openEraPage`**

把

```kotlin
        onNewSearch = { _pagePanel.value = PagePanelState() },
    )
```

改成

```kotlin
        onNewSearch = { _pagePanel.value = PagePanelState() },
        openEraPage = charts::openEraPage,
    )
```

- [ ] **Step 5: 编译，并跑 `:app` 既有单测**

```bash
./gradlew :app:testDebugUnitTest --console=plain > /tmp/biliseek-eraav-t3.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-eraav-t3.log; echo "--- 错误 ---"; grep -nE '^e: ' /tmp/biliseek-eraav-t3.log | cut -c1-240 | head -n 8; echo "--- 相关警告 ---"; grep -nE '^w: .*(chart/|ChartScreen|SearchController)' /tmp/biliseek-eraav-t3.log | cut -c1-240 | head -n 6; echo "(以上为空 = 无)"
```

Expected：`exit=0`，`BUILD SUCCESSFUL`；`ChartKind` 新增了 `ERA_PAGE`，`when` 都是穷尽的；`SearchIntent` 新增 `EraPage`，`submitSearch` 的 `when` 也穷尽；没有相关新警告。

---

### Task 4: 全量验证与文档同步

**Files:**
- Modify: `docs/05-功能优化篇.md`（总览表加一行；末尾追加 §13.30）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 15）
- Modify: `docs/03-架构设计篇.md`（首页描述）

**Interfaces:** 无。

- [ ] **Step 1: 全量验证（先于写文档，文档里要写验证结果）**

```bash
./gradlew test assembleDebug --console=plain > /tmp/biliseek-search-era-av-verify.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-search-era-av-verify.log
```

放后台跑并轮询日志末尾。Expected：`exit=0`，`BUILD SUCCESSFUL`。有测试失败就停下来如实报告，不继续写「通过」。

然后数出实际例数：

```bash
python3 - <<'EOF'
import glob, time, os, xml.etree.ElementTree as ET
def count(pattern):
    t = f = e = s = 0; oldest = None
    for p in glob.glob(pattern):
        r = ET.parse(p).getroot()
        t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
        e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
        m = os.path.getmtime(p); oldest = m if oldest is None else min(oldest, m)
    return t, f, e, s, (time.time() - oldest) if oldest else -1
print("core:network  tests=%d failures=%d errors=%d skipped=%d (最旧报告 %.0f 秒前)" % count("core/network/build/test-results/testDebugUnitTest/*.xml"))
print("app           tests=%d failures=%d errors=%d skipped=%d (最旧报告 %.0f 秒前)" % count("app/build/test-results/testDebugUnitTest/*.xml"))
EOF
ls -l --time-style=+%H:%M:%S app/build/outputs/apk/debug/app-debug.apk | awk '{print $5, $6, $7}'
```

Expected：两行都 `failures=0 errors=0 skipped=0`；`:core:network` 337、`:app` 288；APK 时间是刚刚。

- [ ] **Step 2: `docs/05-功能优化篇.md`：总览表**

把

```markdown
| 13.29 | 首页「热门播客」入口：解析运营活动页，按期数切换 | ✅ 已实现（解析与整理离线单测）；真机待用户验证 |
```

改成

```markdown
| 13.29 | 首页「热门播客」入口：解析运营活动页，按期数切换 | ✅ 已实现（解析与整理离线单测）；真机待用户验证 |
| 13.30 | 搜索框：短链 / 网址落到活动页时列出视频；收紧 av 号识别 | ✅ 已实现（识别规则离线单测）；真机待用户验证 |
```

- [ ] **Step 3: `docs/05-功能优化篇.md`：追加 §13.30**

在文件最后一条（§13.29 的「验证」那行）之后追加。把

```markdown
点条目起播、断网重试。
```

改成下面的内容（例数以 Step 1 的实际输出为准；若与这里的不同，改成实际数字）：

```markdown
点条目起播、断网重试。

### 13.30 搜索框：活动页列视频 + 收紧 av 号（2026-10-02）

设计稿：[search-era-and-av](superpowers/specs/2026-10-02-search-era-and-av-design.md)。接上 §13.29 留的口子（解析器通用）。

- **起因**：搜一条展开后是活动页的 b23 短链（如热门播客那条 `b23.tv/1smoWpr`），原来展开后既没 BV 号也没 av 号，退回「拿链接原文当关键词去搜」，用户看到的是搜索结果页或「没有找到」。
- **活动页**：`parseSearchInput` 新增 `SearchIntent.EraPage`，认 `bilibili.com/blackboard/era/<id>.html`（`www.` / `m.` 可有可无，`http` 也认），规整成不带分享参数的 `https://www.bilibili.com/blackboard/era/<id>.html`；优先级：b23 短链 → 活动页 → BV → av → 关键词。短链展开后得到活动页、或直接粘贴活动页网址，都**清空搜索框**并打开榜单页，复用热门播客的做法（解析标签页与 BV 号、批量补标题封面）；标题用页面的 `<title>`，有多个标签页才显示期数选择条。解析不出视频时榜单页显示「这个活动页没能解析：里面没有找到视频，或者页面结构认不出来」，可返回，不再退回搜索。展开后既不是视频也不是活动页的链接，行为不变。
- **泛化**：仓库 `podcastIssues()` 换成 `eraPage(url, emptyMessage)`（取页面 + 解析标题与分组）和 `podcastPage()`（固定网址）；`ChartController` 的 `podcast*` 改名 `era*`，新增 `openEraPage(url)`；`ChartKind` 加 `ERA_PAGE`，`ChartState` 加 `title`。
- **一次额外请求**：展开短链时 App 本来就把落点页面下载了一遍但没保留，榜单页再走自己的取页面（限速、冷却、HTTP 412 处理）取一次。只发生在「短链落到活动页」这条少见的路径上。
- **收紧 av 号**：只认整段输入就是 `av170001`（不分大小写）、或文本里带 `/video/av170001`（后面可跟 `/`、`?`、`.html`），数字须大于 0。不再认：纯数字（搜「2024」不再去播 av2024）、`av` 后带空格或字母、链接里别的 `/数字/`（UP 收藏夹 `…/12345/favlist` 不再被当成 av12345）、`aid=`。
- **验证**：`SearchInputTest` 新增 9 例（av 大小写、视频页网址、纯数字、`favlist` / `cv` 链接、`avatar2024` 等；活动页网址规整、`m.` 站与 `http`、分享文本里挑出、`activity-xxx` / 别的域名 / `bilibili.com.evil.com` 仿冒域名不算），`EraPagePolicyTest` 新增 2 例（`<title>`）；`./gradlew test assembleDebug` 通过（`:core:network` 337 例、`:app` 288 例全绿）。**未验证**：真机上搜活动页短链与直接粘贴网址的完整流程、榜单页标题与期数条、清空搜索框后的返回行为；其他 era 页面如果结构不同，会走「没能解析」的错误提示。
```

- [ ] **Step 4: `docs/BiliSeek-手动测试指南.md`：步骤 15**

把

```markdown
| `看看这个 https://b23.tv/BV17x411w7KC 快来看`      | 混着说明文字也能挑出链接并起播                       |
```

改成

```markdown
| `看看这个 https://b23.tv/BV17x411w7KC 快来看`      | 混着说明文字也能挑出链接并起播                       |
| `AV170001`（大写）、`https://www.bilibili.com/video/av170001?p=2` | 同样直接起播（av 号不分大小写；视频页网址里的 av 号也认）|
| `2024`（纯数字）                                  | 按关键词搜索，**不会**去播 av2024                   |
| `https://space.bilibili.com/12345/favlist`（UP 的收藏夹链接）| 按关键词搜索（没有结果也行），**不会**被当成 av12345 起播 |
| `https://b23.tv/1smoWpr`（热门播客活动页的短链）          | 展开后打开榜单页：标题是页面标题「热门播客榜单」，顶栏下有「第N期」选择条，列表有标题封面；**搜索框被清空**，返回回到首页 |
| `https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html`（直接粘贴活动页网址）| 同上                                                |
| 一条展开后既不是视频也不是活动页的短链                    | 仍然退回把链接文字当关键词搜索（保持原样）                |
```

- [ ] **Step 5: `docs/03-架构设计篇.md`**

把

```
AV/BV/b23.tv 直达
```

改成

```
AV/BV/b23.tv 直达（b23.tv 或网址落到活动页时，打开榜单页列出页面里的视频）
```

- [ ] **Step 6: 收尾检查**

```bash
git status --short
```

Expected：在前几步的基础上多出（**没有任何提交**）：
```
?? docs/superpowers/plans/2026-10-02-search-era-and-av.md
?? docs/superpowers/specs/2026-10-02-search-era-and-av-design.md
```
以及已有文件的修改：`SearchInput.kt`、`EraPagePolicy.kt`（未跟踪文件里）、`ChartModels.kt`（未跟踪）、`BiliRepository.kt`、`ChartController.kt`（未跟踪）、`ChartScreen.kt`（未跟踪）、`SearchController.kt`、`AppViewModel.kt`、`SearchInputTest.kt`、`EraPagePolicyTest.kt`、`era_podcast_sample.html`、三份文档。

然后向用户汇报：改了什么、验证了什么（`./gradlew test assembleDebug` 结果与例数）、哪些没验证（真机流程）、行为变化（纯数字不再当 av 号、活动页打开时搜索框被清空）、与设计稿的出入；并问是否现在装到手机（装机前提醒先点亮并解锁手机，安装时要在手机上点确认）。**不提交。**
