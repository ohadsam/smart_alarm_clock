package com.smartring.app.util

import com.smartring.app.domain.model.Alarm
import java.util.Calendar

/**
 * How the main list is grouped, and how it is searched.
 *
 * A flat list ordered by id answers "what alarms exist". The question people actually
 * arrive with is "what is going to wake me, and when" — and that is a question about
 * *time*, which a flat list makes the reader reconstruct row by row. Grouping answers it
 * at a glance: everything under "היום" rings before the user next sleeps.
 *
 * Pure, taking `now` as a parameter, because every interesting case here is a boundary —
 * an alarm at 23:59, one at 00:01 tomorrow, one exactly seven days out — and boundaries
 * tested against the real clock are boundaries tested on whatever day CI happens to run.
 */

enum class AlarmSection { TODAY, TOMORROW, THIS_WEEK, LATER, OFF }

/** The Hebrew heading for a section. */
fun sectionTitle(section: AlarmSection): String = when (section) {
    AlarmSection.TODAY     -> "היום"
    AlarmSection.TOMORROW  -> "מחר"
    AlarmSection.THIS_WEEK -> "השבוע"
    AlarmSection.LATER     -> "בהמשך"
    AlarmSection.OFF       -> "כבויים"
}

/** An alarm together with the moment it will actually next ring, if it will. */
data class AlarmRow(val alarm: Alarm, val nextFireAt: Long?)

data class AlarmGroup(val section: AlarmSection, val rows: List<AlarmRow>) {
    val title: String get() = sectionTitle(section)
}

/**
 * Which group a row belongs in.
 *
 * `isActive` (enabled and unfrozen) and a non-null next ring are one condition, not two:
 * an alarm that is switched on but whose schedule has run out is, to the user, exactly as
 * silent as one they switched off — and putting it under "היום" because its hour field
 * still says 07:00 would be the list stating something untrue.
 */
fun sectionFor(row: AlarmRow, nowMillis: Long = System.currentTimeMillis()): AlarmSection {
    val at = row.nextFireAt
    if (!row.alarm.isActive || at == null) return AlarmSection.OFF
    val days = calendarDaysBetween(nowMillis, at)
    return when {
        days <= 0L -> AlarmSection.TODAY
        days == 1L -> AlarmSection.TOMORROW
        days <= 7L -> AlarmSection.THIS_WEEK
        else       -> AlarmSection.LATER
    }
}

/**
 * Whole calendar days from [fromMillis] to [toMillis], counting date changes rather than
 * elapsed hours.
 *
 * The difference matters at both ends of the day and is the whole reason this is not
 * `(to - from) / 86_400_000`: at 23:30, an alarm forty minutes away is *tomorrow*, and at
 * 00:30 one twenty-two hours away is still *today*. Elapsed-hours arithmetic gets both
 * backwards, which is precisely when someone is most likely to be checking.
 *
 * Also why this walks the calendar rather than dividing: days are not all 24 hours long.
 * Israel's DST transitions make one 23 and one 25, so a fixed divisor drifts a whole day
 * around each changeover.
 */
private fun calendarDaysBetween(fromMillis: Long, toMillis: Long): Long {
    val from = startOfDay(fromMillis)
    val to = startOfDay(toMillis)
    if (to <= from) return 0L
    val cal = Calendar.getInstance().apply { timeInMillis = from }
    var days = 0L
    // Bounded: anything past a year is "later" regardless, and an unbounded loop here
    // would turn a far-future date into a hang rather than a wrong heading.
    while (cal.timeInMillis < to && days < 366L) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
        days++
    }
    return days
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The grouped list, in reading order, with empty groups left out.
 *
 * Armed groups come first and in time order; "כבויים" is last because it is the one group
 * nobody is scanning for when they open the app at night. Inside an armed group rows are
 * ordered by when they ring; inside "כבויים" there is no ring to order by, so they fall
 * back to their configured time of day, which is at least stable between renders.
 */
fun buildAlarmGroups(
    rows: List<AlarmRow>,
    nowMillis: Long = System.currentTimeMillis(),
): List<AlarmGroup> {
    val bySection = rows.groupBy { sectionFor(it, nowMillis) }
    return AlarmSection.entries.mapNotNull { section ->
        val group = bySection[section]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val sorted = if (section == AlarmSection.OFF) {
            group.sortedWith(compareBy({ it.alarm.hour }, { it.alarm.minute }, { it.alarm.id }))
        } else {
            group.sortedWith(compareBy({ it.nextFireAt }, { it.alarm.id }))
        }
        AlarmGroup(section, sorted)
    }
}

/**
 * Past this many alarms, the list gets a search field.
 *
 * Below it, searching is slower than looking: a field that costs a tap and a keyboard to
 * filter eight rows visible on one screen is a control that makes the screen worse. This
 * is the point at which scrolling starts to cost more than typing.
 */
const val SEARCH_VISIBLE_FROM = 10

/**
 * Name search, trimmed and case-insensitive.
 *
 * Matches anywhere in the name rather than only at the start: people remember a word from
 * the middle of "תרופה של אבא בערב" far more reliably than its first letter. A blank
 * query returns everything rather than nothing, so clearing the field restores the list
 * instead of emptying it.
 */
fun filterAlarms(rows: List<AlarmRow>, query: String): List<AlarmRow> {
    val q = query.trim()
    if (q.isEmpty()) return rows
    return rows.filter { it.alarm.name.contains(q, ignoreCase = true) }
}
