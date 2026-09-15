package com.smartring.app.build

import com.smartring.app.BuildConfig
import com.smartring.app.presentation.whatsnew.WHATS_NEW_HISTORY
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two things about this app that no other test on this machine can see.
 *
 * R8 only runs for the release build, and the instrumented suite installs the *debug*
 * APK — so a deleted keep rule is invisible to all 253 JVM tests, to both APK builds,
 * and to the emulator suite. It shows up exactly once: as a crash on first launch for
 * whoever installs the release APK. That happened, twice, in v1.6.8 and v1.6.9.
 *
 * `scripts/release-smoke-test.sh` is the real check — it launches the minified build on
 * an emulator — but it costs an emulator boot and only runs in CI. This test is the
 * cheap tripwire that fails in seconds if one of the load-bearing rules is dropped
 * while tidying the file, and it names the crash the rule prevents so the next person
 * doesn't have to rediscover it from an obfuscated stack trace.
 */
class ProguardRulesTest {

    private val rules: String by lazy {
        // Gradle runs unit tests with the module directory as the working directory,
        // but IDE runners sometimes use the project root — accept either.
        val candidates = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("proguard-rules.pro not found from ${File(".").absolutePath}")
        file.readText()
    }

    private fun requireRule(rule: String, whyItMatters: String) =
        assertTrue(
            "Missing keep rule `$rule` in proguard-rules.pro — $whyItMatters",
            rules.lineSequence().any { it.trim() == rule },
        )

    @Test
    fun `the lifecycle CompositionLocal bridge stays unobfuscated`() {
        // lifecycle-runtime-compose 2.8.x reaches for compose-ui's LocalLifecycleOwner
        // by name; rename the facade class and every screen's collectAsStateWithLifecycle()
        // throws "CompositionLocal LocalLifecycleOwner not present" in the first frame.
        requireRule(
            "-keep class androidx.compose.ui.platform.** { *; }",
            "the release APK cannot draw a first frame without it",
        )
        requireRule(
            "-keep class androidx.lifecycle.** { *; }",
            "the release APK cannot draw a first frame without it",
        )
    }

    @Test
    fun `everything Hilt resolves by name stays unobfuscated`() {
        requireRule(
            "-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }",
            "hiltViewModel() looks the ViewModel up by its class-name string",
        )
        requireRule(
            "-keep @dagger.hilt.EntryPoint interface * { *; }",
            "every widget reaches the repository through EntryPointAccessors",
        )
        requireRule(
            "-keep @androidx.hilt.work.HiltWorker class * { <init>(...); }",
            "WorkManager builds no worker without it: boot reschedule, log cleanup and widget refresh all stop",
        )
    }

    @Test
    fun `the room entities and domain models stay unobfuscated`() {
        requireRule(
            "-keep class com.smartring.app.data.db.** { *; }",
            "Room's generated code and the on-disk schema are matched by name",
        )
        requireRule(
            "-keep class com.smartring.app.domain.model.** { *; }",
            "these cross process boundaries into the widgets",
        )
    }

    /**
     * WHATS_NEW_HISTORY is hand-maintained alongside the version bump in
     * app/build.gradle.kts, and nothing else connects the two. An entry dated *ahead* of
     * the installed build would either never appear or, after the next bump, appear
     * together with that version's own notes.
     */
    @Test
    fun `no whats-new entry is newer than the build it ships in`() {
        val newest = WHATS_NEW_HISTORY.maxOf { it.versionCode }
        assertTrue(
            "WHATS_NEW_HISTORY has an entry for versionCode $newest but this build is " +
                "${BuildConfig.VERSION_CODE} — bump versionCode in app/build.gradle.kts",
            newest <= BuildConfig.VERSION_CODE,
        )
        assertTrue(
            "every What's New entry needs a versionName",
            WHATS_NEW_HISTORY.all { it.versionName.isNotBlank() },
        )
    }
}
