package com.smartring.app.presentation.alarmedit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.*
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class AlarmEditUiState(
    // Basic
    val name: String                       = "",
    val hour: Int                          = 7,
    val minute: Int                        = 0,
    // DateTime-specific
    val specificDateTime: Long?            = null,
    // Recurrence
    val repeatDaysBitmask: Int             = 0,
    val repeatFrequency: RepeatFrequency   = RepeatFrequency.WEEKLY,
    val recurrenceEndType: RecurrenceEndType = RecurrenceEndType.FOREVER,
    val recurrenceUntilDate: Long?         = null,
    val recurrenceCount: Int               = 10,
    // Specific dates
    val specificDates: List<AlarmDate>     = emptyList(),
    // Ring
    val ringDurationSeconds: Int           = 60,
    val rings: List<AlarmRing>             = listOf(AlarmRing(volumePercent = 100)),
    // Snooze
    val snoozeMinutes: Int                 = 10,
    val snoozeMaxCount: Int                = 3,
    // Misc
    val reminderText: String               = "",
    val vibrationMode: VibrationMode       = VibrationMode.SOUND_AND_VIBRATION,
    val vibrationOnlySeconds: Int          = 10,
    val crescendoEnabled: Boolean          = false,
    val crescendoStartVolume: Int          = 10,
    val crescendoStepSeconds: Int          = 15,
    val crescendoStepPercent: Int          = 10,
    // UI state
    val isSaving: Boolean                  = false,
    val isSaved: Boolean                   = false,
    val nameError: Boolean                 = false,
    // "next fire" hint shown to user
    val nextFireHint: String?              = null,
    // Preserved verbatim from the loaded alarm; not editable on this screen but must
    // survive save() so editing an alarm doesn't silently re-enable/un-freeze it or
    // reset its COUNT-recurrence progress.
    val isEnabled: Boolean                 = true,
    val isFrozen: Boolean                  = false,
    val occurrencesFired: Int              = 0,
)

@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()
    private var editingId = 0L
    private var originalState: AlarmEditUiState? = null

    fun loadAlarm(id: Long) {
        if (id <= 0L) return
        editingId = id
        viewModelScope.launch {
            val a = repository.getAlarm(id) ?: return@launch
            val loaded = AlarmEditUiState(
                    name                 = a.name,
                    hour                 = a.hour,
                    minute               = a.minute,
                    specificDateTime     = a.specificDateTime,
                    repeatDaysBitmask    = a.repeatDaysBitmask,
                    repeatFrequency      = a.repeatFrequency,
                    recurrenceEndType    = a.recurrenceEnd.type,
                    recurrenceUntilDate  = a.recurrenceEnd.untilDate,
                    recurrenceCount      = a.recurrenceEnd.count,
                    specificDates        = a.specificDates,
                    ringDurationSeconds  = a.ringDurationSeconds,
                    rings                = a.rings.ifEmpty { listOf(AlarmRing(volumePercent = 100)) },
                    snoozeMinutes        = a.snoozeMinutes,
                    snoozeMaxCount       = a.snoozeMaxCount,
                    reminderText         = a.reminderText.orEmpty(),
                    vibrationMode        = a.vibrationMode,
                    vibrationOnlySeconds = a.vibrationOnlySeconds,
                    crescendoEnabled     = a.crescendoEnabled,
                    crescendoStartVolume = a.crescendoStartVolume,
                    crescendoStepSeconds = a.crescendoStepSeconds,
                    crescendoStepPercent = a.crescendoStepPercent,
                    isEnabled            = a.isEnabled,
                    isFrozen             = a.isFrozen,
                    occurrencesFired     = a.occurrencesFired,
            )
            originalState = loaded
            _state.update { loaded }
            updateNextFireHint()
        }
    }

    val isDirty: Boolean get() = originalState != null && _state.value != originalState

    /** Pre-fills a brand-new (unsaved) alarm from a History "load again" action. */
    fun prefill(name: String?, hour: Int?, minute: Int?) {
        _state.update { s ->
            s.copy(
                name   = name ?: s.name,
                hour   = hour ?: s.hour,
                minute = minute ?: s.minute,
            )
        }
        updateNextFireHintLater()
    }

    // ── Setters ───────────────────────────────────────────────────
    fun setName(v: String)                       = _state.update { it.copy(name = v, nameError = false) }
    fun setTime(h: Int, m: Int)                   = _state.update { it.copy(hour = h, minute = m).also { updateNextFireHintLater() } }
    fun setSpecificDateTime(dt: Long?)            = _state.update { it.copy(specificDateTime = dt) }
    fun setReminderText(v: String)                = _state.update { it.copy(reminderText = v) }
    fun setSnoozeMinutes(v: Int)                  = _state.update { it.copy(snoozeMinutes = v) }
    fun setSnoozeMaxCount(v: Int)                 = _state.update { it.copy(snoozeMaxCount = v) }
    fun setRingDuration(v: Int)                   = _state.update { it.copy(ringDurationSeconds = v) }
    fun setRepeatFrequency(v: RepeatFrequency)    = _state.update { it.copy(repeatFrequency = v).also { updateNextFireHintLater() } }
    fun setRecurrenceEndType(v: RecurrenceEndType)= _state.update { it.copy(recurrenceEndType = v) }
    fun setRecurrenceUntilDate(v: Long?)          = _state.update { it.copy(recurrenceUntilDate = v) }
    fun setRecurrenceCount(v: Int)                = _state.update { it.copy(recurrenceCount = v) }
    fun setVibrationMode(v: VibrationMode)        = _state.update { it.copy(vibrationMode = v) }
    fun setVibrationOnlySeconds(v: Int)           = _state.update { it.copy(vibrationOnlySeconds = v) }
    fun setCrescendoEnabled(v: Boolean)           = _state.update { it.copy(crescendoEnabled = v) }
    fun setCrescendoStartVolume(v: Int)           = _state.update { it.copy(crescendoStartVolume = v) }
    fun setCrescendoStepSeconds(v: Int)           = _state.update { it.copy(crescendoStepSeconds = v) }
    fun setCrescendoStepPercent(v: Int)           = _state.update { it.copy(crescendoStepPercent = v) }

    fun toggleDay(i: Int) {
        _state.update { it.copy(repeatDaysBitmask = it.repeatDaysBitmask xor (1 shl i)) }
        updateNextFireHintLater()
    }

    // ── Rings ─────────────────────────────────────────────────────
    fun addRing() {
        if (_state.value.rings.size >= 10) return
        _state.update { s ->
            s.copy(rings = s.rings + AlarmRing(orderIndex = s.rings.size, durationSeconds = 30, volumePercent = 80, delayAfterSeconds = 300))
        }
    }
    fun updateRing(i: Int, r: AlarmRing) = _state.update {
        it.copy(rings = it.rings.toMutableList().also { l -> l[i] = r })
    }
    fun removeRing(i: Int) {
        if (_state.value.rings.size <= 1) return
        _state.update {
            it.copy(rings = it.rings.toMutableList().also { l -> l.removeAt(i) }
                .mapIndexed { idx, r -> r.copy(orderIndex = idx) })
        }
    }

    // ── Specific dates ────────────────────────────────────────────
    fun addDate(epochMillis: Long, label: String? = null) = _state.update {
        it.copy(specificDates = it.specificDates + AlarmDate(date = epochMillis, label = label))
    }
    fun removeDate(i: Int) = _state.update {
        it.copy(specificDates = it.specificDates.toMutableList().also { l -> l.removeAt(i) })
    }

    // ── Next fire hint ────────────────────────────────────────────
    private fun updateNextFireHintLater() = viewModelScope.launch { updateNextFireHint() }

    private fun updateNextFireHint() {
        val s = _state.value
        val alarm = buildAlarm(s)
        val next = scheduler.nextFireTime(alarm)
        val hint = if (next == null) null else {
            val cal = Calendar.getInstance().apply { timeInMillis = next }
            val today = Calendar.getInstance()
            val isToday = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            val isTomorrow = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) + 1
            val dateStr = when {
                isToday    -> "היום"
                isTomorrow -> "מחר"
                else       -> "%02d/%02d/%04d".format(
                    cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.YEAR))
            }
            "הצלצול הבא: $dateStr בשעה %02d:%02d".format(
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        _state.update { it.copy(nextFireHint = hint) }
    }

    // ── Save ──────────────────────────────────────────────────────
    fun save() {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(nameError = true) }; return }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val alarm = buildAlarm(s)
            val savedId = repository.saveAlarm(alarm)
            scheduler.schedule(alarm.copy(id = savedId))
            _state.update { it.copy(isSaving = false, isSaved = true) }
        }
    }

    private fun buildAlarm(s: AlarmEditUiState) = Alarm(
        id                   = editingId,
        name                 = s.name.trim(),
        hour                 = s.hour,
        minute               = s.minute,
        specificDateTime     = s.specificDateTime,
        isEnabled            = s.isEnabled,
        isFrozen             = s.isFrozen,
        occurrencesFired     = s.occurrencesFired,
        repeatDaysBitmask    = s.repeatDaysBitmask,
        repeatFrequency      = s.repeatFrequency,
        recurrenceEnd        = RecurrenceEnd(s.recurrenceEndType, s.recurrenceUntilDate, s.recurrenceCount),
        specificDates        = s.specificDates,
        ringDurationSeconds  = s.ringDurationSeconds,
        rings                = s.rings,
        snoozeMinutes        = s.snoozeMinutes,
        snoozeMaxCount       = s.snoozeMaxCount,
        reminderText         = s.reminderText.takeIf { it.isNotBlank() },
        vibrationMode        = s.vibrationMode,
        vibrationOnlySeconds = s.vibrationOnlySeconds,
        crescendoEnabled     = s.crescendoEnabled,
        crescendoStartVolume = s.crescendoStartVolume,
        crescendoStepSeconds = s.crescendoStepSeconds,
        crescendoStepPercent = s.crescendoStepPercent,
    )
}
