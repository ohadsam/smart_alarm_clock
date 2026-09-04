package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SnoozeAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L); if (id < 0) return
        ctx.stopService(Intent(ctx, AlarmFiringService::class.java))
        goAsync().also { p -> CoroutineScope(Dispatchers.IO).launch {
            repository.getAlarm(id)?.let { alarm ->
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
            }; p.finish()
        }}
    }
}
