package com.smartring.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Pure-Kotlin logic on [Alarm] — no Android dependencies, runs as a plain JVM test. */
class AlarmTest {

    // ── volumeAtSecond() / crescendo ────────────────────────────────

    @Test
    fun `volumeAtSecond returns base volume when crescendo disabled`() {
        val alarm = Alarm(crescendoEnabled = false)
        assertEquals(80, alarm.volumeAtSecond(base = 80, elapsed = 999))
    }

    @Test
    fun `volumeAtSecond starts at crescendoStartVolume`() {
        val alarm = Alarm(
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 15, crescendoStepPercent = 10,
        )
        assertEquals(10, alarm.volumeAtSecond(base = 100, elapsed = 0))
    }

    @Test
    fun `volumeAtSecond ramps up by one step per crescendoStepSeconds`() {
        val alarm = Alarm(
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 15, crescendoStepPercent = 10,
        )
        assertEquals(10, alarm.volumeAtSecond(base = 100, elapsed = 14))
        assertEquals(20, alarm.volumeAtSecond(base = 100, elapsed = 15))
        assertEquals(20, alarm.volumeAtSecond(base = 100, elapsed = 29))
        assertEquals(30, alarm.volumeAtSecond(base = 100, elapsed = 30))
    }

    @Test
    fun `volumeAtSecond never exceeds base volume`() {
        val alarm = Alarm(
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 1, crescendoStepPercent = 50,
        )
        assertEquals(100, alarm.volumeAtSecond(base = 100, elapsed = 1_000))
    }

    @Test
    fun `volumeAtSecond clamps when crescendoStartVolume exceeds a quieter ring's base volume`() {
        // A ring configured quieter (10%) than the alarm's crescendo start (50%) must
        // not crash coerceIn(min, max) with min > max — it should clamp down to base.
        val alarm = Alarm(
            crescendoEnabled = true, crescendoStartVolume = 50,
            crescendoStepSeconds = 15, crescendoStepPercent = 10,
        )
        assertEquals(10, alarm.volumeAtSecond(base = 10, elapsed = 0))
        assertEquals(10, alarm.volumeAtSecond(base = 10, elapsed = 1_000))
    }

    @Test
    fun `volumeAtSecond does not divide by zero when crescendoStepSeconds is zero`() {
        val alarm = Alarm(
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 0, crescendoStepPercent = 10,
        )
        // Should not throw (steps treated as elapsed/1, not elapsed/0).
        assertEquals(60, alarm.volumeAtSecond(base = 100, elapsed = 5))
    }

    // ── isRecurrenceExpired() ────────────────────────────────────────

    @Test
    fun `isRecurrenceExpired is false for FOREVER`() {
        val alarm = Alarm(recurrenceEnd = RecurrenceEnd(RecurrenceEndType.FOREVER))
        assertFalse(alarm.isRecurrenceExpired())
    }

    @Test
    fun `isRecurrenceExpired is true once untilDate has passed`() {
        val past = System.currentTimeMillis() - 1_000
        val alarm = Alarm(recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = past))
        assertTrue(alarm.isRecurrenceExpired())
    }

    @Test
    fun `isRecurrenceExpired is false while untilDate is in the future`() {
        val future = System.currentTimeMillis() + 1_000_000
        val alarm = Alarm(recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = future))
        assertFalse(alarm.isRecurrenceExpired())
    }

    @Test
    fun `isRecurrenceExpired is true once occurrencesFired reaches count`() {
        val alarm = Alarm(
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 5),
            occurrencesFired = 5,
        )
        assertTrue(alarm.isRecurrenceExpired())
    }

    @Test
    fun `isRecurrenceExpired is false before occurrencesFired reaches count`() {
        val alarm = Alarm(
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 5),
            occurrencesFired = 4,
        )
        assertFalse(alarm.isRecurrenceExpired())
    }

    // ── acceptsInteraction / isActive / isRecurring ──────────────────

    @Test
    fun `acceptsInteraction is false only in Shabbat mode`() {
        assertFalse(Alarm(isShabbatMode = true).acceptsInteraction)
        assertTrue(Alarm(isShabbatMode = false).acceptsInteraction)
    }

    @Test
    fun `isActive requires enabled and not frozen`() {
        assertTrue(Alarm(isEnabled = true, isFrozen = false).isActive)
        assertFalse(Alarm(isEnabled = false, isFrozen = false).isActive)
        assertFalse(Alarm(isEnabled = true, isFrozen = true).isActive)
    }

    @Test
    fun `isRecurring requires a non-zero day mask and a non-NONE frequency`() {
        assertFalse(Alarm(repeatDaysBitmask = 0, repeatFrequency = RepeatFrequency.WEEKLY).isRecurring)
        assertFalse(Alarm(repeatDaysBitmask = 0b1, repeatFrequency = RepeatFrequency.NONE).isRecurring)
        assertTrue(Alarm(repeatDaysBitmask = 0b1, repeatFrequency = RepeatFrequency.WEEKLY).isRecurring)
    }

    // ── timeFormatted / scheduleSummary: what the alarm list and widgets show ──

    @Test
    fun `timeFormatted uses the hour and minute fields for an ordinary alarm`() {
        assertEquals("07:05", Alarm(hour = 7, minute = 5).timeFormatted)
    }

    @Test
    fun `timeFormatted follows the specific datetime, not the hour and minute fields`() {
        // A specific-datetime alarm rings at that datetime, but hour/minute are what
        // every display outside the edit screen reads — so an unsynced pair (the
        // untouched 07:00 default here) showed a time the alarm never rings at.
        val ninePmThirty = Calendar.getInstance().apply {
            set(2025, Calendar.MARCH, 12, 21, 30, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("21:30", Alarm(hour = 7, minute = 0, specificDateTime = ninePmThirty).timeFormatted)
    }

    @Test
    fun `scheduleSummary describes a one-time alarm`() {
        assertEquals("חד־פעמי", Alarm(repeatDaysBitmask = 0).scheduleSummary())
    }

    @Test
    fun `scheduleSummary describes every-day and partial weekday selections`() {
        assertEquals("כל יום", Alarm(repeatDaysBitmask = 0b1111111).scheduleSummary())
        assertEquals("ימי חול", Alarm(repeatDaysBitmask = 0b0011111).scheduleSummary())
        assertEquals("סוף שבוע", Alarm(repeatDaysBitmask = 0b1100000).scheduleSummary())
        // bit0 = Sunday, bit2 = Tuesday
        assertEquals("א׳, ג׳", Alarm(repeatDaysBitmask = 0b0000101).scheduleSummary())
    }

    @Test
    fun `scheduleSummary spells out a non-weekly cadence`() {
        assertEquals("כל יום · כל שבועיים",
            Alarm(repeatDaysBitmask = 0b1111111, repeatFrequency = RepeatFrequency.BIWEEKLY).scheduleSummary())
        assertEquals("כל יום · פעם בחודש",
            Alarm(repeatDaysBitmask = 0b1111111, repeatFrequency = RepeatFrequency.MONTHLY).scheduleSummary())
    }

    @Test
    fun `scheduleSummary counts extra specific dates when no weekday is picked`() {
        val alarm = Alarm(repeatDaysBitmask = 0, specificDates = listOf(AlarmDate(date = 1L), AlarmDate(date = 2L)))
        assertEquals("2 תאריכים", alarm.scheduleSummary())
    }
}
