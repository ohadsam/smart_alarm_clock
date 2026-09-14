package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The selection and ordering rules every home-screen widget renders from. This logic
 * used to live inline inside SmartRingWidget.provideGlance(), where neither the JVM
 * nor the instrumented suite could reach it — the widgets were the only subsystem in
 * the app with no test coverage at all.
 *
 * Pure JVM (no Robolectric): buildUpcomingAlarms takes its two lookups as parameters,
 * so none of this needs an AlarmManager, a Glance host, or the real clock.
 */
class UpcomingAlarmsTest {

    private fun alarm(id: Long, name: String = "a", hour: Int = 7) =
        Alarm(id = id, name = name, hour = hour)

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2030, Calendar.JUNE, 1, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `orders by the real next fire time, not by time of day`() {
        // b's 06:00 is earlier in the day, but it is a's occurrence that comes first.
        val a = alarm(1, "a", hour = 23)
        val b = alarm(2, "b", hour = 6)
        val tomorrowMorning = at(23) + 7 * 3_600_000L
        val result = buildUpcomingAlarms(
            alarms = listOf(b, a),
            snoozeAt = { null },
            nextFireAt = { if (it.id == 1L) at(23) else tomorrowMorning },
        )
        assertEquals(listOf(1L, 2L), result.map { it.alarm.id })
    }

    @Test
    fun `a pending snooze wins over the regular schedule`() {
        val a = alarm(1)
        val result = buildUpcomingAlarms(
            alarms = listOf(a),
            snoozeAt = { at(7, 8) },
            nextFireAt = { at(23) },
        )
        assertEquals(1, result.size)
        assertEquals(at(7, 8), result[0].fireAt)
        assertTrue(result[0].isSnoozed)
    }

    @Test
    fun `a snoozed alarm shows the snooze time, not its configured time`() {
        // The bug this guards: the widget paired the snooze countdown ("in 8 min")
        // with Alarm.timeFormatted ("07:00"), so at 07:52 a snoozed alarm read
        // "07:00 · בעוד 8 דק׳" — two numbers that flatly contradict each other.
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val result = buildUpcomingAlarms(
                alarms = listOf(alarm(1, hour = 7)),
                snoozeAt = { at(7, 52) },
                nextFireAt = { at(23) },
            )
            assertEquals("07:52", result[0].timeText)
            assertEquals("07:00", result[0].alarm.timeFormatted)
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun `an alarm with no next fire time is dropped`() {
        val result = buildUpcomingAlarms(
            alarms = listOf(alarm(1)),
            snoozeAt = { null },
            nextFireAt = { null },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an expired recurrence is dropped even though it still has a next fire time`() {
        // nextFireAt answers unconditionally here (nextFireTime()'s "same time
        // tomorrow" fallback does exactly that in production) — the expiry check is
        // what has to keep a finished alarm off the widget.
        val expired = alarm(1).copy(
            occurrencesFired = 10,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 10),
        )
        val result = buildUpcomingAlarms(
            alarms = listOf(expired),
            snoozeAt = { null },
            nextFireAt = { at(23) },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an expired recurrence still shows while its last occurrence is snoozed`() {
        // A COUNT-limited alarm's very last ring, snoozed: the recurrence is over, but
        // the alarm really is about to go off again in a few minutes. Dropping it would
        // make it vanish from the widget right before it rings.
        val expired = alarm(1).copy(
            occurrencesFired = 10,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 10),
        )
        val result = buildUpcomingAlarms(
            alarms = listOf(expired),
            snoozeAt = { at(7, 9) },
            nextFireAt = { null },
        )
        assertEquals(1, result.size)
        assertTrue(result[0].isSnoozed)
        assertEquals(at(7, 9), result[0].fireAt)
    }

    @Test
    fun `an empty alarm list yields an empty result`() {
        assertTrue(buildUpcomingAlarms(emptyList(), { null }, { null }).isEmpty())
    }

    @Test
    fun `isSnoozed is false for an ordinary scheduled alarm`() {
        val result = buildUpcomingAlarms(listOf(alarm(1)), { null }, { at(23) })
        assertFalse(result[0].isSnoozed)
    }

    @Test
    fun `formatTimeOfDay zero-pads both halves`() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("06:05", formatTimeOfDay(at(6, 5)))
            assertEquals("00:00", formatTimeOfDay(at(0, 0)))
            assertEquals("23:59", formatTimeOfDay(at(23, 59)))
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}
