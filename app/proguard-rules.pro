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
