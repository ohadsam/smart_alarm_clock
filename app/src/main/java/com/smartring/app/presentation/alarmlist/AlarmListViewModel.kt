package com.smartring.app.presentation.alarmlist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.util.AlarmRow
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import com.smartring.app.util.GENERIC_ALARM_NAME
import com.smartring.app.util.formatDayAndTime
import com.smartring.app.util.nextOccasionalDate
import java.util.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmListUiState(
    val alarms: List<Alarm> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * The same alarms, each paired with the moment it will actually next ring.
     *
     * Carried alongside rather than derived in the UI because only the scheduler can
     * answer it — it accounts for a pending snooze, a spent recurrence and an ad-hoc date
     * already gone, none of which are readable off the Alarm itself. The grouping is
     * meaningless without it: it is what separates "rings today" from "says 07:00 and will
     * never ring again".
     */
    val rows: List<AlarmRow> = emptyList(),
)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val defaultsRepository: AlarmDefaultsRepository,
    private val scheduler: AlarmScheduler,
    private val appLogger: AppLogger,
) : ViewModel() {
    val uiState = repository.observeAlarms()
        .map { alarms ->
            AlarmListUiState(
                alarms = alarms,
                isLoading = false,
                rows = alarms.map { AlarmRow(it, scheduler.effectiveNextFireTime(it)) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmListUiState())

    fun toggle(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(alarm.id, enabled)
        if (enabled) scheduler.schedule(alarm.copy(isEnabled = true)) else scheduler.cancel(alarm.id)
        appLogger.log("AlarmList", "\"${alarm.name}\" (#${alarm.id}) ${if (enabled) "הופעל" else "כובה"}")
    }
    /**
     * The alarm most recently deleted, held so it can be put back.
     *
     * Deleting now happens immediately with an undo offer rather than behind a
     * confirmation dialog. That is both faster and *safer*: a dialog asks before every
     * delete including the hundreds that were intended, and still cannot help with the one
     * that was a mis-tap, because the tap has already been confirmed by then.
     *
     * The full alarm is kept, not just its id — rings, extra dates and all — because the
     * row is gone from the database and nothing else remembers what was in it.
     */
    private val _undoableDelete = MutableStateFlow<Alarm?>(null)
    val undoableDelete = _undoableDelete.asStateFlow()

    fun delete(alarm: Alarm) = viewModelScope.launch {
        // Re-read before deleting: the list's copy is complete today, but restoring a
        // partially-populated alarm would quietly drop its rings, and the user would have
        // no way to know their undo gave them back something different.
        val full = repository.getAlarm(alarm.id) ?: alarm
        repository.deleteAlarm(alarm.id); scheduler.cancel(alarm.id)
        _undoableDelete.value = full
        appLogger.log("AlarmList", "נמחק: \"${alarm.name}\" (#${alarm.id})")
    }

    /** Puts the last deleted alarm back, re-arming it if it was armed. */
    fun undoDelete() = viewModelScope.launch {
        val alarm = _undoableDelete.value ?: return@launch
        _undoableDelete.value = null
        // saveAlarm inserts when the id no longer exists (see saveAlarmTransaction), so
        // the alarm comes back under its original id and keeps its history.
        repository.saveAlarm(alarm)
        scheduler.schedule(alarm)
        appLogger.log("AlarmList", "שוחזר: \"${alarm.name}\" (#${alarm.id})")
    }

    /** Called once the undo offer has been shown and dismissed. */
    fun clearUndo() { _undoableDelete.value = null }
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

    // ── Quick create ───────────────────────────────────────────────────────

    /**
     * Creates an ad-hoc alarm at [at], from the user's configured defaults, in one tap.
     *
     * The shortest path to an alarm was previously: tap +, land on a form with fifteen
     * controls, set a time, name it, save. For "wake me in eight hours" that is a lot of
     * screen for a decision already made. This skips all of it — everything except the
     * time comes from the defaults the user set in Settings, so a quick alarm rings the
     * way their alarms ring.
     */
    fun createQuickAlarm(at: Long) = viewModelScope.launch {
        val d = defaultsRepository.defaults.first()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val alarm = Alarm(
            id = 0L,
            name = GENERIC_ALARM_NAME,
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            // A specific datetime rather than a time-of-day, which is what makes it an
            // ad-hoc alarm: it rings once, and the card offers "schedule for the next day"
            // afterwards rather than silently repeating tomorrow.
            specificDateTime = at,
            ringDurationSeconds = d.ringDurationSeconds,
            rings = listOf(AlarmRing(volumePercent = d.ringVolumePercent)),
            snoozeEnabled = d.snoozeEnabled,
            snoozeMinutes = d.snoozeMinutes,
            snoozeMaxCount = d.snoozeMaxCount,
            vibrationMode = d.vibrationMode,
            vibrationOnlySeconds = d.vibrationOnlySeconds,
            crescendoEnabled = d.crescendoEnabled,
            crescendoStartVolume = d.crescendoStartVolume,
            crescendoStepSeconds = d.crescendoStepSeconds,
            crescendoStepPercent = d.crescendoStepPercent,
            isShabbatMode = d.isShabbatMode,
        )
        val id = repository.saveAlarm(alarm)
        scheduler.schedule(alarm.copy(id = id))
        appLogger.log("AlarmList", "שעמור מהיר נוצר ל-${formatDayAndTime(at)}")
    }

    // ── Multi-select ───────────────────────────────────────────────────────

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds = _selectedIds.asStateFlow()

    fun toggleSelection(id: Long) = _selectedIds.update {
        if (id in it) it - id else it + id
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    fun setSelectedEnabled(enabled: Boolean) = viewModelScope.launch {
        val ids = _selectedIds.value
        ids.forEach { id ->
            val alarm = repository.getAlarm(id) ?: return@forEach
            repository.setEnabled(id, enabled)
            if (enabled) scheduler.schedule(alarm.copy(isEnabled = true)) else scheduler.cancel(id)
        }
        clearSelection()
        appLogger.log("AlarmList", "${ids.size} שעמורים ${if (enabled) "הופעלו" else "כובו"} בבחירה מרובה")
    }

    /**
     * Deletes everything selected.
     *
     * No undo for a bulk delete, and the screen asks first instead. Undo works for one
     * alarm because one alarm fits in a held value and in a sentence; restoring an
     * arbitrary set silently is a much bigger promise, and getting it half-right would be
     * worse than asking.
     */
    fun deleteSelected() = viewModelScope.launch {
        val ids = _selectedIds.value
        ids.forEach { id ->
            repository.deleteAlarm(id)
            scheduler.cancel(id)
        }
        clearSelection()
        appLogger.log("AlarmList", "${ids.size} שעמורים נמחקו בבחירה מרובה")
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
