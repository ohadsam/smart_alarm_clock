package com.smartring.app.util

import java.util.Calendar

/**
 * The short "which day" label a widget row carries beside its time.
 *
 * A widget row that reads only "07:00" answers half the question. Two rows both reading
 * 07:00 — one tomorrow, one next Thursday — are indistinguishable, and the thing someone
 * glances at a home screen to find out is precisely *when*, not merely at what o'clock.
 * The recurrence summary that used to fill this space ("כל יום", "ימי חול") describes the
 * pattern, which is a different fact and not the one being asked.
 *
 * Kept deliberately short: this sits on one line next to a name, on a widget that can be
 * two cells wide.
 */
fun widgetDayLabel(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
    val days = calendarDaysBetween(nowMillis, targetMillis)
    return when {
        days == 0L -> "היום"
        days == 1L -> "מחר"
        // Inside the coming week a weekday name is the most useful form — "יום ה׳" is
        // something you can act on without counting. Past that it stops being useful
        // ("יום ה׳" three weeks out tells you nothing) and a date is honest instead.
        days <= 6L -> hebrewWeekdayLabel(target.get(Calendar.DAY_OF_WEEK))
        else -> "%02d/%02d".format(
            target.get(Calendar.DAY_OF_MONTH), target.get(Calendar.MONTH) + 1)
    }
}

/** Saturday is simply "שבת" — "יום ש׳" is not how anyone says it. */
private fun hebrewWeekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY    -> "יום א׳"
    Calendar.MONDAY    -> "יום ב׳"
    Calendar.TUESDAY   -> "יום ג׳"
    Calendar.WEDNESDAY -> "יום ד׳"
    Calendar.THURSDAY  -> "יום ה׳"
    Calendar.FRIDAY    -> "יום ו׳"
    else               -> "שבת"
}

/**
 * Whole calendar days between two instants, counting date changes rather than elapsed
 * hours — the same rule the alarm list groups by, and for the same reason.
 *
 * At 23:30 an alarm forty minutes away is *tomorrow*, and at 00:30 one twenty-two hours
 * away is still *today*; dividing elapsed milliseconds gets both backwards, which is
 * exactly when someone is most likely to be looking at their home screen. Walking the
 * calendar rather than dividing also survives DST, where one day is 23 hours and one 25.
 *
 * Shared with `AlarmSections.kt` rather than duplicated: two copies of a day-bucketing
 * rule is how the widget and the list start disagreeing about what "היום" means, and a
 * widget contradicting the app it belongs to is worse than either being wrong alone.
 */
internal fun calendarDaysBetween(fromMillis: Long, toMillis: Long): Long {
    val from = startOfDayMillis(fromMillis)
    val to = startOfDayMillis(toMillis)
    if (to <= from) return 0L
    val cal = Calendar.getInstance().apply { timeInMillis = from }
    var days = 0L
    // Bounded: anything past a year reads the same whatever the exact count, and an
    // unbounded loop would turn a far-future date into a hang rather than a wrong label.
    while (cal.timeInMillis < to && days < 366L) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
        days++
    }
    return days
}

internal fun startOfDayMillis(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * What a widget drew, in one line for the log.
 *
 * Pure and here rather than inline in the widget, following this codebase's standing rule
 * (see `docs/ARCHITECTURE.md`): the *decision* goes in a testable function, the Android
 * component only performs it. The decision this one makes is the distinction that matters
 * most in a log — "nothing is set up" versus "things are set up and none is armed" versus
 * "here is what is armed". Conflating the first two is what made a widget that had lost
 * its data look identical to one with an empty schedule.
 */
fun widgetRenderSummary(rowCount: Int, nextTimeText: String?, nextName: String?): String = when {
    rowCount == 0 -> "אין שעמורים"
    nextTimeText == null -> "$rowCount שעמורים, אף אחד לא פעיל"
    else -> "$rowCount שעמורים, הבא $nextTimeText (\"${nextName.orEmpty()}\")"
}
