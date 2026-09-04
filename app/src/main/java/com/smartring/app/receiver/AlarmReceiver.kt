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
        ctx.startForegroundService(Intent(ctx, AlarmFiringService::class.java).putExtra(EXTRA_ALARM_ID, id))
    }
    companion object { const val EXTRA_ALARM_ID = "extra_alarm_id" }
}
