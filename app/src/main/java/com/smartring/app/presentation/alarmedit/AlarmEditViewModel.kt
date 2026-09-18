package com.smartring.app.presentation.alarmedit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.*
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.COPY_SUFFIX
import com.smartring.app.util.GENERIC_ALARM_NAME
import com.smartring.app.util.AppLogger
import com.smartring.app.util.endOfPickedDay
import com.smartring.app.util.formatNextFireAt
import com.smartring.app.util.occasionalTodayAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    // Recurrence: see the matching comment on Alarm.repeatDaysBitmask/repeatFrequency
    // for why the default is WEEKLY even though a fresh alarm (0 days picked) fires
    // only once — AlarmEditScreen's frequency chip row shows nothing selected until
    // repeatDaysBitmask != 0, so this default doesn't visually contradict "one-time".
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
    // Snooze — off by default, see Alarm.snoozeEnabled
    val snoozeEnabled: Boolean             = false,
    val snoozeMinutes: Int                 = 10,
    val snoozeMaxCount: Int                = 3,
    // Shabbat mode
    val isShabbatMode: Boolean             = false,
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
    // The last save attempt threw. Cleared when the next one starts; surfaced on the
    // screen so a failed save is visible rather than looking like a stuck button.
    val saveError: Boolean                 = false,
    // "next fire" hint shown to user
    val nextFireHint: String?              = null,
    // True when this alarm, exactly as configured right now, has no future occurrence
    // left at all — a specific date/time already in the past, every specific date
    // passed, or a recurrence that has already ended. Saving in that state is still
    // allowed (the user may be mid-edit and about to pick a date), but it will never
    // ring, and the screen used to say nothing at all: the "next fire" hint just
    // vanished, which reads as a rendering quirk rather than "this will not go off".
    val neverFires: Boolean                = false,
    /**
     * Editable on this screen, which it was not until v1.6.13 — and that was the single
     * worst bug in the app.
     *
     * AlarmFiringService switches a one-time alarm off once it has rung, which is right.
     * But this field was documented as "preserved verbatim, not editable here", so
     * opening that alarm, giving it a new time and saving wrote `isEnabled = false`
     * straight back — and `schedule()` cancels anything that is not active. The alarm
     * saved perfectly and could never ring again, with nothing on screen to say why. It
     * also emptied the widgets and the status-bar indicator, which both only ever show
     * armed alarms.
     *
     * The original intent — never silently re-enable an alarm the user deliberately
     * turned off — still holds. It is met by making the state visible and letting the
     * user decide, not by making it unreachable.
     */
    val isEnabled: Boolean                 = true,
    /**
     * Whether the alarm was already off when this screen opened. Saving with the toggle
     * back on is then an explicit revival, and [AlarmEditViewModel.save] resets the
     * COUNT-recurrence progress so a finished alarm can genuinely run again instead of
     * being re-enabled into an already-expired recurrence.
     */
    val loadedDisabled: Boolean            = false,
    val isFrozen: Boolean                  = false,
    val occurrencesFired: Int              = 0,
    /**
     * The chosen specific date/time is in the past. Blocks the save rather than warning,
     * because there is no reading of "ring me last Tuesday" that this app can honour.
     */
    val pastDateError: Boolean             = false,
    /**
     * The "no name needed" toggle. Mirrors `name == GENERIC_ALARM_NAME` rather than being
     * derived from it on the fly, so that clearing the toggle can restore an empty field
     * instead of leaving the generic name sitting there looking like the user typed it.
     */
    val unnamed: Boolean                   = false,
)

@HiltViewModel
class AlarmEditViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val defaultsRepository: AlarmDefaultsRepository,
    private val scheduler: AlarmScheduler,
    private val appLogger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()
    private var editingId = 0L
    private var originalState: AlarmEditUiState? = null

    // A one-shot event (not part of AlarmEditUiState) rather than a boolean/nonce
    // pair to keep in sync: isDirty compares the whole state against originalState by
    // structural equality, so any ever-changing field added there would permanently
    // read as "dirty" the moment it changed. The event itself carries the intent
    // ("scroll to the name field now"), so the screen doesn't need to separately
    // track "was this the same nameError value as last time".
    private val _scrollToNameRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToNameRequests: SharedFlow<Unit> = _scrollToNameRequests.asSharedFlow()

    fun loadAlarm(id: Long) {
        if (id <= 0L) return
        editingId = id
        viewModelScope.launch {
            val a = repository.getAlarm(id) ?: return@launch
            val loaded = stateFromAlarm(a)
            originalState = loaded
            _state.update { loaded }
        }
    }

    /**
     * Every field of an [Alarm] as edit-screen state. Shared by [loadAlarm] and
     * [loadAsCopy] so a field added to one can never be forgotten by the other — the
     * duplicate path silently dropping a setting would be invisible until an alarm rang
     * differently from the one it was copied from.
     */
    private fun stateFromAlarm(a: Alarm): AlarmEditUiState =
            AlarmEditUiState(
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
                    snoozeEnabled        = a.snoozeEnabled,
                    snoozeMinutes        = a.snoozeMinutes,
                    snoozeMaxCount       = a.snoozeMaxCount,
                    isShabbatMode        = a.isShabbatMode,
                    reminderText         = a.reminderText.orEmpty(),
                    vibrationMode        = a.vibrationMode,
                    vibrationOnlySeconds = a.vibrationOnlySeconds,
                    crescendoEnabled     = a.crescendoEnabled,
                    crescendoStartVolume = a.crescendoStartVolume,
                    crescendoStepSeconds = a.crescendoStepSeconds,
                    crescendoStepPercent = a.crescendoStepPercent,
                    unnamed              = a.name == GENERIC_ALARM_NAME,
                    isEnabled            = a.isEnabled,
                    loadedDisabled       = !a.isEnabled,
                    isFrozen             = a.isFrozen,
                    occurrencesFired     = a.occurrencesFired,
                    // Computed up front (not via a separate updateNextFireHint() call
                    // after the fact) so originalState and the initial _state are the
                    // exact same value — otherwise isDirty (structural equality against
                    // originalState) would read true the instant the screen opens for
                    // any alarm whose next fire time isn't null.
                    nextFireHint         = nextFireHintFor(a),
                    neverFires           = scheduler.effectiveNextFireTime(a) == null,
            )

    /**
     * Loads [sourceId]'s settings into a brand-new, unsaved alarm.
     *
     * `editingId` stays 0, so saving inserts rather than overwrites. Three things are
     * deliberately *not* copied:
     *
     *  - the enabled state and the occurrence counter, because a copy of a finished alarm
     *    should arrive ready to run, not pre-retired;
     *  - `originalState`, which stays null. `isDirty` is false while it is null, so
     *    backing out of an unsaved copy leaves without prompting — the same as backing
     *    out of any other new alarm, which is the existing behaviour for id 0 and is
     *    left alone here rather than changed as a side effect of adding duplication;
     *  - nothing about the date. The copy keeps the original's `specificDateTime` even
     *    when it is in the past, precisely so `save()` refuses it and the user has to
     *    choose a future one. Silently clearing or advancing the date would be guessing
     *    at which date they meant.
     */
    fun loadAsCopy(sourceId: Long) {
        if (sourceId <= 0L) return
        editingId = 0L
        viewModelScope.launch {
            val a = repository.getAlarm(sourceId) ?: return@launch
            _state.update {
                stateFromAlarm(a).copy(
                    name             = copyNameFor(a.name),
                    isEnabled        = true,
                    loadedDisabled   = false,
                    occurrencesFired = 0,
                    unnamed          = false,
                )
            }
        }
    }

    /** "קום לעבודה" -> "קום לעבודה (עותק)", and left alone if it already says so. */
    private fun copyNameFor(name: String): String =
        if (name.endsWith(COPY_SUFFIX)) name else "$name$COPY_SUFFIX"

    val isDirty: Boolean get() = originalState != null && _state.value != originalState

    /**
     * Starts a brand-new alarm from the user's configured defaults, then applies any
     * prefill from History's "load again" action on top.
     *
     * Replaces the old `prefill`, which only ever set name/hour/minute and left every
     * other field on the compiled-in constant. Defaults are read once, not collected:
     * a new alarm is seeded when the screen opens, and having the form mutate underneath
     * someone because they changed a default in another window would be worse than
     * stale.
     */
    fun startNew(name: String? = null, hour: Int? = null, minute: Int? = null) {
        viewModelScope.launch {
            val d = defaultsRepository.defaults.first()
            _state.update {
                AlarmEditUiState(
                    name                 = name ?: if (d.unnamed) GENERIC_ALARM_NAME else "",
                    unnamed              = name == null && d.unnamed,
                    hour                 = hour ?: d.hour,
                    minute               = minute ?: d.minute,
                    ringDurationSeconds  = d.ringDurationSeconds,
                    rings                = listOf(AlarmRing(volumePercent = d.ringVolumePercent)),
                    snoozeEnabled        = d.snoozeEnabled,
                    snoozeMinutes        = d.snoozeMinutes,
                    snoozeMaxCount       = d.snoozeMaxCount,
                    vibrationMode        = d.vibrationMode,
                    vibrationOnlySeconds = d.vibrationOnlySeconds,
                    crescendoEnabled     = d.crescendoEnabled,
                    crescendoStartVolume = d.crescendoStartVolume,
                    crescendoStepSeconds = d.crescendoStepSeconds,
                    crescendoStepPercent = d.crescendoStepPercent,
                    isShabbatMode        = d.isShabbatMode,
                )
            }
            updateNextFireHintLater()
        }
    }

    // ── Setters ───────────────────────────────────────────────────
    // Typing clears the toggle: the field and the toggle would otherwise disagree, and
    // the toggle is what decides whether clearing it wipes the field.
    fun setName(v: String) = _state.update {
        it.copy(name = v, nameError = false, unnamed = v == GENERIC_ALARM_NAME)
    }
    fun setEnabled(v: Boolean)                   = _state.update { it.copy(isEnabled = v) }

    /**
     * Fills in (or clears) the generic name.
     *
     * Turning it off only clears the field when it still holds the generic name: someone
     * who switched it on, off, and then typed their own name must not have that erased by
     * a later toggle.
     */
    fun setUnnamed(v: Boolean) = _state.update {
        it.copy(
            unnamed = v,
            name = if (v) GENERIC_ALARM_NAME
                   else if (it.name == GENERIC_ALARM_NAME) "" else it.name,
            nameError = false,
        )
    }
    fun setTime(h: Int, m: Int)                   = _state.update { it.copy(hour = h, minute = m, pastDateError = false).also { updateNextFireHintLater() } }
    /**
     * Keeps hour/minute in step with the chosen datetime. Everything that displays an
     * alarm outside this screen — the list card, every widget size, the notification —
     * reads `hour`/`minute`, while only the scheduler reads `specificDateTime`, so
     * leaving them unsynced showed a one-off alarm at whatever time-of-day the fields
     * happened to hold (07:00 for a brand-new alarm) while it actually rang at the
     * picked time.
     */
    fun setSpecificDateTime(dt: Long?) = _state.update { s ->
        if (dt == null) s.copy(specificDateTime = null, pastDateError = false)
        else {
            val cal = Calendar.getInstance().apply { timeInMillis = dt }
            s.copy(
                specificDateTime = dt,
                hour   = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                // Cleared on every pick so the refusal disappears the moment the user
                // chooses a valid date, rather than lingering until the next save.
                pastDateError = false,
            ).also { updateNextFireHintLater() }
        }
    }
    /**
     * "מזדמן — היום": pins the alarm to today at the time already chosen above.
     *
     * Refuses rather than rolls forward when that time has passed. The user asked for
     * today; quietly substituting tomorrow is how somebody gets woken on the wrong day.
     */
    fun setOccasionalToday() = _state.update { s ->
        val today = occasionalTodayAt(s.hour, s.minute)
        if (today == null) s.copy(pastDateError = true)
        else s.copy(specificDateTime = today, pastDateError = false)
            .also { updateNextFireHintLater() }
    }

    /** Pins the alarm to a day [daysFromToday] out, keeping the chosen time of day. */
    fun setSpecificDaysFromToday(daysFromToday: Int) = _state.update { s ->
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysFromToday)
            set(Calendar.HOUR_OF_DAY, s.hour)
            set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        s.copy(specificDateTime = cal.timeInMillis, pastDateError = false)
            .also { updateNextFireHintLater() }
    }

    fun setReminderText(v: String)                = _state.update { it.copy(reminderText = v) }
    fun setSnoozeEnabled(v: Boolean)               = _state.update { it.copy(snoozeEnabled = v) }
    fun setSnoozeMinutes(v: Int)                  = _state.update { it.copy(snoozeMinutes = v) }
    fun setSnoozeMaxCount(v: Int)                 = _state.update { it.copy(snoozeMaxCount = v) }
    fun setShabbatMode(v: Boolean)                = _state.update { it.copy(isShabbatMode = v) }
    fun setRingDuration(v: Int)                   = _state.update { it.copy(ringDurationSeconds = v) }
    fun setRepeatFrequency(v: RepeatFrequency)    = _state.update { it.copy(repeatFrequency = v).also { updateNextFireHintLater() } }
    fun setRecurrenceEndType(v: RecurrenceEndType)= _state.update { it.copy(recurrenceEndType = v).also { updateNextFireHintLater() } }
    /**
     * Stores the picked "repeat until" day as the *end* of that day in local time.
     * Compose's DatePicker hands back UTC midnight, and isRecurrenceExpired() treats
     * the stored value as a hard cutoff — so storing it raw made an alarm set to
     * repeat "until the 20th" expire during the small hours of the 20th (UTC midnight
     * is 02:00/03:00 local here) and skip that morning's ring entirely, one day
     * earlier than the user asked for.
     */
    fun setRecurrenceUntilDate(v: Long?) = _state.update { s ->
        s.copy(recurrenceUntilDate = v?.let { endOfPickedDay(it) }).also { updateNextFireHintLater() }
    }
    fun setRecurrenceCount(v: Int)                = _state.update { it.copy(recurrenceCount = v).also { updateNextFireHintLater() } }
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
            .also { updateNextFireHintLater() }
    }
    fun removeDate(i: Int) = _state.update {
        it.copy(specificDates = it.specificDates.toMutableList().also { l -> l.removeAt(i) })
            .also { updateNextFireHintLater() }
    }

    // ── Next fire hint ────────────────────────────────────────────
    private fun updateNextFireHintLater() = viewModelScope.launch { updateNextFireHint() }

    private fun updateNextFireHint() {
        val alarm = buildAlarm(_state.value)
        val next = scheduler.effectiveNextFireTime(alarm)
        _state.update { it.copy(nextFireHint = formatNextFire(next), neverFires = next == null) }
    }

    private fun nextFireHintFor(alarm: Alarm): String? =
        formatNextFire(scheduler.effectiveNextFireTime(alarm))

    private fun formatNextFire(next: Long?): String? =
        next?.let { formatNextFireAt(it) }

    // ── Save ──────────────────────────────────────────────────────
    fun save() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(nameError = true) }
            _scrollToNameRequests.tryEmit(Unit)
            return
        }
        // A specific date/time already in the past is refused outright, not saved with a
        // warning. It is the one configuration that is certainly a mistake: the previous
        // behaviour let it through, `nextFireTime` returned null, `schedule()` cancelled,
        // and the user was left with an alarm that looked saved and was not armed. This
        // is reachable straight from editing or duplicating an alarm that already rang.
        if (s.specificDateTime != null && s.specificDateTime <= System.currentTimeMillis()) {
            _state.update { it.copy(pastDateError = true) }
            return
        }
        _state.update { it.copy(isSaving = true, saveError = false, pastDateError = false) }
        viewModelScope.launch {
            // Without this, a failing write (a database error, an AlarmManager refusing
            // one more exact alarm) left isSaving stuck at true forever: the save button
            // span on a screen that never closed, and the user had no way to tell that
            // their alarm had not been saved at all.
            try {
                val alarm = buildAlarm(s)
                val savedId = repository.saveAlarm(alarm)
                // Logged so a revival is visible in the diagnostics: this is the path
                // that used to fail silently, and "why did my alarm stop working" is
                // exactly the question the log exists to answer.
                if (s.loadedDisabled && s.isEnabled) appLogger.log("AlarmEdit",
                    "\"${alarm.name}\" (#$savedId) הופעל מחדש; מונה החזרות אופס")
                scheduler.schedule(alarm.copy(id = savedId))
                appLogger.log("AlarmEdit", (if (editingId > 0L) "שעמור עודכן: " else "שעמור חדש נוצר: ") +
                    "\"${alarm.name}\" (#$savedId) ל-%02d:%02d".format(alarm.hour, alarm.minute))
                _state.update { it.copy(isSaving = false, isSaved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.log("AlarmEdit", "שמירת השעמור \"${s.name}\" נכשלה: ${e.message}")
                _state.update { it.copy(isSaving = false, saveError = true) }
            }
        }
    }

    /**
     * Turning a disabled alarm back on resets [Alarm.occurrencesFired].
     *
     * Without it, re-enabling a COUNT-limited alarm that had run out of occurrences puts
     * it straight back into `isRecurrenceExpired()`, and `schedule()` cancels it again —
     * the toggle would appear to do nothing. Only on an explicit off-to-on transition:
     * editing the name of an alarm that is mid-way through its count must not restart it.
     */
    private fun buildAlarm(s: AlarmEditUiState) = Alarm(
        id                   = editingId,
        name                 = s.name.trim(),
        hour                 = s.hour,
        minute               = s.minute,
        specificDateTime     = s.specificDateTime,
        isEnabled            = s.isEnabled,
        isFrozen             = s.isFrozen,
        occurrencesFired     = if (s.loadedDisabled && s.isEnabled) 0 else s.occurrencesFired,
        repeatDaysBitmask    = s.repeatDaysBitmask,
        repeatFrequency      = s.repeatFrequency,
        recurrenceEnd        = RecurrenceEnd(s.recurrenceEndType, s.recurrenceUntilDate, s.recurrenceCount),
        specificDates        = s.specificDates,
        ringDurationSeconds  = s.ringDurationSeconds,
        rings                = s.rings,
        snoozeEnabled        = s.snoozeEnabled,
        snoozeMinutes        = s.snoozeMinutes,
        snoozeMaxCount       = s.snoozeMaxCount,
        isShabbatMode        = s.isShabbatMode,
        reminderText         = s.reminderText.takeIf { it.isNotBlank() },
        vibrationMode        = s.vibrationMode,
        vibrationOnlySeconds = s.vibrationOnlySeconds,
        crescendoEnabled     = s.crescendoEnabled,
        crescendoStartVolume = s.crescendoStartVolume,
        crescendoStepSeconds = s.crescendoStepSeconds,
        crescendoStepPercent = s.crescendoStepPercent,
    )
}
