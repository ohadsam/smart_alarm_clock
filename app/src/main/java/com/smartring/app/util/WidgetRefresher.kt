package com.smartring.app.util

import android.content.Context
import com.smartring.app.presentation.widget.refreshAllWidgets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget widget refresh, with a reason attached.
 *
 * Called directly from `AlarmScheduler.scheduleAt()` (snoozing) and `rescheduleAll()`
 * (boot reschedule) — the two cases that change what the widgets should show without a
 * corresponding alarms-table write for `SmartRingApp`'s `observeAlarms()` collector to
 * react to. Every other alarm mutation relies on that collector instead. Reads live DB
 * state at execution time, so it does not matter that this fires before the caller's own
 * write has necessarily committed.
 *
 * ## Why every call is logged now
 *
 * v1.12.0 logged only when the *count* of placed widgets changed, reasoning that a burst
 * of identical lines would bury the log. The reasoning was wrong about where the noise
 * comes from: the 15-minute periodic refresh does not go through this class at all
 * ([com.smartring.app.service.WidgetRefreshWorker] calls `refreshAllWidgets` directly and
 * logs nothing), so everything here is user-triggered and therefore bounded. What the
 * dedup actually suppressed was six consecutive alarm mutations in the one report that
 * needed them — the log went quiet for five minutes across a delete, a create, an edit
 * and another delete, and "the refresh ran four times" was indistinguishable from "the
 * refresh never ran".
 *
 * The reason string is what makes the line worth reading: *which* change triggered it.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(reason: String) {
        scope.launch {
            runCatching { refreshAllWidgets(context) }
                .onSuccess { report ->
                    val line = when {
                        // The diagnosis, in one line: the refresh ran and there was
                        // nothing registered for it to update.
                        report.found == 0 -> "רענון ($reason) רץ אך לא נמצא אף ווידג'ט מוצב"
                        // Honest about what is known. `found` is how many widgets are
                        // placed; `updated` is how many update calls came back without
                        // throwing. v1.12.0 reported `found` as "widgets updated", which
                        // counted a size whose update threw exactly like one that rendered.
                        report.failed ->
                            "רענון ($reason): ${report.found} מוצבים, ${report.updated} עודכנו, " +
                                "שגיאות: ${report.errors.joinToString("; ")}"
                        else -> "רענון ($reason): ${report.found} מוצבים, ${report.updated} עודכנו"
                    }
                    appLogger.log("WidgetRefresher", line)
                }
                .onFailure {
                    appLogger.log("WidgetRefresher", "רענון ($reason) נכשל: ${it.message}")
                }
        }
    }
}
