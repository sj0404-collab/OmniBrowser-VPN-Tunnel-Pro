plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // v4.87.0: нужен модулю :rupoop (Rupoop использует kotlinx.serialization)
    alias(libs.plugins.kotlin.serialization) apply false
}

// v4.87.0: lintVital для release падает на модуле :rupoop (баг lint в AGP 8.7 при анализе их кода).
// Линт не влияет на работу приложения, поэтому отключаем только release-вариант, обычный lint остаётся.
subprojects {
    tasks.configureEach {
        if (name.startsWith("lintVital")) enabled = false
    }
}
