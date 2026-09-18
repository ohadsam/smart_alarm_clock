package com.smartring.app.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartring.app.service.AlarmFiringService
import com.smartring.app.util.AlarmHandoffWakeLock
import com.smartring.app.util.AlarmNotifications
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L); if (id < 0) return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
        val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)
        // Start the service first and log second: onReceive has a short, hard budget
        // to get the foreground service going, and AppLogger's write is fire-and-forget
        // anyway, so it has no business sitting in front of the one time-critical call
        // in this receiver.
        // Taken before the service start and handed to AlarmFiringService, which
        // releases it once its own lock is held: the platform's alarm wake lock ends
        // with onReceive, and startForegroundService() is asynchronous, so without
        // this the device can go back to sleep in between and the alarm rings late or
        // not at all. See AlarmHandoffWakeLock.
        AlarmHandoffWakeLock.acquire(ctx)
        try {
            ctx.startForegroundService(Intent(ctx, AlarmFiringService::class.java)
                .putExtra(EXTRA_ALARM_ID, id)
                .putExtra(EXTRA_IS_SNOOZE, isSnooze)
                .putExtra(EXTRA_IS_TEST, isTest))
            appLogger.log("AlarmReceiver", "אזעקה התקבלה עבור שעמור #$id" +
                when { isTest -> " (בדיקה)"; isSnooze -> " (נודניק)"; else -> "" })
        } catch (e: Exception) {
            // Nothing is coming to hand the lock off to.
            AlarmHandoffWakeLock.release()
            // Reachable in practice: an app the user (or the OEM) has put in the
            // "restricted" battery state can receive its alarm broadcast and still be
            // refused a foreground service start, which would otherwise throw straight
            // out of onReceive and crash the process without ringing anything.
            AlarmNotifications.postFallback(ctx, id)
            appLogger.log("AlarmReceiver",
                "הפעלת שירות הצלצול נכשלה עבור שעמור #$id (${e.javaClass.simpleName}: ${e.message}) — הוצגה התראה חלופית")
        }
    }
    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"

        /**
         * Marks a rehearsal rather than a real alarm.
         *
         * A test ring goes through the identical path — AlarmManager, this receiver, the
         * wake-lock hand-off, the foreground service, the real volume and vibration, the
         * ring screen — because a "test" that skips any of it proves nothing about the
         * part that actually fails at 06:30. What it must *not* do is the bookkeeping:
         * counting an occurrence, switching a one-time alarm off, writing a FIRED row, or
         * arming the next occurrence. Those belong to real rings only.
         */
        const val EXTRA_IS_TEST = "extra_is_test"
    }
}
