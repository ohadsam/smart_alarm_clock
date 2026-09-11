package com.smartring.app.util

import android.content.Context
import com.smartring.app.presentation.widget.refreshAllWidgets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget widget refresh, called from every place that changes what the
 * home-screen widgets should show (AlarmScheduler.schedule/cancel/rescheduleAll —
 * the single choke point every alarm mutation already flows through). Reads live DB
 * state at execution time, so it doesn't matter that this fires before the caller's
 * own DB write has necessarily committed.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh() {
        scope.launch {
            runCatching { refreshAllWidgets(context) }
                .onFailure { appLogger.log("WidgetRefresher", "רענון ווידג'טים נכשל: ${it.message}") }
        }
    }
}
