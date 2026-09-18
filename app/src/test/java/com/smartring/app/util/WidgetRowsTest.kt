package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Pure JVM (no Robolectric): buildWidgetRows takes its two lookups as parameters, so
 * none of this needs an AlarmManager, a Glance host, or the real clock.
 *
 * Ported from an earlier `buildUpcomingAlarms`, which selected only alarms with a ring
 * coming. That was the right model for "what is next" and the wrong one for a widget with
 * per-row controls: a widget that hides disabled alarms can switch one off and then offers
 * no way to switch it back on. So an alarm with no next ring is no longer *dropped* — it
 * sorts below the armed ones with a null `fireAt`, and the assertions here say that
 * instead. Keeping two selection rules side by side is how they drift apart, which this
 * codebase has already been bitten by once (see AlarmFiringService.startAudioSequence).
 */
class WidgetRowsTest {

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
        val result = buildWidgetRows(
            alarms = listOf(b, a),
            snoozeAt = { null },
            nextFireAt = { if (it.id == 1L) at(23) else tomorrowMorning },
        )
        assertEquals(listOf(1L, 2L), result.map { it.alarm.id })
    }

    @Test
    fun `a pending snooze wins over the regular schedule`() {
        val a = alarm(1)
        val result = buildWidgetRows(
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
            val result = buildWidgetRows(
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
    fun `an alarm with no next fire time is listed but not armed`() {
        val result = buildWidgetRows(
            alarms = listOf(alarm(1)),
            snoozeAt = { null },
            nextFireAt = { null },
        )
        // Listed, so the widget can offer switching it on; not armed, so it does not
        // claim a countdown it does not have.
        assertEquals(1, result.size)
        assertFalse(result[0].isArmed)
        assertNull(result[0].fireAt)
    }

    @Test
    fun `an expired recurrence is not armed even though it still has a next fire time`() {
        // nextFireAt answers unconditionally here (nextFireTime()'s "same time
        // tomorrow" fallback does exactly that in production) — the expiry check is
        // what has to keep a finished alarm from showing a countdown.
        val expired = alarm(1).copy(
            occurrencesFired = 10,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 10),
        )
        val result = buildWidgetRows(
            alarms = listOf(expired),
            snoozeAt = { null },
            nextFireAt = { at(23) },
        )
        assertEquals(1, result.size)
        assertFalse(result[0].isArmed)
    }

    @Test
    fun `a disabled alarm is listed but not armed`() {
        val result = buildWidgetRows(
            alarms = listOf(alarm(1).copy(isEnabled = false)),
            snoozeAt = { null },
            nextFireAt = { at(7) },
        )
        assertEquals(1, result.size)
        assertFalse("a disabled alarm must never show a countdown", result[0].isArmed)
    }

    @Test
    fun `a frozen alarm is listed but not armed`() {
        val result = buildWidgetRows(
            alarms = listOf(alarm(1).copy(isFrozen = true)),
            snoozeAt = { null },
            nextFireAt = { at(7) },
        )
        assertEquals(1, result.size)
        assertFalse(result[0].isArmed)
    }

    /** Armed alarms first by fire time; everything idle below, by its own time of day. */
    @Test
    fun `armed alarms sort above idle ones`() {
        val armed = alarm(1, "armed", hour = 23)
        val offEarly = alarm(2, "off-early", hour = 5).copy(isEnabled = false)
        val offLate = alarm(3, "off-late", hour = 9).copy(isEnabled = false)

        val result = buildWidgetRows(
            alarms = listOf(offLate, offEarly, armed),
            snoozeAt = { null },
            nextFireAt = { if (it.id == 1L) at(23) else at(1) },
        )

        assertEquals(listOf("armed", "off-early", "off-late"), result.map { it.alarm.name })
    }

    @Test
    fun `an idle row shows the alarm's configured time rather than nothing`() {
        val result = buildWidgetRows(
            alarms = listOf(alarm(1, hour = 6).copy(minute = 5, isEnabled = false)),
            snoozeAt = { null },
            nextFireAt = { null },
        )
        assertEquals("06:05", result[0].timeText)
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
        val result = buildWidgetRows(
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
        assertTrue(buildWidgetRows(emptyList(), { null }, { null }).isEmpty())
    }

    @Test
    fun `isSnoozed is false for an ordinary scheduled alarm`() {
        val result = buildWidgetRows(listOf(alarm(1)), { null }, { at(23) })
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
