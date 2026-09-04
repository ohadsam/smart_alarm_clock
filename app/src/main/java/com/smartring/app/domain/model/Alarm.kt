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
    val snoozeMinutes: Int              = 10,
    val snoozeMaxCount: Int             = 3,
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
    val timeFormatted: String
        get() = "%02d:%02d".format(hour, minute)

    val isActive: Boolean
        get() = isEnabled && !isFrozen

    val isRecurring: Boolean
        get() = repeatDaysBitmask != 0 && repeatFrequency != RepeatFrequency.NONE

    val isDateTimeSpecific: Boolean
        get() = specificDateTime != null

    /** Whether recurrence has ended based on end rules. */
    fun isRecurrenceExpired(): Boolean = when (recurrenceEnd.type) {
        RecurrenceEndType.FOREVER -> false
        RecurrenceEndType.UNTIL   -> recurrenceEnd.untilDate?.let {
            System.currentTimeMillis() > it } ?: false
        RecurrenceEndType.COUNT   -> occurrencesFired >= recurrenceEnd.count
    }

    fun volumeAtSecond(base: Int, elapsed: Int): Int {
        if (!crescendoEnabled) return base
        val steps = elapsed / crescendoStepSeconds
        return (crescendoStartVolume + steps * crescendoStepPercent).coerceIn(crescendoStartVolume, base)
    }

    fun soundActiveAt(e: Int) = when (vibrationMode) {
        VibrationMode.VIBRATION_ONLY        -> false
        VibrationMode.VIBRATION_THEN_SOUND  -> e >= vibrationOnlySeconds
        else                                -> true
    }

    fun vibrateActiveAt(e: Int) = vibrationMode != VibrationMode.SOUND_ONLY
}
