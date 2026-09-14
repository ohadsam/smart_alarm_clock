package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.data.repository.AlarmRepository
import dagger.assisted.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Trims app_logs (the technical/diagnostic log, Settings -> Logs) to the last 3
 *  days — enqueued as periodic work from SmartRingApp.onCreate(). */
@HiltWorker
class LogCleanupWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val repository: AlarmRepository,
) : CoroutineWorker(ctx, params) {
    // Retry rather than fail on a transient DB error, and re-throw cancellation instead
    // of swallowing it — the same rule RescheduleWorker and WidgetRefreshWorker follow.
    override suspend fun doWork(): Result = try {
        repository.deleteAppLogsOlderThan(System.currentTimeMillis() - RETENTION_MILLIS)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }
    companion object {
        const val WORK_NAME = "log_cleanup"
        val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(3)
    }
}
