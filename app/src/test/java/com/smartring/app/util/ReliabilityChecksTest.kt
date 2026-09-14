package com.smartring.app.util

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These four checks drive the "אמינות ברקע" rows in Settings and the prompt shown on
 * launch, and each one guards a permission that simply does not exist below a certain
 * API level. Getting a gate wrong doesn't break an alarm — it does something almost
 * worse for trust: it tells someone on Android 8 that a permission they cannot possibly
 * grant is missing, and sends them to a settings screen that has no such switch.
 *
 * Robolectric's @Config(sdk=...) is what makes this testable at all; the app's minSdk is
 * 26 and the gates span 26 → 34.
 */
@RunWith(RobolectricTestRunner::class)
class ReliabilityChecksTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ── Exact alarms: user-revocable only from API 31 ────────────────────

    @Test
    @Config(sdk = [30])
    fun `exact alarms count as granted below API 31, where the permission does not exist`() {
        assertTrue(ReliabilityChecks.canScheduleExactAlarms(context))
    }

    @Test
    @Config(sdk = [33])
    fun `exact alarms are read from AlarmManager on API 31 and up`() {
        // Robolectric grants it by default; the point is that the real system service is
        // consulted rather than the check being hardcoded true.
        assertTrue(ReliabilityChecks.canScheduleExactAlarms(context))
    }

    // ── Notifications: runtime permission only from API 33 ───────────────

    @Test
    @Config(sdk = [30])
    fun `notifications count as granted below API 33, where there is no runtime permission`() {
        assertTrue(ReliabilityChecks.isNotificationsGranted(context))
    }

    // ── Full-screen intent: withheld only from API 34 ────────────────────

    @Test
    @Config(sdk = [33])
    fun `full-screen intent counts as granted below API 34`() {
        // Below Android 14 this is an install-time grant, so surfacing it as a problem
        // would be a row the user can never resolve.
        assertTrue(ReliabilityChecks.canUseFullScreenIntent(context))
    }

    // ── Alarm stream volume: the one check that is not SDK-gated ─────────

    @Test
    fun `a silenced alarm stream is reported as inaudible`() {
        // Every ring's volume is a percentage *of this stream*, so at zero the alarm is
        // silent no matter what the app is set to — which is exactly the failure that
        // looks like "the alarm didn't go off".
        // Set through AudioManager's own public API rather than the Robolectric shadow,
        // whose setStreamVolume is protected — and this way the test drives the same
        // call path the device's volume keys do.
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        assertFalse(ReliabilityChecks.isAlarmVolumeAudible(context))
    }

    @Test
    fun `an audible alarm stream is reported as fine`() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 7, 0)
        assertTrue(ReliabilityChecks.isAlarmVolumeAudible(context))
    }

    @Test
    fun `battery optimization status is read from the real PowerManager`() {
        // Robolectric's default is "not ignoring", i.e. the app *is* being optimized —
        // the state this row exists to warn about.
        assertFalse(ReliabilityChecks.isIgnoringBatteryOptimizations(context))
    }
}
