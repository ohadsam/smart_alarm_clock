package com.smartring.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps the app's real [SmartRingApp] for Hilt's test Application in instrumented
 * tests, so `@HiltAndroidTest` classes get a real, injectable DI graph without
 * SmartRingApp.onCreate()'s production side effects (the periodic WorkManager
 * enqueues and the forever-running observeAlarms() widget-refresh collector)
 * running underneath every test.
 *
 * Wired in via `testInstrumentationRunner` in app/build.gradle.kts.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
