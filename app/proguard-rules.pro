# ===== BiliSeek release 混淆规则（M10 新增）=====
#
# ⚠️ 裁错不会编译报错，只会运行时崩或接口静默解析失败——
# release 包必须整轮跑 Part A 回归（见验收记录 M10）。
# 规则按「会被 R8 裁掉的东西」分类，只补必要的，不滥用通配 keep。

# ── 反射 / 注解基础属性 ──────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── kotlinx.serialization DTO ────────────────────────────────────
# 全部 @Serializable 类集中在 core/network/dto/BiliDto.kt。
# 序列化器是编译期生成的 Xxx$$serializer，运行时按名反射查找——
# 裁掉的表现是接口静默解析失败（字段落默认值），不是编译报错。
# 规则照 kotlinx.serialization 官方文档（1.11.x）。
-keep,includedescriptorclasses class com.biliseek.core.network.dto.**$$serializer { *; }
-keepclassmembers class com.biliseek.core.network.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.biliseek.core.network.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.AnnotationsKt
# dto 包之外唯一的 @Serializable：设备指纹持久化（core/network/internal）。
# 用具体化类型解码，序列化器本应编译期静态解析；仍显式保留——一旦被裁，解码静默失败会
# 导致每次启动都重新生成指纹，这本身就是风控信号。
-keep,includedescriptorclasses class com.biliseek.core.network.internal.DeviceFingerprint$$serializer { *; }
-keepclassmembers class com.biliseek.core.network.internal.DeviceFingerprint {
    *** Companion;
}

# ── Room：实体 / DAO / 数据库 ────────────────────────────────────
# Room 生成的实现类按全名引用实体与 DAO；混淆后打开即崩。
# 本 App 的数据层全部在 app.data 包内。
-keep class com.biliseek.app.data.** { *; }

# ── Media3：session 服务与渲染器反射入口 ─────────────────────────
# PlaybackService 是 manifest 声明的 MediaSessionService 子类，
# 系统 binder 与 Media3 session 通过名称/反射找到它；自定义渲染器
# 工厂在 ExoPlayer.Builder 上装配，属运行时反射入口。
-keep class com.biliseek.core.player.PlaybackService { *; }
-keep class com.biliseek.core.player.internal.HiResCompatibleRenderersFactory { *; }
-keep class com.biliseek.core.player.internal.FlacTolerantAdapterFactory { *; }

# ── manifest 组件（R8 通常自动保留，显式声明以防万一）────────────
-keep class com.biliseek.app.BiliSeekApp { *; }
-keep class com.biliseek.app.MainActivity { *; }
-keep class com.biliseek.app.download.DownloadService { *; }

# ── Retrofit 接口：注解驱动的动态代理 ─────────────────────────────
# Retrofit 在运行时为接口生成代理，方法上的注解是唯一契约。
-keep,allowobfuscation,allowshrinking interface com.biliseek.core.network.BiliApi

# ── 其余三方库（OkHttp / Retrofit / Coil / DataStore / Media3 / zxing）
#    自带 consumer rules，无需在此重复；release 回归必须验到它们工作正常。
