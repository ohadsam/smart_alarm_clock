package com.smartring.app.data.repository
import com.smartring.app.data.db.*
import com.smartring.app.domain.model.*

// ── Entity → Domain ───────────────────────────────────────────────

/**
 * Enum columns are stored as their `name`, and [Enum.valueOf] throws on anything it
 * doesn't recognise. That throw happens *while mapping the whole list*, so a single
 * unreadable row — a value written by a newer build the user downgraded from, a
 * hand-edited or partially-restored database — would take the entire alarm list down
 * rather than degrading that one alarm. Falling back to the same default the entity
 * declares keeps the row usable and the list intact.
 */
private inline fun <reified T : Enum<T>> enumOrDefault(stored: String, default: T): T =
    runCatching { enumValueOf<T>(stored) }.getOrDefault(default)

internal fun AlarmWithDetails.toDomain() = Alarm(
    id                   = alarm.id,
    name                 = alarm.name,
    hour                 = alarm.hour,
    minute               = alarm.minute,
    specificDateTime     = alarm.specificDateTime,
    isEnabled            = alarm.isEnabled,
    isFrozen             = alarm.isFrozen,
    repeatDaysBitmask    = alarm.repeatDaysBitmask,
    repeatFrequency      = enumOrDefault(alarm.repeatFrequency, RepeatFrequency.WEEKLY),
    recurrenceEnd        = RecurrenceEnd(
        type        = enumOrDefault(alarm.recurrenceEndType, RecurrenceEndType.FOREVER),
        untilDate   = alarm.recurrenceUntilDate,
        count       = alarm.recurrenceCount,
    ),
    occurrencesFired     = alarm.occurrencesFired,
    specificDates        = dates.map { it.toDomain() },
    ringDurationSeconds  = alarm.ringDurationSeconds,
    rings                = rings.sortedBy { it.orderIndex }.map { it.toDomain() },
    snoozeEnabled        = alarm.snoozeEnabled,
    snoozeMinutes        = alarm.snoozeMinutes,
    snoozeMaxCount       = alarm.snoozeMaxCount,
    isShabbatMode        = alarm.isShabbatMode,
    reminderText         = alarm.reminderText,
    vibrationMode        = enumOrDefault(alarm.vibrationMode, VibrationMode.SOUND_AND_VIBRATION),
    vibrationOnlySeconds = alarm.vibrationOnlySeconds,
    crescendoEnabled     = alarm.crescendoEnabled,
    crescendoStartVolume = alarm.crescendoStartVolume,
    crescendoStepSeconds = alarm.crescendoStepSeconds,
    crescendoStepPercent = alarm.crescendoStepPercent,
)

internal fun AlarmRingEntity.toDomain() = AlarmRing(id, alarmId, orderIndex, durationSeconds, volumePercent, ringtoneUri, delayAfterSeconds)
internal fun AlarmDateEntity.toDomain() = AlarmDate(id, alarmId, date, label)
internal fun AlarmLogEntity.toDomain()  = AlarmLog(id, alarmId ?: 0L, alarmName, firedAt, scheduledFor, action)
internal fun AppLogEntity.toDomain()    = AppLogEntry(id, timestamp, tag, message)

// ── Domain → Entity ───────────────────────────────────────────────

internal fun Alarm.toEntity() = AlarmEntity(
    id                   = id,
    name                 = name,
    hour                 = hour,
    minute               = minute,
    specificDateTime     = specificDateTime,
    isEnabled            = isEnabled,
    isFrozen             = isFrozen,
    repeatDaysBitmask    = repeatDaysBitmask,
    repeatFrequency      = repeatFrequency.name,
    recurrenceEndType    = recurrenceEnd.type.name,
    recurrenceUntilDate  = recurrenceEnd.untilDate,
    recurrenceCount      = recurrenceEnd.count,
    occurrencesFired     = occurrencesFired,
    ringDurationSeconds  = ringDurationSeconds,
    snoozeEnabled        = snoozeEnabled,
    snoozeMinutes        = snoozeMinutes,
    snoozeMaxCount       = snoozeMaxCount,
    isShabbatMode        = isShabbatMode,
    reminderText         = reminderText,
    vibrationMode        = vibrationMode.name,
    vibrationOnlySeconds = vibrationOnlySeconds,
    crescendoEnabled     = crescendoEnabled,
    crescendoStartVolume = crescendoStartVolume,
    crescendoStepSeconds = crescendoStepSeconds,
    crescendoStepPercent = crescendoStepPercent,
)

internal fun AlarmRing.toEntity(aId: Long) = AlarmRingEntity(id, aId, orderIndex, durationSeconds, volumePercent, ringtoneUri, delayAfterSeconds)
internal fun AlarmDate.toEntity(aId: Long) = AlarmDateEntity(id, aId, date, label)
