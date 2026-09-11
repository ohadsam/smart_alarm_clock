package com.smartring.app
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.smartring.app.service.LogCleanupWorker
import com.smartring.app.service.WidgetRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SmartRingApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Runs daily (not every 3 days) so a log written right after one cleanup pass
        // is trimmed within ~1 day of crossing the 3-day retention line, not ~3 more.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LogCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        // Fallback refresh so the widgets' next-alarm countdown doesn't go stale even
        // without an alarm mutation; immediate refreshes also fire from WidgetRefresher
        // on every schedule/cancel. 15 minutes is WorkManager's periodic-work minimum.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WidgetRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WidgetRefreshWorker.INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
        )
    }
}
