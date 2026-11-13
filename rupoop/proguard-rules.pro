# v4.87.0: правила модуля Rupoop для общего R8 нашего APK (consumer rules).
-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault, *Annotation*, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# модели, парсеры, сеть и сервисы Rupoop
-keep class com.santiago43rus.rupoop.data.** { *; }
-keep class com.santiago43rus.rupoop.parser.** { *; }
-keep class com.santiago43rus.rupoop.network.** { *; }
-keep class com.santiago43rus.rupoop.service.** { *; }

# kotlinx.serialization
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
-keepclasseswithmembers class * { *** Companion; }

# Retrofit (иначе вызовы API превращаются в 404 из-за переименования параметров)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.conscrypt.**
