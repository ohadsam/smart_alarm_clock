package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import com.smartring.app.util.SnoozeDecision
import com.smartring.app.util.snoozeDecision
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * "נודניק" on the alarm notification. The rules it follows — Shabbat mode accepting
 * nothing, snooze-disabled and cap-reached both degrading to a stop rather than
 * leaving the phone ringing — live in the pure [snoozeDecision] so they can be tested
 * directly; this class only carries them out.
 */
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
                // Both reads fail open. A transient database error must not strand the
                // alarm ringing (a null alarm still silences it), and must not block a
                // snooze the user is entitled to (an unreadable count reads as 0, which
                // allows the snooze rather than refusing it).
                val alarm = runCatching { repository.getAlarm(id) }.getOrNull()
                val alreadySnoozed =
                    if (alarm?.snoozeEnabled == true)
                        runCatching { repository.snoozeCountSinceLastFire(id) }.getOrDefault(0)
                    else 0
                val stopService = { ctx.stopService(Intent(ctx, AlarmFiringService::class.java)) }
                when (val decision = snoozeDecision(alarm, alreadySnoozed)) {
                    // Shabbat mode: the service is deliberately left running. Stopping it
                    // here would itself be acting on a tap this alarm accepts none of.
                    SnoozeDecision.Ignore      -> Unit
                    SnoozeDecision.SilenceOnly -> stopService()
                    is SnoozeDecision.StopInstead -> {
                        stopService()
                        repository.log(id, alarm?.name.orEmpty(), System.currentTimeMillis(), decision.action)
                    }
                    is SnoozeDecision.Snooze -> {
                        stopService()
                        // Non-null by construction: snoozeDecision only returns Snooze
                        // for an alarm it was given.
                        alarm?.let {
                            scheduler.scheduleAt(it, System.currentTimeMillis() + decision.minutes * 60_000L)
                            repository.log(id, it.name, System.currentTimeMillis(), "SNOOZED")
                        }
                    }
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
