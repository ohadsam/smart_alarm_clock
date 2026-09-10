package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.data.repository.AlarmRepository
import dagger.assisted.*
import java.util.concurrent.TimeUnit

/** Trims app_logs (the technical/diagnostic log, Settings -> Logs) to the last 3
 *  days — enqueued as periodic work from SmartRingApp.onCreate(). */
@HiltWorker
class LogCleanupWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val repository: AlarmRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        repository.deleteAppLogsOlderThan(cutoff)
        return Result.success()
    }
    companion object {
        const val WORK_NAME = "log_cleanup"
        val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(3)
    }
}
