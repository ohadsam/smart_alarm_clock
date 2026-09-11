package com.smartring.app.service
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.smartring.app.presentation.widget.refreshAllWidgets
import dagger.assisted.*

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
    override suspend fun doWork(): Result {
        refreshAllWidgets(applicationContext)
        return Result.success()
    }
    companion object {
        const val WORK_NAME = "widget_refresh"
        const val INTERVAL_MINUTES = 15L
    }
}
