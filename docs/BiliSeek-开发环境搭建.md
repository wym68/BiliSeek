# BiliSeek 开发环境搭建

> 版本：v1.0
> 日期：2026-08-28
> 目标：在一台**干净的机器**上拿到源码后，照本文从上往下执行，
> 能跑通 `./gradlew test` 与 `./gradlew installDebug`，并把 App 装进模拟器或真机。

本文只讲**环境**。装完之后怎么测功能看 [手动测试指南](BiliSeek-手动测试指南.md)，
代码怎么组织看 [架构设计篇](BiliSeek-开发文档-架构设计篇.md)。

基准环境是 **Linux x86_64**（本项目的开发机为 Ubuntu 系，内核 7.0，shell 用 zsh）。
Windows / macOS 的差异集中在 [§9](#9-windows--macos-的差异)，其余步骤通用。

---

## 0. 环境清单

下表是**当前开发机上实际在用**的版本，全部经 `./gradlew test`（19 个测试类 / 154 个用例）与
`./gradlew installDebug` 验证通过。装新机时照抄这一列最稳。

| 项 | 版本 | 来源 |
| --- | --- | --- |
| JDK | **25.0.2**（JetBrains Runtime） | Android Studio 捆绑的 `jbr`，不单独装 |
| Android Studio | **2026.1.3**（AI-261.26222.65.2613.16025427） | 官网下载 |
| Gradle | **9.7.1** | 项目自带 wrapper，无需手装 |
| AGP | **9.3.1** | `gradle/libs.versions.toml` |
| Kotlin | **2.4.10** | 同上（AGP 9 内置 Kotlin，见 [§7](#7-项目特有的构建约定必读)） |
| KSP | **2.3.11** | 同上，Room 编译器用 |
| compileSdk / targetSdk / minSdk | **37 / 35 / 26** | 同上 |
| SDK Platform | **android-37.0**（本机另有 37.1） | sdkmanager |
| Build-Tools | **37.0.0**（本机另有 36.0.0） | sdkmanager |
| Platform-Tools（adb） | **37.0.1** | sdkmanager |
| Emulator | **37.1.11** | sdkmanager |
| System Image | **android-35;google_apis;x86_64** | sdkmanager |
| Media3 | 1.11.0 | `libs.versions.toml` |
| 源码 Java 兼容级 | **21**（各模块 `compileOptions`） | 各 `build.gradle.kts` |

**磁盘占用**（本机实测）：Android Studio 3.5G + SDK 5.2G（其中系统镜像 3.5G、模拟器 821M）
+ Gradle 缓存 `~/.gradle` 3.3G ≈ **12G**。留 20G 空间比较从容。
**内存**：Gradle daemon 配了 `-Xmx4096m`，加上模拟器 2G，建议 16G 起。

> **不需要装**：NDK、CMake、Ninja（本项目没有原生代码）；
> 也不需要单独装 Gradle 或系统 JDK——wrapper 和 JBR 各管一头。

---

## 1. 装 Android Studio（顺便就有了 JDK）

去 https://developer.android.com/studio 下 Linux 版压缩包，解到 `~/android-studio`：

```bash
tar -xzf android-studio-*-linux.tar.gz -C ~
~/android-studio/bin/studio.sh          # 首次启动，走完向导
```

**关键点：不要另外装 OpenJDK。** Android Studio 自带 JetBrains Runtime（`~/android-studio/jbr`），
本项目的 Gradle 也用它。两边同一个 JDK，才不会出现"IDE 构建完命令行再构建又全量重跑"的问题
（原因见 [§4](#4-配置-gradle-用哪个-jdk)）。

确认 JBR 在位：

```bash
~/android-studio/jbr/bin/java -version
# 期望：openjdk version "25.0.2" ... (build ...-b329.117)
```

> 如果你的公司环境不允许装 Studio，装一个 **JDK 21 或更高**的发行版也能构建
> （AGP 9 要求 ≥ 21，Studio 本身 `minRequiredJavaVersion` 也是 21），
> 把后文所有 `~/android-studio/jbr` 换成你的 `JAVA_HOME` 即可。只是那样就没有 IDE。

---

## 2. 装 Android SDK 组件

首次启动 Studio 的向导会把 SDK 装到 `~/Android/Sdk`。向导装的版本不一定对得上本项目，
所以用命令行按清单补齐最省事。命令行工具在 `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`
（若向导没装它，在 Studio 的 *SDK Manager → SDK Tools* 里勾上 **Android SDK Command-line Tools (latest)**）。

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# 先接受许可，否则后面构建会以 "not accepted the license agreements" 失败
yes | "$SDKMGR" --licenses

"$SDKMGR" \
  "platform-tools" \
  "platforms;android-37.0" \
  "build-tools;37.0.0" \
  "emulator" \
  "system-images;android-35;google_apis;x86_64"
```

几处说明：

- **`platforms;android-37.0`**：`compileSdk = 37` 需要 API 37（Android 17）平台。
  AGP 9 起平台带小版本号，本机同时装了 `android-37.0` 与 `android-37.1`（两者都是 API 37）。
  先装 `37.0`，若构建报缺另一个再补 `"platforms;android-37.1"`。
- **`system-images;android-35;...`**：本项目的 AVD 用 API 35。跑真机的话这条可以不装，
  能省 3.5G。想跑其它 API 级别的模拟器，换镜像即可，App 的 `minSdk` 是 26。
- **不装 `platforms;android-35`**：targetSdk 只是清单里的一个数字，不需要对应平台包。

验证：

```bash
"$SDKMGR" --list_installed
```

期望能看到 `platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0` 三项。

---

## 3. 配置环境变量

把下面几行加到 `~/.zshrc`（bash 用户加到 `~/.bashrc`），然后 `source` 一下或重开终端：

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$HOME/android-studio/jbr"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
```

这样 `adb`、`emulator`、`sdkmanager`、`avdmanager` 都能直接敲。
[手动测试指南](BiliSeek-手动测试指南.md) 里的命令全部假设它们在 `PATH` 上。

验证：

```bash
adb version        # 期望 1.0.41 / Version 37.0.1-...
java -version      # 期望 25.0.2
emulator -version  # 期望 37.1.11
```

---

## 4. 拉源码并写两个本地配置文件

```bash
git clone git@github.com:wym68/BiliSeek.git ~/Documents/biliseek
cd ~/Documents/biliseek
```

这两个文件都**不在版本库里**（机器相关），必须手写：

### 4.1 `local.properties`（项目内，告诉 Gradle SDK 在哪）

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

> 用 Studio 打开项目时它也会自动生成这一行。命令行先构建的话就得自己写，
> 否则报 `SDK location not found`。

### 4.2 `~/.gradle/gradle.properties`（配置 Gradle 用哪个 JDK）

```bash
mkdir -p ~/.gradle
echo "org.gradle.java.home=$HOME/android-studio/jbr" >> ~/.gradle/gradle.properties
```

**为什么要有这一条**：Studio 内部构建用 JBR，命令行如果用另一个 JDK，
Gradle 会起**第二个 daemon**，两个 daemon 的构建缓存互相判定失效——
表现就是"刚在 IDE 里构建完，命令行 `./gradlew installDebug` 又从头编译一遍"，反之亦然。
指到同一个 JDK 就没这个问题。

> 写在 `~/.gradle/` 而不是项目里，是因为它是机器级路径，不该进版本库。
> 项目内的 `gradle.properties` 只放跨机器通用的配置（内存、并行、缓存开关）。

对应地，Studio 里也要确认：*Settings → Build, Execution, Deployment → Build Tools → Gradle →
**Gradle JDK** 选 `jbr-25`（Embedded JDK）*。

---

## 5. 首次构建与验证

按顺序跑，每一步都该成功：

```bash
cd ~/Documents/biliseek

# 1) 环境自检：确认 Gradle 版本与 daemon JDK
./gradlew --version
#    期望 Gradle 9.7.1 / Daemon JVM: .../android-studio/jbr (from org.gradle.java.home)

# 2) 单元测试（不需要设备）。首次会下载依赖，约几分钟
./gradlew test

# 3) 打 debug 包
./gradlew assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk

# 4) 装到已连接的设备/模拟器
./gradlew installDebug
```

单元测试当前是 **19 个测试类 / 154 个用例**，分布在三个模块：

| 模块 | 覆盖 |
| --- | --- |
| `:core:network` | WBI 签名、AV/BV 换算、搜索输入与合并、错误分类、重试预算、`CorrespondPath` |
| `:core:player` | 播放模式策略、队列策略、续播、睡眠定时、响度、音质与无损清单、FLAC 缓冲 |
| `:app` | 时长格式化、歌单重排、视频闸门策略 |

仪器化测试要连设备（目前只有一个 Room 迁移测试）：

```bash
./gradlew connectedDebugAndroidTest
```

> 首次构建慢是正常的：要下 Gradle 9.7.1 发行包、AGP、Compose BOM 一整套依赖。
> 之后有配置缓存 + 构建缓存，增量构建通常几秒。

---

## 6. 准备一台设备

### 6.1 模拟器（日常迭代用）

本项目约定的 AVD 叫 **`BiliSeek_API35`**，[手动测试指南 Part A](BiliSeek-手动测试指南.md)
里的命令直接用这个名字。新机上这样建：

```bash
avdmanager create avd \
  -n BiliSeek_API35 \
  -k "system-images;android-35;google_apis;x86_64" \
  -d pixel_7
```

本机 AVD 的关键配置（`~/.android/avd/BiliSeek_API35.avd/config.ini`）：

| 项 | 值 |
| --- | --- |
| 设备型号 | `pixel_7` |
| ABI | `x86_64` |
| 内存 | `hw.ramSize=2G` |
| 数据分区 | `10G` |
| GPU | `hw.gpu.enabled=no`（软件渲染） |

启动：

```bash
emulator -avd BiliSeek_API35 &
adb wait-for-device
adb shell getprop sys.boot_completed     # 返回 1 即就绪
```

> **模拟器的两条已知限制**（已写进测试指南，不是配置问题）：
> **没有音频输出**，倍速的音调变化听不出来；adb 输入法打不了中文，搜索中文要用界面软键盘。
> 音频焦点、耳机、真实解码器这些都得上真机。

Linux 上模拟器起不来，多半是 KVM 权限：

```bash
ls -l /dev/kvm                            # 应该存在
sudo usermod -aG kvm $USER && newgrp kvm  # 把自己加进 kvm 组
```

### 6.2 真机（发版前验证用）

手机上打开*开发者选项 → USB 调试*，插上 USB，弹框选"允许"：

```bash
adb devices                    # 记下序列号
adb -s <序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

同时接了模拟器和真机时，后续 adb 命令都要带 `-s <序列号>`，或者先 `adb emu kill` 关掉模拟器。

本项目的真机基准是**一加 PJD110（ColorOS / Android 16 / API 36）**。
国产 ROM 有几处 adb 管不到的地方（画中画权限默认不给且不允许 adb 改、锁屏 adb 解不开），
详见 [手动测试指南 Part B](BiliSeek-手动测试指南.md)。

---

## 7. 项目特有的构建约定（必读）

这几条不知道会踩坑，写在这里：

**1. AGP 9 内置 Kotlin，不要再声明 `org.jetbrains.kotlin.android`。**
声明了会直接报错。版本目录里 `kotlin-android` 这个 alias 还留着，但**没有任何模块用它**——
模块只声明 `kotlin.compose` / `kotlin.serialization` / `ksp` 这类插件。

**2. 配置缓存默认开着。**`gradle.properties` 里 `org.gradle.configuration-cache=true`。
改了 `build.gradle.kts` 或版本目录后第一次构建会重算配置，属正常。
构建脚本里在配置阶段读环境变量/系统属性会让缓存失效并报错，写脚本时注意。

**3. Room 的 schema 导出目录是 `app/schemas/`，且被 androidTest 当 assets 读。**
改数据库结构必须**提交新的 schema JSON**（目前有 `1.json`、`2.json`），
否则 `MigrationTest` 会挂。相关配置在 `app/build.gradle.kts` 的 `ksp { arg("room.schemaLocation", ...) }`。

**4. debug 变体的包名带 `.debug` 后缀。**
`applicationIdSuffix = ".debug"`，所以调试时的包名是 **`com.biliseek.debug`**，
不是 `com.biliseek`。所有 adb 命令都要用带后缀的那个。
启动 Activity 是 `com.biliseek.app.MainActivity`。

**5. 各模块统一 `JavaVersion.VERSION_21`。**
JDK 用 25 跑，但字节码目标是 21。新增模块时照抄现有模块的 `compileOptions`。

**6. release 变体没开混淆**（`isMinifyEnabled = false`），也没有配签名。
本项目目前只走 debug 流程，要发正式包得自己加 `signingConfigs`（密钥别提交，`.gitignore`
里有注释掉的 `*.jks` / `*.keystore` 规则，按需取消注释）。

**7. 仓库根目录锁死了依赖源。**
`settings.gradle.kts` 用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，
模块里再写 `repositories {}` 会直接构建失败——加新仓库只能改根 settings。

---

## 8. 常用命令速查

```bash
# 构建 / 安装
./gradlew assembleDebug                  # 只打包
./gradlew installDebug                   # 打包并装到设备
./gradlew clean                          # 清构建产物

# 测试
./gradlew test                           # 全部单元测试
./gradlew :core:player:test              # 只跑某模块
./gradlew connectedDebugAndroidTest      # 仪器化测试（需设备）

# 排障
./gradlew --stop                         # 停掉所有 daemon
./gradlew assembleDebug --no-configuration-cache   # 绕开配置缓存复现问题
./gradlew assembleDebug --info            # 详细日志

# 运行时
adb shell am start -n com.biliseek.debug/com.biliseek.app.MainActivity
adb shell am force-stop com.biliseek.debug
adb logcat -c && adb logcat -s BiliSeekAuth:V BiliSeekPlayUrl:V BiliSeekPlayer:V
```

项目的日志 tag 统一以 `BiliSeek` 开头，按子系统分：
`BiliSeekAuth`（风控与登录）、`BiliSeekPlayUrl`（取流）、`BiliSeekPlayer`（播放内核）。

> ⚠️ **别对真机跑 `adb shell pm clear com.biliseek.debug`**：
> 会清掉扫码登录态和本地歌单，重新登录要人拿手机扫码。模拟器上随便清。

---

## 9. Windows / macOS 的差异

整体流程一样，改这几处：

| 项 | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Studio 安装位置 | `~/android-studio` | `/Applications/Android Studio.app` | `C:\Program Files\Android\Android Studio` |
| JBR 路径 | `~/android-studio/jbr` | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` | `...\Android Studio\jbr` |
| SDK 默认位置 | `~/Android/Sdk` | `~/Library/Android/sdk` | `%LOCALAPPDATA%\Android\Sdk` |
| wrapper | `./gradlew` | `./gradlew` | `gradlew.bat` |
| 系统镜像 ABI | `x86_64` | Apple Silicon 用 `arm64-v8a` | `x86_64` |
| 模拟器加速 | KVM | 系统自带 | WHPX / Hyper-V |

Windows 上 `local.properties` 的路径分隔符要转义：`sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk`。

---

## 10. 常见问题排查

**`SDK location not found`**
`local.properties` 没写或路径不对。回 [§4.1](#41-localproperties项目内告诉-gradle-sdk-在哪)。

**`Failed to install the following SDK components ... not accepted the license agreements`**
跑 `yes | sdkmanager --licenses`。

**`Unsupported class file major version` / `requires Java 21 or higher`**
Gradle 用的 JDK 太老。`./gradlew --version` 看 `Daemon JVM:` 那行指向哪里，
不是 JBR 就回 [§4.2](#42-gradlegradleproperties配置-gradle-用哪个-jdk)，然后 `./gradlew --stop` 让旧 daemon 退出。

**IDE 构建完命令行又全量重编（反之亦然）**
两边 JDK 不一致。同上，两边都指到 `~/android-studio/jbr`。

**声明了 `kotlin.android` 插件后构建报错**
AGP 9 内置 Kotlin 支持，删掉这行。见 [§7](#7-项目特有的构建约定必读) 第 1 条。

**`Configuration cache problems found`**
先用 `--no-configuration-cache` 确认是不是缓存本身的问题；
如果是新加的构建脚本导致，改成配置阶段不读外部状态（用 `providers.environmentVariable` 之类）。

**依赖下不动 / `Could not resolve ...`**
`settings.gradle.kts` 里只配了 `google()` 和 `mavenCentral()`。
国内网络慢的话在**根 settings** 里加镜像（模块里加会被 `FAIL_ON_PROJECT_REPOS` 拒绝）。

**`MigrationTest` 失败，提示找不到 schema**
改了 Room 实体但没提交新的 `app/schemas/*.json`。跑一次 `./gradlew :app:assembleDebug` 生成，
然后把 JSON 一起提交。

**模拟器起不来 / `KVM is not installed`**
见 [§6.1](#61-模拟器日常迭代用) 末尾的 KVM 权限处理。也可以改用 `-gpu swiftshader_indirect` 软渲染。

**`adb devices` 里真机显示 `unauthorized`**
手机上没点"允许 USB 调试"。拔插一次，注意看手机弹框；
还不行就在开发者选项里"撤销 USB 调试授权"后重来。

**构建卡在 `Configuration cache entry stored` 之后很久**
第一次跑或者刚改过版本目录，属于正常。持续复现的话 `./gradlew --stop` 再试。

---

## 11. 换机迁移小抄

不想重下 5G 依赖的话，从旧机拷这三份就能离线开工：

| 目录 | 内容 | 大小 |
| --- | --- | --- |
| `~/.gradle` | Gradle 发行包 + 依赖缓存 + `gradle.properties` | ~3.3G |
| `~/Android/Sdk` | SDK 全部组件（含 licenses） | ~5.2G |
| `~/.android/avd` | AVD 定义与快照 | 视快照而定 |

拷完仍要做的：改 `local.properties` 里的 `sdk.dir`、改 `~/.gradle/gradle.properties` 里的
`org.gradle.java.home`（这两条都写着绝对路径），以及 `~/.zshrc` 里的环境变量。

---

## 附：相关文档

| 文档 | 内容 |
| --- | --- |
| [README](../README.md) | 项目定位、能力清单、模块结构 |
| [手动测试指南](BiliSeek-手动测试指南.md) | 模拟器 / 真机有线 / 真机无线三条完整测试流程 |
| [架构设计篇](BiliSeek-开发文档-架构设计篇.md) | 模块边界、播放内核、切轨时序 |
| [功能选型篇](BiliSeek-开发文档-功能选型篇.md) | 做什么、从哪取、为什么不做某些事 |
| [功能优化篇](BiliSeek-开发文档-功能优化篇.md) | M5–M9 规划与欠账总表 |
