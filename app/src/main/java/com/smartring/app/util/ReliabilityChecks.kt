package com.smartring.app.util
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Runtime status checks surfaced in Settings so the user can fix the OS-level
 * settings that most commonly stop an Android alarm clock from firing: exact-alarm
 * permission revoked, battery optimization killing the app in the background, or
 * notifications blocked (which can hide the alarm's own notification even though
 * the underlying foreground service keeps running). None of these can be granted
 * programmatically — only requested via a system settings screen/dialog.
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
}
