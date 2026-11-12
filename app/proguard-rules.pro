# ===== OmniBrowser: правила R8 =====

# Методы, вызываемые из JavaScript (мосты расширений Chrome, загрузок blob:), должны сохранить имена
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Предупреждения о необязательных TLS-провайдерах
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Psiphon (gomobile JNI)
-keep class go.** { *; }
-keep class psi.** { *; }
-keep class ca.psiphon.** { *; }

# WireGuard (v4.66.0): сервис библиотеки объявлен только в манифесте, поэтому R8 его не видит
-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
-dontwarn com.wireguard.**
