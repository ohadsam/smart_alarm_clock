-keep class com.smartring.app.data.db.** { *; }
-keep class com.smartring.app.domain.model.** { *; }
-keep class dagger.hilt.** { *; }
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers enum com.smartring.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# DataStore – prevent obfuscation of preference keys
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences { *; }

# Application class – needed for WorkManager Configuration.Provider
-keep class com.smartring.app.SmartRingApp { *; }

# Glance widget receivers – prevent stripping
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Hilt generated components
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltComponents { *; }
