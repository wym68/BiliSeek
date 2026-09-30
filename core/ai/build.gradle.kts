plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// AI 总结能力模块（工单 docs/10-AI总结-实现工单.md）。
// 刻意不依赖任何 Android API、也不依赖 :core:network / :core:player——不认识 B 站，
// 只认「文本 + 时间戳 → Markdown」。B 站相关接线在 :app。
android {
    namespace = "com.biliseek.core.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 公开 API 里出现 OkHttpClient（ProviderFactory）、JsonElement（ChatCapabilities.extraBody）、Flow
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
