plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.biliseek.core.network"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 网络栈
    api(libs.okhttp)
    api(libs.retrofit)
    implementation(libs.retrofit.serialization)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging)

    // 风控凭据持久化
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    // 单测里 buildActivationPayload 走真实 org.json（Android 桩件会抛 not-mocked）
    testImplementation("org.json:json:20240303")
}
