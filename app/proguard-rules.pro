# ===== OmniBrowser: правила R8 =====

# Методы, вызываемые из JavaScript (мосты расширений Chrome, загрузок blob:), должны сохранить имена
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Предупреждения о необязательных TLS-провайдерах + сохранение JNI-классов Conscrypt (Android 5–9 TLS 1.3)
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Psiphon (gomobile JNI)
-keep class go.** { *; }
-keep class psi.** { *; }
-keep class ca.psiphon.** { *; }
