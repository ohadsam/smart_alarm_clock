package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.assisted.*
import kotlinx.coroutines.CancellationException

@HiltWorker
class RescheduleWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val repository: AlarmRepository, private val scheduler: AlarmScheduler,
    private val appLogger: AppLogger,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        appLogger.log("RescheduleWorker", "עבודת רקע התחילה")
        // Retry rather than fail: this is the only thing that re-arms alarms after a
        // reboot, so giving up on a transient error (e.g. the database not yet
        // readable on a just-booted, still-locked device) would leave every alarm
        // silently unscheduled until the user happened to open the app again.
        return try {
            scheduler.rescheduleAll(repository.getActiveAlarms())
            appLogger.log("RescheduleWorker", "עבודת רקע הסתיימה")
            Result.success()
        } catch (e: CancellationException) {
            // Never turn "WorkManager stopped this worker" into a retry — rethrow so
            // the coroutine actually unwinds (the same rule AlarmFiringService follows
            // around its own suspend calls).
            throw e
        } catch (e: Exception) {
            appLogger.log("RescheduleWorker", "תזמון מחדש נכשל (${e.message}) — ינסה שוב")
            Result.retry()
        }
    }
    companion object { const val WORK_NAME = "reschedule_alarms" }
}
