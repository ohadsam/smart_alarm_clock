package com.smartring.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * These conversions are the app's most repeatedly-broken area — four separate date
 * bugs so far — and the reason is always the same: a value that names a *calendar day*
 * (midnight UTC, straight from Compose's DatePicker) read as if it were an instant, or
 * the reverse.
 *
 * Every case runs under both a positive and a negative UTC offset, because that is what
 * makes this class of bug so durable here: in Israel the error stays inside the same
 * day and everything looks correct, so a display path can be wrong for months and only
 * misbehave for users west of Greenwich.
 */
class CalendarDatesTest {

    private lateinit var originalZone: TimeZone

    @Before fun setUp() { originalZone = TimeZone.getDefault() }
    @After fun tearDown() { TimeZone.setDefault(originalZone) }

    private fun withZone(id: String, body: () -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        body()
    }

    /** What Compose's DatePicker hands back for a chosen day: midnight UTC. */
    private fun pickedDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis

    private fun localFieldsOf(millis: Long): Triple<Int, Int, Int> =
        Calendar.getInstance().apply { timeInMillis = millis }.let {
            Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }

    private val zones = listOf("Asia/Jerusalem", "America/New_York", "Pacific/Kiritimati", "Etc/GMT+12")

    // ── localInstantOnPickedDay ──────────────────────────────────────────────

    @Test
    fun `lands on the chosen day at the chosen local time, in every timezone`() {
        zones.forEach { zone ->
            withZone(zone) {
                val result = localInstantOnPickedDay(pickedDay(2030, 6, 20), 7, 30)
                val (y, m, d) = localFieldsOf(result)
                assertEquals("year in $zone", 2030, y)
                assertEquals("month in $zone", 6, m)
                assertEquals("day in $zone", 20, d)
                val cal = Calendar.getInstance().apply { timeInMillis = result }
                assertEquals("hour in $zone", 7, cal.get(Calendar.HOUR_OF_DAY))
                assertEquals("minute in $zone", 30, cal.get(Calendar.MINUTE))
                assertEquals("seconds must be zeroed in $zone", 0, cal.get(Calendar.SECOND))
            }
        }
    }

    @Test
    fun `does not drift across a month boundary`() {
        withZone("America/New_York") {
            val (y, m, d) = localFieldsOf(localInstantOnPickedDay(pickedDay(2030, 3, 1), 0, 0))
            assertEquals(2030, y); assertEquals(3, m); assertEquals(1, d)
        }
    }

    // ── endOfPickedDay ───────────────────────────────────────────────────────

    @Test
    fun `end of the picked day is that day's last local millisecond`() {
        zones.forEach { zone ->
            withZone(zone) {
                val cal = Calendar.getInstance().apply { timeInMillis = endOfPickedDay(pickedDay(2030, 6, 20)) }
                assertEquals("day in $zone", 20, cal.get(Calendar.DAY_OF_MONTH))
                assertEquals("hour in $zone", 23, cal.get(Calendar.HOUR_OF_DAY))
                assertEquals("minute in $zone", 59, cal.get(Calendar.MINUTE))
                assertEquals("second in $zone", 59, cal.get(Calendar.SECOND))
                assertEquals("millis in $zone", 999, cal.get(Calendar.MILLISECOND))
            }
        }
    }

    @Test
    fun `an alarm on the chosen day still fires before the until-cutoff`() {
        // The bug this encodes: storing the picked value raw made "repeat until the
        // 20th" expire during the small hours of the 20th and skip that morning's ring.
        withZone("Asia/Jerusalem") {
            val cutoff = endOfPickedDay(pickedDay(2030, 6, 20))
            val ringThatMorning = localInstantOnPickedDay(pickedDay(2030, 6, 20), 7, 0)
            assertTrue("a 07:00 ring on the cutoff day must still be inside it", ringThatMorning < cutoff)
        }
    }

    // ── formatPickedDay ──────────────────────────────────────────────────────

    @Test
    fun `a picked day displays as itself even west of Greenwich`() {
        // The specific-dates list used to format this with a device-local formatter,
        // which renders midnight UTC as the *previous* day for any negative offset —
        // showing 19/06 for a date the alarm correctly rang on the 20th.
        zones.forEach { zone ->
            withZone(zone) {
                assertEquals("displayed date in $zone", "20/06/2030", formatPickedDay(pickedDay(2030, 6, 20)))
            }
        }
    }

    @Test
    fun `formatPickedDay honours a custom pattern`() {
        withZone("America/New_York") {
            assertEquals("2030-06-20", formatPickedDay(pickedDay(2030, 6, 20), "yyyy-MM-dd"))
        }
    }

    // ── localInstantToPickedDay ──────────────────────────────────────────────

    @Test
    fun `a stored end-of-day instant round-trips back to the same picked day`() {
        // Reopening the "repeat until" picker fed it the raw 23:59:59.999 local instant,
        // which the picker reads as UTC — landing a day late for any negative offset.
        zones.forEach { zone ->
            withZone(zone) {
                val original = pickedDay(2030, 6, 20)
                val roundTripped = localInstantToPickedDay(endOfPickedDay(original))
                assertEquals("round trip in $zone", original, roundTripped)
            }
        }
    }

    @Test
    fun `a specific datetime round-trips to the day it falls on`() {
        zones.forEach { zone ->
            withZone(zone) {
                val instant = localInstantOnPickedDay(pickedDay(2030, 1, 31), 23, 15)
                assertEquals("round trip in $zone", pickedDay(2030, 1, 31), localInstantToPickedDay(instant))
            }
        }
    }

    @Test
    fun `round trip holds at both ends of a local day`() {
        withZone("America/New_York") {
            val day = pickedDay(2030, 6, 20)
            assertEquals(day, localInstantToPickedDay(localInstantOnPickedDay(day, 0, 0)))
            assertEquals(day, localInstantToPickedDay(localInstantOnPickedDay(day, 23, 59)))
        }
    }
}
