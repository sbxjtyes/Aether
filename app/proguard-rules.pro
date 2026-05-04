# Aether — keep all app classes to prevent R8 stripping runtime dependencies
-keep class com.zhousl.aether.** { *; }
-keep class com.baimoqilin.aether.** { *; }

# com.rosan.app_process intentionally references hidden Android framework
# classes that are present on-device but unavailable to R8's android.jar.
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.LoadedApk
-keep class com.rosan.app_process.** { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# PostHog
-keep class com.posthog.** { *; }
-dontwarn com.posthog.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# JSoup / Flexmark / SnakeYAML
-keep class org.jsoup.** { *; }
-keep class com.vladsch.flexmark.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.vladsch.flexmark.**
-dontwarn org.yaml.snakeyaml.**

# AndroidX
-dontwarn androidx.compose.**
-dontwarn androidx.datastore.**
-dontwarn androidx.security.**

# Kotlin
-dontwarn kotlinx.coroutines.**

# Preserve annotations and signatures for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
