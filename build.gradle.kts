plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // v4.87.0: нужен модулю :rupoop (Rupoop использует kotlinx.serialization)
    alias(libs.plugins.kotlin.serialization) apply false
}
