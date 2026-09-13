package com.smartring.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.smartring.app.R
import com.smartring.app.receiver.AlarmReceiver

/**
 * The alarm notification channel, plus the last-resort notification used when the
 * firing service itself can't be started.
 *
 * Lives here rather than inside AlarmFiringService because both the service and
 * AlarmReceiver's fallback path need it, and because the channel must exist before
 * anything tries to notify on it — a notification posted to a channel that was never
 * created is dropped silently on API 26+.
 */
object AlarmNotifications {
    const val CHANNEL_ID = "smartring_alarm_channel_v2"

    /** The pre-v1.5.0 channel, created with the system default notification sound and
     *  vibration. Channel settings are immutable after creation, so silencing it in
     *  place is impossible — [CHANNEL_ID] replaces it and this one is deleted. */
    private const val LEGACY_CHANNEL_ID = "smartring_alarm_channel"

    const val FALLBACK_NOTIF_ID = 1002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SmartRing Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // The alarm's own sound and vibration are driven by AlarmFiringService
                // (a MediaPlayer on the alarm stream plus the configured vibration
                // pattern). Leaving the channel's defaults on played the system
                // notification sound over the user's chosen ringtone and added an
                // unconfigured buzz on top of the alarm's own pattern.
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Posted when [android.content.Context.startForegroundService] is refused — on a
     * device where the app has been put in the "restricted" battery state, an alarm
     * broadcast can arrive and still not be allowed to start a foreground service. A
     * full-screen notification at least wakes the screen and tells the user the alarm
     * is due, instead of the alarm silently doing nothing at all.
     */
    fun postFallback(context: Context, alarmId: Long) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, alarmId.toInt(),
            (context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("השעמור אמור לצלצל עכשיו")
            .setContentText("לא ניתן היה להפעיל את הצלצול ברקע. פתח את האפליקציה.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(FALLBACK_NOTIF_ID, notification)
        }
    }
}
