package com.smartring.app.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartring.app.service.AlarmFiringService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L); if (id < 0) return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
        ctx.startForegroundService(Intent(ctx, AlarmFiringService::class.java)
            .putExtra(EXTRA_ALARM_ID, id)
            .putExtra(EXTRA_IS_SNOOZE, isSnooze))
    }
    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"
    }
}
