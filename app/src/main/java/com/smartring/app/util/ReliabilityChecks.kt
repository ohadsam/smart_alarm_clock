package com.smartring.app.util
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
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
}
