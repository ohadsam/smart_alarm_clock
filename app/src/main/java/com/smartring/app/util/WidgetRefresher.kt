package com.smartring.app.util

import android.content.Context
import com.smartring.app.presentation.widget.refreshAllWidgets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget widget refresh. Called directly from AlarmScheduler.scheduleAt()
 * (snoozing) and rescheduleAll() (boot reschedule) — the two cases that change what
 * the widgets should show without a corresponding alarms-table write for
 * SmartRingApp's observeAlarms()-based fallback collector to react to on its own.
 * Every other alarm mutation (schedule/cancel/cancelAll) relies on that fallback
 * instead of calling this directly. Reads live DB state at execution time, so it
 * doesn't matter that this fires before the caller's own DB write has necessarily
 * committed.
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
