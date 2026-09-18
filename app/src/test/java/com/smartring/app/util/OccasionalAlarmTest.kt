package com.smartring.app.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ad-hoc ("מזדמן") alarm's date arithmetic.
 *
 * The one thing this must never do is produce a time in the past. The feature is a single
 * tap that is supposed to leave a working alarm behind, and a tap that silently schedules
 * something already gone is worse than no button — it would look like it worked.
 */
class OccasionalAlarmTest {

    @Before
    fun fixTimeZone() {
        // The arithmetic is wall-clock, so a test that passes in UTC and fails in a
        // half-hour offset zone would be a real bug hidden by the runner's default.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun fieldsOf(millis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return Triple(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ── "היום" ─────────────────────────────────────────────────────────────

    @Test
    fun `a time later today is accepted`() {
        val now = at(2026, 9, 18, 14, 0)
        val result = occasionalTodayAt(22, 30, now)
        assertNotNull(result)
        assertEquals(Triple(18, 22, 30), fieldsOf(result!!))
    }

    /**
     * Refused, not rolled forward to tomorrow. The user asked for today; substituting a
     * different day is how somebody gets woken on the wrong one.
     */
    @Test
    fun `a time that has already passed today is refused`() =
        assertNull(occasionalTodayAt(6, 0, at(2026, 9, 18, 14, 0)))

    @Test
    fun `the current minute counts as passed`() =
        assertNull(occasionalTodayAt(14, 0, at(2026, 9, 18, 14, 0)))

    @Test
    fun `one minute from now is accepted`() =
        assertNotNull(occasionalTodayAt(14, 1, at(2026, 9, 18, 14, 0)))

    // ── The one-tap day stepper ────────────────────────────────────────────

    @Test
    fun `stepping a today alarm moves it to tomorrow at the same time`() {
        val now = at(2026, 9, 18, 23, 0)
        val next = nextOccasionalDate(at(2026, 9, 18, 6, 30), 6, 30, now)
        assertEquals(Triple(19, 6, 30), fieldsOf(next))
    }

    /** Pressing the button twice has to reach the day after tomorrow. */
    @Test
    fun `stepping twice reaches the day after tomorrow`() {
        val now = at(2026, 9, 18, 23, 0)
        val once = nextOccasionalDate(at(2026, 9, 18, 6, 30), 6, 30, now)
        val twice = nextOccasionalDate(once, 6, 30, now)
        assertEquals(Triple(20, 6, 30), fieldsOf(twice))
    }

    /**
     * A long-abandoned alarm must not need one tap per elapsed day: it jumps to the next
     * future occurrence instead of stepping from where it was stranded.
     */
    @Test
    fun `a months-old alarm jumps straight to the next future occurrence`() {
        val now = at(2026, 9, 18, 14, 0)
        val next = nextOccasionalDate(at(2026, 3, 1, 6, 30), 6, 30, now)
        assertTrue("must be in the future", next > now)
        assertEquals("the very next 06:30 is tomorrow's", Triple(19, 6, 30), fieldsOf(next))
    }

    @Test
    fun `an alarm with no stored date steps from today`() {
        val now = at(2026, 9, 18, 14, 0)
        assertEquals(Triple(19, 7, 0), fieldsOf(nextOccasionalDate(null, 7, 0, now)))
    }

    @Test
    fun `stepping never returns a time in the past`() {
        val now = at(2026, 9, 18, 14, 0)
        // Every hour of the day, from a stale date, must land ahead of now.
        for (hour in 0..23) {
            val next = nextOccasionalDate(at(2026, 9, 1, hour, 0), hour, 0, now)
            assertTrue("hour $hour landed in the past", next > now)
        }
    }

    @Test
    fun `stepping crosses a month boundary`() {
        val now = at(2026, 9, 30, 23, 0)
        assertEquals(Triple(1, 6, 30), fieldsOf(nextOccasionalDate(at(2026, 9, 30, 6, 30), 6, 30, now)))
    }

    @Test
    fun `stepping crosses a year boundary`() {
        val now = at(2026, 12, 31, 23, 0)
        val next = nextOccasionalDate(at(2026, 12, 31, 6, 30), 6, 30, now)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2027, c.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, c.get(Calendar.MONTH))
        assertEquals(1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `the stepped time keeps seconds and millis at zero`() {
        val next = nextOccasionalDate(at(2026, 9, 18, 6, 30), 6, 30, at(2026, 9, 18, 23, 0))
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(0, c.get(Calendar.MILLISECOND))
    }

    // ── Quick-create shortcuts ─────────────────────────────────────────────

    @Test
    fun `in eight hours lands eight hours later, on a whole minute`() {
        val now = at(2026, 9, 18, 14, 30)
        val result = quickAlarmInHours(8, now)
        assertEquals(Triple(18, 22, 30), fieldsOf(result))
        val c = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(0, c.get(Calendar.MILLISECOND))
    }

    @Test
    fun `in eight hours crosses midnight into the next day`() {
        val now = at(2026, 9, 18, 20, 0)
        assertEquals(Triple(19, 4, 0), fieldsOf(quickAlarmInHours(8, now)))
    }

    /** The chip says tomorrow, so it must mean tomorrow — never today. */
    @Test
    fun `tomorrow at a time still ahead today is still tomorrow`() {
        val now = at(2026, 9, 18, 6, 0)
        assertEquals(Triple(19, 7, 0), fieldsOf(quickAlarmTomorrowAt(7, 0, now)))
    }

    @Test
    fun `tomorrow at a time already passed today is tomorrow`() {
        val now = at(2026, 9, 18, 22, 0)
        assertEquals(Triple(19, 7, 0), fieldsOf(quickAlarmTomorrowAt(7, 0, now)))
    }

    @Test
    fun `tomorrow crosses a month boundary`() {
        val now = at(2026, 9, 30, 22, 0)
        assertEquals(Triple(1, 7, 0), fieldsOf(quickAlarmTomorrowAt(7, 0, now)))
    }

    @Test
    fun `both shortcuts always produce a future time`() {
        val now = at(2026, 9, 18, 23, 59)
        assertTrue(quickAlarmInHours(1, now) > now)
        assertTrue(quickAlarmTomorrowAt(0, 1, now) > now)
    }
}
