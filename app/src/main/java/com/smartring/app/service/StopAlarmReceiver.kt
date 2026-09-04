package com.smartring.app.service
import android.content.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.receiver.AlarmReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class StopAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: AlarmRepository
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L); if (id < 0) return
        ctx.stopService(Intent(ctx, AlarmFiringService::class.java))
        goAsync().also { p -> CoroutineScope(Dispatchers.IO).launch {
            val a = repository.getAlarm(id); repository.log(id, a?.name ?: "", System.currentTimeMillis(), "STOPPED"); p.finish()
        }}
    }
}
