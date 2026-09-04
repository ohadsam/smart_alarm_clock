package com.smartring.app.data.repository
import com.smartring.app.data.db.*
import com.smartring.app.domain.model.*

// ── Entity → Domain ───────────────────────────────────────────────

internal fun AlarmWithDetails.toDomain() = Alarm(
    id                   = alarm.id,
    name                 = alarm.name,
    hour                 = alarm.hour,
    minute               = alarm.minute,
    specificDateTime     = alarm.specificDateTime,
    isEnabled            = alarm.isEnabled,
    isFrozen             = alarm.isFrozen,
    repeatDaysBitmask    = alarm.repeatDaysBitmask,
    repeatFrequency      = RepeatFrequency.valueOf(alarm.repeatFrequency),
    recurrenceEnd        = RecurrenceEnd(
        type        = RecurrenceEndType.valueOf(alarm.recurrenceEndType),
        untilDate   = alarm.recurrenceUntilDate,
        count       = alarm.recurrenceCount,
    ),
    occurrencesFired     = alarm.occurrencesFired,
    specificDates        = dates.map { it.toDomain() },
    ringDurationSeconds  = alarm.ringDurationSeconds,
    rings                = rings.sortedBy { it.orderIndex }.map { it.toDomain() },
    snoozeMinutes        = alarm.snoozeMinutes,
    snoozeMaxCount       = alarm.snoozeMaxCount,
    reminderText         = alarm.reminderText,
    vibrationMode        = VibrationMode.valueOf(alarm.vibrationMode),
    vibrationOnlySeconds = alarm.vibrationOnlySeconds,
    crescendoEnabled     = alarm.crescendoEnabled,
    crescendoStartVolume = alarm.crescendoStartVolume,
    crescendoStepSeconds = alarm.crescendoStepSeconds,
    crescendoStepPercent = alarm.crescendoStepPercent,
)

internal fun AlarmRingEntity.toDomain() = AlarmRing(id, alarmId, orderIndex, durationSeconds, volumePercent, ringtoneUri, delayAfterSeconds)
internal fun AlarmDateEntity.toDomain() = AlarmDate(id, alarmId, date, label)
internal fun AlarmLogEntity.toDomain()  = AlarmLog(id, alarmId ?: 0L, alarmName, firedAt, scheduledFor, action)

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
    snoozeMinutes        = snoozeMinutes,
    snoozeMaxCount       = snoozeMaxCount,
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
