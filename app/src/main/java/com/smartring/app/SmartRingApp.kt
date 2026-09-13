package com.smartring.app
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.service.LogCleanupWorker
import com.smartring.app.service.WidgetRefreshWorker
import com.smartring.app.util.AlarmNotifications
import com.smartring.app.util.AppLogger
import com.smartring.app.util.WidgetRefresher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SmartRingApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var alarmRepository: AlarmRepository
    @Inject lateinit var widgetRefresher: WidgetRefresher
    @Inject lateinit var appLogger: AppLogger
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Created up front, in every process that hosts this Application (the alarm
        // receiver's included), so nothing can ever post to a channel that doesn't
        // exist yet — a notification to a missing channel is dropped silently on
        // API 26+, which would take the receiver's "couldn't start the service"
        // fallback notification down with it on a fresh install.
        AlarmNotifications.ensureChannel(this)
        // Runs daily (not every 3 days) so a log written right after one cleanup pass
        // is trimmed within ~1 day of crossing the 3-day retention line, not ~3 more.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LogCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        // Fallback refresh so the widgets' next-alarm countdown doesn't go stale even
        // without an alarm mutation; immediate refreshes also fire from the
        // observeAlarms() collector below (plus AlarmScheduler.scheduleAt/rescheduleAll
        // directly, for the cases that collector can't see). 15 minutes is
        // WorkManager's periodic-work minimum.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WidgetRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WidgetRefreshWorker.INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
        )
        // Belt-and-suspenders: refresh widgets on every change to the alarms table,
        // whatever code path caused it, rather than relying only on every mutation
        // site (AlarmScheduler.scheduleAt/rescheduleAll — the two that still refresh
        // explicitly, for cases a DB write alone wouldn't cover) remembering to call
        // WidgetRefresher itself — Room's Flow already re-emits on any write to a
        // table it queries, so this is a cheap, always-correct fallback. Meant to run
        // for the app's entire lifetime: a bare .catch{} would log one failure and
        // then let the flow complete, permanently ending this collector for the rest
        // of the process's life, so this retries indefinitely instead (with a short
        // backoff so a failure that recurs immediately doesn't spin in a tight loop).
        appScope.launch {
            while (true) {
                runCatching { alarmRepository.observeAlarms().collect { widgetRefresher.refresh() } }
                    .onFailure { e -> appLogger.log("SmartRingApp", "מעקב אחר שינויי שעמורים לעדכון ווידג'טים נכשל, ינסה שוב: ${e.message}") }
                delay(5_000)
            }
        }
    }
}
