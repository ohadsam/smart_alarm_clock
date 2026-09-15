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

# ── Things Hilt resolves by *name* at runtime ────────────────────────────────
# All three of these are looked up through a string or a Class at runtime, so
# obfuscating them makes the lookup miss — and the failure is invisible to every test
# here except the release smoke test, because the debug build isn't minified.

# @HiltViewModel generates a multibinding keyed by the ViewModel's fully-qualified
# class-name string, and hiltViewModel() resolves it with modelClass.getName(). Rename
# the class and the two no longer agree: the factory throws during composition, which
# is the crash scripts/release-smoke-test.sh caught on its first working run.
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }

# @EntryPoint interfaces are fetched by Class — EntryPointAccessors.fromApplication(
# ctx, WidgetEntryPoint::class.java) in SmartRingWidget, which is how every widget
# reaches the repository and the scheduler.
-keep @dagger.hilt.EntryPoint interface * { *; }

# @HiltWorker's generated assisted factories, reached through Dagger's map from worker
# class name to factory. Without them WorkManager can build no worker at all, so the
# boot reschedule, the log cleanup and the widget refresh all stop silently.
-keep @androidx.hilt.work.HiltWorker class * { <init>(...); }

# ── The Compose ↔ Lifecycle CompositionLocal bridge ──────────────────────────
# lifecycle-runtime-compose 2.8.x declares its own androidx.lifecycle.compose.
# LocalLifecycleOwner, while compose-ui 1.6.8 (BOM 2024.06.00) only ever provides
# androidx.compose.ui.platform.LocalLifecycleOwner. 2.8.x bridges the two by reaching
# for the compose-ui one *by name* — the top-level val compiles into the facade class
# AndroidCompositionLocals_AndroidKt, so R8 renaming it makes the bridge miss and the
# lifecycle local falls through to its default, which throws
#   java.lang.IllegalStateException: CompositionLocal LocalLifecycleOwner not present
# on the very first composition. Every screen in this app reads it through
# collectAsStateWithLifecycle(), so the app dies at launch.
#
# This is invisible to every other check here: the JVM suite never runs R8, and the
# instrumented suite installs the *debug* APK, which isn't minified — so the emulator
# happily renders MainActivity while the shipped APK cannot start at all. Only
# scripts/release-smoke-test.sh, which launches the real release build, sees it.
-keep class androidx.compose.ui.platform.** { *; }
-keep class androidx.lifecycle.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * implements androidx.lifecycle.LifecycleOwner { *; }
