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
        minSdk = 21
        targetSdk = 35
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 4000 + ciRunNumber
        versionName = "4.15.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Ресурсы библиотек только на русском и английском (остальные ~80 языков вырезаются из APK)
        resourceConfigurations += listOf("ru", "en")
    }

    // Android 5.0+ (API 21+): собираем и 32-битный armeabi-v7a (большинство старых телефонов Android 5–7),
    // и 64-битный arm64-v8a, и единый universal ARM APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
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
                enableV1Signing = true
                enableV2Signing = true
            }
        }
        create("release") {
            if (unifiedKeystore.exists()) {
                storeFile = unifiedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                // Подпись v1 (JAR) обязательна для установки на Android 5.0–6.0 (API 21–23), v2 — для Android 7.0+
                enableV1Signing = true
                enableV2Signing = true
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
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf(
                "/lib/x86/**",
                "/lib/x86_64/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
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
        // Desugaring Java 8+/11+ коллекций (Collection.removeIf, Map.getOrDefault, Stream, java.time и др.) для Android 5–6 (API 21–23)
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Строгая байткод-проверка совместимости с Android 5.0+ (minSdk 21) и верификатором классов ART
        checkOnly += setOf("NewApi", "InlinedApi", "ClassVerificationFailure", "Instantiatable")
        error += setOf("NewApi", "InlinedApi", "ClassVerificationFailure", "Instantiatable")
        abortOnError = true
        textReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    // Современный BoringSSL (TLS 1.2 + TLS 1.3 + SNI) для Android 5.0–9.0
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // v4.4.3: запасной канал Psiphon
    implementation("ca.psiphon:psiphontunnel:2.0.42")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
