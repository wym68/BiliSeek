# 按视频、按天记收听时长（统一日播放记录与当日听歌时长的口径）

日期：2026-10-07　关联：功能优化篇 §13.40（遗留的「日列表口径待用户决定」）

## 问题

- 「当日听歌时长」来自 `listen_day`（按天累计真实收听毫秒数），不知道听的是哪个视频。
- 日播放记录页的列表、首页热力图深浅来自 `play_history`：每个 (bvid, cid) 只有一行、只记最后一次播放。视频改天重播，就从旧日子的列表和格子里挪走。
- 结果：某天显示听了 2 小时，列表里却可能只剩几条甚至为空。

用户选定：按视频、按天记收听时长；热力图深浅按当天收听分钟数分档。

## 设计

### 1. 数据

- 新表 `listen_entry`：主键 (`epochDay`, `bvid`, `cid`)，字段 `listenedMs`（这天累计收听毫秒）、`lastListenedAt`（这天最后一次计入的墙钟毫秒）。
- `cid` 与 `play_history` 同口径：播放请求里的 cid（`PlaybackState.cid`，起播第一 P 时可能为 0），保证能和 `play_history` 按 (bvid, cid) 对上。
- 标题、UP 主、封面、时长不重复存，展示时 JOIN `play_history`。起播时 `trackStartRecorder` 必然先写 `play_history`。
- DB 升到 **v10**，`MIGRATION_9_10` 只建表；提交 `app/schemas/.../10.json`。
- 累加与 `listen_day` 同法：`INSERT OR IGNORE` 建行，再 `UPDATE … listenedMs = listenedMs + :ms, lastListenedAt = :at`，包在一个事务里（minSdk 26 的 SQLite 3.18 不支持 UPSERT）。
- `listen_day` **停止写入、保留不删**：10-04～10-07 只有按天总数、没有分视频明细，靠它回退显示。

### 2. 计时

- `ListenTimeTracker` 改为观察 (`isPlaying`, 当前视频键 `bvid + cid`)。当前视频键为空（`bvid` 为空，如 `Direct` 请求）时不计时。
- 纯状态机 `ListenClock`（`data/ListenTimePolicy.kt`）的每一段带上视频键：
  - `start(key, …)`：没在计时就开始；正在给**另一个**视频计时，先结算旧的一段再给新视频开始。
  - `tick` / `stop` 不变，结算出的切片带视频键。
- 切片类型改为 `ListenSlice(epochDay, bvid, cid, ms, endWallMs)`；`splitByLocalDay` 仍负责跨零点按毫秒切分。
- 其余口径不变：只在播放中计时（暂停、缓冲不算），倍速按实际过去的时间算，播放中每 30 秒落库一次。

### 3. 日播放记录页

- 列表：这天 `listen_entry` 的全部行 JOIN `play_history`，按 `lastListenedAt` 倒序。听得很短（几秒）也列出，不设门槛。
- 每行副标题：「UP 主 · 听了 mm:ss」（替换原来的完整时长）。
- 顶部：当日听歌时长 = 这天各行 `listenedMs` 之和；「共 N 首」= 行数。
- 回退（这天 `listen_entry` 没有行）：列表回到 `play_history` 当天口径（副标题仍显示完整时长）；时长取 `listen_day`，没有则「未统计」。
- 选哪种口径由纯函数决定（输入：这天的明细行、`listen_day` 值、`play_history` 当天条目），单测覆盖。

### 4. 首页热力图

- 每天的分钟数 = 当天 `listen_entry` 合计；没有则取 `listen_day`。
- 分档（纯函数，替换 `heatmapLevelAlpha` 的输入）：0 分钟不涂；1–9 分钟 0.2；10–29 分钟 0.4；30–59 分钟 0.6；≥ 60 分钟 1.0。不足 1 分钟但大于 0 按 1–9 档。
- 两张表都没有数据的日子（10-04 以前），仍按 `play_history` 当天条目数用原来的次数分档。

## 不做

- 不回填 10-07 以前的分视频明细（没有数据）。
- 不删除 `listen_day`、不改 `play_history` 的语义（续播仍依赖它）。
- 不加「清空收听记录」入口。

## 验证

- 单测（全离线）：`ListenClock` 换视频结算、同一视频不重置、跨零点、`Direct` 不计时；分钟分档；日页口径回退；热力图三级回退。
- `MigrationTest` 补 9→10（仪器化，只做编译核对，不在设备上跑）。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin` 通过；不装机。
- 真机待用户验证：同一视频隔天重播，两天的列表都有；列表各行时长之和等于顶部时长；热力图深浅随收听分钟变化。
