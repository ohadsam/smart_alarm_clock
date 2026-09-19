package com.smartring.app.util

import java.util.Calendar

/**
 * Date arithmetic for the "מזדמן" (ad-hoc) alarm — one set for a single day, then
 * re-armed for a later day with one tap when it is needed again.
 *
 * An ad-hoc alarm is not a new kind of alarm in the database: it is an ordinary one-off
 * with a `specificDateTime`, which is what [com.smartring.app.domain.model.Alarm.isOneOffDated]
 * recognises. Keeping it that way avoided a schema migration for something the existing
 * fields already describe exactly, and means an ad-hoc alarm edits, rings, snoozes and
 * duplicates like any other.
 *
 * The arithmetic lives here rather than in the ViewModel because the one thing this
 * feature must never do is land in the past: the whole point is a single tap that
 * produces a working alarm, and a tap that silently schedules an alarm for a moment
 * already gone would be worse than no button.
 */

/**
 * Today at [hour]:[minute], for the "מזדמן — היום" pick.
 *
 * Returns null when that time has already passed, so the caller can say so instead of
 * storing an alarm that cannot ring. Deliberately not rolled forward to tomorrow: the
 * user asked for *today*, and quietly giving them tomorrow is the kind of helpfulness
 * that gets somebody up at the wrong time.
 */
fun occasionalTodayAt(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long? {
    val candidate = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return candidate.takeIf { it > nowMillis }
}

/**
 * The next day this alarm should ring, one tap at a time.
 *
 * Steps forward from the alarm's own date rather than always from today, so pressing the
 * button twice gets the day after tomorrow — which is what "or the days after that, with
 * one tap" asks for. Never returns a time in the past: a date left far behind rolls up to
 * the next future occurrence of [hour]:[minute] instead of stepping one day at a time
 * from wherever it was stranded.
 */
fun nextOccasionalDate(
    currentDateTime: Long?,
    hour: Int,
    minute: Int,
    nowMillis: Long = System.currentTimeMillis(),
): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = currentDateTime ?: nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }
    // A stale alarm (finished days or months ago) would otherwise need one tap per day
    // that has passed. Bounded rather than a bare `while`: nothing should reach it, and
    // an unbounded loop over corrupt stored data would hang the UI thread.
    var guard = 0
    while (cal.timeInMillis <= nowMillis && guard < 400) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
        guard++
    }
    return cal.timeInMillis
}

/** "היום", "מחר", or a date — for the button that advances an ad-hoc alarm by a day. */
fun occasionalDayLabel(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    formatDayAndTime(targetMillis, nowMillis)
