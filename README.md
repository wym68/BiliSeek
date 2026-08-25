# BiliSeek

> **Seek and you shall hear.** 搜之，即得其声。

一个搜索驱动、默认只听、可按需看视频、**没有推荐首页**的第三方哔哩哔哩 Android 客户端。

[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

---

## 这是什么

Android 上有两类 B 站第三方应用，中间空着一块：

- **纯音频播放器**（BBPlayer、BiliSound）干净无推荐，但**只能听、不能看**
- **完整客户端**（BiliPai、bilimiao、PiliPlus）能看能听，但**都带推荐首页**、以视频浏览为中心

BiliSeek 要填的就是这个空位。

### 四条差异点

1. **全站关键词搜索** —— 打开就是搜索框，搜遍全 B 站，不限音乐区
2. **默认只听** —— 默认只下载音频轨，锁屏后台可听，省流量
3. **可切看视频** —— 一个按钮切到看模式，**切换无缝**：不重建播放源、不 seek、位置不丢
4. **没有推荐** —— 没有信息流，入口只有搜索 + 你自己的歌单和行为记录

> `seek` 一语双关：既是「搜索」，也是播放器进度条的 `seek`。

---

## 当前状态

**MVP（M0–M4）已完成。** 可以日常使用的最小闭环：

搜索 → 结果转可播放条目 → 默认听（后台 + 锁屏控制）→ **一键切看** → 歌单与续播

| 能力 | 状态 |
| --- | --- |
| 风控接入（buvid / bili_ticket / WBI 签名 / buvid 激活） | ✅ |
| 全站搜索 + 自动分页 | ✅ |
| 多 P 视频折叠展开（一个 BV 在结果里只占一条） | ✅ |
| 默认只听 + 后台播放 + 锁屏/通知栏控制 | ✅ |
| **一键切看（无缝）** | ✅ 实测 buffering=0、seek=0 |
| 画中画 | ✅ |
| 播放队列（顺序/列表循环/单曲/随机） | ✅ |
| 续播（含「接近结尾则从头播」） | ✅ |
| 本地歌单 | ✅ |
| 首页空状态（搜索历史 / 最近播放 / 播放热力） | ✅ |
| Web 扫码登录 | 🔸 流程完整，登录态续期未完成 |
| 收藏夹同步、歌词、下载导出 | ⏸ 规划中（P1/P2） |

已知缺口见 [手动测试指南 · 附录 3](docs/BiliSeek-手动测试指南.md)。

---

## 构建

### 环境

| 项 | 版本 |
| --- | --- |
| minSdk / targetSdk / compileSdk | 26 / 35 / 37 |
| AGP | 9.3.1 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.10 |
| JDK | Android Studio 捆绑的 JBR（当前为 25） |
| Media3 | 1.11.0 |

> **AGP 9 内置 Kotlin 支持**，不要再声明 `org.jetbrains.kotlin.android` 插件——声明了会直接报错。

### 步骤

```bash
git clone <repo> && cd biliseek

# 配置 SDK 路径
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 让 Gradle 用与 IDE 相同的 JDK，避免两套守护进程互相失效缓存
mkdir -p ~/.gradle
echo "org.gradle.java.home=$HOME/android-studio/jbr" >> ~/.gradle/gradle.properties

./gradlew installDebug
```

### 测试

```bash
./gradlew test                        # 单元测试
./gradlew connectedDebugAndroidTest   # 仪器化测试（需连设备）
```

---

## 模块结构

```
:app              UI、导航、feature 层、本地数据库
:core:network     风控接入、WBI 签名、Cookie、B 站 API
:core:player      取流、DASH 组装、播放内核、PlaybackService
:design-system    主题与设计 token
```

两条贯穿全局的架构约束：

- **播放器归 Service 持有**，UI 经 `MediaController` 访问。「默认只听」意味着用户大多数时间在别的 App 里、锁着屏，播放器生命周期不能绑 UI。
- **播放态由呈现态推导**：`PlaybackMode` × `PresentationMode` 两个正交维度，不变式「视频轨启用 ⟺ 存在可见 surface」由结构保证，无法被违反。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [功能选型篇](docs/BiliSeek-开发文档-功能选型篇.md) | 做什么、从哪取、为什么不做某些事 |
| [架构设计篇](docs/BiliSeek-开发文档-架构设计篇.md) | 模块边界、播放内核、切轨时序、数据流 |
| [验收清单](docs/BiliSeek-开发文档-验收清单.md) | 每个里程碑的验收判据 |
| [手动测试指南](docs/BiliSeek-手动测试指南.md) | 模拟器 / 真机有线 / 真机无线三条完整流程 |
| [验收记录](docs/验收记录/) | M0–M4 的实测证据与缺陷记录 |
| [学习文档](docs/) | BiliPai / BBPlayer / biu 的读码实录 |

---

## 授权

本项目采用 **[GPLv3](LICENSE)**。

参照了 BiliPai（GPLv3）的实现，因此派生作品必须同为 GPLv3。
第三方项目的致谢与各自授权说明见 [NOTICE](NOTICE)。

> ⚠️ 参考项目 [biu](https://github.com/wood3n/biu) 采用 PolyForm Noncommercial 1.0.0，
> **不是开源许可证，与 GPLv3 不兼容**。本项目仅参考其产品设计与公开接口用法，
> 未使用其任何代码或资源。

---

## 免责声明

- 本项目为**非官方**第三方客户端，与哔哩哔哩无任何官方关联或背书
- 数据来自哔哩哔哩公开接口，仅供**学习研究**，非商业用途
- 使用时须遵守哔哩哔哩用户协议与相关法律法规
- 因使用本项目导致的账号异常或其他后果，由使用者自行承担

关于账号安全，本项目做了这些取舍：只做 Web 扫码登录（不碰密码、不过极验、
不冒用官方 appkey）、收藏夹只读不写、风控码 `-352` 绝不重试。
