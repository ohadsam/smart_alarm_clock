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

    /**
     * Last count reported, so a burst of refreshes does not fill the log with the same
     * line. A change in the count is the interesting event — especially a drop to zero.
     */
    private var lastReported: Int? = null

    fun refresh() {
        scope.launch {
            runCatching { refreshAllWidgets(context) }
                .onSuccess { count ->
                    // Logged, not silent. Until v1.12.0 this method logged only failures,
                    // which made a refresh that succeeded-but-updated-nothing look
                    // identical to one that worked — and "the widgets don't sync" reports
                    // could not be told apart from "the widgets are fine" in the log.
                    // Zero is the diagnosis: the refresh ran, and there was nothing
                    // registered for it to update.
                    if (count != lastReported) {
                        lastReported = count
                        appLogger.log(
                            "WidgetRefresher",
                            if (count == 0) "רענון ווידג'טים רץ אך לא נמצא אף ווידג'ט מוצב"
                            else "רענון ווידג'טים: $count ווידג'טים עודכנו",
                        )
                    }
                }
                .onFailure {
                    lastReported = null
                    appLogger.log("WidgetRefresher", "רענון ווידג'טים נכשל: ${it.message}")
                }
        }
    }
}
