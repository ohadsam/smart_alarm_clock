package com.smartring.app.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartring.app.service.AlarmFiringService
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
        // Start the service first and log second: onReceive has a short, hard budget
        // to get the foreground service going, and AppLogger's write is fire-and-forget
        // anyway, so it has no business sitting in front of the one time-critical call
        // in this receiver.
        try {
            ctx.startForegroundService(Intent(ctx, AlarmFiringService::class.java)
                .putExtra(EXTRA_ALARM_ID, id)
                .putExtra(EXTRA_IS_SNOOZE, isSnooze))
            appLogger.log("AlarmReceiver", "אזעקה התקבלה עבור שעמור #$id" + if (isSnooze) " (נודניק)" else "")
        } catch (e: Exception) {
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
    }
}
