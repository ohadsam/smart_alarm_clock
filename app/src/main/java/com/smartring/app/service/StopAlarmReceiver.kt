package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class StopAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L); if (id < 0) return
        appLogger.log("StopAlarmReceiver", "עצירה מהתראה עבור שעמור #$id")
        goAsync().also { p -> CoroutineScope(Dispatchers.IO).launch {
            // Fail-open on a read error (null): a DB hiccup shouldn't turn a tap on
            // Stop into a no-op, and Shabbat mode is the minority case anyway.
            val a = runCatching { repository.getAlarm(id) }.getOrNull()
            // Shabbat-mode alarms accept no interaction, notification actions
            // included; buildNotification() already omits this action for them, but
            // guard here too in case a stale PendingIntent still exists. Checked
            // before stopService() runs, not after — an already-stopped service
            // can't be un-stopped if this came back true.
            if (a?.acceptsInteraction == false) { p.finish(); return@launch }
            ctx.stopService(Intent(ctx, AlarmFiringService::class.java))
            repository.log(id, a?.name ?: "", System.currentTimeMillis(), "STOPPED")
            p.finish()
        }}
    }
}
