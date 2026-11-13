// v4.87.0: RupoopFork как БИБЛИОТЕЧНЫЙ модуль нашего APK — единый интерфейс, без второго приложения.
plugins {
    // версии плагинов заданы в корневом build.gradle.kts
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.santiago43rus.rupoop"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        manifestPlaceholders["appAuthRedirectScheme"] = "rupoop"
        // правила R8 из этого файла применяются ко ВСЕМУ нашему APK
        consumerProguardFiles("proguard-rules.pro")

        // их код читает эти поля из BuildConfig — в библиотеке их надо объявить явно
        buildConfigField("String", "GH_CLIENT_ID", "\"\"")
        buildConfigField("String", "PROXY_URL", "\"\"")
        buildConfigField("String", "DONATE_URL", "\"\"")
        buildConfigField("String", "VERSION_NAME", "\"Rupoop integrated\"")
        buildConfigField("int", "VERSION_CODE", "1")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.security.crypto)
    implementation(libs.appauth)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
}
