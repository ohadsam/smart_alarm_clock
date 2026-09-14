package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import java.util.Calendar

/**
 * One alarm paired with the moment it will actually ring next.
 *
 * [fireAt] is the real trigger timestamp, which is *not* always the alarm's own
 * hour/minute: a snoozed alarm rings a few minutes from now, and a specific-datetime
 * alarm rings at that datetime's time of day. [timeText] is derived from it rather
 * than from `Alarm.timeFormatted` so the time a widget shows and the countdown next
 * to it can never disagree — previously a snoozed 07:00 alarm read "07:00 · בעוד 8
 * דק׳" at 07:52, which looks like a plain bug to anyone glancing at their home screen.
 */
data class UpcomingAlarm(
    val alarm: Alarm,
    val fireAt: Long,
    val isSnoozed: Boolean,
) {
    val timeText: String get() = formatTimeOfDay(fireAt)
}

/** Device-local "HH:mm" for an epoch-millis instant. */
fun formatTimeOfDay(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/**
 * The alarms that still have a ring coming, soonest first.
 *
 * Pure (the two lookups are passed in rather than reached for) so the selection and
 * ordering rules the widgets depend on are unit-testable without an AlarmManager, a
 * Glance host, or a device clock — this used to live inline in SmartRingWidget.kt,
 * where none of it could be covered by either test suite.
 *
 * A still-armed snooze wins over the regular schedule and counts even once the
 * recurrence has expired: that can be a COUNT-limited alarm's very last occurrence,
 * snoozed, and dropping it would make the alarm vanish from the widget minutes before
 * it actually rings.
 */
fun buildUpcomingAlarms(
    alarms: List<Alarm>,
    snoozeAt: (Alarm) -> Long?,
    nextFireAt: (Alarm) -> Long?,
): List<UpcomingAlarm> =
    alarms.mapNotNull { alarm ->
        val snooze = snoozeAt(alarm)
        when {
            snooze != null            -> UpcomingAlarm(alarm, snooze, isSnoozed = true)
            alarm.isRecurrenceExpired() -> null
            else -> nextFireAt(alarm)?.let { UpcomingAlarm(alarm, it, isSnoozed = false) }
        }
    }.sortedBy { it.fireAt }
