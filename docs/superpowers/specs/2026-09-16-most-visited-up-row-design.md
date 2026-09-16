# BiliSeek「最常访问」栏真实化改造 — 设计文档

> **版本**: v1.0
> **日期**: 2026-09-16
> **状态**: 已确认

---

## 1. 目标

动态页「最常访问」头像栏存在三个问题，本设计一次性解决：

1. **昵称过长撑宽 item**：昵称 Text 无宽度约束，长名字（如"张小珺商业访谈录"）导致一屏只能显示 4 个 UP 主。
2. **筛选后无法返回全量**：点击头像筛选该 UP 后，UI 没有回到"全部"动态的入口。
3. **排名名不副实**：当前展示的是关注列表接口（`x/relation/followings`）的前 6 个，即"最近关注的前几个"，与"最常访问"无关。需要实现真实的本地访问统计。

## 2. 现状

### 2.1 数据流

```
initDynamic() → repository.followings()（x/relation/followings，pn=1 ps=50 order=desc）
              → _dynamic.upChips = 前 50 个关注
MostVisitedUpRow: LazyRow { items(upChips.take(6)) }  ← 无宽度约束的昵称 Text
点击头像 → selectDynamicUp(up) → selectedUpMid = up.mid → dynamicSpace(mid) 只看该 UP
       → 回全量只有 selectDynamicUp(null)，UI 未暴露
```

### 2.2 关键文件

| 文件 | 作用 |
|------|------|
| `app/.../ui/DynamicScreen.kt:274-323` | `MostVisitedUpRow`（标题 + 头像横滑列表） |
| `app/.../AppViewModel.kt:782-845` | `initDynamic` / `selectDynamicUp` / `refreshUpChips` / `loadDynamicPage` |
| `app/.../AppViewModel.kt:1739` | `openUpSpace(user: SearchUser)` —— 打开空间页唯一汇聚点 |
| `app/.../AppViewModel.kt:2520` | `play(request: PlaybackRequest)` —— 起播唯一汇聚点 |
| `app/.../data/Entities.kt` / `Daos.kt` / `BiliSeekDatabase.kt` | Room 数据库（版本号 + Migration 模式已有先例） |
| `core/player/.../PlaybackModels.kt:52-64` | `PlaybackRequest.Bili`（有 author 无 mid） |
| `core/network/.../dto/BiliDto.kt:158` | `SearchVideo.mid`（视频 owner mid 可得） |

## 3. 设计

### 3.1 UI 修复（DynamicScreen.kt）

- 昵称 `Text` 加 `Modifier.width(48.dp)`（头像 40dp + 余量），`maxLines = 1` + Ellipsis；item 间距 16dp → 12dp。
- `take(6)` → `take(10)`（LazyRow 可横滑，看不完可滑或点"更多 >"）。
- 行首加「全部」chip：圆形 `surfaceVariant` 底 + "全"字，点击调 `onSelectUp(null)` 回全量；无筛选时带主题色描边高亮。
- 再点已选中的头像 = 取消筛选（toggle 语义，选中头像带主题色描边）。
- 「全部」与选中态高亮实现：`MostVisitedUpRow` 增加 `selectedMid: Long?` 参数，描边用 `Modifier.border(2.dp, primary, CircleShape)`。

### 3.2 访问统计（数据层）

新 Room 实体与 DAO（数据库版本 +1，Migration 参照 `MigrationTest` 先例）：

```kotlin
@Entity(tableName = "up_visits")
data class UpVisitEntity(
    @PrimaryKey val mid: Long,
    val visitCount: Long,
    val lastVisitMs: Long,
)
```

- `recordVisit(mid, nowMs)`：同日去重 —— `lastVisitMs` 与今天同日期则只更新 `lastVisitMs`，否则 `visitCount + 1`。以本地时区自然日判断。
- DAO 接口方法：
  - `@Upsert recordVisit(entity)` 或 `@Query` 递增（去重判断放 Repository/UseCase 层，便于单测）
  - `topVisited(): List<UpVisitEntity>`（`ORDER BY visitCount DESC, lastVisitMs DESC`）
- 计次全部走 Room 协程（`Dispatchers.IO` via Room），失败静默，绝不影响主流程。

### 3.3 计数挂接（AppViewModel，三处）

| 触发行为 | 挂接点 | mid 来源 |
|---------|--------|---------|
| 点头像筛选 | `selectDynamicUp(up != null)` | `up.mid` |
| 打开 UP 空间页 | `openUpSpace(user)` | `user.mid`（mid=0 跳过） |
| 看视频 | `play(PlaybackRequest.Bili)` | 新增 `authorMid` 字段，构造处填充（`SearchVideo.toRequest` 等） |

### 3.4 排名合并

纯函数（便于单测）：

```kotlin
fun mergeUpChips(followings: List<FollowingUp>, visits: List<UpVisitEntity>, limit: Int = 10): List<FollowingUp>
```

1. 有访问记录的关注 UP：按 `visitCount` desc、`lastVisitMs` desc 排前；
2. 未访问过的：按关注列表原顺序补齐；
3. 返回前 `limit` 个。

展示数据 `upChips` 即合并结果，名字/头像始终来自关注列表（`FollowingUp`），访问表只存 mid + 计数。

## 4. 错误处理

- 计数失败静默（`runCatching`），不冒泡到 UI。
- 访问表查询失败降级为原关注列表顺序展示。

## 5. 测试

| 层级 | 内容 |
|------|------|
| 单元测试 | `mergeUpChips` 排序/补齐/limit；同日去重纯函数；DAO 递增 |
| Migration 测试 | DB 版本 +1 升级不丢数据（参照现有 `MigrationTest`） |
| UI 测试 | 昵称宽度约束渲染；"全部"chip 回全量；再点取消筛选；选中描边 |
| 真人测试 | 真实账号：连续几天使用后排名变化；筛选/空间页/播放后排名更新（按项目 AGENTS.md 要求由测试人员手动执行） |

## 6. 明确不做（YAGNI）

- 不做时间衰减权重（同日去重 + 次数排序已够用）。
- 不做未关注 UP 的访问展示（栏位数据以关注列表为界）。
- 不做计数的设置项/清零入口。
- 不改动「更多 >」关注列表页（`FollowingListScreen`）内部。
