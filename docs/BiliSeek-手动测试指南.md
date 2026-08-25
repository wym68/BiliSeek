# BiliSeek 手动测试指南

> 版本：v1.0  
> 日期：2026-08-25  
> 适用：MVP（M0–M4）已完成的构建  
> 用途：你自己上手验证，覆盖模拟器与真机两条路径

---

## 0. 五分钟跑起来

```bash
cd ~/Documents/biliseek

# 1. 起模拟器（带界面，方便手动操作）
emulator -avd BiliSeek_API35 &

# 2. 构建并安装
./gradlew installDebug

# 3. 启动
adb shell am start -n com.biliseek.debug/com.biliseek.app.MainActivity
```

装完后 App 会出现在启动器里，名字是 **BiliSeek**。

> 应用包名是 `com.biliseek.debug`（debug 变体带 `.debug` 后缀），后面所有 adb 命令都用这个。

---

## 1. 模拟器与真机：分别能验什么

**这张表决定了你该在哪台设备上测什么。** 有些项在模拟器上永远是「假通过」。

| 测试项 | 模拟器 | 真机 | 说明 |
| --- | --- | --- | --- |
| 搜索 / 播放 / 切看 | ✅ | ✅ | 两边等价 |
| 后台播放 / 锁屏控制 | ✅ | ✅ | 模拟器需先启用锁屏，见 §4.3 |
| **PiP 画中画** | ⚠️ | ✅ | **模拟器上 PiP 恒可用**，因此「PiP 不可用时回落音频」这条分支在模拟器上永远走不到 |
| **音频焦点**（来电、其他 App 抢占） | ❌ | ✅ | 模拟器无法真实模拟 |
| **蓝牙耳机控制** | ❌ | ✅ | 模拟器无蓝牙设备 |
| **拔耳机自动暂停** | ❌ | ✅ | 需真实音频设备 |
| **低端机降级**（模糊/动效） | ❌ | ✅ | 需一台 2–4 GB 内存的老机器 |
| **Hi-Res FLAC 解码** | ❌ | ✅ | 部分设备的 `c2.android.flac.decoder` 只给 32 KiB 输入缓冲会崩，模拟器撞不到 |
| 扫码登录 | ✅ | ✅ | 都需要你用手机 B 站 App 扫 |
| 流量消耗对比 | ⚠️ | ✅ | 模拟器 netstats 是粗粒度轮询，见 §5.3 |

**结论**：日常迭代用模拟器，**发版前必须用真机过一遍** ⚠️ 和 ❌ 那些行。

---

## 2. 真机准备

### 2.1 打开开发者选项

设置 → 关于手机 → 连续点「版本号」7 次 → 返回 → 系统 → 开发者选项 → 打开 **USB 调试**。

### 2.2 连接确认

```bash
adb devices
```

期望看到序列号 + `device`。若显示 `unauthorized`，看手机上的授权弹窗；若显示 `no permissions`，udev 规则没生效：

```bash
sudo apt install -y android-sdk-platform-tools-common   # 需在真正的终端里跑
```

装完拔插一次数据线。

### 2.3 无线调试（可选，Android 11+）

有线更稳，但测「拔耳机」「走动中切网络」这类场景时无线更方便：

```bash
# 手机：开发者选项 → 无线调试 → 使用配对码配对设备
adb pair <手机显示的IP:端口>
adb connect <手机IP:5555>
```

### 2.4 安装

```bash
./gradlew installDebug
```

同时连了模拟器和真机时会报错，指定设备：

```bash
adb devices                          # 记下序列号
adb -s <序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. 调试观测手段

### 3.1 日志过滤（最常用）

App 里有四个自定义 tag：

| Tag | 内容 |
| --- | --- |
| `BiliSeekAuth` | 风控凭据种植、恢复、失败 |
| `BiliSeekPlayUrl` | 取流、MPD 组装、URL 缓存命中 |
| `BiliSeekPlayer` | **EventLogger**——轨道选择、解码器、缓冲、首帧 |
| `BiliSeekHttp` | 完整 HTTP 请求/响应头 |

```bash
# 只看播放器
adb logcat -s BiliSeekPlayer:V

# 看全部业务日志
adb logcat -s BiliSeekAuth:V BiliSeekPlayUrl:V BiliSeekPlayer:V BiliSeekHttp:V

# 先清屏再看（推荐，避免被历史日志干扰）
adb logcat -c && adb logcat -s BiliSeekPlayer:V
```

### 3.2 界面内的自检卡片

**切换听/看时，播放页会直接给出诊断结果**，不需要翻日志：

```
✓ 切换无缝：听 → 看
buffering 次数      0   (须为 0)
seek 不连续次数     0   (须为 0)
位置 25340ms → 25342ms
```

只要出现 `✗`，就说明切换过程中重新缓冲或做了 seek——那不是无缝。

### 3.3 系统状态速查

```bash
# 播放服务是否活着、前台类型对不对
adb shell dumpsys activity services | grep -A 8 PlaybackService

# 媒体会话状态（锁屏/通知栏控制的前提）
adb shell dumpsys media_session | grep -E "package=com.biliseek|state=PlaybackState"

# 是否在画中画
adb shell dumpsys activity activities | grep -i PictureInPictureMode

# 内存
adb shell dumpsys meminfo com.biliseek.debug | grep "TOTAL PSS"

# 崩溃
adb logcat -d -s AndroidRuntime:E | grep -A 10 FATAL
```

---

## 4. 逐项手动测试

按你实际会怎么用 App 的顺序组织，不是按技术模块。

### 4.1 首次启动

**操作**：全新安装后打开（若装过，先 `adb shell pm clear com.biliseek.debug`）。

| 检查 | 期望 |
| --- | --- |
| 首屏 | 出现「Seek and you shall hear.」和一行说明，**不是空白** |
| 底部导航 | 搜索 / 音乐库 / 设置，共三个，**没有第四个** |
| 通知权限弹窗 | Android 13+ 会弹一次；**拒绝也不应影响后续使用** |
| 后台日志 | `adb logcat -s BiliSeekAuth:V` 应看到「风控凭据种植完成」 |

**再次启动**应看到「凭据从本地恢复，跳过网络请求」——不重复打网络请求。

### 4.2 搜索与播放

**操作**：搜索框输入关键词 → 点「搜索」。

| 检查 | 期望 |
| --- | --- |
| 结果列表 | 显示「约 N 个结果 · 第 1/M 页」，条目含标题、UP 主、时长、播放量 |
| 标题清洗 | **不应出现 `&amp;quot;` 或 `<em>` 这类残留** |
| 分页 | 往下滑到底自动加载下一页，页码递增 |
| 点击条目 | 起播，播放器展开，进度推进 |
| 多 P 视频 | 搜索结果里**只占一条**；点入展开成分 P 列表；点某个 P 播放对应内容（**核对时长是否与列表标注一致**） |
| 长按条目 | 加入歌单（没有歌单时自动建「我的歌单」） |
| 粘贴 BV 号 | 直接播放，不走搜索 |

**搜索没结果时**要区分两种情况：显示错误卡片 = 请求失败；列表空但无错误 = 确实没匹配。

### 4.3 后台与锁屏

**操作**：播放中按 Home 键。

| 检查 | 期望 |
| --- | --- |
| 音频 | **继续播放** |
| 通知栏 | 出现媒体控制卡片，可暂停/继续 |
| 锁屏 | 锁屏界面出现媒体控制，标题正确 |

**模拟器需要先启用锁屏**（AVD 默认关掉了）：

```bash
adb shell locksettings set-disabled false
adb shell input keyevent KEYCODE_SLEEP && sleep 2
adb shell input keyevent KEYCODE_WAKEUP
```

> 锁屏媒体卡片由 **MediaSession** 驱动，即使通知权限被拒也会显示；但**通知栏**控制依赖通知权限。这两者是分开的。

### 4.4 一键切看（核心卖点）

**操作**：播放中展开播放器 → 点「切「看」」。

| 检查 | 期望 |
| --- | --- |
| 画面 | 3 秒内出现在封面区，**页面不跳转** |
| **声音** | **不中断、不重复、进度不跳** ← 这才是关键 |
| 自检卡片 | `✓ 切换无缝`，buffering=0，seek=0 |
| 退回「听」 | 画面消失，音频继续 |

**用耳机验一遍**。眼睛看画面出没出来很容易，耳朵听有没有断点才是真判据。

**严格验证视频轨是否真被禁用**（不是画面消失就行）：

```bash
adb logcat -s BiliSeekPlayer:V | grep -E "videoEnabled|videoDisabled"
```

听模式下应看到 `videoDisabled`；EventLogger 打印的轨道列表里视频轨应是 `[ ]` 而非 `[X]`。

> 为什么较这个真：`clearVideoSurface()` 也会让画面消失，但视频轨可能仍在下载和解码——那样省电省流量一点没省，而这正是 BiliSeek 的核心承诺。

### 4.5 全屏与画中画

| 操作 | 期望 |
| --- | --- |
| 看模式下视频区上滑 | 进全屏，界面只剩视频 |
| 全屏按返回 | 回到展开态，**不退出 App** |
| 再按返回 | 收回 MiniPlayer；**底部导航选中态、列表滚动位置都不变** |
| 看模式下按 Home | 进画中画，**PiP 窗口有画面不黑屏** |
| 从 PiP 点回 App | 回到内联看模式，画面连续 |

PiP 窗口黑屏是最典型的失败信号——说明「进后台」判定误伤了 PiP。

### 4.6 队列与续播

**操作**：点多 P 视频的某个 P，或播放本地歌单。

| 检查 | 期望 |
| --- | --- |
| 队列显示 | 播放页显示「队列 N / M」 |
| 上一首 / 下一首 | 序号正确变化 |
| 播放模式 | 点按钮循环：顺序 → 列表循环 → 单曲 → 随机 |
| 单曲循环下点「下一首」 | **应该换歌**，不是原地打转 |
| 顺序模式播到最后一首结束 | 停止，不回头 |

**续播**：

```bash
# 播一会儿（至少 10 秒，位置每 5 秒回写一次）
adb shell am force-stop com.biliseek.debug
adb shell am start -n com.biliseek.debug/com.biliseek.app.MainActivity
```

首页「最近播放」应显示保存的位置；点进去应从该位置继续。

> 注意一个刻意的行为：**如果上次听到距结尾不足 15 秒，会从头开始**而不是续播。这不是 bug——从 61 秒续播一首 62 秒的歌，用户看到的是「点了没反应」。

### 4.7 本地歌单

| 操作 | 期望 |
| --- | --- |
| 音乐库 → 新建 | 输入名字后出现在列表 |
| 搜索结果长按 | 加入歌单 |
| 点歌单 | 进详情，显示「歌单名 · N 首」 |
| 点条目 | 整个歌单作为队列播放，从该条开始 |
| 移除 / 改名 / 删除 | 立即生效，重启后保持 |

### 4.8 登录（需要你的账号）

**这部分我没法替你验。**

设置 → 生成二维码 → 用手机 B 站 App 扫。

| 阶段 | 期望 |
| --- | --- |
| 生成 | 显示二维码 |
| 扫描后（未确认） | 界面变为「✓ 已扫描，请在手机上确认」 |
| 确认 | 「✓ 登录成功」，显示 UID |
| 重启 App | 仍是登录态 |
| 二维码放置 3 分钟 | 显示「二维码已失效」，可重新生成 |
| 登出 | 回到未登录，**但搜索和播放仍然可用** |

> **登录非强制**是产品身份的一部分：不登录也能搜索、播放、建歌单。请务必验证这一点——全新安装后不登录直接用一遍。

> ⚠️ **Cookie 刷新尚未实现**（RSA 加密算 `correspondPath` + 解析 HTML 取 `refresh_csrf` 两步是空的）。登录后过一段时间可能掉登录，这是已知缺口。

---

## 5. 进阶验证

### 5.1 弱网与断网

```bash
# 断网
adb shell svc wifi disable && adb shell svc data disable

# 恢复
adb shell svc wifi enable && adb shell svc data enable
```

| 场景 | 期望 |
| --- | --- |
| 断网启动 App | **界面完整可交互，搜索框能输入**，不白屏不 ANR |
| 断网时点播放 | 显示明确错误，**不是无限转圈** |
| 恢复网络后重试 | 自动恢复正常 |
| 播放中断网 20 秒再恢复 | 自动续上，不永久卡死 |

真机上更真实的测法：走进电梯 / 切飞行模式 / Wi-Fi 切 4G。

### 5.2 长时间稳定性

播 30 分钟以上，期间频繁切页、切看/听、进出后台。

```bash
# 每隔一会儿采一次
adb shell dumpsys meminfo com.biliseek.debug | grep "TOTAL PSS"
```

内存应趋于平稳。**持续单调增长**才是泄漏信号（短期波动正常）。

### 5.3 流量对比（验证「听模式省流量」）

⚠️ **模拟器上必须先强制轮询**，否则读到的永远是 0：

```bash
adb shell dumpsys netstats --poll
adb shell dumpsys netstats detail | grep -A 12 "uid=<你的uid>"
```

uid 用 `adb shell dumpsys package com.biliseek.debug | grep appId` 查。

真机上更简单：**设置 → 网络 → 流量使用 → BiliSeek**，分别在听模式和看模式各播 5 分钟对比。

预期是**量级差异**（实测约 160 倍），不是几个百分点。

### 5.4 低端机（只能真机）

找一台 2–4 GB 内存的老机器：

| 检查 | 期望 |
| --- | --- |
| 界面流畅度 | 滚动不卡顿 |
| 模糊与动效 | 应自动降级（⚠️ **此功能尚未移植**，当前会与旗舰机表现一致） |
| 长时间播放 | 不被系统杀掉 |

### 5.5 Hi-Res 音质（需大会员账号）

登录大会员账号后播放支持 Hi-Res 的视频。

**特别注意**：部分设备的系统 FLAC 解码器只分配 32 KiB 输入缓冲，而 B 站 Hi-Res 单帧可能超过，会触发崩溃。**这个坑只在真机上撞得到。**

```bash
adb logcat -s BiliSeekPlayer:V | grep -i "flac\|InsufficientCapacity"
```

---

## 6. 自动化测试

手动测之外，这些能一条命令跑完：

```bash
# 单元测试（秒级）
./gradlew test

# 仪器化测试（需连设备，含数据库迁移测试）
./gradlew connectedDebugAndroidTest
```

当前覆盖：`core:network` 23 例、`core:player` 23 例、数据库迁移 1 例。

**能自动化的**：轨道选择、音质回退、状态迁移判定、错误码分流、队列推进、续播位置、WBI 签名、搜索输入解析、数据库迁移。

**不能自动化的**：声音是否真的出来、切换听感是否无缝、画面是否及时、锁屏控制是否可用、低端机是否卡顿。**播放器类应用的质量主要靠手工验收保证，测试覆盖率不代表质量。**

---

## 7. 排查手册

| 现象 | 优先排查 |
| --- | --- |
| 搜索一直没结果、无错误 | buvid 未激活。`adb shell pm clear` 后重启，看日志有无「buvid 激活: code=0」 |
| 有画面没声音 / 有数据播不出 | 请求头缺 Referer，CDN 返回 403。看 `BiliSeekHttp` 日志 |
| 接口返回 `-412` | WBI 签名参数集不对 |
| 接口返回 `-352` | 触发风控。**别反复重试**，等一会儿再用 |
| 切看时声音断一下 | 播放源被重建了。看自检卡片的 buffering / seek 计数 |
| 关了画面但流量照跑 | 只清了 surface 没禁用视频轨。看 `videoDisabled` 日志 |
| PiP 窗口黑屏 | 「进后台」判定误伤 PiP |
| 切 tab 声音中断 | MiniPlayer 被卷进导航重组 |
| 点了播放没反应 | 可能是续播到了结尾。看日志有无 `state ... ENDED` |
| Android 14+ 启动即崩 | 前台服务类型未声明 `mediaPlayback` |

---

## 8. 已知缺口

测之前先知道这些**本来就没做**，避免误报：

| 缺口 | 影响 |
| --- | --- |
| **Cookie 刷新未实现** | 登录后可能周期性掉登录 |
| 低端机降级策略未移植 | 老机器上模糊/动效不降级 |
| 流量策略（仅 Wi-Fi 拉视频） | 移动网络下看视频不受限 |
| 队列列表与拖拽重排 | 只显示「队列 N / M」 |
| 播放历史的时长 | 显示为「61s / 0s」，时长恒为 0 |
| AV 号 / b23.tv 短链 | 识别了但退回关键词搜索 |
| **错误浮现慢（约 85 秒）** | 断网时点播放，要等很久才报错 |
| 加入歌单固定选第一个 | 没有「选择歌单」对话框 |

---

## 附：常用命令速查

```bash
# 构建安装
./gradlew installDebug

# 清数据重来（测首次启动必用）
adb shell pm clear com.biliseek.debug

# 启动
adb shell am start -n com.biliseek.debug/com.biliseek.app.MainActivity

# 强制停止（测续播用）
adb shell am force-stop com.biliseek.debug

# 截图
adb exec-out screencap -p > /tmp/shot.png

# 录屏（测切换动效很有用）
adb shell screenrecord /sdcard/demo.mp4      # Ctrl+C 停止
adb pull /sdcard/demo.mp4

# 清日志再看
adb logcat -c && adb logcat -s BiliSeekPlayer:V
```
