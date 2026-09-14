package com.smartring.app.domain.model

import java.util.Calendar

// ── Recurrence ────────────────────────────────────────────────────

enum class RepeatFrequency { NONE, WEEKLY, BIWEEKLY, MONTHLY }

enum class VibrationMode {
    SOUND_ONLY, VIBRATION_ONLY, SOUND_AND_VIBRATION, VIBRATION_THEN_SOUND
}

/**
 * How long a repeating alarm continues.
 * FOREVER  = no end
 * UNTIL    = ends on a specific date (epoch millis)
 * COUNT    = ends after N occurrences
 */
enum class RecurrenceEndType { FOREVER, UNTIL, COUNT }

data class RecurrenceEnd(
    val type: RecurrenceEndType  = RecurrenceEndType.FOREVER,
    val untilDate: Long?         = null,   // epoch millis, used when type=UNTIL
    val count: Int               = 10,     // used when type=COUNT
)

// ── Alarm sub-models ──────────────────────────────────────────────

data class AlarmRing(
    val id: Long              = 0,
    val alarmId: Long         = 0,
    val orderIndex: Int       = 0,
    val durationSeconds: Int  = 60,
    val volumePercent: Int    = 100,
    val ringtoneUri: String   = "default",
    val delayAfterSeconds: Int = 0,
)

data class AlarmDate(
    val id: Long          = 0,
    val alarmId: Long     = 0,
    val date: Long        = 0L,   // epoch millis – UTC midnight of the date
    val label: String?    = null,
)

data class AlarmLog(
    val id: Long       = 0,
    val alarmId: Long  = 0,
    val alarmName: String = "",
    val firedAt: Long  = 0L,
    val scheduledFor: Long = 0L,   // what time it was supposed to fire
    val action: String = "FIRED",  // FIRED | STOPPED | SNOOZED | MISSED
)

/** A technical/diagnostic log line (scheduling, boot, background work) — separate
 *  from [AlarmLog], which is the user-facing ring-history shown in HistoryScreen. */
data class AppLogEntry(
    val id: Long        = 0,
    val timestamp: Long = 0L,
    val tag: String     = "",
    val message: String = "",
)

// ── Main Alarm model ──────────────────────────────────────────────

data class Alarm(
    val id: Long                        = 0,
    val name: String                    = "",
    // ── Schedule ──────────────────────────────────────────────────
    val hour: Int                       = 7,
    val minute: Int                     = 0,
    /** Null = daily/weekly schedule. Non-null = fires once at this exact datetime. */
    val specificDateTime: Long?         = null,
    val isEnabled: Boolean              = true,
    val isFrozen: Boolean               = false,
    // ── Recurrence ────────────────────────────────────────────────
    // repeatDaysBitmask defaults to 0 (no days picked) — a fresh alarm with no days
    // selected fires once (nextFireTime()'s "simple time-of-day" fallback), not on a
    // schedule, regardless of repeatFrequency; the latter only matters once the user
    // actually picks a day. Keep the default WEEKLY (not NONE): the recurrence-end
    // section (AlarmEditScreen) is gated on `repeatFrequency != NONE`, so a user who
    // taps a weekday without ever touching the frequency chips must still land on a
    // real repeating cadence with a working "when does this stop repeating" UI --
    // AlarmEditScreen instead shows the frequency chip row as all-unselected while
    // repeatDaysBitmask == 0, so a brand-new alarm doesn't visually look like
    // "weekly" was already chosen.
    val repeatDaysBitmask: Int          = 0,          // bit0=Sun … bit6=Sat
    val repeatFrequency: RepeatFrequency = RepeatFrequency.WEEKLY,
    val recurrenceEnd: RecurrenceEnd    = RecurrenceEnd(),
    val occurrencesFired: Int           = 0,          // tracks COUNT-based end
    /** One-off specific dates (list of epoch-midnight values) */
    val specificDates: List<AlarmDate>  = emptyList(),
    // ── Ring behavior ─────────────────────────────────────────────
    val ringDurationSeconds: Int        = 60,
    val rings: List<AlarmRing>          = emptyList(),
    // ── Snooze ────────────────────────────────────────────────────
    // Off by default (v1.4.0): most users don't want a stray notification snooze
    // action on every alarm unless they opt in explicitly.
    val snoozeEnabled: Boolean          = false,
    val snoozeMinutes: Int              = 10,
    val snoozeMaxCount: Int             = 3,
    // ── Shabbat mode: while ringing, Stop/Snooze are disabled (no notification
    // actions either) so nothing can be pressed; the alarm still auto-stops via
    // ringDurationSeconds. Off by default. ──────────────────────────
    val isShabbatMode: Boolean          = false,
    // ── Reminder ─────────────────────────────────────────────────
    val reminderText: String?           = null,
    // ── Vibration ─────────────────────────────────────────────────
    val vibrationMode: VibrationMode    = VibrationMode.SOUND_AND_VIBRATION,
    val vibrationOnlySeconds: Int       = 10,
    // ── Crescendo ─────────────────────────────────────────────────
    val crescendoEnabled: Boolean       = false,
    val crescendoStartVolume: Int       = 10,
    val crescendoStepSeconds: Int       = 15,
    val crescendoStepPercent: Int       = 10,
) {
    /**
     * The time this alarm actually rings. For a specific-datetime alarm that's the
     * datetime's own time of day, not the hour/minute fields: only the scheduler reads
     * specificDateTime, so everything else (list, widgets, notification) would
     * otherwise show a one-off alarm at a time it never rings — most visibly the
     * untouched 07:00 default on an alarm set for, say, 09:30 next Tuesday. Alarms
     * saved before this was fixed keep stale fields in the DB, so deriving it here
     * rather than only syncing on save covers those too.
     */
    val timeFormatted: String
        get() = specificDateTime?.let {
            val cal = Calendar.getInstance().apply { timeInMillis = it }
            "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        } ?: "%02d:%02d".format(hour, minute)

    val isActive: Boolean
        get() = isEnabled && !isFrozen

    /** Shabbat mode: no Stop/Snooze interaction is accepted anywhere (ring screen,
     *  notification actions) while this alarm is ringing. Single source of truth for
     *  that check — every entry point (buildNotification, StopAlarmReceiver,
     *  SnoozeAlarmReceiver, AlarmRingViewModel) reads this instead of re-deriving it. */
    val acceptsInteraction: Boolean
        get() = !isShabbatMode

    val isRecurring: Boolean
        get() = repeatDaysBitmask != 0 && repeatFrequency != RepeatFrequency.NONE

    /** Whether recurrence has ended based on end rules. */
    fun isRecurrenceExpired(): Boolean = when (recurrenceEnd.type) {
        RecurrenceEndType.FOREVER -> false
        RecurrenceEndType.UNTIL   -> recurrenceEnd.untilDate?.let {
            System.currentTimeMillis() > it } ?: false
        RecurrenceEndType.COUNT   -> occurrencesFired >= recurrenceEnd.count
    }

    fun volumeAtSecond(base: Int, elapsed: Int): Int {
        if (!crescendoEnabled) return base
        // Defensive clamps: coerceIn(min, max) throws if min > max, which is reachable
        // in practice — the ring-volume and crescendo-start-volume sliders are edited
        // independently, so a ring can end up quieter than the alarm's crescendo start
        // (e.g. a 10%-volume ring with a 50% crescendo start). Without this, playing
        // that ring crashed the firing coroutine instead of just clamping down to it.
        val floor = crescendoStartVolume.coerceAtMost(base)
        val steps = elapsed / crescendoStepSeconds.coerceAtLeast(1)
        return (floor + steps * crescendoStepPercent).coerceIn(floor, base)
    }

    /**
     * One-line Hebrew summary of when this alarm rings — "כל יום", "א׳, ג׳, ה׳",
     * "חד־פעמי", a specific date, etc. The alarm list used to show nothing but the
     * time and name, so two alarms at 07:00 (one every weekday, one a single
     * next-Tuesday reminder) were indistinguishable without opening each one.
     */
    fun scheduleSummary(): String {
        specificDateTime?.let {
            val cal = Calendar.getInstance().apply { timeInMillis = it }
            return "%02d/%02d/%04d".format(
                cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        }
        if (repeatDaysBitmask == 0) {
            return if (specificDates.isEmpty()) "חד־פעמי" else "${specificDates.size} תאריכים"
        }
        val dayNames = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
        val picked = dayNames.filterIndexed { i, _ -> (repeatDaysBitmask shr i) and 1 == 1 }
        val days = when {
            picked.size == 7                                      -> "כל יום"
            repeatDaysBitmask == WEEKDAYS_SUN_TO_THU_MASK         -> "ימי חול"
            repeatDaysBitmask == WEEKEND_FRI_SAT_MASK             -> "סוף שבוע"
            else                                                  -> picked.joinToString(", ")
        }
        val cadence = when (repeatFrequency) {
            RepeatFrequency.WEEKLY   -> ""
            RepeatFrequency.BIWEEKLY -> " · כל שבועיים"
            RepeatFrequency.MONTHLY  -> " · פעם בחודש"
            RepeatFrequency.NONE     -> " · פעם אחת"
        }
        return days + cadence
    }

    /** Whether the *sound* half of this alarm is playing yet, [e] seconds into the
     *  ring. Only VIBRATION_THEN_SOUND has a window where it isn't: the configured
     *  vibrate-first period. Single source of truth for that rule — AlarmRingScreen's
     *  "רטט בלבד" countdown badge reads it rather than re-deriving the comparison, so
     *  the badge can't drift out of step with what is actually audible. */
    fun soundActiveAt(e: Int) = when (vibrationMode) {
        VibrationMode.VIBRATION_ONLY        -> false
        VibrationMode.VIBRATION_THEN_SOUND  -> e >= vibrationOnlySeconds
        else                                -> true
    }

    /**
     * The rounds as they are actually played. AlarmFiringService substitutes a single
     * full-volume round for an alarm with none configured and plays them in
     * orderIndex order; anything that wants to reason about what is audible has to
     * apply the same two rules, so they live here once.
     */
    val effectiveRings: List<AlarmRing>
        get() = rings.ifEmpty { listOf(AlarmRing(volumePercent = 100)) }.sortedBy { it.orderIndex }

    /**
     * Which round is playing [e] seconds after the alarm started, or null when
     * nothing is (vibration-only, the vibrate-first window, or one of the silent
     * delayAfterSeconds gaps between rounds).
     *
     * Mirrors AlarmFiringService.startAudioSequence(): the rounds are walked in order
     * and the whole list loops until the alarm stops, with each round followed by its
     * own silent gap.
     */
    fun ringAtSecond(e: Int): IndexedValue<AlarmRing>? {
        if (!soundActiveAt(e)) return null
        val playing = effectiveRings
        val soundStartedAt = if (vibrationMode == VibrationMode.VIBRATION_THEN_SOUND) vibrationOnlySeconds else 0
        val cycleSeconds = playing.sumOf { it.durationSeconds + it.delayAfterSeconds }
        // A whole cycle of zero-length rounds can't be walked (and can't happen from
        // the sliders, whose minimum round length is 5s) — treat it as the first round.
        if (cycleSeconds <= 0) return IndexedValue(0, playing.first())
        var offset = (e - soundStartedAt) % cycleSeconds
        playing.forEachIndexed { index, ring ->
            if (offset < ring.durationSeconds) return IndexedValue(index, ring)
            offset -= ring.durationSeconds
            if (offset < ring.delayAfterSeconds) return null   // the silent gap after this round
            offset -= ring.delayAfterSeconds
        }
        return null
    }

    /**
     * The volume percentage actually coming out of the speaker [e] seconds in, or
     * null while nothing is playing.
     *
     * The ring screen's crescendo bar reads this rather than deriving its own number:
     * it used to chart volumeAtSecond(100, ...) — a hard-coded full-volume base — so
     * a 50%-volume round with a crescendo starting at 10% climbed 10→50 through the
     * speaker while the bar drew 10→100, and the bar kept climbing for minutes after
     * the sound had stopped getting louder.
     */
    fun audibleVolumeAtSecond(e: Int): Int? =
        ringAtSecond(e)?.let { volumeAtSecond(it.value.volumePercent, e) }

    private companion object {
        // bit0=Sun … bit6=Sat, matching repeatDaysBitmask.
        const val WEEKDAYS_SUN_TO_THU_MASK = 0b0011111
        const val WEEKEND_FRI_SAT_MASK     = 0b1100000
    }
}
