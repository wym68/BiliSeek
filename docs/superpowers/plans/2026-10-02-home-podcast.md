# 首页「热门播客」入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页快捷入口加第四张卡片「热门播客」：解析运营搭建的活动页 HTML 得到按期分组的 BV 号，再批量补标题封面，在榜单页里用期数选择条切换。

**Architecture:** `:core:network` 新增通用的活动页解析纯函数（`parseEraGroups`）和批量卡片整理（`resolveCardItems`），仓库新增 `podcastIssues()` / `videoCards()`；`:app` 在已有的榜单框架（`ChartKind` / `ChartState` / `ChartController` / `ChartScreen`）上加第四种，多一排期数选择条，控制器内存缓存已看过的期。

**Tech Stack:** Kotlin、kotlinx.serialization（`JsonElement`）、Retrofit、Jetpack Compose（Material 3）、JUnit4。

设计稿：[2026-10-02-home-podcast-design.md](../specs/2026-10-02-home-podcast-design.md)

## Global Constraints

以下抄自设计稿，每个任务都隐含遵守：

- 固定网址：`https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html`，写在 `BiliRepository` 里；取页面时限定 `bilibili.com` 域内（`isTrustedBiliHost`）。
- 页面数据在 `window.__BILIACT_EVAPAGEDATA__ = {…};`；用「认字符串与转义的括号配对」取出 JSON 对象，**不靠 `;` 或下一个变量名定界**。
- 解析**不写死层级路径**：找所有 `name == "EvaTabs.Panel"` 的节点（找到就不再往里钻）；标签 = `props.tabItem.tabItemProps.textContent.content`，缺失用「第N组」（N 为该面板在页面里的位置，从 1 起）；BV = 面板内所有键名为 `bvid`、值为 `BV` + 10 位字母数字的字符串，按出现顺序去重；没有 BV 的面板丢掉。没有任何标签面板时退化成一组「全部」（整份 JSON 里所有合法 `bvid` 去重）。取不到数据变量、JSON 非法、或没有分组，都返回空列表。
- 仓库把空分组变成明确的错误：「热门播客页面结构变了，没能解析」；不要退化成只有 BV 号的列表。
- 页面请求：套 `FEED` 限速桶；冷却期内本地快速失败；**HTTP 412 当作被拦**，记入全局冷却且**不重试**；其他非 2xx 报「页面加载失败：HTTP N」；解析在 `Dispatchers.Default` 上做。
- 卡片请求：`GET x/article/cards?ids=BV…,BV…`，走 `chartRead`（`accountRead` + `FEED` 桶）；一次最多 30 个；按卡片里的 `bvid` 字段对应，不依赖 map 键的写法；取不到或没有 `bvid` 的跳过；响应 DTO 复用 `PopularVideo`。
- 不加任何重试循环。不落库。
- 打开 = 页面 1 次 + 当期卡片 1 次；切换到没看过的期只多 1 次卡片请求；看过的期缓存在 `ChartController` 内存里，关闭榜单页或下拉刷新时清掉；下拉刷新 = 重取页面 + 重取当期。
- 榜单页：热门播客在顶栏下多一排期数选择条（横向滚动的 `FilterChip`，默认选最新一期）；`issues` 为空的榜单不显示这一行；顶栏副标题显示「N 个视频」。点条目整期作临时队列起播（沿用 `ListPlayer.play(items, index, loadMore = null)`）。
- 异步结果回来时，榜单页已关闭、换榜、或用户又切到了别的期，一律丢弃。
- 首页：第四张卡片「热门播客」（`Icons.Outlined.Podcasts`），与前三张一行排开。
- 对外新增的类型仅 `EraGroup(label, bvids)`（`ChartModels.kt`）。
- 不做：下载、长按菜单、「b23 短链展开成活动页时列视频」、搜索框对非视频链接的处理。

执行层面的约束：

- **不执行 `git commit` / `git push`**：用户尚未同意提交，改完留在工作区。
- **不访问 Bilibili 服务器**（CLAUDE.md 安全规则）：不 `curl` B 站、不运行 App。测试全部离线；`x/article/cards` 的样例按文档手写，**没对照过真实响应**；活动页样例是从用户存下的真实页面裁剪的（`core/network/src/test/resources/era_podcast_sample.html`，已生成）。
- 工作区里 `docs/01-开发环境搭建.md` 的未提交修改不是本次改动，**不要动、不要纳入**；前两步（首页标题栏小改、三个榜单）的未提交改动也保持原样。
- 不要用 `cd` 改变工作目录，命令都在仓库根目录（`./`）下执行。
- Gradle 命令放后台跑并轮询日志末尾，不要靠退出码一个信号下结论。

---

### Task 1: 活动页解析与卡片整理（`:core:network`，测试先行）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt`（加 `EraGroup`）
- Create: `core/network/src/main/java/com/biliseek/core/network/internal/EraPagePolicy.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt`（`toChartItem` 改 `internal`；加 `resolveCardItems`）
- Test: `core/network/src/test/java/com/biliseek/core/network/EraPagePolicyTest.kt`（新建）
- Test: `core/network/src/test/java/com/biliseek/core/network/ChartPolicyTest.kt`（追加）
- Test: `core/network/src/test/java/com/biliseek/core/network/ChartDtoTest.kt`（追加）
- 已存在的测试资源：`core/network/src/test/resources/era_podcast_sample.html`

**Interfaces:**
- Produces（Task 2、3 依赖）：
  - `data class EraGroup(val label: String, val bvids: List<String>)`
  - `internal fun parseEraGroups(html: String): List<EraGroup>`
  - `internal fun extractJsonObject(text: String, start: Int): String?`
  - `internal fun resolveCardItems(bvids: List<String>, cards: Map<String, PopularVideo?>): List<ChartItem>`

- [ ] **Step 1: 新建活动页解析测试**

创建 `core/network/src/test/java/com/biliseek/core/network/EraPagePolicyTest.kt`：

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.internal.extractJsonObject
import com.biliseek.core.network.internal.parseEraGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动页（blackboard/era）HTML 的解析。
 *
 * 真实页面样例取自用户用 App 同款 UA 存下的《热门播客》页，裁剪成 3 期、每期 3 个 BV，
 * 保留真实的嵌套结构；其余边界情况用内联 JSON 钉住。
 */
class EraPagePolicyTest {

    private val a = "BV1aaaaaaaaa"
    private val b = "BV1bbbbbbbbb"
    private val c = "BV1ccccccccc"

    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("era_podcast_sample.html")) { "缺少测试资源" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 把一段 JSON 包成最小的活动页 HTML：数据变量后面还跟着别的脚本内容。 */
    private fun page(json: String) =
        "<html><script>\nwindow.__BILIACT_EVAPAGEDATA__ = $json;\nwindow.other = {};\n</script></html>"

    /** 一个标签面板；[label] 为 null 表示没有标签文字。 */
    private fun panel(label: String?, vararg bvids: String): String {
        val props = if (label == null) {
            ""
        } else {
            "\"props\":{\"tabItem\":{\"tabItemProps\":{\"textContent\":{\"content\":\"$label\"}}}},"
        }
        val ids = bvids.joinToString(",") { "{\"bvid\":\"$it\"}" }
        return "{\"name\":\"EvaTabs.Panel\",$props\"slots\":[{\"children\":" +
            "[{\"name\":\"NativePickResource\",\"props\":{\"ids\":[$ids]}}]}]}"
    }

    private fun tabs(vararg panels: String) =
        "{\"layerTree\":[{\"name\":\"EvaTabs\",\"slots\":[{\"children\":[${panels.joinToString(",")}]}]}]}"

    @Test
    fun `真实页面样例按期分组，保持页面里的顺序`() {
        val groups = parseEraGroups(sample())
        assertEquals(listOf("第63期", "第62期", "第61期"), groups.map { it.label })
        assertEquals(listOf("BV1bLei6BEGo", "BV1Zhen63EeK", "BV1LMeb6FEqp"), groups[0].bvids)
        assertEquals(listOf("BV15iY56DE85", "BV1XMYE6xEkM", "BV1CCY76kEmt"), groups[1].bvids)
        assertEquals(listOf("BV1hwtB6YEnk", "BV1jati6NE1i", "BV1bdbA6YEXr"), groups[2].bvids)
    }

    @Test
    fun `面板内重复的 BV 去重并保持顺序`() {
        val groups = parseEraGroups(page(tabs(panel("第2期", a, b, a))))
        assertEquals(listOf(a, b), groups.single().bvids)
    }

    @Test
    fun `有面板时只认面板里的 bvid，面板外的不算`() {
        val json = "{\"outside\":{\"bvid\":\"$c\"},\"layerTree\":[{\"name\":\"EvaTabs\"," +
            "\"slots\":[{\"children\":[${panel("第1期", a)}]}]}]}"
        val groups = parseEraGroups(page(json))
        assertEquals(1, groups.size)
        assertEquals(listOf(a), groups[0].bvids)
    }

    @Test
    fun `没有标签面板时退化成一组全部`() {
        val json = "{\"layerTree\":[{\"name\":\"Whatever\",\"props\":{\"ids\":[{\"bvid\":\"$a\"},{\"bvid\":\"$b\"}]}}]}"
        val groups = parseEraGroups(page(json))
        assertEquals(listOf("全部"), groups.map { it.label })
        assertEquals(listOf(a, b), groups[0].bvids)
    }

    @Test
    fun `面板缺标签文字时用第N组，没有 BV 的面板丢掉，N 是面板在页面里的位置`() {
        val groups = parseEraGroups(page(tabs(panel(null, a), panel("第3期"), panel(null, b))))
        assertEquals(listOf("第1组", "第3组"), groups.map { it.label })
        assertEquals(listOf(listOf(a), listOf(b)), groups.map { it.bvids })
    }

    @Test
    fun `不合法的 BV 号忽略`() {
        val groups = parseEraGroups(page(tabs(panel("第1期", "BV1short", "BV1aaaaaaaaaaTOOLONG", a))))
        assertEquals(listOf(a), groups.single().bvids)
    }

    @Test
    fun `字符串里的花括号和转义引号不影响括号配对`() {
        val json = "{\"text\":\"a } b { \\\" c\",\"layerTree\":[{\"name\":\"EvaTabs\"," +
            "\"slots\":[{\"children\":[${panel("第1期", a)}]}]}]}"
        assertEquals(listOf(a), parseEraGroups(page(json)).single().bvids)
    }

    @Test
    fun `没有数据变量、数据不是合法 JSON、或没有任何 bvid 时返回空列表`() {
        assertTrue(parseEraGroups("<html>nothing</html>").isEmpty())
        assertTrue(parseEraGroups("<script>window.__BILIACT_EVAPAGEDATA__ = {\"layerTree\": [;</script>").isEmpty())
        assertTrue(parseEraGroups("<script>window.__BILIACT_EVAPAGEDATA__ = {\"layerTree\":[]};</script>").isEmpty())
    }

    @Test
    fun `括号配对取出完整的对象，不被字符串里的右花括号截断`() {
        val text = "x = {\"a\":{\"b\":1},\"c\":\"}\"}; y = {}"
        assertEquals("{\"a\":{\"b\":1},\"c\":\"}\"}", extractJsonObject(text, text.indexOf('{')))
    }

    @Test
    fun `起点不是左花括号或括号没配上时返回 null`() {
        assertNull(extractJsonObject("abc", 0))
        assertNull(extractJsonObject("{\"a\":1", 0))
        assertNull(extractJsonObject("{}", 5))
    }
}
```

- [ ] **Step 2: 在 `ChartPolicyTest` 追加卡片整理测试**

(a) 加 import。把

```kotlin
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
```

改成

```kotlin
import com.biliseek.core.network.internal.resolveCardItems
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
```

(b) 在文件末尾追加。把

```kotlin
        assertEquals(listOf(2, null), page.items.map { it.rank })
    }
}
```

改成

```kotlin
        assertEquals(listOf(2, null), page.items.map { it.rank })
    }

    // ── 批量视频卡片 → 条目 ──────────────────────────

    @Test
    fun `卡片按请求顺序整理，按 bvid 对应而不是 map 的键`() {
        val cards = mapOf(
            "k2" to PopularVideo(
                bvid = "BV1bbbbbbbbb", title = "乙", pic = "http://i0.hdslb.com/b.jpg", duration = 20,
                owner = PopularOwner(name = "UP乙"), stat = PopularStat(view = 5),
            ),
            "k1" to PopularVideo(bvid = "BV1aaaaaaaaa", title = "甲", duration = 10),
        )
        val items = resolveCardItems(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb"), cards)
        assertEquals(listOf("甲", "乙"), items.map { it.title })
        assertEquals("https://i0.hdslb.com/b.jpg", items[1].coverUrl)
        assertEquals("UP乙", items[1].author)
        assertEquals(20L, items[1].durationSec)
        assertEquals(5L, items[1].playCount)
    }

    @Test
    fun `取不到的卡片和 null 值都跳过`() {
        val cards = mapOf<String, PopularVideo?>(
            "BV1aaaaaaaaa" to PopularVideo(bvid = "BV1aaaaaaaaa", title = "甲"),
            "BV1ccccccccc" to null,
        )
        val items = resolveCardItems(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb", "BV1ccccccccc"), cards)
        assertEquals(listOf("甲"), items.map { it.title })
    }

    @Test
    fun `没有 bvid 的卡片当作不可用`() {
        val cards = mapOf<String, PopularVideo?>("x" to PopularVideo(bvid = "", title = "坏卡片"))
        assertEquals(emptyList<ChartItem>(), resolveCardItems(listOf("BV1aaaaaaaaa"), cards))
    }
}
```

- [ ] **Step 3: 在 `ChartDtoTest` 追加卡片响应解析测试**

(a) 加 import。把

```kotlin
import com.biliseek.core.network.dto.PreciousData
```

改成

```kotlin
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
```

(b) 在文件末尾追加。把

```kotlin
        assertEquals("BV1b", s.creationBvid)
    }
}
```

改成

```kotlin
        assertEquals("BV1b", s.creationBvid)
    }

    @Test
    fun `批量视频卡片按 id 为键解析，null 值与未知字段都不抛错`() {
        val r = json.decodeFromString<BiliResponse<Map<String, PopularVideo?>>>(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "BV1xx411c79H":{"aid":706,"bvid":"BV1xx411c79H","cid":3724723,"title":"【東方】Bad Apple!!",
                "pic":"http://i1.hdslb.com/bfs/archive/7534.jpg","duration":219,
                "owner":{"mid":37,"name":"折射","face":"https://i0.hdslb.com/bfs/face/x.gif"},
                "stat":{"view":11329388,"danmaku":82247},"vt_switch":true},
              "BV1gone000000":null
            }}
            """.trimIndent(),
        )
        val data = r.data!!
        assertEquals(setOf("BV1xx411c79H", "BV1gone000000"), data.keys)
        val v = data.getValue("BV1xx411c79H")!!
        assertEquals("BV1xx411c79H", v.bvid)
        assertEquals("折射", v.owner?.name)
        assertEquals(11329388L, v.stat?.view)
        assertNull(data.getValue("BV1gone000000"))
    }
}
```

- [ ] **Step 4: 跑测试，确认因为类型和函数还不存在而编译失败**

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.EraPagePolicyTest" --tests "com.biliseek.core.network.ChartPolicyTest" --tests "com.biliseek.core.network.ChartDtoTest" --console=plain > /tmp/biliseek-podcast-t1-red.log 2>&1; echo "exit=$?"; grep -nE '^e: ' /tmp/biliseek-podcast-t1-red.log | cut -c1-200 | head -n 6
```

Expected：`exit=1`，日志里有 `e: ... Unresolved reference: parseEraGroups`（或 `resolveCardItems`、`extractJsonObject`）。

- [ ] **Step 5: 加 `EraGroup` 模型**

在 `core/network/src/main/java/com/biliseek/core/network/ChartModels.kt` 末尾追加：

```kotlin

/** 活动页里的一组视频（页面里的一个标签页，如「第63期」）。 */
data class EraGroup(
    val label: String,
    val bvids: List<String>,
)
```

- [ ] **Step 6: 写活动页解析**

创建 `core/network/src/main/java/com/biliseek/core/network/internal/EraPagePolicy.kt`：

```kotlin
package com.biliseek.core.network.internal

import com.biliseek.core.network.EraGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 活动页里内嵌的页面数据变量。 */
private val PAGE_DATA = Regex("""window\.__BILIACT_EVAPAGEDATA__\s*=\s*""")

/** 合法 BV 号：`BV` + 10 位字母数字。 */
private val BVID_FULL = Regex("BV[0-9A-Za-z]{10}")

private const val TABS_PANEL = "EvaTabs.Panel"

/**
 * 从活动页（`blackboard/era`）的 HTML 里取出按标签页分组的 BV 号。
 *
 * 页面是运营用搭建工具做的，数据在内嵌的 `window.__BILIACT_EVAPAGEDATA__`。**不写死层级路径**，
 * 搭建器的布局改了也尽量能认：找所有标签面板，标签取面板的标签文字，BV 取面板内所有 `bvid`。
 * 没有任何标签面板时，整页的 BV 号退化成一组「全部」。解析不出来返回空列表。
 */
internal fun parseEraGroups(html: String): List<EraGroup> {
    val marker = PAGE_DATA.find(html) ?: return emptyList()
    val text = extractJsonObject(html, marker.range.last + 1) ?: return emptyList()
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()

    val panels = mutableListOf<JsonObject>()
    collectPanels(root, panels)
    if (panels.isEmpty()) {
        val all = collectBvids(root)
        return if (all.isEmpty()) emptyList() else listOf(EraGroup(label = "全部", bvids = all))
    }
    return panels.mapIndexedNotNull { index, panel ->
        val bvids = collectBvids(panel)
        if (bvids.isEmpty()) null else EraGroup(label = panelLabel(panel) ?: "第${index + 1}组", bvids = bvids)
    }
}

/**
 * 从 [start]（必须是 `{`）开始，取出配对的 JSON 对象文本。
 * 认字符串和转义：字符串里的花括号、`\"` 都不参与配对。括号没配上、起点不对返回 null。
 */
internal fun extractJsonObject(text: String, start: Int): String? {
    if (start !in text.indices || text[start] != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
    }
    return null
}

/** 找所有标签面板。找到一个就不再往它里面钻，免得嵌套的标签页被重复计数。 */
private fun collectPanels(element: JsonElement, out: MutableList<JsonObject>) {
    when (element) {
        is JsonObject ->
            if ((element["name"] as? JsonPrimitive)?.contentOrNull == TABS_PANEL) {
                out += element
            } else {
                element.values.forEach { collectPanels(it, out) }
            }
        is JsonArray -> element.forEach { collectPanels(it, out) }
        else -> Unit
    }
}

/** [element] 里所有键名为 `bvid`、值是合法 BV 号的字符串，按出现顺序去重。 */
private fun collectBvids(element: JsonElement): List<String> {
    val found = LinkedHashSet<String>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> e.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (key == "bvid" && text != null && BVID_FULL.matches(text)) found += text else walk(value)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(element)
    return found.toList()
}

private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

private fun panelLabel(panel: JsonObject): String? =
    (panel.field("props").field("tabItem").field("tabItemProps").field("textContent").field("content") as? JsonPrimitive)
        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
```

- [ ] **Step 7: 加卡片整理，并把 `toChartItem` 改成 `internal`**

在 `core/network/src/main/java/com/biliseek/core/network/internal/ChartPolicy.kt` 里：

(a) 把

```kotlin
private fun PopularVideo.toChartItem(): ChartItem? =
```

改成

```kotlin
internal fun PopularVideo.toChartItem(): ChartItem? =
```

(b) 在 `toChartPage`（入站必刷那个）之后、热歌榜的 `toChartPage` 之前不用动；直接在文件末尾追加：

```kotlin

/**
 * 把批量取到的视频卡片按请求顺序整理成条目：按卡片里的 `bvid` 对应（不依赖 map 键的写法），
 * 取不到、值为 null、或没有 `bvid`（已不可用）的跳过。
 */
internal fun resolveCardItems(bvids: List<String>, cards: Map<String, PopularVideo?>): List<ChartItem> {
    val byBvid = cards.values.filterNotNull().filter { it.bvid.isNotBlank() }.associateBy { it.bvid }
    return bvids.mapNotNull { byBvid[it]?.toChartItem() }
}
```

- [ ] **Step 8: 跑测试，确认通过**

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.EraPagePolicyTest" --tests "com.biliseek.core.network.ChartPolicyTest" --tests "com.biliseek.core.network.ChartDtoTest" --console=plain > /tmp/biliseek-podcast-t1-green.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-podcast-t1-green.log; grep -nE '^e: ' /tmp/biliseek-podcast-t1-green.log | cut -c1-220 | head -n 6
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
for name in ("EraPagePolicyTest", "ChartPolicyTest", "ChartDtoTest"):
    for f in glob.glob(f"core/network/build/test-results/testDebugUnitTest/*.{name}.xml"):
        r = ET.parse(f).getroot()
        print(name, "tests=%s failures=%s errors=%s skipped=%s" % (r.get("tests"), r.get("failures"), r.get("errors"), r.get("skipped")))
EOF
```

Expected：`exit=0`，`BUILD SUCCESSFUL`；`EraPagePolicyTest` 10 例、`ChartPolicyTest` 18 例（原 15 + 新 3）、`ChartDtoTest` 7 例（原 6 + 新 1），全部 `failures=0 errors=0`。有失败就停下来如实报告，先判断是实现错了还是期望写错了，不要为了变绿去改期望。

---

### Task 2: 接口声明与仓库方法（`:core:network`）

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`（import `PopularVideo`；加 `eraPage`、`articleCards`）
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`（imports；`podcastIssues`、`videoCards`、`eraPageRead`；两个常量）

**Interfaces:**
- Consumes（Task 1）：`EraGroup`、`parseEraGroups`、`resolveCardItems`、`ChartItem`。
- Produces（Task 3 依赖）：`BiliRepository.podcastIssues(): Result<List<EraGroup>>`、`BiliRepository.videoCards(bvids: List<String>): Result<List<ChartItem>>`。

仓库在现有测试里不会被构造，这一步没有新单测，验证靠编译加整个模块既有测试不被破坏。

- [ ] **Step 1: `BiliApi.kt` 加 import 与两个接口**

(a) 把

```kotlin
import com.biliseek.core.network.dto.PreciousData
```

改成

```kotlin
import com.biliseek.core.network.dto.PopularVideo
import com.biliseek.core.network.dto.PreciousData
```

(b) 在 `musicToplistSongs` 之后加两个接口。把

```kotlin
    /** 音频榜单某一期的歌曲。 */
    @GET("x/copyright-music-publicity/toplist/music_list")
    suspend fun musicToplistSongs(@Query("list_id") listId: Long): BiliResponse<MusicListData>
```

改成

```kotlin
    /** 音频榜单某一期的歌曲。 */
    @GET("x/copyright-music-publicity/toplist/music_list")
    suspend fun musicToplistSongs(@Query("list_id") listId: Long): BiliResponse<MusicListData>

    /**
     * 取 B 站活动页（`blackboard/era`）的 HTML。页面是运营搭建的，内嵌 JSON 里有按期分组的视频 BV 号。
     * 返回体是整页 HTML，调用方自己读。
     */
    @GET
    suspend fun eraPage(@Url url: String): Response<ResponseBody>

    /**
     * 批量取视频卡片：`ids` 用逗号分隔，可填 BV / av 号。返回以每个 id 为键的对象，
     * 只要求 `Referer` 在 bilibili.com 下，不需要 WBI。热门播客用它给一期的 BV 号补标题、封面、
     * UP 主、时长、播放量。取不到的 id 可能没有键，或值为 null。
     */
    @GET("x/article/cards")
    suspend fun articleCards(@Query("ids") ids: String): BiliResponse<Map<String, PopularVideo?>>
```

- [ ] **Step 2: `BiliRepository.kt` 加 imports**

把

```kotlin
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
import com.biliseek.core.network.internal.resolveLatestWeeklyNumber
```

改成

```kotlin
import com.biliseek.core.network.internal.parseEraGroups
import com.biliseek.core.network.internal.resolveCardItems
import com.biliseek.core.network.internal.resolveLatestMusicPeriod
import com.biliseek.core.network.internal.resolveLatestWeeklyNumber
```

再把

```kotlin
import kotlinx.serialization.json.put
```

改成

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
```

- [ ] **Step 3: `BiliRepository.kt` 加仓库方法**

在 `chartRead` 之后、`// ── UP 主搜索与空间投稿（M11-D）` 之前追加。把

```kotlin
    private suspend fun <T> chartRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }
```

改成

```kotlin
    private suspend fun <T> chartRead(what: String, request: suspend () -> BiliResponse<T>): T? =
        accountRead(what) {
            requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) { request() }
        }

    // ── 热门播客（运营搭建的活动页）──────────────────────────

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

    /**
     * 批量取视频的标题、封面、UP 主、时长、播放量（`x/article/cards`，一次请求），按传入顺序返回，
     * 取不到或已不可用的跳过。公开接口，不需要登录。一次最多 [MAX_CARD_IDS] 个。
     */
    suspend fun videoCards(bvids: List<String>): Result<List<ChartItem>> = runCatching {
        val ids = bvids.distinct().take(MAX_CARD_IDS)
        if (ids.isEmpty()) return@runCatching emptyList()
        val cards = chartRead("视频信息") { api.articleCards(ids.joinToString(",")) }.orEmpty()
        resolveCardItems(ids, cards)
    }

    /**
     * 取 B 站活动页（`bilibili.com` 域内）的 HTML。公开页面，不需要登录。
     *
     * 页面不是 JSON 接口，没有 `code` 字段：冷却期内本地快速失败；HTTP 412 当作被拦，
     * 记入全局冷却且**不重试**；其他非 2xx 报「页面加载失败」。套 FEED 限速桶。
     */
    private suspend fun eraPageRead(url: String): String {
        require(isTrustedBiliHost(url)) { "只取 bilibili.com 域内的页面：$url" }
        authGate.awaitReady()
        ensureNotCoolingRead()
        val response = requestThrottle.gate(ThrottleBucket.FEED, FEED_MIN_INTERVAL_MS, THROTTLE_JITTER_MS) {
            api.eraPage(url)
        }
        if (response.code() == 412) {
            riskCooldown.onRiskControl()
            throw SearchException(SearchError.Blocked, "触发风控（HTTP 412），已进入退避")
        }
        if (!response.isSuccessful) {
            throw SearchException(
                SearchError.Unknown("HTTP ${response.code()}"),
                "页面加载失败：HTTP ${response.code()}",
            )
        }
        riskCooldown.onSuccess()
        return response.body()?.string().orEmpty()
    }
```

- [ ] **Step 4: `BiliRepository.kt` 加两个常量**

把

```kotlin
        const val THROTTLE_JITTER_MS = 300L
    }
```

改成

```kotlin
        const val THROTTLE_JITTER_MS = 300L

        /** 热门播客：运营搭建的 H5 活动页（App 热门页的「热门播客」入口分享出来的页面）。 */
        const val PODCAST_ERA_URL = "https://www.bilibili.com/blackboard/era/fuwAmTrtqFLexuSx.html"

        /** 批量取视频卡片时一次最多带的 BV 数（一期最多 21 个，留余量）。 */
        const val MAX_CARD_IDS = 30
    }
```

- [ ] **Step 5: 编译并跑整个模块的单测**

```bash
./gradlew :core:network:testDebugUnitTest --console=plain > /tmp/biliseek-podcast-t2.log 2>&1; echo "exit=$?"; tail -n 6 /tmp/biliseek-podcast-t2.log; echo "--- 错误 / 相关警告 ---"; grep -nE '^(e|w): .*(BiliApi|BiliRepository|EraPage|Chart)' /tmp/biliseek-podcast-t2.log | cut -c1-220 | head -n 8; echo "(以上为空 = 无)"
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in glob.glob("core/network/build/test-results/testDebugUnitTest/*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print("core:network 全部单测 tests=%d failures=%d errors=%d skipped=%d" % (t, f, e, s))
EOF
```

Expected：`exit=0`，`BUILD SUCCESSFUL`；`:core:network` 全部单测 `failures=0 errors=0`（总数 = 改前 312 + 新增 14 = 326）；没有相关新警告或错误。

---

### Task 3: 控制器支持热门播客（`:app`）

**Files:**
- Modify (整文件重写): `app/src/main/java/com/biliseek/app/chart/ChartController.kt`

**Interfaces:**
- Consumes（Task 2）：`BiliRepository.podcastIssues()` / `videoCards()`；`EraGroup`。
- Produces（Task 4 依赖）：
  - `ChartKind.PODCAST`（`title` = 「热门播客」）
  - `ChartState` 新增 `issues: List<String> = emptyList()`、`selectedIssue: Int = 0`
  - `ChartController.selectIssue(index: Int)`；`open` / `close` / `retry` / `play` 签名不变

控制器是薄胶水，仓库是具体类、现有控制器也没有仓库级测试，这一步没有新单测，验证靠编译；行为由真机验证。

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

/** 首页快捷入口里的榜单。 */
enum class ChartKind(val title: String) {
    WEEKLY("每周必看"),
    PRECIOUS("入站必刷"),
    HOT_SONGS("热歌榜"),
    PODCAST("热门播客"),
}

/** 榜单页状态。只在内存里，不落库。 */
data class ChartState(
    val kind: ChartKind,
    /** 页头一行说明（期名 · 主题 / 官方说明 / 第 N 期 / N 个视频）。 */
    val subtitle: String = "",
    val items: List<ChartItem> = emptyList(),
    /** 热门播客的期数标签（最新在前）；其他榜单为空，页面不显示期数选择条。 */
    val issues: List<String> = emptyList(),
    /** [issues] 里当前选中的下标。 */
    val selectedIssue: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 首页榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）。公开接口，不需要登录。
 *
 * 从 AppViewModel 拆出，由 `AppViewModel.charts` 持有。每次打开或下拉刷新重新取，不落库
 * （设计稿：docs/superpowers/specs/2026-10-02-home-charts-design.md、…-home-podcast-design.md）。
 * 风控由 [BiliRepository] 的读请求出口负责；这里不重试、不循环。
 * 点条目起播经 [playList] 回到宿主，整张榜单（热门播客是当前这一期）作为临时队列（docs/10）。
 *
 * 热门播客是活动页里按期分组的 BV 号：先取页面得到各期的 BV 号，再按期批量取标题封面。
 * 取过卡片的期缓存在内存里，关闭榜单页或下拉刷新时清掉。
 */
class ChartController(
    private val scope: CoroutineScope,
    private val repository: BiliRepository,
    private val playList: ListPlayer,
) {

    private val _opened = MutableStateFlow<ChartState?>(null)

    /** 当前打开的榜单页。null = 未打开。 */
    val opened: StateFlow<ChartState?> = _opened.asStateFlow()

    /** 热门播客页面里取到的各期分组，以及已取过卡片的期（下标 → 条目）。 */
    private var podcastGroups: List<EraGroup> = emptyList()
    private val podcastCache = mutableMapOf<Int, List<ChartItem>>()

    fun open(kind: ChartKind) {
        podcastGroups = emptyList()
        podcastCache.clear()
        _opened.value = ChartState(kind, isLoading = true)
        fetch(kind)
    }

    fun close() {
        _opened.value = null
    }

    /** 下拉刷新 / 失败后重试。已在加载时忽略。热门播客会重取页面，并重取当前这一期。 */
    fun retry() {
        val state = _opened.value ?: return
        if (state.isLoading) return
        _opened.value = state.copy(isLoading = true, errorMessage = null)
        fetch(state.kind, issue = state.selectedIssue)
    }

    /** 热门播客：切到第 [index] 期。取过卡片的期直接用缓存，没取过的发一次卡片请求。 */
    fun selectIssue(index: Int) {
        val state = _opened.value?.takeIf { it.kind == ChartKind.PODCAST } ?: return
        if (index == state.selectedIssue || index !in podcastGroups.indices) return
        val cached = podcastCache[index]
        _opened.value = if (cached != null) {
            state.copy(
                selectedIssue = index, items = cached, subtitle = podcastSubtitle(cached),
                isLoading = false, errorMessage = null,
            )
        } else {
            state.copy(
                selectedIssue = index, items = emptyList(), subtitle = "",
                isLoading = true, errorMessage = null,
            )
        }
        if (cached == null) scope.launch { loadPodcastIssue(index) }
    }

    private fun fetch(kind: ChartKind, issue: Int = 0) {
        scope.launch {
            when (kind) {
                ChartKind.PODCAST -> fetchPodcast(issue)
                else -> fetchChart(kind)
            }
        }
    }

    private suspend fun fetchChart(kind: ChartKind) {
        val result = when (kind) {
            ChartKind.WEEKLY -> repository.weeklyMustWatch()
            ChartKind.PRECIOUS -> repository.preciousVideos()
            ChartKind.HOT_SONGS -> repository.hotSongs()
            ChartKind.PODCAST -> error("热门播客走 fetchPodcast")
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

    /** 热门播客：先取页面里的各期分组（刷新时重取），再取要看的那一期的卡片。 */
    private suspend fun fetchPodcast(issue: Int) {
        podcastCache.clear()
        val groups = repository.podcastIssues().getOrElse { error ->
            failPodcast(index = null, error)
            return
        }
        podcastGroups = groups
        val index = issue.coerceIn(0, groups.lastIndex)
        // 先把期数选择条显示出来，再等这一期的卡片
        update(ChartKind.PODCAST) { it.copy(issues = groups.map { g -> g.label }, selectedIssue = index) }
        loadPodcastIssue(index)
    }

    private suspend fun loadPodcastIssue(index: Int) {
        val group = podcastGroups.getOrNull(index) ?: return
        val items = podcastCache[index]
            ?: repository.videoCards(group.bvids).getOrElse { error ->
                failPodcast(index, error)
                return
            }.also { podcastCache[index] = it }
        // 等待期间用户又切到了别的期：丢弃
        update(ChartKind.PODCAST) { current ->
            if (current.selectedIssue != index) {
                current
            } else {
                current.copy(
                    items = items, subtitle = podcastSubtitle(items),
                    isLoading = false, errorMessage = null,
                )
            }
        }
    }

    /** [index] 为 null 表示页面本身没取到；否则只在用户还停在这一期时才显示错误。 */
    private fun failPodcast(index: Int?, error: Throwable) {
        update(ChartKind.PODCAST) { current ->
            if (index != null && current.selectedIssue != index) {
                current
            } else {
                current.copy(isLoading = false, errorMessage = error.message ?: "加载失败")
            }
        }
    }

    private fun podcastSubtitle(items: List<ChartItem>) = "${items.size} 个视频"

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

- [ ] **Step 2: 编译**

```bash
./gradlew :app:compileDebugKotlin --console=plain > /tmp/biliseek-podcast-t3.log 2>&1; echo "exit=$?"; tail -n 5 /tmp/biliseek-podcast-t3.log; grep -nE '^e: ' /tmp/biliseek-podcast-t3.log | cut -c1-240 | head -n 8; echo "(以上 e: 为空 = 无编译错误；此时 ChartScreen 还没带 onIssueSelect，MainActivity 的调用暂时可编译)"
```

Expected：`exit=0`，`BUILD SUCCESSFUL`。`ChartKind` 新增了 `PODCAST` 分支，`when` 都是穷尽的，不应有新的编译错误。

---

### Task 4: 期数选择条与首页第四张卡片（`:app` UI）

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`（整文件重写：加 `onIssueSelect` 与期数选择条）
- Modify: `app/src/main/java/com/biliseek/app/ui/HomeScreen.kt`（import、KDoc、注释、第四张卡片）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（`ChartScreen` 调用加 `onIssueSelect`）

**Interfaces:**
- Consumes（Task 3）：`ChartKind.PODCAST`、`ChartState.issues` / `selectedIssue`、`ChartController.selectIssue`。
- Produces：`ChartScreen(state, onClose, onRetry, onItemClick, onIssueSelect, modifier)`。

- [ ] **Step 1: 重写 `ChartScreen.kt`**

用下面的内容整文件覆盖 `app/src/main/java/com/biliseek/app/ui/ChartScreen.kt`：

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biliseek.app.chart.ChartState
import com.biliseek.app.chart.chartRowSubtitle
import com.biliseek.core.network.ChartItem
import com.biliseek.designsystem.component.BsEmptyState
import com.biliseek.designsystem.component.BsErrorState
import com.biliseek.designsystem.component.BsLoadingState
import com.biliseek.designsystem.component.BsTopBar

/**
 * 榜单页（每周必看 / 入站必刷 / 热歌榜 / 热门播客）。公开接口，不需要登录。
 *
 * 形态照「稍后再看」页：顶栏（副标题放页头说明）+ 下拉刷新 + 加载中 / 失败可重试 / 空列表 + 列表。
 * 热门播客在顶栏下多一排期数选择条（[ChartState.issues] 非空时显示）。
 * 列表不给 key：热歌榜里不同的歌可能指向同一个视频，用 bvid 当 key 会因重复而崩溃。
 */
@Composable
fun ChartScreen(
    state: ChartState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (ChartItem) -> Unit,
    onIssueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BsTopBar(title = state.kind.title, subtitle = state.subtitle, onNavigate = onClose)

        if (state.issues.isNotEmpty()) {
            IssueChips(state.issues, state.selectedIssue, onIssueSelect)
        }

        PullRefreshContainer(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            errorMessage = state.errorMessage,
            successText = "${state.kind.title}已刷新",
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> BsLoadingState()

                state.errorMessage != null && state.items.isEmpty() ->
                    BsErrorState(state.errorMessage, onRetry = onRetry)

                state.items.isEmpty() -> BsEmptyState("榜单暂时是空的", icon = Icons.Outlined.Leaderboard)

                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.items) { item ->
                        ChartRow(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

/** 期数选择条：横向滚动的 FilterChip，最新一期在最左。 */
@Composable
private fun IssueChips(issues: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(issues) { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ChartRow(item: ChartItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.rank?.let { rank ->
            Text(
                rank.toString(),
                modifier = Modifier.widthIn(min = 28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (rank <= 3) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        CoverThumbnail(item.coverUrl, width = 96.dp)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chartRowSubtitle(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 2: `HomeScreen.kt` 加第四张卡片**

(a) import。把

```kotlin
import androidx.compose.material.icons.outlined.Leaderboard
```

改成

```kotlin
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Podcasts
```

(b) KDoc 和注释里的榜单清单。把

```kotlin
 * 快捷入口是官方榜单（每周必看 / 入站必刷 / 热歌榜），全站同一份，不需要登录。
```

改成

```kotlin
 * 快捷入口是官方榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客），全站同一份，不需要登录。
```

再把

```kotlin
        // 快捷入口：官方榜单（每周必看 / 入站必刷 / 热歌榜），不需要登录
```

改成

```kotlin
        // 快捷入口：官方榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客），不需要登录
```

(c) 第四张卡片。把

```kotlin
                QuickEntryCard(
                    icon = Icons.Outlined.Leaderboard,
                    label = ChartKind.HOT_SONGS.title,
                    onClick = { onOpenChart(ChartKind.HOT_SONGS) },
                    modifier = Modifier.weight(1f),
                )
```

改成

```kotlin
                QuickEntryCard(
                    icon = Icons.Outlined.Leaderboard,
                    label = ChartKind.HOT_SONGS.title,
                    onClick = { onOpenChart(ChartKind.HOT_SONGS) },
                    modifier = Modifier.weight(1f),
                )
                QuickEntryCard(
                    icon = Icons.Outlined.Podcasts,
                    label = ChartKind.PODCAST.title,
                    onClick = { onOpenChart(ChartKind.PODCAST) },
                    modifier = Modifier.weight(1f),
                )
```

- [ ] **Step 3: `MainActivity.kt` 传 `onIssueSelect`**

把

```kotlin
                                onItemClick = viewModel.charts::play,
                            )
```

改成

```kotlin
                                onItemClick = viewModel.charts::play,
                                onIssueSelect = viewModel.charts::selectIssue,
                            )
```

- [ ] **Step 4: 验证**

```bash
grep -n "onIssueSelect" app/src/main/java/com/biliseek/app/MainActivity.kt app/src/main/java/com/biliseek/app/ui/ChartScreen.kt
grep -n "Podcasts" app/src/main/java/com/biliseek/app/ui/HomeScreen.kt
./gradlew :app:compileDebugKotlin --console=plain > /tmp/biliseek-podcast-t4.log 2>&1; echo "exit=$?"; tail -n 5 /tmp/biliseek-podcast-t4.log; echo "--- 错误 ---"; grep -nE '^e: ' /tmp/biliseek-podcast-t4.log | cut -c1-240 | head -n 8; echo "--- 相关警告 ---"; grep -nE '^w: .*(ChartScreen|ChartController|HomeScreen)' /tmp/biliseek-podcast-t4.log | cut -c1-240 | head -n 8; echo "(以上为空 = 无)"
```

Expected：`onIssueSelect` 在 `MainActivity.kt` 1 处、`ChartScreen.kt` 2 处（形参 + 传给 `IssueChips`）；`Podcasts` 在 `HomeScreen.kt` 2 处（import + 图标使用）；Gradle `exit=0`、`BUILD SUCCESSFUL`；这几个文件没有新警告。

---

### Task 5: 全量验证与文档同步

**Files:**
- Modify: `docs/05-功能优化篇.md`（§10.0 待办一行；§13.1 总览表加一行；末尾追加 §13.29）
- Modify: `docs/BiliSeek-手动测试指南.md`（步骤 14）
- Modify: `docs/03-架构设计篇.md`（控制器表与首页描述）

**Interfaces:** 无。

- [ ] **Step 1: 全量验证（先于写文档，文档里要写验证结果）**

```bash
./gradlew test assembleDebug --console=plain > /tmp/biliseek-home-podcast-verify.log 2>&1; echo "exit=$?"; tail -n 8 /tmp/biliseek-home-podcast-verify.log
```

这一步耗时较长，放后台跑并轮询日志末尾。Expected：`exit=0`，日志末尾 `BUILD SUCCESSFUL`。有测试失败就停下来如实报告，不继续写「通过」。

然后数出这次实际的测试例数，Step 3 要写进文档：

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

Expected：两行都是 `failures=0 errors=0 skipped=0`；`:core:network` 总数 326（改前 312 + 新增 14）、`:app` 总数 288 不变；APK 时间是刚刚。

- [ ] **Step 2: `docs/05-功能优化篇.md`：待办与总览表**

(a) §10.0 的「纯粹没排」一行，去掉已经做了的热门播客。把

```markdown
、热门播客入口（App 热门页的运营活动页，接口待抓包，见 §13.28）。
```

改成

```markdown
。
```

（即这一行变回「……桌面歌词（卡 Lyricon 授权回溯）。」）

(b) §13.1 总览表。把

```markdown
| 13.28 | 首页快捷入口改为榜单：每周必看 / 入站必刷 / 热歌榜 | ✅ 已实现（纯函数与解析离线单测）；真机待用户验证 |
```

改成

```markdown
| 13.28 | 首页快捷入口改为榜单：每周必看 / 入站必刷 / 热歌榜 | ✅ 已实现（纯函数与解析离线单测）；真机待用户验证 |
| 13.29 | 首页「热门播客」入口：解析运营活动页，按期数切换 | ✅ 已实现（解析与整理离线单测）；真机待用户验证 |
```

- [ ] **Step 3: `docs/05-功能优化篇.md`：追加 §13.29**

在文件最后一条（§13.28 的「验证」那行）之后追加。把

```markdown
榜单页真机外观、断网后重试。
```

改成下面的内容（例数以 Step 1 的实际输出为准；若与这里的不同，改成实际数字）：

```markdown
榜单页真机外观、断网后重试。

### 13.29 首页「热门播客」入口（2026-10-02）

设计稿：[home-podcast](superpowers/specs/2026-10-02-home-podcast-design.md)。补做 §13.28 里搁置的热门播客。

- **它是什么**：官方 App 热门页的「热门播客」入口，分享出来是运营搭建的 H5 活动页 `blackboard/era/fuwAmTrtqFLexuSx.html`（App 端走 gRPC，不走）。用户用 App 同款 UA 存下真实页面核实：HTML 里内嵌 `window.__BILIACT_EVAPAGEDATA__`，其中一个 `EvaTabs` 下有 63 个标签面板（「第63期」…「第1期」，最新在前），每期一个编辑推荐卡，10–21 个 `{"bvid": …}`；**只有 BV 号，没有标题封面**。
- **入口与页面**：首页第四张卡片「热门播客」，进入榜单页；顶栏下多一排期数选择条（`FilterChip`，默认最新一期），点哪期看哪期；列表行与其他榜单一致，点条目整期作临时队列起播，副标题「N 个视频」。
- **取数**：打开 = 取页面 HTML 1 次 + 当期卡片 1 次；切到没看过的期只多 1 次卡片请求；看过的期缓存在内存，关闭或下拉刷新时清掉。卡片用 `GET x/article/cards?ids=BV…,BV…`（社区文档：可传 BV，只要 `Referer`，不要 WBI，一次一期），按卡片里的 `bvid` 对应。页面请求限定 `bilibili.com` 域、套 `FEED` 桶、冷却期快速失败，HTTP 412 当被拦（记冷却、不重试），其他非 2xx 报「页面加载失败」。
- **解析**：`core/network/internal/EraPagePolicy.kt`，不写死层级路径——找所有 `EvaTabs.Panel`，标签取 `props.tabItem.tabItemProps.textContent.content`，BV 取面板内所有合法 `bvid`；没有标签面板时退化成一组「全部」；JSON 用认字符串与转义的括号配对取出，不靠 `;` 定界。解析不出时显示「热门播客页面结构变了，没能解析」，不白屏。解析器与 `videoCards` 都是通用的，之后「b23 短链展开成活动页时列视频」可复用，本次不接。
- **结构**：`:core:network` 新增 `EraGroup`、`resolveCardItems`、仓库方法 `podcastIssues()` / `videoCards()`、接口 `eraPage` / `articleCards`；`:app` 的 `ChartKind` 加 `PODCAST`，`ChartState` 加 `issues` / `selectedIssue`，`ChartController` 加 `selectIssue`，`ChartScreen` 加期数选择条。
- **风险**：只绑定这一个活动页，运营换页（网址变）或改版（组件结构变）就失效；`x/article/cards` 只来自社区文档。
- **验证**：`EraPagePolicyTest`（10 例，样例取自用户存下的真实页面，裁剪成 3 期 × 3 个 BV，保留真实嵌套结构）、`ChartPolicyTest` 新增 3 例、`ChartDtoTest` 新增 1 例（卡片响应按文档手写，**没对照过真实响应**）；`./gradlew test assembleDebug` 通过（`:core:network` 326 例、`:app` 288 例全绿）。**未验证**：真机上热门播客页能否取到并解析、`x/article/cards` 的真实返回与限制、期数选择条与切换、标题封面显示、点条目起播、断网重试。
```

- [ ] **Step 4: `docs/BiliSeek-手动测试指南.md`：步骤 14**

(a) 把

```markdown
| 搜索历史（未聚焦） | **不直接显示**——首页只显示 slogan（新用户）/ 播放热力 / 快捷入口（每周必看、入站必刷、热歌榜，不需要登录） |
```

改成

```markdown
| 搜索历史（未聚焦） | **不直接显示**——首页只显示 slogan（新用户）/ 播放热力 / 快捷入口（每周必看、入站必刷、热歌榜、热门播客，不需要登录） |
```

(b) 把

```markdown
| 登录前后各点一次榜单卡片 | 两种状态下都能打开、内容一致（榜单不依赖账号） |
```

改成

```markdown
| 登录前后各点一次榜单卡片 | 两种状态下都能打开、内容一致（榜单不依赖账号） |
| 点「热门播客」 | 进入榜单页：顶栏下有一排「第N期」选择条，默认选最新一期；列表是这一期的视频，每行有封面、标题、「UP · 时长 · 播放量」，副标题「N 个视频」 |
| 在「热门播客」里切换期数 | 点另一期，列表换成那一期（第一次切到会有一下加载）；切回看过的期是立刻出来的 |
| 「热门播客」下拉刷新 | 重新取页面和当前这一期，期数条与列表都刷新 |
| 点「热门播客」里的条目 | 起播，MiniPlayer 出现；整期作队列 |
| 「热门播客」显示「页面结构变了，没能解析」 | 说明运营改了活动页或换了网址，不是网络问题；告诉开发者，需要重新对页面结构（这是预期会发生的失效方式） |
| 对照官方 App：首页 → 热门 → 热门播客 | 期数、每期的视频大体对得上（App 里的顺序和这里可能略有差别） |
```

- [ ] **Step 5: `docs/03-架构设计篇.md`**

(a) 控制器表。把

```markdown
| `ChartController` | `app/chart/` | 首页榜单：每周必看 / 入站必刷 / 热歌榜（公开接口，不需登录） |
```

改成

```markdown
| `ChartController` | `app/chart/` | 首页榜单：每周必看 / 入站必刷 / 热歌榜 / 热门播客（公开接口，不需登录；热门播客解析运营活动页的内嵌 JSON 再批量取卡片） |
```

(b) 首页描述。把

```
快捷入口（每周必看 / 入站必刷 / 热歌榜，点开是 Tab 内覆盖的榜单页）
```

改成

```
快捷入口（每周必看 / 入站必刷 / 热歌榜 / 热门播客，点开是 Tab 内覆盖的榜单页）
```

(c) 不落库清单。把

```markdown
首页榜单（每周必看 / 入站必刷 / 热歌榜）
```

改成

```markdown
首页榜单（每周必看 / 入站必刷 / 热歌榜 / 热门播客）
```

- [ ] **Step 6: 收尾检查**

```bash
git status --short
```

Expected：在前两步的基础上多出：
```
?? core/network/src/main/java/com/biliseek/core/network/internal/EraPagePolicy.kt
?? core/network/src/test/java/com/biliseek/core/network/EraPagePolicyTest.kt
?? core/network/src/test/resources/
?? docs/superpowers/plans/2026-10-02-home-podcast.md
?? docs/superpowers/specs/2026-10-02-home-podcast-design.md
```
以及已有文件的修改（`BiliApi.kt`、`BiliRepository.kt`、`ChartController.kt`、`ChartScreen.kt`、`HomeScreen.kt`、`MainActivity.kt`、`ChartPolicy.kt`、`ChartPolicyTest.kt`、`ChartDtoTest.kt`、三份文档）。**没有任何提交。**

然后向用户汇报：改了什么、验证了什么（`./gradlew test assembleDebug` 结果与例数）、哪些没验证（真机上页面与 `x/article/cards` 的真实行为）、与设计稿的出入；并问是否现在装到手机（装机前提醒先点亮并解锁手机，安装时要在手机上点确认）。**不提交。**
