# 参考项目学习文档 · biu

> 版本：v1.0（读码实录）
> 日期：2026-08-23
> 方法：通过 GitHub API 拉取完整文件树 + 读取关键源文件，非二手资料
> 定位：BiliSeek 的**产品原型参照**与**B 站接口地图**；⚠️ **代码一行都不能抄**

---

## 0. 先读这一条：授权是硬约束

| | |
| --- | --- |
| 授权 | **PolyForm Noncommercial License 1.0.0** |
| 版权 | `Copyright (c) 2022–2025 wood3n` |
| 是否开源许可证 | **否**。它限制商业用途，不满足 OSD（开源定义） |
| 与 GPLv3 兼容性 | **不兼容**。GPLv3 禁止附加使用限制 |

**结论：**

- ❌ 不能复制 biu 的任何源码、资源、图标进入 BiliSeek
- ❌ 不能把 biu 的代码「改写后」并入（衍生作品同样受限）
- ✅ 可以阅读、学习**思路与产品设计**（想法、架构模式不受版权保护）
- ✅ 可以参照它**用了哪些 B 站接口、传了哪些参数**——API 端点与参数名是事实性信息，不构成表达

> 选型文档 §3「参考项目与授权」目前只列了 BiliPai（GPLv3）与 BBPlayer（MIT）。既然 biu 已被列为第三个参考项目，**必须把这条授权约束写进合规清单**，并在 README 致谢时明确「仅参考产品设计，未使用其代码」。

---

## 1. 事实卡片

| 项目 | 实测值 |
| --- | --- |
| 仓库 | `wood3n/biu`，默认分支 `master` |
| 版本 | v1.16.0 |
| 技术栈 | **Electron + React + TypeScript + rsbuild**（桌面端，非移动端） |
| 规模 | 423 个文件（`src` 307 / `electron` 54 / `shared` 14） |
| 活跃度 | 2637 star，最后推送 2026-08-17 |
| 自我定位 | 「基于哔哩哔哩公开接口的跨平台桌面音乐播放器」 |

---

## 2. 事实修正：**biu 并非「没有推荐」**

选型文档 §1 背景写道：

> 「桌面端 biu 证明了一种干净的使用方式：打开即搜索、把 B 站当音频库来听，**没有信息流推荐**。」

实测：biu **有一个专门的推荐页** `src/pages/music-recommend/`（6 个文件），背后调用：

```
src/service/web-interface-new-music.ts          新歌
src/service/web-interface-new-music-banner.ts   新歌 banner
src/service/music-comprehensive-web-rank.ts     音乐综合排行榜
src/service/web-interface-region-feed-rcmd.ts   分区推荐 feed
```

准确的说法应该是：**biu 没有「视频信息流推荐」，但有「音乐榜单/新歌推荐」页面。**

> **对 BiliSeek 的意义**：这不影响我们「不做推荐」的定位，但要注意区分两件事——
> - **算法信息流**（个性化、无限下拉）：违背 BiliSeek 身份，坚决不做
> - **榜单/新歌**（全站统一、非个性化、有限列表）：不违背身份，但也不在当前规划内
>
> 建议在选型文档里把背景描述改准确，避免用一个不成立的前提来论证定位。

---

## 3. 最有价值的部分：`src/service/` 是一张 B 站接口地图

biu 采用**一个接口一个文件**的组织方式，约 90 个文件，文件名即接口路径。这是三个参考项目里**接口覆盖最系统、可读性最好**的一份，适合当查询手册用。

### 3.1 播放与视频

```
player-playurl.ts                    播放地址
player-pagelist.ts                   分 P 列表
web-interface-view.ts                视频详情
web-player.ts                        播放器信息
audio-song-info.ts                   B 站音频区（au）歌曲信息
audio-web-url.ts                     B 站音频区播放地址
click-interface-click-web-h5.ts      播放行为上报
click-interface-web-heartbeat.ts     播放心跳（写入观看历史/进度）
```

> **注意 `audio-*` 这两个**：B 站有独立的「音频区」（`au` 号，非 `BV`），接口与视频完全不同。BiliPai 的 `MusicPlaybackSource` 也区分了 `AudioSong(sid)` 与 `VideoAudio(bvid,cid)`。BiliSeek 若只做视频抽音频，可以不碰；但要意识到搜索结果里可能混入音频区内容。

`player-playurl.ts` 的参数注释很清楚：

```ts
qn?: number;          // 视频清晰度  未登录默认 32(480P)  登录后默认 64(720P)
fnval?: number;       // 流格式位掩码
fnver?: number;       // 固定为 0
platform?: string;    // pc: web播放(默认)  html5: 移动端HTML5播放
high_quality?: number; // platform=html5 时置 1 可使画质到 1080p
```

> `platform=html5 + high_quality=1` 是一条**不需要登录也能拿到 1080p** 的路径（代价是只有 MP4 单流、无 DASH、无高码率音频）。BiliPai 的 ApiClient 里也有对应的「HTML5 降级方案（无 Referer 鉴权，仅 MP4 格式）」注释。**这是取流失败时的最后兜底**，BiliSeek 值得保留。

### 3.2 搜索

`web-interface-search-type.ts` 把搜索参数枚举得很完整：

```ts
search_type: "video" | "bili_user" | "article" | "photo" | "live"

// video
order?: "totalrank" | "click" | "pubdate" | "dm" | "stow" | "scores"
        // 综合   / 最多点击 / 最新发布 / 最多弹幕 / 最多收藏 / 最多评论
duration?: 0 | 1 | 2 | 3 | 4   // 全部 / <10 / 10-30 / 30-60 / >60 分钟
tids?: number                   // 分区 tid，0 = 全部

// bili_user
order?: 0 | "fans" | "level"
order_sort?: 0 | 1              // 0 降序 1 升序
```

配套还有 `main-suggest.ts`（搜索建议）与 `web-interface-history-search.ts`（搜索历史）。

> **对 BiliSeek 的意义**：
> - `duration` 过滤对「听」场景意义很大——想听完整专辑就筛 `>60 分钟`，想听单曲就筛 `<10 分钟`。**建议 BiliSeek 的搜索结果页把 duration 做成一级筛选器**，这是「音频优先」的差异化点，视频客户端不会这么设计。
> - `order=stow`（最多收藏）通常比 `totalrank` 更能筛出「值得反复听」的内容。可以作为默认排序候选。
> - MVP 只做 `search_type=video`，与我们已定的方向一致。

### 3.3 收藏夹（数量最多，说明这是核心）

```
fav-folder-created-list.ts / -all.ts    我创建的收藏夹
fav-folder-collected-list.ts            我收藏的收藏夹
fav-folder-info.ts / -add / -edit / -del
fav-folder-deal.ts                      移动/复制
fav-folder-fav.ts / -unfav.ts           订阅/取消订阅
fav-resource.ts / -infos.ts             收藏夹内容
fav-resource-batch-del.ts / -clean.ts
fav-season-fav.ts / -unfav.ts           合集订阅
medialist-gateway-base-created.ts
medialist-gateway-coll-resource-check.ts / -deal.ts
```

**14 个收藏夹相关接口**——这印证了「收藏夹同步」不是一个小功能，而是一整块。BiliSeek 把它放 P1 是合理的，但要预期到工作量。

### 3.4 登录与风控

```
passport-login-web-qrcode-generate.ts   扫码：生成
passport-login-web-qrcode-poll.ts       扫码：轮询
passport-login-web-sms-send.ts          短信：发送
passport-login-web-login-sms.ts         短信：登录
passport-login-web-login-passport.ts    密码登录
passport-login-web-key.ts               密码加密公钥
passport-login-captcha.ts               验证码
passport-login-web-country.ts           国家区号
passport-login-exit.ts                  登出
passport-login-web-cookie-info.ts       ★ Cookie 有效期查询
passport-login-web-confirm-refresh.ts   ★ 确认刷新
passport-login-web-cookie-refresh.ts    ★ Cookie 刷新
gaia-vgate.ts                           ★ 风控验证网关
```

> **`cookie-info` / `confirm-refresh` / `cookie-refresh` 这三个是 BiliSeek 应当重视的。**
> B 站的 Cookie 会过期，官方提供了主动刷新机制（查询是否需要刷新 → 用 `refresh_token` 换新 Cookie → 确认旧凭证作废）。**不做刷新，用户就会隔一段时间被登出一次**，对「打开即用」的体验伤害很大。
> BiliPai 有 `core/network/TokenRefreshHelper.kt` 对应这块，可以直接复用（GPLv3）。
> 选型文档目前没有这一条，**建议补进 MVP 或 P1**。

### 3.5 其他

```
history-*.ts               历史记录 / 稍后再看
relation-*.ts              关注关系
space-wbi-*.ts             UP 主空间（需 WBI 签名）
series-*.ts                合集/系列
web-dynamic*.ts            动态
ai-lyrics.ts               ★ AI 歌词
vu-web-cover-up.ts         封面上传
```

`ai-lyrics.ts` 值得一提：biu 用 AI 生成/匹配歌词，是 BBPlayer「网易云/QQ/酷狗多源匹配」之外的另一条路。BiliSeek 的 P2 歌词方案可以把它作为兜底候选（但依赖外部 AI 服务，与「纯本地」定位有张力）。

---

## 4. 风控接入层：`electron/network/` 四件套

这是 biu 最值得学的工程细节。虽然是 Electron 实现，**思路可以完整迁移到 Android/OkHttp**。

### 4.1 User-Agent：伪装成桌面 Chrome

`electron/network/user-agent.ts`（就一行）：

```ts
export const UserAgent = `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36`;
```

### 4.2 buvid：向服务端索取，而非本地生成

`electron/network/web-buvid.ts`：

```ts
GET https://api.bilibili.com/x/frontend/finger/spi_v2
→ { data: { b_3: string /* buvid3 */, b_4: string /* buvid4 */ } }
```

> **与 BiliPai 的做法形成对比**：BiliPai 的 `AppSessionCookieJar` 在本地用 `UUID.randomUUID() + "infoc"` 造一个 buvid3；biu 则老老实实调 `spi_v2` 让服务端下发。
> **服务端下发的版本更不容易被风控盯上**（本地生成的 UUID 格式与真实 buvid 有分布差异）。BiliSeek 建议采用 biu 的思路：**优先调 `spi_v2` 获取，失败时才回退本地生成**。

### 4.3 bili_ticket：容易被忽略但很关键

`electron/network/web-bili-ticket.ts`：

```ts
POST https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket
参数：
  key_id      = "ec02"                              // 固定
  hexsign     = HMAC-SHA256(key="XgwSnGZ1p", msg="ts"+timestamp)  // 十六进制
  context[ts] = UNIX 秒级时间戳
  csrf        = bili_jct（可为空）

返回：
  data.ticket      bili_ticket（JWT）
  data.ttl         259200 秒（3 天）
  data.nav.img     ★ WBI img_key
  data.nav.sub     ★ WBI sub_key
```

三个要点：

1. `bili_ticket` 是近年新增的风控 Cookie，缺失会显著提高触发验证的概率。
2. HMAC 的密钥 `"XgwSnGZ1p"` 是硬编码常量，`key_id = "ec02"` 同理。
3. **这个接口顺带返回 WBI 密钥** —— 意味着可以一次请求同时解决 `bili_ticket` 和 WBI key，不必再单独打 `nav`。BiliPai 和 BBPlayer 都是单独调 `nav` 拿 WBI key 的，**biu 这条路径更省一次请求**。

> **BiliSeek 建议**：冷启动流程做成
> `spi_v2 取 buvid3/4` → `GenWebTicket 取 bili_ticket + WBI keys` → 完成接入
> 两次请求把风控三件套（buvid / bili_ticket / wbi）全部备齐。

### 4.4 请求拦截：统一注入 Referer / Origin / UA

`electron/network/interceptor.ts`：

```ts
headers["Referer"]    = "https://www.bilibili.com";
headers["Origin"]     = "https://www.bilibili.com";
headers["User-Agent"] = UserAgent;
```

还有一段响应侧的处理——重写 `Set-Cookie` 的 `SameSite=none; Secure=true`，这是 Electron 环境特有的问题（跨源 Cookie 存不下），**Android 端不需要**。

> 但 **Referer 是必须的**：B 站的音视频 CDN 会校验 Referer，不带就 403。BiliSeek 的播放请求（不只是 API 请求）也必须带 Referer 和 UA——BiliPai 的 `feature/download/DownloadRequestHeaders.kt` 就是干这个的。

---

## 5. 产品设计：可借鉴的与不适用的

### 5.1 页面构成

```
src/pages/
  search/            11 文件   搜索
  video-collection/  17 文件   合集/收藏夹（最大的一块）
  user-profile/      11 文件   UP 主页
  history/            7        历史
  settings/           7        设置
  music-recommend/    6        音乐推荐（见 §2）
  dynamic-feed/       5        动态
  later/              5        稍后再看
  download-list/      4        下载管理
  follow-list/        4        关注
  local-music/        3        本地音乐
  mini-player/        3        迷你播放器
```

### 5.2 值得借鉴的组件设计

| 组件 | 对 BiliSeek 的启发 |
| --- | --- |
| `components/full-screen-player/` | 全屏播放页——BiliSeek「看模式呈现形态」待决问题的参照 |
| `components/lyrics/` + `lyrics-search-modal/` | 歌词展示 + 手动搜索歌词 |
| `components/music-playlist-drawer/` | 播放队列抽屉 |
| `components/virtual-list/`、`virtual-page-list/`、`virtual-grid-page-list/` | **三种虚拟列表**——收藏夹动辄上千条，虚拟化是必需品 |
| `components/audio-waveform/` | 音频波形 |
| `components/video-pages-download-select-modal/` | 多 P 下载选择 |
| `store/play-progress.ts` | 播放进度持久化（对应我们的「续播」） |
| `store/search-history.ts` | 搜索历史（对应「冷启动空状态」） |

### 5.3 明确不适用

| biu 的做法 | 为什么 BiliSeek 用不了 |
| --- | --- |
| `electron/ffmpeg/` 打包四平台 ffmpeg 二进制做转码 | Android 上 DASH audio 本身就是 M4A/AAC，**直接存盘即可，无需转码**；打包 ffmpeg 会让 APK 暴涨且有 GPL/专利问题 |
| `electron/windows/{tray,thumbar}` 系统托盘、任务栏缩略图 | 桌面端专有 |
| `electron/shortcut.ts` 全局快捷键 | 桌面端专有 |
| `mini-player.ts` 独立小窗口 | Android 对应物是 PiP，机制完全不同 |
| `electron/ipc/*` 主进程/渲染进程 IPC 架构 | Electron 专有 |
| 密码登录 / 极验 | BiliSeek 已定「MVP 仅扫码」 |

---

## 6. 对 BiliSeek 的取用清单

### 6.1 可以取（只是知识，不涉及代码）

- **B 站接口地图**：哪些端点、什么参数、什么语义（`src/service/` 目录本身就是索引）
- **风控接入顺序**：`spi_v2` → `GenWebTicket`（顺带拿 WBI key）→ 带 Referer/Origin/UA 请求
- **Cookie 刷新机制**的存在与三步流程
- **搜索参数**：`duration` / `order` 的取值与语义
- **产品形态**：全屏播放页、播放队列抽屉、虚拟列表、播放进度持久化

### 6.2 绝对不能取

任何源代码文件、UI 资源、图标、样式、文案。

### 6.3 需要在合规清单里补的条目

```
- [ ] README 致谢 biu 时明确标注：仅参考产品设计与公开接口用法，
      未使用其任何代码或资源（其授权为 PolyForm Noncommercial 1.0.0，
      与本项目 GPLv3 不兼容）
```

---

## 7. 对现有选型文档的修正建议

| # | 现文档表述 | 实测情况 | 建议 |
| --- | --- | --- | --- |
| 1 | §1 背景「biu……没有信息流推荐」 | biu 有 `music-recommend` 页（新歌/榜单/分区推荐） | 改为「没有**视频**信息流推荐」，避免用不成立的前提论证定位 |
| 2 | §3 只列 BiliPai / BBPlayer 两个参考项目及授权 | biu 是 PolyForm Noncommercial，与 GPLv3 **不兼容** | **必须补入**，并加合规清单条目 |
| 3 | 无「Cookie 刷新 / 登录态续期」条目 | biu 有完整三接口流程，BiliPai 有 `TokenRefreshHelper.kt` | **建议补入 MVP 或 P1**，否则用户会周期性掉登录 |
| 4 | 4.1 新增的「WBI 签名 / 设备指纹 cookie 种植」 | 还应包含 `bili_ticket` | 说明栏补上 `bili_ticket`；并注明 `GenWebTicket` 顺带返回 WBI key，可省一次请求 |
| 5 | buvid3 获取方式未指定 | BiliPai 本地生成 UUID；biu 调 `spi_v2` 由服务端下发 | 采用 biu 思路：优先 `spi_v2`，失败回退本地生成 |
| 6 | 搜索结果页未提筛选维度 | biu 枚举了 `duration` / `order` 全部取值 | 建议把 `duration` 做成一级筛选器（听场景的差异化点） |
| 7 | §2 非目标「暂不做视频下载」 | biu 支持视频下载但依赖 ffmpeg 转码 | 维持不做；同时说明音频下载**无需转码**，成本远低于视频 |
