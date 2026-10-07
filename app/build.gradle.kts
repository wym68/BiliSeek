import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ===== M10 发版签名 =====
// 密钥与口令不进仓库：从 local.properties（已被 .gitignore 忽略）或环境变量读取。
// 四键齐全才给 release 配置签名；缺键时 release 仍可构建 unsigned 包，
// 但 installRelease 会因签名缺失快速失败——这是刻意的快速失败信号。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv("BILISEEK_" + key.uppercase().replace('.', '_'))

val signingKeyPath = signingValue("signing.keyPath")
val signingKeyAlias = signingValue("signing.keyAlias")
val signingKeyStorePassword = signingValue("signing.keyStorePassword")
val signingKeyPassword = signingValue("signing.keyPassword")
val hasReleaseSigning =
    listOf(signingKeyPath, signingKeyAlias, signingKeyStorePassword, signingKeyPassword)
        .all { !it.isNullOrBlank() }

android {
    namespace = "com.biliseek.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.biliseek"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // M10 版本策略：versionName 与里程碑对齐（M9 完成 → 0.2.0，M11 完成 → 0.3.0，0.3.x 逐条优化收口 → 0.4.0，
        // 0.4.0 的功能连同 AI 总结 1.0 一起以 0.4.1 发布，0.4.1 之后的逐条优化以 0.4.2 发布，其后的小修补以 0.4.3 发布）；
        // versionCode 每次发版 +1（MVP=1 → M10=2 → M11=3 → 0.4.0=4 → 0.4.1=5 → 0.4.2=6 → 0.4.3=7），只在发版提交上动。
        versionCode = 7
        versionName = "0.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeyPath!!)
                storePassword = signingKeyStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // M10：开 R8 + 资源收缩。
            // ⚠️ 裁错不会编译报错，只会运行时崩或接口静默解析失败——
            // release 包必须整轮跑 Part A 回归（见 docs/验收记录/M10）。
            isMinifyEnabled = true
            isShrinkResources = true
            // AI-7：离线识别原生库只发 arm64（debug 不加过滤，:core:asr 另带 x86_64 供模拟器）
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

android.sourceSets.getByName("androidTest") {
    // 迁移测试需要读取导出的 schema
    assets.srcDir(projectDir.resolve("schemas"))
}

// ===== M10-D 发版归档 =====
// 每次 release 构建后运行：把 APK 与 mapping.txt 按版本归档到 releases/<versionName>/。
// **没有 mapping.txt 就不算一次发版**——用户报的崩溃堆栈没有它无从还原。
// releases/ 不进仓库（APK 已被 .gitignore 忽略），mapping 走本地/私有存储。
tasks.register("archiveReleaseArtifacts") {
    group = "release"
    description = "把 release APK 与 mapping.txt 归档到 releases/<versionName>/"
    dependsOn("assembleRelease")

    // 配置期把路径全部求值成局部变量——任务闭包不能引用脚本对象（配置缓存要求）
    val versionName = android.defaultConfig.versionName ?: "unknown"
    val outDir = rootProject.projectDir.resolve("releases").resolve(versionName)
    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile

    doLast {
        outDir.mkdirs()
        var archived = 0
        if (apkFile.exists()) {
            apkFile.copyTo(outDir.resolve(apkFile.name), overwrite = true)
            archived++
        } else {
            logger.warn("release APK 不存在：${apkFile.path}")
        }
        if (mappingFile.exists()) {
            mappingFile.copyTo(outDir.resolve("mapping.txt"), overwrite = true)
            archived++
        } else {
            logger.warn("mapping.txt 不存在：${mappingFile.path} —— 本次发版无法还原崩溃堆栈")
        }
        logger.lifecycle("归档完成：${outDir.path}（${archived} 个文件）")
    }
}

// Room schema 导出目录，迁移测试依赖它
ksp {
    arg("room.schemaLocation", projectDir.resolve("schemas").path)
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:player"))
    implementation(project(":core:ai"))
    implementation(project(":core:asr"))
    implementation(project(":design-system"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zoomimage.compose.coil3)

    // AI 总结阅读页（工单 AI-6）
    implementation(libs.markdown.renderer.m3)

    // 封面取色（M12-A）。官方小库，配合 design-system 的策略层
    implementation(libs.palette.ktx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 用户设置（SettingsStore）。会话与风控凭据各有自己的 store，在 :core:network 里
    implementation(libs.androidx.datastore.preferences)
    // SAF 自定义目录写入（下载「保存位置」三选一）
    implementation(libs.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // M12-B：Compose UI 测试
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.room.testing)
}
