package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.presentation.widget.refreshAllWidgets
import dagger.assisted.*
import kotlinx.coroutines.CancellationException

/**
 * Periodic fallback that keeps the home-screen widgets' live countdown fresh even
 * without an alarm mutation to trigger WidgetRefresher directly — enqueued as
 * periodic work from SmartRingApp.onCreate(). 15 minutes is WorkManager's minimum
 * periodic interval; the current-time clock itself stays live between runs via the
 * embedded TextClock, which doesn't depend on this worker at all.
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    // Mirrors RescheduleWorker: a transient failure (the Glance session losing a race
    // with a widget being removed, a DB read failing) becomes a retry rather than a
    // hard failure, and CancellationException is re-thrown rather than swallowed —
    // catching it would tell WorkManager the run succeeded while the coroutine was in
    // fact being torn down.
    override suspend fun doWork(): Result = try {
        refreshAllWidgets(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }
    companion object {
        const val WORK_NAME = "widget_refresh"
        const val INTERVAL_MINUTES = 15L
    }
}
