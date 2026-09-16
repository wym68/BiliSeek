# 设计规格：充电视频标签功能增强

## 背景

当前实现使用搜索 API 的 `badgepay` 字段来标识充电视频，但测试发现该字段不可靠：
- 搜索接口返回 `badgepay:false`，但视频实际上是充电视频
- 例如：视频 BV1x7ep6fESB（【改革开放史】1978：在争论中前进的中国）显示23:17，但实际只能播放5:00

## 问题

B站搜索 API 的 `badgepay` 字段没有正确标识充电视频。

## 解决方案

使用视频详情 API (`x/web-interface/view`) 获取准确的充电状态。

### 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 接受现状** | 无 | 功能无法生效 |
| **B. 使用 `is_charge_video`** | 简单 | 字段也不可靠（logcat 显示也是 0） |
| **C. 时长对比** | 无需额外 API | 需要播放才能判断，不准确 |
| **D. 视频详情接口** | 准确 | 需要额外 API 调用 |

### 选择方案 D 的理由

1. **准确性** - 视频详情 API 返回准确的充电状态
2. **用户体验** - 搜索结果立即显示标签，无需播放
3. **可扩展性** - 可以用于其他功能（如显示充电价格）

## 技术设计

### 1. 添加视频详情 API

```kotlin
// BiliApi.kt
@GET("x/web-interface/view")
suspend fun videoDetail(@Query("bvid") bvid: String): BiliResponse<VideoDetailData>
```

### 2. 创建 DTO

```kotlin
// BiliDto.kt
@Serializable
data class VideoDetailData(
    val bvid: String = "",
    val aid: Long = 0,
    val title: String = "",
    val desc: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val owner: Owner = Owner(),
    val stat: Stat = Stat(),
    // 充电相关字段
    @SerialName("ugc_pay")
    val ugcPay: Int = 0,
    @SerialName("is_charge_video")
    val isChargeVideo: Int = 0,
)

@Serializable
data class Owner(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class Stat(
    val view: Int = 0,
    val danmaku: Int = 0,
    val reply: Int = 0,
    val favorite: Int = 0,
    val coin: Int = 0,
    val share: Int = 0,
    val like: Int = 0,
)
```

### 3. 搜索结果增强逻辑

```kotlin
// SearchViewModel.kt 或 SearchScreen.kt
suspend fun enhanceSearchResults(videos: List<SearchVideo>): List<EnhancedSearchVideo> {
    return videos.map { video ->
        // 对于每个视频，异步获取详情
        val detail = biliApi.videoDetail(video.bvid)
        EnhancedSearchVideo(
            video = video,
            isChargeVideo = detail.data?.isChargeVideo == 1 || detail.data?.ugcPay == 1,
        )
    }
}
```

### 4. UI 更新

```kotlin
// SearchScreen.kt
CoverThumbnail(
    url = normalizeCoverUrl(video.pic),
    width = 64.dp,
    badge = if (enhancedVideo.isChargeVideo) "充电" else null,
)
```

## 性能考虑

1. **批量请求** - 可以并行请求多个视频详情，减少延迟
2. **缓存** - 缓存视频详情，避免重复请求
3. **延迟加载** - 只在可见区域的视频才请求详情
4. **降级策略** - 如果详情请求失败，使用搜索 API 的 `badgepay` 字段

## 风险

1. **请求量增加** - 每个搜索结果都需要额外请求
2. **响应时间** - 可能增加搜索结果的显示延迟
3. **风控** - 频繁请求可能触发 B站风控

## 缓解措施

1. **限制并发** - 限制同时请求的视频数量
2. **缓存策略** - 缓存视频详情，避免重复请求
3. **用户触发** - 只在用户滚动到视频时才请求详情
4. **降级策略** - 如果请求失败，使用搜索 API 的字段
