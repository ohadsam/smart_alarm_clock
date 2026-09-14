package com.smartring.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** Pure string-formatting logic — no Android dependencies. */
class TimeFormatTest {

    // ── formatDurationSeconds ────────────────────────────────────────

    @Test
    fun `formatDurationSeconds under a minute shows seconds only`() {
        assertEquals("45 שנ׳", formatDurationSeconds(45))
        assertEquals("0 שנ׳", formatDurationSeconds(0))
        assertEquals("59 שנ׳", formatDurationSeconds(59))
    }

    @Test
    fun `formatDurationSeconds on an exact minute boundary omits seconds`() {
        assertEquals("1 דק׳", formatDurationSeconds(60))
        assertEquals("2 דק׳", formatDurationSeconds(120))
    }

    @Test
    fun `formatDurationSeconds with a remainder shows both minutes and seconds`() {
        assertEquals("1 דק׳ 30 שנ׳", formatDurationSeconds(90))
    }

    // ── formatCountdownUntil ──────────────────────────────────────────

    @Test
    fun `formatCountdownUntil under a minute away`() {
        val now = 1_000_000L
        assertEquals("בעוד פחות מדקה", formatCountdownUntil(targetMillis = now + 30_000, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil minutes only`() {
        val now = 1_000_000L
        assertEquals("בעוד 20 דק׳", formatCountdownUntil(targetMillis = now + 20 * 60_000L, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil hours only on an exact hour boundary`() {
        val now = 1_000_000L
        assertEquals("בעוד 3 שע׳", formatCountdownUntil(targetMillis = now + 3 * 3_600_000L, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil hours and minutes`() {
        val now = 1_000_000L
        val target = now + 3 * 3_600_000L + 20 * 60_000L
        assertEquals("בעוד 3 שע׳ 20 דק׳", formatCountdownUntil(targetMillis = target, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil never goes negative for a target already in the past`() {
        val now = 1_000_000L
        assertEquals("בעוד פחות מדקה", formatCountdownUntil(targetMillis = now - 60_000, nowMillis = now))
    }

    // ── formatNextFireAt ──────────────────────────────────────────

    private fun at(year: Int, month: Int, day: Int, hour: Int = 7, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `formatNextFireAt names today and tomorrow`() {
        val now = at(2030, 6, 20, 22, 0)
        assertEquals("הצלצול הבא: היום בשעה 23:30", formatNextFireAt(at(2030, 6, 20, 23, 30), now))
        assertEquals("הצלצול הבא: מחר בשעה 07:00", formatNextFireAt(at(2030, 6, 21, 7, 0), now))
    }

    @Test
    fun `formatNextFireAt spells out any other date`() {
        val now = at(2030, 6, 20, 22, 0)
        assertEquals("הצלצול הבא: 25/06/2030 בשעה 06:05", formatNextFireAt(at(2030, 6, 25, 6, 5), now))
    }

    @Test
    fun `formatNextFireAt does not call a date one year out today`() {
        // The bug this replaces: the day label came from comparing Calendar.DAY_OF_YEAR
        // alone, so the same date a year later shared the day number and was announced
        // as "היום" — and a specific date that far ahead is the normal case for the
        // birthdays the specific-dates feature exists for.
        val now = at(2030, 6, 20, 9, 0)
        assertEquals("הצלצול הבא: 20/06/2031 בשעה 09:00", formatNextFireAt(at(2031, 6, 20, 9, 0), now))
    }

    @Test
    fun `formatNextFireAt recognises tomorrow across a year boundary`() {
        // 1 January is day 1, not day 366, so adding one to today's day-of-year never
        // matched it.
        val now = at(2030, 12, 31, 23, 0)
        assertEquals("הצלצול הבא: מחר בשעה 07:00", formatNextFireAt(at(2031, 1, 1, 7, 0), now))
    }

    @Test
    fun `formatNextFireAt zero-pads the time`() {
        val now = at(2030, 6, 20, 1, 0)
        assertEquals("הצלצול הבא: היום בשעה 06:05", formatNextFireAt(at(2030, 6, 20, 6, 5), now))
    }
}

