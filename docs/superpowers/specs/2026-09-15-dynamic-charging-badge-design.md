# 动态页充电标签功能设计

> **For agentic workers:** This is a design document for the charging badge feature in the dynamic feed page.

**Goal:** 在动态页为特定 UP 主的充电视频显示充电标签，采用混合方案实现懒加载。

**Architecture:** 混合方案（ViewModel + Composable）：ViewModel 管理缓存和请求逻辑，Composable 检测可见性并通知 ViewModel。

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Flow

---

## 1. 需求总结

| 需求项 | 描述 |
|--------|------|
| **位置** | 动态页（非搜索页） |
| **加载时机** | 滚动到时才加载（懒加载） |
| **范围** | 特定 UP 主（用户手动配置） |
| **缓存** | 内存缓存 |
| **请求频率** | 保守策略（最多 3 并发，间隔 100ms） |
| **错误处理** | 静默失败 |
| **UI** | 封面角标（类似搜索页） |

---

## 2. 核心组件

### 2.1 ChargingBadgeManager

管理充电状态缓存和请求逻辑。

```kotlin
class ChargingBadgeManager(
    private val network: BiliNetwork,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_CACHE_SIZE = 1000
        private const val MAX_CONCURRENT = 3
        private const val REQUEST_INTERVAL_MS = 100L
    }
    
    // 线程安全的内存缓存：bvid -> 是否充电视频
    private val cache = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val cacheMutex = Mutex()
    
    // 结果回调：bvid -> 充电状态
    private val resultCallbacks = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    
    // 等待队列
    private val pendingRequests = Channel<String>(Channel.UNLIMITED)
    
    // 并发控制
    private val semaphore = Semaphore(MAX_CONCURRENT)
    
    init {
        // 启动请求消费者
        repeat(MAX_CONCURRENT) { startRequestConsumer() }
    }
    
    /**
     * 获取充电状态
     * @param bvid 视频 BV 号
     * @return Flow<Boolean?> 充电状态，null 表示未加载
     */
    fun getChargingStatus(bvid: String): Flow<Boolean?> = channelFlow {
        // 1. 检查缓存
        cacheMutex.withLock {
            cache[bvid]?.let { send(it); close(); return@channelFlow }
        }
        
        // 2. 发送请求
        pendingRequests.send(bvid)
        
        // 3. 等待结果
        resultCallbacks
            .filter { it.first == bvid }
            .first()
            .let { send(it.second) }
    }
    
    /**
     * 批量预加载
     * @param bvids 视频 BV 号列表
     */
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

### 2.2 DynamicEntry 扩展

在 `DynamicEntry` 中添加充电状态字段。

```kotlin
data class DynamicEntry(
    // ... 现有字段
    /** 充电状态：null=未加载，true=充电视频，false=非充电视频 */
    val isCharging: Boolean? = null,
)
```

### 2.3 DynamicUiState 扩展

在 `DynamicUiState` 中添加充电状态管理。

```kotlin
data class DynamicUiState(
    // ... 现有字段
    /** 充电状态映射：bvid -> 是否充电视频 */
    val chargingStates: Map<String, Boolean> = emptyMap(),
)
```

### 2.4 DynamicCard 修改

在 `DynamicCard` 中检测可见性并请求充电状态。

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

**可见性检测在 LazyColumn 层实现：**

```kotlin
@Composable
private fun DynamicFeedList(
    state: DynamicUiState,
    // ... 其他参数
    onChargingStatusRequested: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    
    // 检测可见项并请求充电状态
    LaunchedEffect(listState.layoutInfo) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        visibleItems.forEach { item ->
            val entry = state.feed.items.getOrNull(item.index)
            entry?.video?.bvid?.let { bvid ->
                onChargingStatusRequested(bvid)
            }
        }
    }
    
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

---

## 3. 数据流

```
LazyColumn 滚动
    ↓
检测可见项 (visibleItemsInfo)
    ↓
遍历可见项，提取 bvid
    ↓
onChargingStatusRequested(bvid)
    ↓
ViewModel 接收请求
    ↓
检查 chargingStates 缓存
    ↓
缓存命中 → 直接更新 UI
缓存未命中 → 加入请求队列
    ↓
ChargingBadgeManager 处理请求
    ↓
检查内存缓存 (cacheMutex)
    ↓
缓存命中 → 发送结果回调
缓存未命中 → 请求视频详情 API (x/web-interface/view)
    ↓
解析 is_upower_exclusive 字段
    ↓
更新缓存 (cacheMutex)
    ↓
发送结果回调 (resultCallbacks)
    ↓
ViewModel 接收结果，更新 DynamicUiState
    ↓
UI 重新渲染，显示充电标签
```

---

## 4. 集成到关注 UP 列表页

### 4.1 关注 UP 列表页扩展

在计划中的关注 UP 列表页添加充电状态开关。

```kotlin
@Composable
fun FollowingUpItem(
    up: FollowingUp,
    // ... 其他参数
    isChargingEnabled: Boolean,
    onChargingToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // UP 主信息
        AsyncImage(
            model = up.face,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(up.uname, style = MaterialTheme.typography.bodyMedium)
            Text("UID: ${up.mid}", style = MaterialTheme.typography.bodySmall)
        }
        
        // 充电状态开关
        Switch(
            checked = isChargingEnabled,
            onCheckedChange = onChargingToggle,
        )
    }
}
```

### 4.2 数据存储

使用 `DataStore` 存储用户配置的充电 UP 主列表。

```kotlin
class ChargingSettingsStore(private val context: Context) {
    private val Context.dataStore by preferencesDataStore(name = "charging_settings")
    
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
    
    companion object {
        val KEY_ENABLED_UP_MIDS = stringPreferencesKey("enabled_charging_up_mids")
    }
}
```

---

## 5. 实现步骤

### Task 1: 添加视频详情 API 端点
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

**验证：** 编译通过，无语法错误。

---

### Task 2: 实现 ChargingBadgeManager
**Files:**
- Create: `app/src/main/java/com/biliseek/app/data/ChargingBadgeManager.kt`

- [ ] **Step 1: 创建 ChargingBadgeManager**

创建新文件 `ChargingBadgeManager.kt`，实现完整的充电状态管理器：

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

**验证：** 编译通过，无语法错误。

---

### Task 3: 扩展 DynamicEntry 和 DynamicUiState
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

**验证：** 编译通过，无语法错误。

---

### Task 4: 修改 DynamicCard
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

- [ ] **Step 2: 修改 DynamicFeedList**

在 `DynamicScreen.kt` 中添加可见性检测：

```kotlin
@Composable
private fun DynamicFeedList(
    state: DynamicUiState,
    // ... 其他参数
    onChargingStatusRequested: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    
    // 检测可见项并请求充电状态
    LaunchedEffect(listState.layoutInfo) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        visibleItems.forEach { item ->
            val entry = state.feed.items.getOrNull(item.index)
            entry?.video?.bvid?.let { bvid ->
                onChargingStatusRequested(bvid)
            }
        }
    }
    
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

**验证：** 编译通过，无语法错误。

---

### Task 5: 实现关注 UP 列表页充电开关
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

**验证：** 编译通过，无语法错误。

---

### Task 6: 集成测试
- [ ] 测试充电状态加载
- [ ] 测试缓存机制
- [ ] 测试错误处理
- [ ] 测试请求频率控制

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| **API 限流** | 请求被拒绝 | 保守策略：3 并发，100ms 间隔 |
| **内存占用** | 缓存过大 | 限制缓存大小（如 1000 条） |
| **UI 闪烁** | 标签突然出现 | 先显示占位符，加载后替换 |
| **错误累积** | 请求堆积 | 静默失败，限制队列大小 |

---

## 7. 成功标准

1. ✅ 充电标签在动态页正确显示
2. ✅ 只对用户配置的 UP 主加载充电状态
3. ✅ 滚动到时才加载（懒加载）
4. ✅ 缓存机制正常工作
5. ✅ 请求频率控制有效
6. ✅ 错误处理静默失败
7. ✅ 关注 UP 列表页充电开关正常
8. ✅ 所有测试通过
