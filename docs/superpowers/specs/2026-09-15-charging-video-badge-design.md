# 充电专属视频标签

## 背景

B站有一类视频是 UP 主设置的「充电专属」内容，需要对 UP 主进行充电付费后才能完整观看，未付费只能试看。用户希望在搜索结果的视频封面上识别并标记这类视频。

## 目标

1. 从 B站搜索接口获取视频是否为充电专属的标识
2. 在搜索结果的视频封面上显示「充电」角标
3. 最小化改动，不引入额外网络请求

## 方案

### 1. DTO 层：SearchVideo 添加 ugcPay 字段

**文件**: `core/network/src/main/java/com/biliseek/core/network/dto/BiliDto.kt`

在 `SearchVideo` data class 中添加：

```kotlin
@SerialName("ugc_pay")
val ugcPay: Int = 0,
```

- `0` = 免费（默认值，向后兼容）
- `1` = 付费/充电专属

**关键设计**：默认值 `0` 确保即使搜索接口不返回此字段，也不会崩溃或改变现有行为。

### 2. UI 层：CoverThumbnail 支持角标

**文件**: `app/src/main/java/com/biliseek/app/ui/CoverImage.kt`

修改 `CoverThumbnail` composable，新增 `badge` 参数：

```kotlin
@Composable
fun CoverThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    badge: String? = null,  // 新增：左上角角标文字
) {
    // ... 现有逻辑 ...

    Box(base) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        // 角标：左上角红底白字
        if (!badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(
                        Color(0xFFFB7299),  // B站粉色
                        RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
```

**样式说明**：
- 位置：左上角
- 背景：B站粉色 `#FB7299`，圆角仅左上+右下
- 文字：白色 `labelSmall`，padding 内缩
- 不遮挡封面主体（角标很小）

### 3. 搜索结果传入角标

**文件**: `app/src/main/java/com/biliseek/app/SearchScreen.kt`

修改 `ResultRow` 中的 `CoverThumbnail` 调用：

```kotlin
CoverThumbnail(
    url = normalizeCoverUrl(video.pic),
    width = 64.dp,
    badge = if (video.ugcPay == 1) "充电" else null,
)
```

### 4. 测试

- 补充 `SearchVideo` 序列化单测：验证 `ugc_pay` 字段正确映射
- 验证默认值 `0` 的向后兼容性

## 验证

1. **真机实测**：搜索已知充电视频，确认封面显示「充电」角标
2. **降级验证**：如果搜索接口不返回 `ugc_pay`，角标不显示（不会崩溃）
3. **回归**：普通视频搜索结果不受影响

## 影响范围

| 组件 | 影响 |
|---|---|
| 搜索结果列表 | 封面可能多一个角标 |
| 网络请求 | 零额外请求（复用搜索接口） |
| 数据库 | 无变更 |
| 播放器 | 无变更 |

## 风险

- **搜索接口可能不返回 `ugc_pay`**：默认值 `0` 保证不崩溃，只是不显示标签。后续可通过详情接口补充。
- **角标遮挡**：64dp 宽的封面 + 小角标，实测不会影响辨识。
