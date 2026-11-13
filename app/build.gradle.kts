plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.winwasm64"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.winwasm64.workstation"
        minSdk = 23   // v4.88.0: универсально для всех телефонов, планшетов и приставок (Compose требует 23+)
        targetSdk = 35
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 4000 + ciRunNumber
        versionName = "4.90.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Ресурсы библиотек только на русском и английском (остальные ~80 языков вырезаются из APK)
        resourceConfigurations += listOf("ru", "en")

        // Только ARM-устройства (телефоны/планшеты). x86/x86_64 нужны лишь эмуляторам ПК
        ndk {
            // v4.4.3: только arm64 — библиотека Psiphon весит 31 МБ на каждую архитектуру
            // v4.88.0: универсальная сборка — телефоны, планшеты и приставки (32 и 64 бита)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        val unifiedKeystore = rootProject.file("debug.keystore").takeIf { it.exists() }
            ?: file("release-keystore.jks")
        getByName("debug") {
            if (unifiedKeystore.exists()) {
                storeFile = unifiedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (unifiedKeystore.exists()) {
                storeFile = unifiedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8: удаление неиспользуемого кода и ресурсов (classes.dex 19 МБ -> ~2 МБ)
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        // v4.4.3: нативные библиотеки сжимаются в APK (Psiphon: 31 МБ → ~11 МБ при скачивании)
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin/**"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // v4.88.0: java.time и прочее для Android 5/6 через десугаринг
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    // v4.87.0: интерфейс и движок RupoopFork (RuTube/VK/OK/LordFilm) внутри нашего APK
    implementation(project(":rupoop"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    // Media3 ExoPlayer: native видеоигра вместо WebView (из RupoopFork)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    // v4.4.3: запасной канал Psiphon
    implementation("ca.psiphon:psiphontunnel:2.0.42")
    // v4.66.0: официальная библиотека WireGuard (Maven Central) — в ней настоящий userspace
    // бэкенд libwg-go.so для всех ABI. Самосборный модуль :tunnel его не содержал, поэтому
    // GoBackend падал с UnsatisfiedLinkError ещё до попытки поднять туннель.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
