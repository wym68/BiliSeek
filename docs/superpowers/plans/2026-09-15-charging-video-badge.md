# Charging Video Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "充电" badge on video covers in search results when the video is a charging-exclusive (paid) content.

**Architecture:** Minimal 3-file change: DTO adds `ugcPay` field, CoverThumbnail gains an optional badge, ResultRow passes the badge based on `ugcPay == 1`.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, B站 API

---

### Task 1: Add ugcPay field to SearchVideo DTO

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt:158-172`
- Test: `core/network/src/test/java/com/biliseek/core/network/SearchVideoTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `core/network/src/test/java/com/biliseek/core/network/SearchVideoTest.kt`:

```kotlin
package com.biliseek.core.network

import com.biliseek.core.network.dto.SearchVideo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchVideoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `ugcPay defaults to 0 when absent`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(0, video.ugcPay)
    }

    @Test
    fun `ugcPay parses value of 1`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"ugc_pay":1}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(1, video.ugcPay)
    }

    @Test
    fun `ugcPay parses value of 0`() {
        val raw = """{"bvid":"BV1xx411c7mD","title":"test","author":"UP","mid":123,"ugc_pay":0}"""
        val video = json.decodeFromString<SearchVideo>(raw)
        assertEquals(0, video.ugcPay)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.SearchVideoTest"`
Expected: FAIL with compilation error (ugcPay field doesn't exist yet)

- [ ] **Step 3: Add ugcPay field to SearchVideo**

Modify `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt`, in the `SearchVideo` data class, add after `pubdate`:

```kotlin
@SerialName("ugc_pay")
val ugcPay: Int = 0,
```

The full `SearchVideo` should now be:

```kotlin
@Serializable
data class SearchVideo(
    val bvid: String = "",
    val aid: Long = 0,
    /** 含 `<em>` 高亮标签，展示前需剥离 */
    val title: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** 形如 "3:51" */
    val duration: String = "",
    /** 封面，返回的是 //i0.hdslb.com/... 需补协议 */
    val pic: String = "",
    val play: Long = 0,
    val danmaku: Long = 0,
    val pubdate: Long = 0,
    @SerialName("ugc_pay")
    val ugcPay: Int = 0,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.biliseek.core.network.SearchVideoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt core/network/src/test/java/com/biliseek/core/network/SearchVideoTest.kt
git commit -m "feat(network): add ugcPay field to SearchVideo DTO

Detects charging-exclusive videos from B站 search API.
Default value 0 ensures backward compatibility."
```

---

### Task 2: Add badge parameter to CoverThumbnail

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/CoverImage.kt:23-44`
- Test: Visual verification (no unit test needed for Compose UI)

- [ ] **Step 1: Modify CoverThumbnail to support badge**

Replace the entire `CoverThumbnail` function in `app/src/main/java/com/biliseek/app/ui/CoverImage.kt`:

```kotlin
package com.biliseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 列表里的 16:9 封面缩略图。
 *
 * 地址为空时渲染一块占位底色而不是留空——否则列表行高会随封面有无跳动。
 *
 * [badge] 左上角角标文字（如「充电」）。非空时显示红底白字小标签。
 */
@Composable
fun CoverThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(4.dp)
    val base = modifier
        .width(width)
        .aspectRatio(16f / 9f)
        .clip(shape)

    if (url.isBlank()) {
        Box(base.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    Box(base) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        // 左上角角标：B站粉色底 + 白字
        if (!badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(
                        Color(0xFFFB7299),
                        RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                androidx.compose.material3.Text(
                    text = badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/biliseek/app/ui/CoverImage.kt
git commit -m "feat(ui): add optional badge to CoverThumbnail

Left-upper corner red badge for charging-exclusive videos."
```

---

### Task 3: Pass badge in search ResultRow

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/SearchScreen.kt:800`

- [ ] **Step 1: Modify ResultRow to pass badge**

In `app/src/main/java/com/biliseek/app/SearchScreen.kt`, find the `ResultRow` function (line ~784). Locate the `CoverThumbnail` call at line ~800:

```kotlin
CoverThumbnail(normalizeCoverUrl(video.pic), width = 64.dp)
```

Replace with:

```kotlin
CoverThumbnail(
    url = normalizeCoverUrl(video.pic),
    width = 64.dp,
    badge = if (video.ugcPay == 1) "充电" else null,
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/biliseek/app/SearchScreen.kt
git commit -m "feat(search): show charging badge on paid videos

Displays '充电' badge on video covers when ugcPay == 1."
```

---

### Task 4: Verify with real device

**Files:**
- None (manual verification)

- [ ] **Step 1: Build and install debug APK**

Run: `./gradlew installDebug`

- [ ] **Step 2: Search for a known charging video**

Search keywords: "充电专属" or specific UP主 names known for charging content.

- [ ] **Step 3: Verify badge appears**

Expected: Videos with `ugc_pay=1` show a red "充电" badge on the upper-left corner of the cover.

- [ ] **Step 4: Verify free videos are unaffected**

Expected: Regular videos have no badge.

- [ ] **Step 5: If search API doesn't return ugc_pay**

Expected: No badge shown (default 0), no crash. This confirms graceful degradation.

---

## Summary

| Task | Files Changed | Risk |
|------|--------------|------|
| 1. DTO | `BiliDto.kt` + test | Low - default value ensures compatibility |
| 2. CoverThumbnail | `CoverImage.kt` | Low - optional parameter |
| 3. ResultRow | `SearchScreen.kt` | Low - single conditional |
| 4. Device test | None | Manual verification |

Total: 3 files modified, 1 test file created.
