package com.smartring.app.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The widget row's day label.
 *
 * Every interesting case is a boundary — late at night, early in the morning, the edge of
 * the week — so `now` is injected rather than read from the clock. A label tested against
 * the real clock is a label tested on whatever day CI happens to run.
 */
class WidgetLabelsTest {

    @Before
    fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis

    // 2026-09-19 is a Saturday.
    private val saturdayMorning = at(2026, 9, 19, 9, 0)

    @Test
    fun `later the same day is today`() =
        assertEquals("היום", widgetDayLabel(at(2026, 9, 19, 22, 0), saturdayMorning))

    @Test
    fun `the next calendar day is tomorrow`() =
        assertEquals("מחר", widgetDayLabel(at(2026, 9, 20, 7, 0), saturdayMorning))

    /**
     * The case that dividing elapsed milliseconds gets backwards, and the hour someone is
     * most likely to be looking at their home screen: at 23:30 a ring forty minutes away
     * belongs to tomorrow, not to today.
     */
    @Test
    fun `forty minutes away at half past eleven at night is tomorrow`() =
        assertEquals("מחר", widgetDayLabel(at(2026, 9, 20, 0, 10), at(2026, 9, 19, 23, 30)))

    /** The mirror image: just after midnight, a ring later that same day is still today. */
    @Test
    fun `twenty-two hours away at half past midnight is still today`() =
        assertEquals("היום", widgetDayLabel(at(2026, 9, 19, 22, 30), at(2026, 9, 19, 0, 30)))

    @Test
    fun `inside the coming week it names the weekday`() {
        assertEquals("יום ב׳", widgetDayLabel(at(2026, 9, 21, 7, 0), saturdayMorning))
        assertEquals("יום ה׳", widgetDayLabel(at(2026, 9, 24, 7, 0), saturdayMorning))
    }

    /** Saturday is "שבת" — "יום ש׳" is not how anyone says it. */
    @Test
    fun `saturday is named, not numbered`() =
        assertEquals("שבת", widgetDayLabel(at(2026, 9, 26, 7, 0), at(2026, 9, 20, 9, 0)))

    /**
     * Past the week a weekday name stops carrying information — "יום ה׳" three weeks out
     * tells you nothing — so it becomes a date instead.
     */
    @Test
    fun `beyond the week it falls back to a date`() =
        assertEquals("12/10", widgetDayLabel(at(2026, 10, 12, 7, 0), saturdayMorning))

    @Test
    fun `a far-future date is a date, not a hang`() =
        assertEquals("01/01", widgetDayLabel(at(2028, 1, 1, 7, 0), saturdayMorning))

    /**
     * The widget and the alarm list must agree about what "היום" means. They share one
     * implementation of the day arithmetic for exactly this reason; this pins that the
     * sharing is real rather than two copies that happen to match today.
     */
    @Test
    fun `the widget's day arithmetic is the same one the list groups by`() {
        val target = at(2026, 9, 20, 0, 10)
        val now = at(2026, 9, 19, 23, 30)
        assertEquals("מחר", widgetDayLabel(target, now))
        assertEquals(
            AlarmSection.TOMORROW,
            sectionFor(AlarmRow(com.smartring.app.domain.model.Alarm(id = 1), target), now),
        )
    }
}
