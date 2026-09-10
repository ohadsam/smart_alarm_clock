package com.smartring.app
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.smartring.app.service.LogCleanupWorker
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
    }
}
