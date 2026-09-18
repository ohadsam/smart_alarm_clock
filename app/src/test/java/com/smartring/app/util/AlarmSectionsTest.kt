package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The list's grouping rule.
 *
 * Every case that matters here is a boundary — late at night, early in the morning,
 * exactly a week out — so `now` is injected rather than read from the clock. Grouping
 * tested against the real clock is grouping tested on whatever day CI happens to run.
 */
class AlarmSectionsTest {

    @Before
    fun fixTimeZone() {
        // Wall-clock arithmetic: a rule that passes in UTC and fails at a real offset is
        // a bug the runner's default would hide.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun row(fireAt: Long?, id: Long = 1, enabled: Boolean = true, frozen: Boolean = false) =
        AlarmRow(Alarm(id = id, name = "a", isEnabled = enabled, isFrozen = frozen), fireAt)

    // ── Which group ────────────────────────────────────────────────────────

    @Test
    fun `a ring later the same day is today`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.TODAY, sectionFor(row(at(2026, 9, 18, 22, 0)), now))
    }

    /**
     * The case elapsed-hours arithmetic gets backwards, and the hour someone is most
     * likely to be checking: at 23:30 an alarm forty minutes away is tomorrow.
     */
    @Test
    fun `forty minutes away at half past eleven at night is tomorrow, not today`() {
        val now = at(2026, 9, 18, 23, 30)
        assertEquals(AlarmSection.TOMORROW, sectionFor(row(at(2026, 9, 19, 0, 10)), now))
    }

    /** The mirror image: at 00:30, a ring twenty-two hours away is still today. */
    @Test
    fun `twenty-two hours away at half past midnight is still today`() {
        val now = at(2026, 9, 18, 0, 30)
        assertEquals(AlarmSection.TODAY, sectionFor(row(at(2026, 9, 18, 22, 30)), now))
    }

    @Test
    fun `three days out is this week`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.THIS_WEEK, sectionFor(row(at(2026, 9, 21, 7, 0)), now))
    }

    @Test
    fun `exactly seven days out is still this week, eight is later`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.THIS_WEEK, sectionFor(row(at(2026, 9, 25, 7, 0)), now))
        assertEquals(AlarmSection.LATER, sectionFor(row(at(2026, 9, 26, 7, 0)), now))
    }

    @Test
    fun `a ring months away is later, not a hang`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.LATER, sectionFor(row(at(2028, 1, 1, 7, 0)), now))
    }

    // ── "Off" is one condition, not two ────────────────────────────────────

    @Test
    fun `a disabled alarm is off`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.OFF,
            sectionFor(row(at(2026, 9, 18, 22, 0), enabled = false), now))
    }

    @Test
    fun `a frozen alarm is off`() {
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(AlarmSection.OFF,
            sectionFor(row(at(2026, 9, 18, 22, 0), frozen = true), now))
    }

    /**
     * Switched on but out of occurrences. To the user this is exactly as silent as one
     * they switched off, and filing it under "היום" because its hour field still reads
     * 07:00 would be the list asserting something untrue.
     */
    @Test
    fun `an enabled alarm with no next ring is off`() {
        assertEquals(AlarmSection.OFF, sectionFor(row(null), at(2026, 9, 18, 9, 0)))
    }

    // ── The grouped list ───────────────────────────────────────────────────

    @Test
    fun `groups come out in reading order with off last`() {
        val now = at(2026, 9, 18, 9, 0)
        val groups = buildAlarmGroups(
            listOf(
                row(null, id = 4),
                row(at(2026, 9, 26, 7, 0), id = 3),
                row(at(2026, 9, 19, 7, 0), id = 2),
                row(at(2026, 9, 18, 22, 0), id = 1),
            ),
            now,
        )
        assertEquals(
            listOf(AlarmSection.TODAY, AlarmSection.TOMORROW, AlarmSection.LATER, AlarmSection.OFF),
            groups.map { it.section },
        )
    }

    @Test
    fun `empty groups are left out entirely`() {
        val now = at(2026, 9, 18, 9, 0)
        val groups = buildAlarmGroups(listOf(row(at(2026, 9, 18, 22, 0))), now)
        assertEquals(1, groups.size)
        assertEquals(AlarmSection.TODAY, groups.single().section)
    }

    @Test
    fun `an empty list produces no groups at all`() =
        assertEquals(emptyList<AlarmGroup>(), buildAlarmGroups(emptyList(), at(2026, 9, 18, 9, 0)))

    @Test
    fun `rows inside an armed group are ordered by when they ring`() {
        val now = at(2026, 9, 18, 9, 0)
        val groups = buildAlarmGroups(
            listOf(
                row(at(2026, 9, 18, 22, 0), id = 1),
                row(at(2026, 9, 18, 10, 0), id = 2),
                row(at(2026, 9, 18, 14, 0), id = 3),
            ),
            now,
        )
        assertEquals(listOf(2L, 3L, 1L), groups.single().rows.map { it.alarm.id })
    }

    /** No ring to order by, so the configured time of day — stable between renders. */
    @Test
    fun `off rows fall back to their configured time of day`() {
        val groups = buildAlarmGroups(
            listOf(
                AlarmRow(Alarm(id = 1, hour = 9, minute = 0, isEnabled = false), null),
                AlarmRow(Alarm(id = 2, hour = 6, minute = 30, isEnabled = false), null),
                AlarmRow(Alarm(id = 3, hour = 6, minute = 0, isEnabled = false), null),
            ),
            at(2026, 9, 18, 9, 0),
        )
        assertEquals(listOf(3L, 2L, 1L), groups.single().rows.map { it.alarm.id })
    }

    @Test
    fun `every alarm lands in exactly one group`() {
        val now = at(2026, 9, 18, 9, 0)
        val rows = listOf(
            row(at(2026, 9, 18, 22, 0), id = 1),
            row(at(2026, 9, 19, 7, 0), id = 2),
            row(at(2026, 9, 22, 7, 0), id = 3),
            row(at(2027, 1, 1, 7, 0), id = 4),
            row(null, id = 5),
        )
        val grouped = buildAlarmGroups(rows, now).flatMap { it.rows }
        assertEquals(rows.size, grouped.size)
        assertEquals(rows.map { it.alarm.id }.toSet(), grouped.map { it.alarm.id }.toSet())
    }

    // ── Search ─────────────────────────────────────────────────────────────

    @Test
    fun `a blank query returns everything rather than nothing`() {
        val rows = listOf(row(null, id = 1), row(null, id = 2))
        assertEquals(rows, filterAlarms(rows, ""))
        assertEquals(rows, filterAlarms(rows, "   "))
    }

    /** People remember a word from the middle of a name far better than its first letter. */
    @Test
    fun `a query matches anywhere in the name`() {
        val rows = listOf(
            AlarmRow(Alarm(id = 1, name = "תרופה של אבא בערב"), null),
            AlarmRow(Alarm(id = 2, name = "בוקר"), null),
        )
        assertEquals(listOf(1L), filterAlarms(rows, "אבא").map { it.alarm.id })
    }

    @Test
    fun `a query ignores case and surrounding spaces`() {
        val rows = listOf(AlarmRow(Alarm(id = 1, name = "Gym"), null))
        assertEquals(1, filterAlarms(rows, "  gYm  ").size)
    }

    @Test
    fun `a query that matches nothing returns nothing`() =
        assertEquals(emptyList<AlarmRow>(), filterAlarms(listOf(row(null)), "אין כזה"))

    /** Below the threshold a search field costs more than it saves. */
    @Test
    fun `search appears only once the list is long enough to need it`() =
        assertTrue(SEARCH_VISIBLE_FROM in 8..15)
}
