package com.smartring.app.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.smartring.app.service.RescheduleWorker
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms every alarm whenever the OS has thrown away what AlarmManager was holding,
 * or whenever the wall clock those alarms were computed against has moved:
 *
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED: every scheduled alarm is gone.
 * - TIME_SET / TIMEZONE_CHANGED: the alarms are still armed, but at absolute epoch
 *   timestamps derived from the *old* local time — after flying a few timezones over,
 *   a 07:00 alarm would otherwise still fire at 07:00 in the timezone it was set in.
 * - SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED: revoking the exact-alarm
 *   permission makes the system cancel every exact alarm the app has scheduled, and
 *   granting it back does not bring them back. Without this the app's own reliability
 *   prompt was a trap: it sends the user to the exact-alarm settings screen, and they
 *   return to a list of alarms that all look enabled and none of which is armed.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        appLogger.log("BootReceiver", "אתחול/עדכון/שינוי שעון זוהה (${intent.action}) — מתזמן מחדש שעמורים")
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            RescheduleWorker.WORK_NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RescheduleWorker>()
                .apply {
                    // Expedited: the ordinary queue can take minutes to get to this, and
                    // every alarm is unarmed until it runs — an alarm due shortly after a
                    // reboot would simply never ring. Falls back to a normal request when
                    // the app is out of expedited quota.
                    //
                    // API 31+ only: below that WorkManager runs expedited work as a
                    // foreground service and calls getForegroundInfo() on the worker,
                    // whose default implementation throws. Re-arming alarms isn't worth a
                    // notification on every reboot, and the ordinary queue picks up
                    // one-time work promptly enough on those releases.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build())
    }

    companion object {
        /**
         * Every broadcast that means "what AlarmManager was holding is gone or wrong".
         * Internal rather than private so BootReceiverActionsTest can check it against
         * the manifest's own intent-filter: adding an action to one side and not the
         * other is silent — the receiver simply never runs for it, which is exactly how
         * the exact-alarm permission case went unnoticed.
         */
        internal val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            // spelled out rather than referenced so this file carries no API-31 symbol
            // (the broadcast simply never arrives on older releases, where the
            // permission it reports on doesn't exist). Must match the manifest.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
