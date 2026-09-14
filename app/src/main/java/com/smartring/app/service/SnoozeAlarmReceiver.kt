package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SnoozeAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L); if (id < 0) return
        appLogger.log("SnoozeAlarmReceiver", "נודניק מהתראה עבור שעמור #$id")
        // finish() in a finally: an unfinished goAsync() result holds the receiver
        // alive until the system force-finishes it, and every call below — the DB
        // reads, the AlarmManager arm inside scheduleAt(), the history writes — can
        // throw.
        goAsync().also { p -> CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fail-open on a read error: don't strand the alarm ringing forever
                // because of a transient DB read failure.
                val alarm = runCatching { repository.getAlarm(id) }.getOrNull()
                if (alarm == null) { ctx.stopService(Intent(ctx, AlarmFiringService::class.java)); return@launch }
                // Shabbat-mode alarms accept no interaction at all, notification actions
                // included — checked, and the service left running, before anything else
                // happens; stopping the service here would itself be "acting on" the tap.
                if (!alarm.acceptsInteraction) return@launch
                ctx.stopService(Intent(ctx, AlarmFiringService::class.java))
                // Snooze disabled for this alarm (button shouldn't be showing at all, but
                // guard defensively): degrade to a plain stop rather than leaving the
                // service running — the user did press a button.
                if (!alarm.snoozeEnabled) {
                    repository.log(id, alarm.name, System.currentTimeMillis(), "STOPPED")
                    return@launch
                }
                // Derived from history rather than an in-memory counter: the
                // notification action can be tapped even after the app process (and
                // any in-app snooze counter) is gone, so snoozeMaxCount must be
                // enforced from persisted state.
                val alreadySnoozed = repository.snoozeCountSinceLastFire(id)
                if (alreadySnoozed < alarm.snoozeMaxCount) {
                    scheduler.scheduleAt(alarm, System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L)
                    repository.log(id, alarm.name, System.currentTimeMillis(), "SNOOZED")
                } else {
                    repository.log(id, alarm.name, System.currentTimeMillis(), "MISSED")
                }
            } catch (e: Exception) {
                // The alarm has already been silenced by this point, so the worst case
                // is a snooze that didn't get armed — which the user needs to be able to
                // find out about afterwards rather than discovering by oversleeping.
                appLogger.log("SnoozeAlarmReceiver", "נודניק מהתראה נכשל עבור שעמור #$id: ${e.message}")
            } finally {
                p.finish()
            }
        }}
    }
}
