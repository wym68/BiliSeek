# 动态卡片点击区域重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `DynamicCard`（动态 tab + UP 空间页共用）的"整卡可点"改成三块独立热区——头像/昵称跳 UP 主页、视频行播放、正文原地展开/收起——并删除被取代的 `DynamicDetailSheet` 模态弹层。

**Architecture:** 沿用 spec（`docs/superpowers/specs/2026-09-13-dynamic-card-click-regions-design.md`）确定的方案：`core:network` 的 `DynamicEntry` 补 `authorMid` 字段；新增 `app/data/DynamicExpandPolicy.kt` 承载"展开/收起下一状态"的纯函数（按项目惯例把决策逻辑从 `AppViewModel` 抽出，单测覆盖）；`AppViewModel` 用一个按 `idStr` 索引的 `Map` 状态替换原来"全局唯一一个详情弹层"的 `_dynamicDetail`，允许多张卡片同时展开；`DynamicCard` 拆分三块独立点击区域，正文用"溢出检测 + 右下角浮层按钮"的方式做原地展开，复用既有的四级降级详情接口（`BiliRepository.dynamicDetail`）取全文。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit4（纯函数单测，不引入 mock 框架），Compose UI Test（`createComposeRule`，只用本地构造的假状态）。

## Global Constraints

- **禁止自动化代码打真实 B 站服务器**（CLAUDE.md 安全规则）。本计划的单测/UI 测试只用本地构造的假数据和 lambda 协作者；`AppViewModel.toggleDynamicExpand` 里真正发起网络请求的那段代码，本计划只验证到"编译通过、逻辑自洽"，四级降级拉取是否真的按预期工作需要人工真机验证（不在本计划任务范围内）。
- **仓库测试哲学**：把决策逻辑抽成纯函数（`*Policy.kt`），用 JUnit4 直接测，不引入 mockk/mockito。`AppViewModel` 目前零单测覆盖，本计划延续这个约定，不为它新增测试基础设施。
- **Public API 约定**：`:core:network` 公开类型列表里已有 `DynamicEntry`（DTO），给它加一个 `authorMid: Long = 0` 字段不算新增公开类型，不违反"公开面是刻意为之"的约定。`DynamicCard` 保持 `internal`（module-scoped）不变——本计划的 Compose UI 测试改为测公开入口 `DynamicScreen`，不需要放开 `DynamicCard` 的可见性。
- **风格约定**：按钮/提示统一用文字而非图标字体（既有约定），本计划新增的"…展开"/"收起"/"加载失败，点击重试" 延续这个风格。

---

## Task 1: `DynamicEntry` 补 `authorMid` 字段

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/DynamicFeed.kt:14-46`（`DynamicEntry` 定义）
- Modify: `core/network/src/main/java/com/biliseek/core/network/DynamicFeed.kt:114-127`（`parseDynamicItem` 里 `base` 的构造）
- Test: `core/network/src/test/java/com/biliseek/core/network/DynamicFeedParseTest.kt`

**Interfaces:**
- Produces: `DynamicEntry.authorMid: Long`（默认 0），供后续任务的头像/昵称点击跳转 UP 主页使用。

原始 JSON 里 `module_author.mid` 已经被 `DynamicAuthor` DTO 解析出来（`core/network/src/main/java/com/biliseek/core/network/dto/DynamicDto.kt:69`），只是没有映射进 `DynamicEntry`。

- [ ] **Step 1: 写失败的单测**

在 `core/network/src/test/java/com/biliseek/core/network/DynamicFeedParseTest.kt` 末尾（`}` 收尾前）追加：

```kotlin

    @Test
    fun `module_author mid 映射为 authorMid`() {
        val data = json.decodeFromString<DynamicFeedData>(
            """
            {
              "items": [{
                "id_str": "a1", "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": { "mid": 12345, "name": "UP" },
                  "module_dynamic": { "major": { "archive": { "bvid": "BV1", "title": "t" } } }
                }
              }]
            }
            """.trimIndent(),
        )
        val entry = parseDynamicItems(data.items)[0]
        assertEquals(12345L, entry.authorMid)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.DynamicFeedParseTest"`
Expected: FAIL——`entry.authorMid` 编译不过（`DynamicEntry` 还没有这个字段）。

- [ ] **Step 3: 给 `DynamicEntry` 加字段**

在 `DynamicFeed.kt` 里把：

```kotlin
    /**
     * 转发 / 评论 / 点赞。转发动态里是**转发者自己的**互动数，与 orig 不同——
     * 与 BiliPai 语义一致："这条转发有多少人转了"是用户的视角，不是原作者的。
     * 接口漏 module_stat 时为 null，UI 据此判断是否渲染底部互动栏。
     */
    val stat: DynamicStatModule? = null,
)
```

改为：

```kotlin
    /**
     * 转发 / 评论 / 点赞。转发动态里是**转发者自己的**互动数，与 orig 不同——
     * 与 BiliPai 语义一致："这条转发有多少人转了"是用户的视角，不是原作者的。
     * 接口漏 module_stat 时为 null，UI 据此判断是否渲染底部互动栏。
     */
    val stat: DynamicStatModule? = null,
    /** UP 主 mid，用于头像/昵称点击跳转空间页；来自 `module_author.mid`。 */
    val authorMid: Long = 0,
)
```

- [ ] **Step 4: 在 `parseDynamicItem` 里补映射**

把：

```kotlin
    val base = DynamicEntry(
        idStr = item.idStr,
        authorName = author.name,
        authorFace = normalizeCoverUrl(author.face),
        pubTs = author.pubTs,
```

改为：

```kotlin
    val base = DynamicEntry(
        idStr = item.idStr,
        authorName = author.name,
        authorFace = normalizeCoverUrl(author.face),
        authorMid = author.mid,
        pubTs = author.pubTs,
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :core:network:test --tests "com.biliseek.core.network.DynamicFeedParseTest"`
Expected: PASS（含新增用例与原有全部用例）。

- [ ] **Step 6: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/DynamicFeed.kt core/network/src/test/java/com/biliseek/core/network/DynamicFeedParseTest.kt
git commit -m "feat: DynamicEntry 补 authorMid 字段，支持头像/昵称跳转 UP 主页"
```

---

## Task 2: 新增 `DynamicExpandPolicy.kt`（展开/收起的纯函数状态机）

**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/DynamicExpandPolicy.kt`
- Test: `app/src/test/java/com/biliseek/app/data/DynamicExpandPolicyTest.kt`

**Interfaces:**
- Consumes: `com.biliseek.core.network.DynamicEntry`（Task 1 之后的形状，不直接用到 `authorMid`）
- Produces:
  - `data class DynamicExpandState(val expanded: Boolean = false, val entry: DynamicEntry? = null, val error: String? = null)`
  - `fun nextDynamicExpandState(current: DynamicExpandState?): DynamicExpandState`
  - `fun shouldFetchDynamicDetail(next: DynamicExpandState): Boolean`
  - 供 Task 3 的 `AppViewModel.toggleDynamicExpand` 编排网络请求时调用。

四种状态转换：首次点击→标记展开待拉取；已展开且已有正文→收起（保留缓存）；收起但有缓存→直接展开不重拉；出错后点击→当作"重试"，重新标记展开待拉取。

- [ ] **Step 1: 写失败的单测**

创建 `app/src/test/java/com/biliseek/app/data/DynamicExpandPolicyTest.kt`：

```kotlin
package com.biliseek.app.data

import com.biliseek.core.network.DynamicEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicExpandPolicyTest {

    private val fetchedEntry = DynamicEntry(
        idStr = "d1",
        authorName = "UP",
        authorFace = "",
        pubTs = 0,
        descText = "完整正文",
        rawType = "DYNAMIC_TYPE_OPUS",
        video = null,
        coverUrl = "",
    )

    @Test
    fun `首次点击 标记展开并需要拉取`() {
        val next = nextDynamicExpandState(null)
        assertTrue(next.expanded)
        assertNull(next.entry)
        assertTrue(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `已展开且已有正文 再点击收起`() {
        val expanded = DynamicExpandState(expanded = true, entry = fetchedEntry)
        val next = nextDynamicExpandState(expanded)
        assertFalse(next.expanded)
        assertEquals(fetchedEntry, next.entry)
        assertFalse(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `收起但有缓存 再点击直接展开不重新拉取`() {
        val collapsedCached = DynamicExpandState(expanded = false, entry = fetchedEntry)
        val next = nextDynamicExpandState(collapsedCached)
        assertTrue(next.expanded)
        assertEquals(fetchedEntry, next.entry)
        assertFalse(shouldFetchDynamicDetail(next))
    }

    @Test
    fun `加载失败后点击重试 重新标记展开并需要拉取`() {
        val errored = DynamicExpandState(expanded = true, error = "网络错误")
        val next = nextDynamicExpandState(errored)
        assertTrue(next.expanded)
        assertNull(next.entry)
        assertNull(next.error)
        assertTrue(shouldFetchDynamicDetail(next))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.DynamicExpandPolicyTest"`
Expected: FAIL——找不到 `DynamicExpandState`/`nextDynamicExpandState`/`shouldFetchDynamicDetail`（还没创建）。

- [ ] **Step 3: 实现纯函数**

创建 `app/src/main/java/com/biliseek/app/data/DynamicExpandPolicy.kt`：

```kotlin
package com.biliseek.app.data

import com.biliseek.core.network.DynamicEntry

/**
 * 动态卡片正文的展开态（按 idStr 独立维护，允许多张卡片同时展开）。
 * `expanded=false` 且 `entry=null` 是初始态（未展开过，等价于 Map 里没有这个 key）。
 */
data class DynamicExpandState(
    val expanded: Boolean = false,
    val entry: DynamicEntry? = null,
    val error: String? = null,
)

/**
 * 点击"展开/收起"后的下一状态（纯函数，不含网络请求，由调用方按
 * [shouldFetchDynamicDetail] 决定是否发起请求）。
 *
 * - 未展开过 → 标记展开，待拉取。
 * - 已展开且已有正文 → 收起，正文保留在 [DynamicExpandState.entry] 里（收起不清缓存）。
 * - 收起但有缓存正文 → 直接展开，不重新拉取。
 * - 出错态（`error != null`）→ 点击视为"重试"，重新标记展开、清掉错误，待拉取。
 */
fun nextDynamicExpandState(current: DynamicExpandState?): DynamicExpandState = when {
    current == null -> DynamicExpandState(expanded = true)
    current.error != null -> DynamicExpandState(expanded = true)
    current.expanded && current.entry != null -> current.copy(expanded = false)
    current.expanded -> current
    current.entry != null -> current.copy(expanded = true)
    else -> DynamicExpandState(expanded = true)
}

/** [next] 是否需要发起网络请求拉正文：标记为展开、且没有缓存正文、且不在错误态。 */
fun shouldFetchDynamicDetail(next: DynamicExpandState): Boolean =
    next.expanded && next.entry == null && next.error == null
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.biliseek.app.data.DynamicExpandPolicyTest"`
Expected: PASS（4 项全绿）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/biliseek/app/data/DynamicExpandPolicy.kt app/src/test/java/com/biliseek/app/data/DynamicExpandPolicyTest.kt
git commit -m "feat: 新增 DynamicExpandPolicy 纯函数状态机，驱动动态卡片原地展开/收起"
```

---

## Task 3: `AppViewModel` 用 `dynamicExpand` 替换 `dynamicDetail`

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt:243-248`（`DynamicDetailState` 定义）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt:1706-1737`（`_dynamicDetail`/`openDynamicDetail`/`dismissDynamicDetail`）
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`（import 块）

**Interfaces:**
- Consumes: Task 2 的 `DynamicExpandState`/`nextDynamicExpandState`/`shouldFetchDynamicDetail`；已存在的 `network.repository.dynamicDetail(idStr, seed): Result<DynamicEntry>`。
- Produces:
  - `val dynamicExpand: StateFlow<Map<String, DynamicExpandState>>`
  - `fun toggleDynamicExpand(entry: DynamicEntry)`
  - 供 Task 5/6 的 `DynamicScreen`/`UpSpaceScreen`、Task 7 的 `MainActivity` 使用。删除 `dynamicDetail`/`openDynamicDetail`/`dismissDynamicDetail`——全仓库没有其他调用点（已用 grep 确认），可以安全整体替换。

这一步没有独立可跑的单测（`AppViewModel` 本身零单测覆盖，见 Global Constraints），用编译通过 + Task 8 的 Compose UI 测试间接验证。

- [ ] **Step 1: 替换 `DynamicDetailState` 定义**

把：

```kotlin
/** 图文动态详情弹层状态（点开看全文）。entry=null 且 error=null 表示加载中。 */
data class DynamicDetailState(
    val idStr: String,
    val entry: DynamicEntry? = null,
    val error: String? = null,
)
```

改为：

```kotlin
```

（整段删掉——状态形状搬到了 `DynamicExpandPolicy.kt` 的 `DynamicExpandState`，按 idStr 用 Map 索引，不再需要在这个数据类里自带 `idStr`。）

- [ ] **Step 2: 替换详情/展开状态与函数**

把：

```kotlin
    // ── 图文动态详情（点开看全文，四级降级）────────────────

    private val _dynamicDetail = MutableStateFlow<DynamicDetailState?>(null)
    val dynamicDetail: StateFlow<DynamicDetailState?> = _dynamicDetail.asStateFlow()

    /**
     * 点开图文动态卡片：按需拉详情（web detail → opus/detail → desktop → rid），
     * 与 feed 条目合并后展示。请求在 repository 层有 LRU 缓存，重复打开不重复拉。
     */
    fun openDynamicDetail(entry: DynamicEntry) {
        if (_dynamicDetail.value != null) return
        _dynamicDetail.value = DynamicDetailState(idStr = entry.idStr)
        viewModelScope.launch {
            network.repository.dynamicDetail(entry.idStr, seed = entry)
                .onSuccess { detail ->
                    // 弹层可能已被关闭/切换，只回写匹配的条目
                    if (_dynamicDetail.value?.idStr == entry.idStr) {
                        _dynamicDetail.value = DynamicDetailState(idStr = entry.idStr, entry = detail)
                    }
                }
                .onFailure { e ->
                    if (_dynamicDetail.value?.idStr == entry.idStr) {
                        _dynamicDetail.value = DynamicDetailState(
                            idStr = entry.idStr,
                            error = e.message ?: "加载失败",
                        )
                    }
                }
        }
    }

    fun dismissDynamicDetail() { _dynamicDetail.value = null }
```

改为：

```kotlin
    // ── 动态卡片正文原地展开/收起（四级降级拉全文）────────────────

    private val _dynamicExpand = MutableStateFlow<Map<String, DynamicExpandState>>(emptyMap())
    val dynamicExpand: StateFlow<Map<String, DynamicExpandState>> = _dynamicExpand.asStateFlow()

    /**
     * 点"展开/收起"：状态转换交给纯函数 [nextDynamicExpandState]，这里只负责按需
     * 发起网络请求（web detail → opus/detail → desktop → rid，与旧版全文弹层同一路径，
     * repository 层有 LRU 缓存）。按 idStr 独立维护，允许多张卡片同时展开。
     */
    fun toggleDynamicExpand(entry: DynamicEntry) {
        val idStr = entry.idStr
        val next = nextDynamicExpandState(_dynamicExpand.value[idStr])
        _dynamicExpand.value = _dynamicExpand.value + (idStr to next)
        if (!shouldFetchDynamicDetail(next)) return
        viewModelScope.launch {
            network.repository.dynamicDetail(idStr, seed = entry)
                .onSuccess { detail ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(entry = detail))
                    }
                }
                .onFailure { e ->
                    val cur = _dynamicExpand.value[idStr]
                    if (cur?.expanded == true && cur.entry == null) {
                        _dynamicExpand.value = _dynamicExpand.value + (idStr to cur.copy(error = e.message ?: "加载失败"))
                    }
                }
        }
    }
```

- [ ] **Step 3: 加 import**

在 import 块（`import com.biliseek.app.data.RemoteListState` 附近）加两行：

```kotlin
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.data.nextDynamicExpandState
import com.biliseek.app.data.shouldFetchDynamicDetail
```

- [ ] **Step 4: 编译确认（此时 UI 层还没跟上，预期报错，先确认是"缺 UI 参数"而不是这个文件本身的问题）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL——`DynamicScreen.kt`/`UpSpaceScreen.kt`/`MainActivity.kt` 仍引用旧的 `onOpenDetail`/`openDynamicDetail`/`dismissDynamicDetail`/`DynamicDetailSheet`（Task 5-7 处理）。确认报错信息只指向这些还没改的文件，不是 `AppViewModel.kt` 自身语法错误。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/biliseek/app/AppViewModel.kt
git commit -m "refactor: AppViewModel 用按 idStr 索引的 dynamicExpand 替换单槽位 dynamicDetail"
```

---

## Task 4: `DynamicCard` 拆分点击区域 + 正文原地展开

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:1-51`（import 块）
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:269-443`（`DynamicCard` 整个函数，新增 `DynamicDescText` 私有组件）

**Interfaces:**
- Consumes: Task 2 的 `DynamicExpandState`。
- Produces: `DynamicCard` 新签名——去掉 `onDetailClick`，新增 `onAuthorClick: () -> Unit = {}`、`expandState: DynamicExpandState? = null`、`onToggleExpand: () -> Unit = {}`；供 Task 5/6 的调用点使用。

- [ ] **Step 1: 加 import**

在 `DynamicScreen.kt` 的 import 块里，`import com.biliseek.app.DynamicUiState` 后面加一行，并在 `androidx.compose.ui.text.style.TextOverflow` 附近加三行：

```kotlin
import com.biliseek.app.data.DynamicExpandState
```

```kotlin
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
```

- [ ] **Step 2: 替换 `DynamicCard` 整个函数体**

把整个 `DynamicCard` 函数（从 `/** 一条动态卡片…` 的文档注释开始，到函数结尾的 `}`，即原文件 269-443 行）替换为：

```kotlin
/** 一条动态卡片：UP 头像/名字/时间 + 文案 + 封面（视频显示标题与时长，图文用 DrawGrid）。
 * 动态 tab 与 UP 空间页「动态」视图共用（M11-D）。Ch4 接入互动操作。
 * 点击区域拆成三块独立热区——头像/昵称跳 UP 主页、视频行播放、正文原地展开/收起，
 * 不再有"整卡可点"的大热区（避免图文动态点空白处误触、视频动态点文字误播放）。 */
@Composable
internal fun DynamicCard(
    entry: DynamicEntry,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit = {},
    expandState: DynamicExpandState? = null,
    onToggleExpand: () -> Unit = {},
    onImageClick: (Int) -> Unit = {},
    onRepostClick: () -> Unit = {},
    onCommentClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.clickable(onClick = onAuthorClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.authorFace,
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    entry.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatRelativeTime(entry.pubTs * 1000),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (entry.descText.isNotBlank()) {
            DynamicDescText(
                text = entry.descText,
                expandState = expandState,
                onToggleExpand = onToggleExpand,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // OPUS 图文标题（无标题为空串，不渲染）；转发图文取 orig 的标题
        if (entry.video == null && entry.opusTitle.isNotBlank()) {
            Text(
                entry.opusTitle,
                Modifier.padding(top = if (entry.descText.isNotBlank()) 8.dp else 0.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        val video = entry.video
        if (video != null) {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().clickable(onClick = onClick),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AsyncImage(
                        model = video.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 96.dp, height = 54.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    if (video.durationText.isNotBlank()) {
                        // 深底白字。不能用主题里的 onSurface / onScrim：
                        // 亮色主题下 onSurface 接近黑，与 scrim 同色，文本不可见。
                        // 跨主题保持黑底白字与 B 站 / 抖音 / 微信平台惯例一致。
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Text(
                                video.durationText,
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val hasPlay = video.playText.isNotBlank()
                    val hasDanmaku = video.danmakuText.isNotBlank()
                    if (hasPlay || hasDanmaku) {
                        val parts = buildList {
                            if (hasPlay) add("${video.playText}播放")
                            if (hasDanmaku) add("${video.danmakuText}弹幕")
                        }
                        Text(
                            parts.joinToString(" · "),
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.rawType == "DYNAMIC_TYPE_FORWARD") {
                        Text("转发动态", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else if (entry.pics.size > 1) {
            // 多图图文动态：用 DrawGrid
            com.biliseek.app.ui.components.DrawGrid(
                pics = entry.pics,
                onImageClick = onImageClick,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (entry.coverUrl.isNotBlank()) {
            // 单图兜底：与原版同款（点击进入全屏预览）
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 200.dp, height = 120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImageClick(0) },
                contentScale = ContentScale.Crop,
            )
        }
        // 无图类型（纯文字 WORD，或 DRAW/OPUS 确实没有配图）：不渲染图片区域。
        // 文字过长时靠 DynamicDescText 自带的"…展开"看全文，不再有整卡点击兜底。

        // Ch4：底部互动按钮
        val stat = entry.stat
        if (stat != null) {
            HorizontalDivider(
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.forward.count,
                    label = "转发",
                    enabled = !stat.forward.forbidden,
                    onClick = onRepostClick,
                )
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.comment.count,
                    label = "评论",
                    onClick = onCommentClick,
                )
                com.biliseek.app.ui.components.ActionButton(
                    count = stat.like.count,
                    label = "点赞",
                    active = stat.like.status,
                    onClick = onLikeClick,
                )
            }
        }
    }
}

/**
 * 动态正文：折叠态最多 3 行，溢出时右下角叠一个可点的"…展开"；
 * 展开态按 [expandState] 显示 loading / 完整正文+"收起" / 失败+重试。
 */
@Composable
private fun DynamicDescText(
    text: String,
    expandState: DynamicExpandState?,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium

    if (expandState?.expanded == true) {
        when {
            expandState.error != null -> Column(modifier) {
                Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "加载失败，点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onToggleExpand),
                )
            }
            expandState.entry == null -> Column(modifier) {
                Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
                CircularProgressIndicator(
                    Modifier.padding(top = 4.dp).size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            else -> {
                val fullText = expandState.entry.descText.ifBlank { text }
                Text(
                    buildAnnotatedString {
                        append(fullText)
                        append("  ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("收起") }
                    },
                    style = style,
                    modifier = modifier.clickable(onClick = onToggleExpand),
                )
            }
        }
        return
    }

    var overflowing by remember(text) { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text,
            style = style,
            maxLines = 3,
            overflow = TextOverflow.Clip,
            onTextLayout = { result -> overflowing = result.hasVisualOverflow },
        )
        if (overflowing) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.align(Alignment.BottomEnd).clickable(onClick = onToggleExpand),
            ) {
                Text(
                    "…展开",
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = style,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL——`DynamicScreen`/`UpSpaceScreen` 函数体里调用 `DynamicCard(...)` 的地方还在传 `onDetailClick`（Task 5/6 处理）。确认报错只指向这些调用点，`DynamicCard`/`DynamicDescText` 本身语法正确。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt
git commit -m "feat: DynamicCard 拆分点击区域，正文改为原地展开/收起"
```

---

## Task 5: `DynamicScreen` 穿透新参数

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:61-82`（`DynamicScreen` 函数签名）
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt:178-187`（`items(...)` 里的 `DynamicCard` 调用）

**Interfaces:**
- Consumes: Task 4 的 `DynamicCard` 新签名；Task 2 的 `DynamicExpandState`。
- Produces: `DynamicScreen` 新增 `onAuthorClick: (DynamicEntry) -> Unit = {}`、`dynamicExpand: Map<String, DynamicExpandState> = emptyMap()`、`onToggleExpand: (DynamicEntry) -> Unit = {}`，去掉 `onOpenDetail`；供 Task 7 的 `MainActivity` 调用。

- [ ] **Step 1: 替换函数签名**

把：

```kotlin
    onEntryClick: (DynamicEntry) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    // 点开图文卡片：四级降级拉详情（可选）
    onOpenDetail: (DynamicEntry) -> Unit = {},
    // Ch4
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
```

改为：

```kotlin
    onEntryClick: (DynamicEntry) -> Unit,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    // 头像/昵称跳 UP 主页
    onAuthorClick: (DynamicEntry) -> Unit = {},
    // 正文原地展开/收起（四级降级拉详情，按 idStr 独立维护）
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
    // Ch4
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
```

- [ ] **Step 2: 替换 `items(...)` 里的 `DynamicCard` 调用**

把：

```kotlin
                            items(state.feed.items, key = { it.idStr }) { entry ->
                                DynamicCard(
                                    entry = entry,
                                    onClick = { onEntryClick(entry) },
                                    onDetailClick = { onOpenDetail(entry) },
                                    onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                                    onRepostClick = { onOpenRepost(entry.idStr) },
                                    onCommentClick = { onOpenComment(entry) },
                                    onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                                )
                                HorizontalDivider()
                            }
```

改为：

```kotlin
                            items(state.feed.items, key = { it.idStr }) { entry ->
                                DynamicCard(
                                    entry = entry,
                                    onClick = { onEntryClick(entry) },
                                    onAuthorClick = { onAuthorClick(entry) },
                                    expandState = dynamicExpand[entry.idStr],
                                    onToggleExpand = { onToggleExpand(entry) },
                                    onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                                    onRepostClick = { onOpenRepost(entry.idStr) },
                                    onCommentClick = { onOpenComment(entry) },
                                    onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                                )
                                HorizontalDivider()
                            }
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL——`UpSpaceScreen.kt`（Task 6）与 `MainActivity.kt`（Task 7）还没跟上。确认报错不再指向 `DynamicScreen.kt`。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt
git commit -m "feat: DynamicScreen 穿透头像点击与正文展开状态"
```

---

## Task 6: `UpSpaceScreen` 穿透新参数

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt:1-49`（import 块）
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt:62-97`（`UpSpaceScreen` 函数签名）
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt:178-187`（`UpDynamicList(...)` 调用）
- Modify: `app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt:362-416`（`UpDynamicList` 函数）

**Interfaces:**
- Consumes: Task 4 的 `DynamicCard` 新签名；Task 2 的 `DynamicExpandState`。
- Produces: `UpSpaceScreen` 新增 `onAuthorClick: (DynamicEntry) -> Unit = {}`、`dynamicExpand: Map<String, DynamicExpandState> = emptyMap()`、`onToggleExpand: (DynamicEntry) -> Unit = {}`，去掉 `onOpenDetail`；供 Task 7 的 `MainActivity` 调用。

- [ ] **Step 1: 加 import**

在 `import com.biliseek.app.UpSpaceView` 后面加一行：

```kotlin
import com.biliseek.app.data.DynamicExpandState
```

- [ ] **Step 2: 替换 `UpSpaceScreen` 函数签名**

把：

```kotlin
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    // 图文动态点开看全文（四级降级）
    onOpenDetail: (DynamicEntry) -> Unit = {},
    // 投稿复选与动作
```

改为：

```kotlin
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    // 头像/昵称跳 UP 主页；正文原地展开/收起（四级降级拉详情）
    onAuthorClick: (DynamicEntry) -> Unit = {},
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
    // 投稿复选与动作
```

- [ ] **Step 3: 替换 `UpDynamicList(...)` 调用**

把：

```kotlin
            UpSpaceView.DYNAMIC -> UpDynamicList(
                dynamic = state.dynamic,
                onEntryClick = onDynamicClick,
                onLoadMore = onLoadMoreDynamic,
                onOpenImagePreview = onOpenImagePreview,
                onOpenRepost = onOpenRepost,
                onOpenComment = onOpenComment,
                onToggleLike = onToggleLike,
                onOpenDetail = onOpenDetail,
            )
```

改为：

```kotlin
            UpSpaceView.DYNAMIC -> UpDynamicList(
                dynamic = state.dynamic,
                onEntryClick = onDynamicClick,
                onLoadMore = onLoadMoreDynamic,
                onOpenImagePreview = onOpenImagePreview,
                onOpenRepost = onOpenRepost,
                onOpenComment = onOpenComment,
                onToggleLike = onToggleLike,
                onAuthorClick = onAuthorClick,
                dynamicExpand = dynamicExpand,
                onToggleExpand = onToggleExpand,
            )
```

- [ ] **Step 4: 替换 `UpDynamicList` 函数签名与其内部的 `DynamicCard` 调用**

把：

```kotlin
private fun UpDynamicList(
    dynamic: com.biliseek.app.data.RemoteListState<DynamicEntry>,
    onEntryClick: (DynamicEntry) -> Unit,
    onLoadMore: () -> Unit,
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    onOpenDetail: (DynamicEntry) -> Unit = {},
) {
```

改为：

```kotlin
private fun UpDynamicList(
    dynamic: com.biliseek.app.data.RemoteListState<DynamicEntry>,
    onEntryClick: (DynamicEntry) -> Unit,
    onLoadMore: () -> Unit,
    onOpenImagePreview: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenRepost: (String) -> Unit = {},
    onOpenComment: (DynamicEntry) -> Unit = {},
    onToggleLike: (String, Boolean) -> Unit = { _, _ -> },
    onAuthorClick: (DynamicEntry) -> Unit = {},
    dynamicExpand: Map<String, DynamicExpandState> = emptyMap(),
    onToggleExpand: (DynamicEntry) -> Unit = {},
) {
```

再把：

```kotlin
                items(dynamic.items, key = { it.idStr }) { entry ->
                    DynamicCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onDetailClick = { onOpenDetail(entry) },
                        onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                        onRepostClick = { onOpenRepost(entry.idStr) },
                        onCommentClick = { onOpenComment(entry) },
                        onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                    )
                    HorizontalDivider()
                }
```

改为：

```kotlin
                items(dynamic.items, key = { it.idStr }) { entry ->
                    DynamicCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onAuthorClick = { onAuthorClick(entry) },
                        expandState = dynamicExpand[entry.idStr],
                        onToggleExpand = { onToggleExpand(entry) },
                        onImageClick = { idx -> onOpenImagePreview(entry.pics.ifEmpty { listOf(entry.coverUrl).filter { it.isNotBlank() } }, idx) },
                        onRepostClick = { onOpenRepost(entry.idStr) },
                        onCommentClick = { onOpenComment(entry) },
                        onLikeClick = { onToggleLike(entry.idStr, entry.stat?.like?.status != true) },
                    )
                    HorizontalDivider()
                }
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL——只剩 `MainActivity.kt`（Task 7）还引用旧的 `onOpenDetail`/`dynamicDetail`/`DynamicDetailSheet`。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/UpSpaceScreen.kt
git commit -m "feat: UpSpaceScreen 穿透头像点击与正文展开状态"
```

---

## Task 7: `MainActivity` 接线 + 删除 `DynamicDetailSheet`

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt`（import 块）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:114`（状态订阅）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:222-244`（`DynamicScreen(...)` 调用）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:261-298`（`UpSpaceScreen(...)` 调用）
- Modify: `app/src/main/java/com/biliseek/app/MainActivity.kt:524-531`（删除 `DynamicDetailSheet` 弹层接入）
- Delete: `app/src/main/java/com/biliseek/app/ui/components/DynamicDetailSheet.kt`

**Interfaces:**
- Consumes: Task 3 的 `AppViewModel.dynamicExpand`/`toggleDynamicExpand`/`openUpSpace(user: SearchUser)`；Task 5/6 的 `DynamicScreen`/`UpSpaceScreen` 新参数；Task 1 的 `DynamicEntry.authorMid`。

- [ ] **Step 1: 加 import**

在 `import com.biliseek.core.network.dto.SearchVideo` 后面加一行：

```kotlin
import com.biliseek.core.network.dto.SearchUser
```

- [ ] **Step 2: 替换状态订阅**

把：

```kotlin
                val dynamicDetail by viewModel.dynamicDetail.collectAsState()
```

改为：

```kotlin
                val dynamicExpand by viewModel.dynamicExpand.collectAsState()
```

- [ ] **Step 3: 替换两处 `onOpenDetail` 调用点**

`DynamicScreen(...)` 调用块里（28 空格缩进），把：

```kotlin
                            onOpenDetail = viewModel::openDynamicDetail,
```

改为：

```kotlin
                            onAuthorClick = { entry ->
                                viewModel.openUpSpace(
                                    SearchUser(mid = entry.authorMid, uname = entry.authorName, upic = entry.authorFace),
                                )
                            },
                            dynamicExpand = dynamicExpand,
                            onToggleExpand = viewModel::toggleDynamicExpand,
```

`UpSpaceScreen(...)` 调用块里（32 空格缩进），把：

```kotlin
                                onOpenDetail = viewModel::openDynamicDetail,
```

改为：

```kotlin
                                onAuthorClick = { entry ->
                                    viewModel.openUpSpace(
                                        SearchUser(mid = entry.authorMid, uname = entry.authorName, upic = entry.authorFace),
                                    )
                                },
                                dynamicExpand = dynamicExpand,
                                onToggleExpand = viewModel::toggleDynamicExpand,
```

两处缩进不同，不要用 `replace_all`——按各自的缩进分别替换（用足够的上下文行让 old_string 在文件里唯一匹配，例如带上前一行 `onToggleLike = viewModel::toggleDynamicLike,`）。

- [ ] **Step 4: 删除弹层接入代码**

把：

```kotlin
                // 图文动态详情弹层（点开看全文，四级降级）
                dynamicDetail?.let { state ->
                    com.biliseek.app.ui.components.DynamicDetailSheet(
                        state = state,
                        onImageClick = { pics, idx -> viewModel.openImagePreview(pics, idx) },
                        onDismiss = viewModel::dismissDynamicDetail,
                    )
                }

```

改为：

```kotlin
```

（整段删掉。）

- [ ] **Step 5: 删除 `DynamicDetailSheet.kt` 文件**

```bash
rm app/src/main/java/com/biliseek/app/ui/components/DynamicDetailSheet.kt
```

- [ ] **Step 6: 全量确认没有遗留引用**

Run: `grep -rn "openDynamicDetail\|dismissDynamicDetail\|DynamicDetailState\|onDetailClick\|onOpenDetail\|DynamicDetailSheet" app/src core`
Expected: 无输出（全部清理干净）。

- [ ] **Step 7: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/biliseek/app/MainActivity.kt
git rm app/src/main/java/com/biliseek/app/ui/components/DynamicDetailSheet.kt
git commit -m "feat: MainActivity 接线头像跳转与正文展开，删除被取代的 DynamicDetailSheet"
```

---

## Task 8: Compose UI 测试 + 全量回归

**Files:**
- Create: `app/src/androidTest/java/com/biliseek/app/DynamicCardClickRegionsUiTest.kt`

**Interfaces:**
- Consumes: 公开入口 `com.biliseek.app.ui.DynamicScreen`（Task 5 的新签名）、`com.biliseek.app.DynamicUiState`、`com.biliseek.app.data.DynamicExpandState`、`com.biliseek.core.network.{BiliSession, DynamicEntry, DynamicVideo, LoginState}`、`com.biliseek.app.data.RemoteListState`。测通过公开的 `DynamicScreen` 入口驱动，不直接碰 `internal` 的 `DynamicCard`（保持它的可见性不变），也不触发真实网络请求（`onInit`/`onToggleExpand` 等全部传本地 lambda）。

- [ ] **Step 1: 写测试**

创建 `app/src/androidTest/java/com/biliseek/app/DynamicCardClickRegionsUiTest.kt`：

```kotlin
package com.biliseek.app

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biliseek.app.data.DynamicExpandState
import com.biliseek.app.data.RemoteListState
import com.biliseek.app.ui.DynamicScreen
import com.biliseek.core.network.BiliSession
import com.biliseek.core.network.DynamicEntry
import com.biliseek.core.network.DynamicVideo
import com.biliseek.core.network.LoginState
import com.biliseek.designsystem.theme.BiliSeekTheme
import org.junit.Rule
import org.junit.Test

/**
 * 动态卡片点击区域重设计的行为验证：视频行播放 / 文案不触发播放 / 头像跳转 /
 * 长文案展开收起。全部用本地构造的假数据，不发真实网络请求。
 */
class DynamicCardClickRegionsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val loggedInSession = BiliSession(
        sessData = "s", biliJct = "j", dedeUserId = "1", refreshToken = "r",
    )

    private fun videoEntry() = DynamicEntry(
        idStr = "v1",
        authorName = "测试UP",
        authorFace = "",
        authorMid = 100L,
        pubTs = 0L,
        descText = "今天更新了一个新视频",
        rawType = "DYNAMIC_TYPE_AV",
        video = DynamicVideo(
            bvid = "BV1xx",
            title = "测试视频标题",
            coverUrl = "",
            durationText = "03:27",
        ),
        coverUrl = "",
    )

    @Test
    fun 视频动态_点视频行播放_点文案不播放_点头像跳转UP主页() {
        var played = false
        var authorClicked = false
        val entry = videoEntry()

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = { played = true },
                    onStartLogin = {},
                    onCancelLogin = {},
                    onAuthorClick = { authorClicked = true },
                )
            }
        }

        // 文案区域没有点击语义（不再有整卡点击兜底）
        compose.onNodeWithText("今天更新了一个新视频").assertHasNoClickAction()

        // 点视频行（标题所在的可点热区）触发播放，不触发头像跳转
        compose.onNodeWithText("测试视频标题").performClick()
        check(played) { "点视频行应触发播放回调" }
        check(!authorClicked) { "点视频行不应触发头像跳转回调" }

        // 点头像/昵称触发跳转 UP 主页
        compose.onNodeWithText("测试UP").performClick()
        check(authorClicked) { "点昵称应触发跳转 UP 主页回调" }
    }

    @Test
    fun 图文动态_长文案显示展开按钮_点击回调正确条目() {
        val longText = "很长的正文内容。".repeat(60)
        val entry = DynamicEntry(
            idStr = "d1",
            authorName = "图文UP",
            authorFace = "",
            authorMid = 200L,
            pubTs = 0L,
            descText = longText,
            rawType = "DYNAMIC_TYPE_OPUS",
            video = null,
            coverUrl = "",
        )
        var toggledIdStr: String? = null

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = {},
                    onStartLogin = {},
                    onCancelLogin = {},
                    onToggleExpand = { e -> toggledIdStr = e.idStr },
                )
            }
        }

        compose.onNodeWithText("…展开", substring = true).performClick()
        check(toggledIdStr == "d1") { "点展开应回调对应条目的 onToggleExpand" }
    }

    @Test
    fun 图文动态_展开态显示完整正文与收起按钮() {
        val fullText = "完整正文内容"
        val entry = DynamicEntry(
            idStr = "d2",
            authorName = "图文UP2",
            authorFace = "",
            authorMid = 300L,
            pubTs = 0L,
            descText = "摘要",
            rawType = "DYNAMIC_TYPE_OPUS",
            video = null,
            coverUrl = "",
        )
        var toggledIdStr: String? = null

        compose.setContent {
            BiliSeekTheme {
                DynamicScreen(
                    state = DynamicUiState(feed = RemoteListState(items = listOf(entry))),
                    session = loggedInSession,
                    loginState = LoginState.Idle,
                    onInit = {},
                    onRefresh = {},
                    onTypeFilterChange = {},
                    onSelectUp = {},
                    onLoadMore = {},
                    onRetryInit = {},
                    onEntryClick = {},
                    onStartLogin = {},
                    onCancelLogin = {},
                    dynamicExpand = mapOf(
                        "d2" to DynamicExpandState(expanded = true, entry = entry.copy(descText = fullText)),
                    ),
                    onToggleExpand = { e -> toggledIdStr = e.idStr },
                )
            }
        }

        compose.onNodeWithText(fullText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("收起", substring = true).performClick()
        check(toggledIdStr == "d2") { "点收起应回调对应条目的 onToggleExpand" }
    }
}
```

- [ ] **Step 2: 运行新测试（需要连接设备/模拟器）**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.biliseek.app.DynamicCardClickRegionsUiTest"`
Expected: PASS（3 项全绿）。

- [ ] **Step 3: 全量回归**

Run: `./gradlew test`
Expected: PASS（全部 JVM 单测，含 Task 1/2 新增用例）。

Run: `./gradlew :app:compileDebugKotlin :core:network:compileDebugKotlin`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/biliseek/app/DynamicCardClickRegionsUiTest.kt
git commit -m "test: 动态卡片点击区域重设计的 Compose UI 测试"
```

- [ ] **Step 5: 记录人工真机验证待办**

这一步不是自动化任务，是提醒：按 CLAUDE.md 安全规则，`toggleDynamicExpand` 里真正调用 `BiliRepository.dynamicDetail`（四级降级拉全文）的路径没有被自动化测试覆盖到"和真实服务端语义一致"这一层，需要人工在真机上验证：
- 图文动态长文案点"展开"能拉到完整正文（尤其 OPUS 摘要被截断的场景）。
- 视频动态点视频行播放、点文案不播放、点头像进 UP 主页。
- 同一屏内多张卡片可以同时展开，互不影响。
