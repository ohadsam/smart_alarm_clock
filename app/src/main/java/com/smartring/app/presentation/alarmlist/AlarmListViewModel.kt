package com.smartring.app.presentation.alarmlist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import com.smartring.app.util.formatDayAndTime
import com.smartring.app.util.nextOccasionalDate
import java.util.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmListUiState(val alarms: List<Alarm> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val appLogger: AppLogger,
) : ViewModel() {
    val uiState = repository.observeAlarms()
        .map { AlarmListUiState(it, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmListUiState())

    fun toggle(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(alarm.id, enabled)
        if (enabled) scheduler.schedule(alarm.copy(isEnabled = true)) else scheduler.cancel(alarm.id)
        appLogger.log("AlarmList", "\"${alarm.name}\" (#${alarm.id}) ${if (enabled) "הופעל" else "כובה"}")
    }
    fun delete(alarm: Alarm) = viewModelScope.launch {
        repository.deleteAlarm(alarm.id); scheduler.cancel(alarm.id)
        appLogger.log("AlarmList", "נמחק: \"${alarm.name}\" (#${alarm.id})")
    }
    /**
     * Re-arms an ad-hoc alarm for the next day, in one tap.
     *
     * This is the whole point of the "מזדמן" alarm: set it once for today, and when you
     * need it again, move it on a day without reopening the editor. Pressing it twice
     * gets the day after tomorrow, because [nextOccasionalDate] steps from the alarm's
     * own date rather than from today.
     *
     * Enabling and clearing the occurrence counter are both required, not tidiness: an
     * ad-hoc alarm has switched itself off after ringing, and re-dating it without
     * re-enabling would store a perfectly correct date on an alarm that cannot ring —
     * the exact failure v1.7.0 was mostly about.
     */
    fun scheduleForNextDay(alarm: Alarm) = viewModelScope.launch {
        // Re-read rather than trusting the list's copy. saveAlarm() replaces an alarm's
        // rings and extra dates wholesale, so saving a partially-populated Alarm would
        // delete them. The list flow happens to be complete today (observeAllAlarms is
        // @Transaction and returns AlarmWithDetails), but a save path should not depend
        // on a projection elsewhere staying complete.
        val full = repository.getAlarm(alarm.id) ?: return@launch
        val next = nextOccasionalDate(full.specificDateTime, full.hour, full.minute)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        val updated = full.copy(
            specificDateTime = next,
            // hour/minute follow the stored datetime because everything outside the
            // scheduler (the card, the widgets, the notification) reads those fields.
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            isEnabled = true,
            occurrencesFired = 0,
        )
        repository.saveAlarm(updated)
        scheduler.schedule(updated)
        appLogger.log("AlarmList",
            "\"${alarm.name}\" (#${alarm.id}) תוזמן מחדש ל-${formatDayAndTime(next)}")
    }

    fun disableAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.disableAll()
        scheduler.cancelAll(alarms.map { it.id })
        appLogger.log("AlarmList", "כל השעמורים כובו (${alarms.size})")
    }
    fun freezeAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.freezeAll()
        scheduler.cancelAll(alarms.map { it.id })
        appLogger.log("AlarmList", "כל השעמורים הוקפאו (${alarms.size})")
    }
    fun unfreezeAll() = viewModelScope.launch {
        // refreshWidgets=false: unfreezeAll()'s own write above already triggers
        // SmartRingApp's observeAlarms()-based widget refresh.
        repository.unfreezeAll(); scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
        appLogger.log("AlarmList", "הקפאה בוטלה לכל השעמורים")
    }
    fun enableAll() = viewModelScope.launch {
        repository.enableAll(); scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
        appLogger.log("AlarmList", "כל השעמורים הופעלו")
    }
}
