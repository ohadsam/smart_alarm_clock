package com.smartring.app.util
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Runtime status checks surfaced in Settings (and proactively on app launch, see
 * ReliabilityGate in AlarmListScreen.kt) so the user can fix the OS-level settings
 * that most commonly stop an Android alarm clock from firing reliably: exact-alarm
 * permission revoked, battery optimization killing the app in the background, or
 * notifications blocked (which can hide the alarm's own notification even though
 * the underlying foreground service keeps running). None of these can be granted
 * programmatically — only requested via a system settings screen/dialog (or, for
 * notifications on API 33+, a runtime permission request).
 */
object ReliabilityChecks {
    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        else true

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    /**
     * Whether the alarm's full-screen notification may actually take over the screen.
     * Android 14 stopped granting USE_FULL_SCREEN_INTENT at install time to every app
     * that merely declares it; without it an alarm going off on a locked phone shows a
     * heads-up banner instead of the ring screen.
     */
    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
        else true

    /**
     * Whether the device's *alarm* stream is audible at all. Each ring's volume
     * percentage scales the player within that stream, so with the system alarm volume
     * at zero every alarm is silent regardless of what the app is set to. Surfaced to
     * the user rather than silently overridden: an alarm clock quietly rewriting the
     * device's volume behind their back (and possibly leaving it changed if the
     * process dies mid-ring) is worse than telling them.
     */
    fun isAlarmVolumeAudible(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return true
        return am.getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }
}
