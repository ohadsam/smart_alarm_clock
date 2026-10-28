package com.smartring.app.sync.platform

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * The slow safety net: every fifteen minutes, deliver anything still waiting for a phone that was
 * out of range when it was edited. The prompt path is [SyncCoordinator]'s own watch on changes;
 * this exists for the case where the app's process was not alive to run it.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
) : CoroutineWorker(ctx, params) {

    // Never Result.retry(): a phone that is simply not nearby is the normal case, and WorkManager's
    // retry back-off would only make the next real attempt later. The next period is the retry.
    override suspend fun doWork(): Result = try {
        coordinator.syncPending()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.success()
    }

    companion object {
        const val WORK_NAME = "device_sync"
        const val INTERVAL_MINUTES = 15L
    }
}
