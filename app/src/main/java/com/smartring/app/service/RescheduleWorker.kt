package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.util.AlarmScheduler
import dagger.assisted.*

@HiltWorker
class RescheduleWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val repository: AlarmRepository, private val scheduler: AlarmScheduler,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        scheduler.rescheduleAll(repository.getActiveAlarms()); return Result.success()
    }
    companion object { const val WORK_NAME = "reschedule_alarms" }
}
