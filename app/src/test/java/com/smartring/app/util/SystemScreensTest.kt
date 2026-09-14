package com.smartring.app.util

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Every settings screen the reliability section links to is optional on some build of
 * Android — the exact-alarm and full-screen-intent pages only exist from API 31 and
 * 34, and ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is missing outright on some
 * AOSP, Go and OEM images. `startActivity` with an intent nothing resolves throws, so
 * before this each of those rows was one tap away from closing the app — from inside
 * the section whose whole purpose is making the app more dependable.
 *
 * `checkActivities(true)` is what makes Robolectric enforce that the way a device
 * does; without it every intent "resolves" and this class of bug is invisible in tests.
 */
@RunWith(RobolectricTestRunner::class)
class SystemScreensTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `starting from a non-Activity context does not throw`() {
        // The Application context is what this test class holds, and it is what caught
        // the hole the helper shipped with: only an Activity may start another one
        // without FLAG_ACTIVITY_NEW_TASK, and Android signals that with
        // AndroidRuntimeException — a different type from ActivityNotFoundException,
        // which sailed straight past the original narrower catch. Every caller today is
        // a Compose screen holding the Activity context, so nothing in the app hits
        // this; the point is that the next caller that isn't cannot reintroduce the
        // crash this whole file exists to prevent.
        assertTrue(openSystemScreen(app, Intent(Settings.ACTION_DATE_SETTINGS)))
    }

    @Test
    fun `a screen that exists is opened directly, with no detour`() {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS)

        assertTrue(openSystemScreen(app, intent))
        assertEquals(
            "the requested screen must be the one opened, not the fallback",
            Settings.ACTION_SOUND_SETTINGS,
            shadowOf(app).nextStartedActivity?.action,
        )
    }

    @Test
    fun `a screen the device does not have reports failure instead of throwing`() {
        // The assertion that matters is that this line returns at all: it used to be an
        // uncaught ActivityNotFoundException on the way out of a click handler.
        shadowOf(app).checkActivities(true)

        assertFalse(openSystemScreen(app, Intent("com.example.NO_SUCH_SETTINGS_SCREEN")))
    }

    @Test
    fun `failure is reported rather than silently swallowed`() {
        // The caller shows a "no such screen on this device" line on false. Returning
        // true on failure would leave the user staring at a button that does nothing.
        shadowOf(app).checkActivities(true)

        assertFalse(openSystemScreen(app, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)))
        assertFalse(openSystemScreen(app, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)))
        assertFalse(openSystemScreen(app, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)))
    }
}
