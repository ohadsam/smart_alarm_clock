package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AppLogger
import com.smartring.app.util.StopDecision
import com.smartring.app.util.stopDecision
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/** "עצור" on the alarm notification. The Shabbat-mode rule lives in the pure
 *  [stopDecision]; this class only carries it out. */
@AndroidEntryPoint
class StopAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L); if (id < 0) return
        appLogger.log("StopAlarmReceiver", "עצירה מהתראה עבור שעמור #$id")
        // finish() in a finally, not at each exit: a BroadcastReceiver whose
        // goAsync() result is never finished holds the receiver alive until the
        // system force-finishes it and logs an error, and one throw below (a DB
        // write failing after the service is already stopped) is all it takes.
        goAsync().also { p -> CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fail-open on a read error (null): a DB hiccup shouldn't turn a tap on
                // Stop into a no-op, and Shabbat mode is the minority case anyway.
                val alarm = runCatching { repository.getAlarm(id) }.getOrNull()
                // buildNotification() already omits this action for a Shabbat alarm, but
                // guard here too in case a stale PendingIntent still exists. Checked
                // before stopService() runs, not after — an already-stopped service
                // can't be un-stopped.
                if (stopDecision(alarm) == StopDecision.IGNORE) return@launch
                ctx.stopService(Intent(ctx, AlarmFiringService::class.java))
                // The alarm is silenced either way; a failed history write must not
                // become an unfinished broadcast on top of it.
                runCatching { repository.log(id, alarm?.name ?: "", System.currentTimeMillis(), "STOPPED") }
                    .onFailure { appLogger.log("StopAlarmReceiver", "כתיבת יומן 'נעצר' נכשלה: ${it.message}") }
            } finally {
                p.finish()
            }
        }}
    }
}
