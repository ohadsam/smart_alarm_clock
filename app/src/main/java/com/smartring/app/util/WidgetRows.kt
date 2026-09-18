package com.smartring.app.util

import com.smartring.app.domain.model.Alarm

/**
 * One row of a widget's alarm list, including alarms that are switched off.
 *
 * [UpcomingAlarm] deliberately holds only alarms that have a ring coming, which is the
 * right model for "what is next". It is the wrong model for a widget that offers controls:
 * a widget that can switch an alarm off and then drops it from the list has taken away
 * the only way to switch it back on.
 *
 * [fireAt] is therefore nullable — null means "this alarm has no ring coming", either
 * because it is switched off, frozen, or its schedule has run out.
 */
data class WidgetAlarmEntry(
    val alarm: Alarm,
    val fireAt: Long?,
    val isSnoozed: Boolean,
) {
    /** Armed and counting down. Drives the row's colour and its toggle's meaning. */
    val isArmed: Boolean get() = fireAt != null

    /** The time to show: the real trigger when there is one, else the configured time. */
    val timeText: String get() = fireAt?.let { formatTimeOfDay(it) } ?: alarm.timeFormatted
}

/**
 * Every alarm, ordered the way a widget should read: what is ringing soonest first, then
 * everything that is not armed.
 *
 * Pure, with both lookups passed in, for the same reason [buildUpcomingAlarms] is — the
 * ordering rules the widgets depend on are then testable without an AlarmManager, a
 * Glance host or a device clock.
 *
 * Inactive rows keep their own hour/minute ordering rather than being interleaved by a
 * fire time they do not have. They are a separate group and reading as one is the point:
 * the top of the list answers "when does my next alarm go off", the bottom answers "what
 * else do I have set up".
 */
fun buildWidgetRows(
    alarms: List<Alarm>,
    snoozeAt: (Alarm) -> Long?,
    nextFireAt: (Alarm) -> Long?,
): List<WidgetAlarmEntry> {
    val entries = alarms.map { alarm ->
        val snooze = snoozeAt(alarm)
        when {
            // A live snooze wins over the regular schedule, and counts even for an alarm
            // whose recurrence has expired — that can be a COUNT-limited alarm's final
            // occurrence, snoozed, and dropping it would make the alarm vanish from the
            // widget minutes before it actually rings.
            snooze != null -> WidgetAlarmEntry(alarm, snooze, isSnoozed = true)
            !alarm.isActive || alarm.isRecurrenceExpired() -> WidgetAlarmEntry(alarm, null, false)
            else -> WidgetAlarmEntry(alarm, nextFireAt(alarm), isSnoozed = false)
        }
    }
    val armed = entries.filter { it.isArmed }.sortedBy { it.fireAt }
    val idle = entries.filterNot { it.isArmed }.sortedWith(
        compareBy({ it.alarm.hour }, { it.alarm.minute }),
    )
    return armed + idle
}
