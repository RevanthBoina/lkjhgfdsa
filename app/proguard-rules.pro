# Proguard rules for Aniob Android NL UI Automation Agent

# Domain models & Actions
-keep class com.aniob.core.domain.** { *; }
-keep class com.aniob.core.domain.AniobAction { *; }
-keep class com.aniob.core.domain.AniobAction$* { *; }
-keep class com.aniob.core.domain.TargetSpec { *; }
-keep class com.aniob.core.skills.** { *; }
-keep class com.aniob.core.tools.** { *; }

# Room Database persistence
-keep class androidx.room.** { *; }
-keep class com.aniob.app.db.** { *; }
-keepnames class * extends androidx.room.RoomDatabase

# Provider adapters, Retrofit, OkHttp, Moshi
-keep class com.aniob.app.provider.** { *; }
-keep class com.aniob.core.providers.** { *; }
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
    @com.squareup.moshi.JsonClass *;
}

# Allow R8 aggressive dead-code optimization for unreferenced code
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
}

# OkHttp optional platform providers
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
