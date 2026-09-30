plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// 离线语音识别模块（工单 docs/archive/10-AI总结-实现工单.md AI-7）。
// 只认「本地音频文件 → 带时间戳的句子」：不依赖 :core:ai / :core:network / :core:player，不知道 B 站。
// 唯一的网络行为是模型下载（独立 OkHttpClient，不带任何 Cookie）。
android {
    namespace = "com.biliseek.core.asr"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // sherpa-onnx 官方 AAR 里的 classes.jar（Kotlin API，包名 com.k2fsa.sherpa.onnx）；
    // 原生库在 src/main/jniLibs。版本与来源见 README.md。
    implementation(files("libs/sherpa-onnx-classes-1.13.8.jar"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
