# 视频动态长按菜单

日期：2026-09-29
范围：`app` 模块的动态卡片 `DynamicCard`（`DynamicScreen.kt`，被「动态」tab 与「UP 空间 → 动态」共用）+ `AppViewModel` + `MainActivity` 接线。

## 背景

视频动态的视频行（封面 + 标题）只挂了 `Modifier.clickable`：点按播放，**长按松手也被当成一次点按**，同样直接开播。
用户希望长按弹出动作菜单：加入列表、加入队列、下一首播放、下载。

这四个动作在搜索结果与 UP 空间投稿的三点菜单里已经齐备（`AppViewModel.startSingleAction` 处理单 P / 多 P，
多 P 进挑 P 页；单条下载走 `DownloadDialog`），动态这边只缺接线。

## 方案

**卡片自己管长按菜单**（否决了「页面层持有菜单状态」——两个页面各写一份；以及「长按进复选」——范围大且不是需求）。

1. **热区**：只在视频行上长按生效（与点按播放同一块）。头像、正文、图片、底部互动栏不变；图文动态没有长按菜单。
2. **交互**：视频行 `clickable` → `combinedClickable(onClick = 播放, onLongClick = 弹菜单)`；
   菜单复用 `ActionListSheet`，标题为视频标题，四项依次：加入列表 / 加入队列 / 下一首播放 / 下载。
   不放「查看UP主」「复制链接」：点头像已能进 UP 空间，转发按钮已能复制链接。
3. **回调**：四个动作打包成 `DynamicVideoActions`（照 `VideoActionHandlers` 的写法），
   `DynamicCard` 新增可空参数 `videoActions`，为 null 时不挂长按（行为同现状）。
   `DynamicScreen` / `UpSpaceScreen` 原样透传；`MainActivity` 组装一份给两处共用。
4. **行为复用**（与搜索结果三点菜单完全一致）：
   - 加入列表 → `startSingleAction(video, AddToPlaylist)` → 收藏夹 / 本地歌单选择框
   - 加入队列 → `AppendToQueue`（追加到队尾）
   - 下一首播放 → `EnqueueNext`（插到当前项之后）
   - 以上三项遇多 P 视频先进挑 P 页（默认全选）
   - 下载 → `DownloadDialog`（只音频 / 视频 + 保存位置）
5. **数据**：`AppViewModel.kt` 在 `SpaceVideo.toSearchVideo()` 旁新增 `DynamicEntry.toSearchVideo(): SearchVideo?`
   （图文动态返回 null）；新增 `AppViewModel.startDynamicSingleAction(entry, action)`。

字段映射：

| SearchVideo | 来源 |
| --- | --- |
| `bvid` / `title` | `entry.video.bvid` / `entry.video.title` |
| `author` / `mid` | `entry.authorName` / `entry.authorMid` |
| `duration` | `entry.video.durationText`（`parseDurationText` 可解析） |
| `pic` | `entry.video.coverUrl` |

## 测试

- 单测：`DynamicEntry.toSearchVideo()` 字段映射；图文动态（`video == null`）返回 null。
- 真机：点按仍播放；长按弹菜单且**不再**顺带开播；四项各自生效；多 P 弹挑 P 页；UP 空间动态列表同样生效。
