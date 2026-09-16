# 动态页充电标签功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在动态页为特定 UP 主的充电视频显示充电标签，采用混合方案实现懒加载。

**Architecture:** 混合方案（ViewModel + Composable）：ViewModel 管理缓存和请求逻辑，Composable 检测可见性并通知 ViewModel。

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Flow, DataStore

**Design Doc:** `docs/superpowers/specs/2026-09-15-dynamic-charging-badge-design.md`

---

## 前置条件

1. ✅ 设计文档已确认
2. ✅ 关注 UP 列表页计划已存在（`docs/superpowers/plans/2026-09-15-dynamic-up-chips-redesign.md`）
3. ✅ 视频详情 API 端点已确认可用

---

## Task 1: 添加视频详情 API 端点

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliApi.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt`
- Modify: `core/network/src/main/java/com/biliseek/core/network/BiliRepository.kt`

- [ ] **Step 1: 添加 VideoDetailResponse DTO**

在 `BiliDto.kt` 中添加：

```kotlin
@Serializable
data class VideoDetailResponse(
    @SerialName("code") val code: Int = 0,
    @SerialName("message") val message: String = "",
    @SerialName("data") val data: VideoDetailData? = null,
)

@Serializable
data class VideoDetailData(
    @SerialName("bvid") val bvid: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("is_upower_exclusive") val isUpowerExclusive: Boolean = false,
    @SerialName("is_upower_play") val isUpowerPlay: Boolean = false,
    @SerialName("no_cache") val noCache: Boolean = false,
)
```

- [ ] **Step 2: 添加 API 端点**

在 `BiliApi.kt` 中添加：

```kotlin
@GET("x/web-interface/view")
suspend fun videoDetail(
    @Query("bvid") bvid: String,
): VideoDetailResponse
```

- [ ] **Step 3: 添加 Repository 方法**

在 `BiliRepository.kt` 中添加：

```kotlin
suspend fun videoDetail(bvid: String): VideoDetailData {
    return BiliNetwork.api.videoDetail(bvid).data
        ?: throw IllegalStateException("Failed to fetch video detail for $bvid")
}
```

- [ ] **Step 4: 验证**

编译通过，无语法错误。

**验证命令:** `./gradlew :core:network:compileDebugKotlin`

---

## Task 2: 实现 ChargingBadgeManager

**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/ChargingBadgeManager.kt`

- [ ] **Step 1: 创建 ChargingBadgeManager**

创建新文件 `ChargingBadgeManager.kt`：

```kotlin
package com.biliseek.app.data

import com.biliseek.core.network.BiliNetwork
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ChargingBadgeManager(
    private val network: BiliNetwork,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_CACHE_SIZE = 1000
        private const val MAX_CONCURRENT = 3
        private const val REQUEST_INTERVAL_MS = 100L
    }
    
    // 线程安全的内存缓存
    private val cache = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val cacheMutex = kotlinx.coroutines.sync.Mutex()
    
    // 结果回调
    private val resultCallbacks = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    
    // 等待队列
    private val pendingRequests = Channel<String>(Channel.UNLIMITED)
    
    // 并发控制
    private val semaphore = Semaphore(MAX_CONCURRENT)
    
    init {
        repeat(MAX_CONCURRENT) { startRequestConsumer() }
    }
    
    fun getChargingStatus(bvid: String): Flow<Boolean?> = channelFlow {
        cacheMutex.withLock {
            cache[bvid]?.let { send(it); close(); return@channelFlow }
        }
        
        pendingRequests.send(bvid)
        
        resultCallbacks
            .filter { it.first == bvid }
            .first()
            .let { send(it.second) }
    }
    
    fun preload(bvids: List<String>) {
        scope.launch {
            bvids.forEach { bvid ->
                cacheMutex.withLock {
                    if (bvid !in cache) {
                        pendingRequests.trySend(bvid)
                    }
                }
            }
        }
    }
    
    private fun startRequestConsumer() {
        scope.launch {
            for (bvid in pendingRequests) {
                cacheMutex.withLock {
                    if (bvid in cache) continue@launch
                }
                semaphore.withPermit {
                    fetchChargingStatus(bvid)
                    delay(REQUEST_INTERVAL_MS)
                }
            }
        }
    }
    
    private suspend fun fetchChargingStatus(bvid: String) {
        runCatching {
            network.repository.videoDetail(bvid)
        }.onSuccess { detail ->
            cacheMutex.withLock {
                cache[bvid] = detail.isUpowerExclusive
            }
            resultCallbacks.emit(bvid to detail.isUpowerExclusive)
        }.onFailure {
            // 静默失败
        }
    }
}
```

- [ ] **Step 2: 验证**

编译通过，无语法错误。

**验证命令:** `./gradlew :app:compileDebugKotlin`

---

## Task 3: 扩展 DynamicEntry 和 DynamicUiState

**Files:**
- Modify: `core/network/src/main/java/com/biliseek/core/network/DynamicFeed.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`

- [ ] **Step 1: 扩展 DynamicEntry**

在 `DynamicFeed.kt` 中修改 `DynamicEntry`：

```kotlin
data class DynamicEntry(
    // ... 现有字段
    /** 充电状态：null=未加载，true=充电视频，false=非充电视频 */
    val isCharging: Boolean? = null,
)
```

- [ ] **Step 2: 扩展 DynamicUiState**

在 `AppViewModel.kt` 中修改 `DynamicUiState`：

```kotlin
data class DynamicUiState(
    // ... 现有字段
    /** 充电状态映射：bvid -> 是否充电视频 */
    val chargingStates: Map<String, Boolean> = emptyMap(),
)
```

- [ ] **Step 3: 验证**

编译通过，无语法错误。

**验证命令:** `./gradlew :app:compileDebugKotlin`

---

## Task 4: 修改 DynamicCard 和 DynamicScreen

**Files:**
- Modify: `app/src/main/java/com/biliseek/app/ui/DynamicScreen.kt`
- Modify: `app/src/main/java/com/biliseek/app/AppViewModel.kt`

- [ ] **Step 1: 修改 DynamicCard 签名**

在 `DynamicScreen.kt` 中修改 `DynamicCard`：

```kotlin
@Composable
internal fun DynamicCard(
    entry: DynamicEntry,
    // ... 其他参数
    onChargingStatusRequested: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 请求充电状态
    LaunchedEffect(entry.video?.bvid) {
        entry.video?.bvid?.let { bvid ->
            onChargingStatusRequested(bvid)
        }
    }
    
    // 渲染
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        // ... 现有内容
        
        // 封面（带充电标签）
        entry.video?.let { video ->
            CoverThumbnail(
                url = video.coverUrl,
                badge = if (entry.isCharging == true) "充电" else null,
            )
        }
    }
}
```

- [ ] **Step 2: 修改 DynamicScreen**

在 `DynamicScreen.kt` 中添加可见性检测：

```kotlin
@Composable
fun DynamicScreen(
    state: DynamicUiState,
    // ... 其他参数
    onChargingStatusRequested: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ... 现有代码
    
    // 在 LazyColumn 中添加可见性检测
    LazyColumn(state = listState) {
        items(state.feed.items, key = { it.idStr }) { entry ->
            DynamicCard(
                entry = entry,
                // ... 其他参数
                onChargingStatusRequested = onChargingStatusRequested,
            )
        }
    }
}
```

- [ ] **Step 3: 在 AppViewModel 中添加充电状态管理**

在 `AppViewModel.kt` 中添加：

```kotlin
private val chargingBadgeManager = ChargingBadgeManager(network, viewModelScope)

fun requestChargingStatus(bvid: String) {
    viewModelScope.launch {
        chargingBadgeManager.getChargingStatus(bvid)
            .collect { isCharging ->
                if (isCharging != null) {
                    _dynamic.value = _dynamic.value.copy(
                        chargingStates = _dynamic.value.chargingStates + (bvid to isCharging)
                    )
                }
            }
    }
}
```

- [ ] **Step 4: 在 MainActivity 中绑定回调**

在 `MainActivity.kt` 中修改 `DynamicScreen` 调用：

```kotlin
DynamicScreen(
    state = state,
    // ... 其他参数
    onChargingStatusRequested = viewModel::requestChargingStatus,
)
```

- [ ] **Step 5: 验证**

编译通过，无语法错误。

**验证命令:** `./gradlew :app:compileDebugKotlin`

---

## Task 5: 实现关注 UP 列表页充电开关

**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/ChargingSettingsStore.kt`
- Modify: 关注 UP 列表页（待实现）

- [ ] **Step 1: 创建 ChargingSettingsStore**

创建新文件 `ChargingSettingsStore.kt`：

```kotlin
package com.biliseek.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "charging_settings")

class ChargingSettingsStore(private val context: Context) {
    companion object {
        private val KEY_ENABLED_UP_MIDS = stringPreferencesKey("enabled_charging_up_mids")
    }
    
    val enabledUpMids: Flow<Set<Long>> = context.dataStore.data
        .map { prefs ->
            prefs[KEY_ENABLED_UP_MIDS]?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: emptySet()
        }
    
    suspend fun setEnabled(mid: Long, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_ENABLED_UP_MIDS]?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: emptySet()
            val new = if (enabled) current + mid else current - mid
            prefs[KEY_ENABLED_UP_MIDS] = new.joinToString(",")
        }
    }
    
    fun isEnabled(mid: Long): Flow<Boolean> {
        return enabledUpMids.map { it.contains(mid) }
    }
}
```

- [ ] **Step 2: 集成到关注 UP 列表页**

等待关注 UP 列表页实现后，在每个 UP 主项添加充电开关。

- [ ] **Step 3: 验证**

编译通过，无语法错误。

**验证命令:** `./gradlew :app:compileDebugKotlin`

---

## Task 6: 集成测试

- [ ] **Step 1: 测试充电状态加载**

创建测试用例验证充电状态正确加载。

- [ ] **Step 2: 测试缓存机制**

验证缓存命中和过期逻辑。

- [ ] **Step 3: 测试错误处理**

验证请求失败时的静默失败行为。

- [ ] **Step 4: 测试请求频率控制**

验证并发限制和间隔控制。

- [ ] **Step 5: 运行所有测试**

```bash
./gradlew test
```

---

## 依赖关系

```
Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6
```

- Task 1 是基础，后续任务依赖 API 端点
- Task 2 实现充电状态管理器
- Task 3 扩展数据结构
- Task 4 修改 UI 组件
- Task 5 实现用户配置
- Task 6 集成测试

---

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| **API 限流** | 请求被拒绝 | 保守策略：3 并发，100ms 间隔 |
| **内存占用** | 缓存过大 | 限制缓存大小（1000 条） |
| **UI 闪烁** | 标签突然出现 | 先显示占位符，加载后替换 |
| **错误累积** | 请求堆积 | 静默失败，限制队列大小 |
| **依赖未实现** | 关注列表页 | Task 5 可延后实现 |

---

## 成功标准

1. ✅ 所有任务完成
2. ✅ 编译通过，无错误
3. ✅ 所有测试通过
4. ✅ 充电标签在动态页正确显示
5. ✅ 只对用户配置的 UP 主加载充电状态
6. ✅ 滚动到时才加载（懒加载）
7. ✅ 缓存机制正常工作
8. ✅ 请求频率控制有效
9. ✅ 错误处理静默失败

---

## 下一步

1. 开始实现 Task 1：添加视频详情 API 端点
2. 逐步完成所有任务
3. 集成测试验证
4. 真机测试验证
