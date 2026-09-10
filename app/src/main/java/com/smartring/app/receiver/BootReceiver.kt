package com.smartring.app.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.smartring.app.service.RescheduleWorker
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var appLogger: AppLogger
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        appLogger.log("BootReceiver", "אתחול מכשיר/עדכון אפליקציה זוהה (${intent.action}) — מתזמן מחדש שעמורים")
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            RescheduleWorker.WORK_NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RescheduleWorker>().build())
    }
}
