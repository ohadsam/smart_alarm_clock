package com.smartring.app.util

import java.util.Calendar

/** "90" -> "1 דק' 30 שנ'", "45" -> "45 שנ'", "120" -> "2 דק'". */
fun formatDurationSeconds(totalSeconds: Int): String {
    if (totalSeconds < 60) return "$totalSeconds שנ׳"
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (secs == 0) "$minutes דק׳" else "$minutes דק׳ $secs שנ׳"
}

/** Minute-granularity countdown from [nowMillis] to [targetMillis], e.g. "בעוד 3 שע' 20 דק'". */
fun formatCountdownUntil(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val totalMinutes = ((targetMillis - nowMillis).coerceAtLeast(0) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "בעוד $hours שע׳ $minutes דק׳"
        hours > 0                -> "בעוד $hours שע׳"
        minutes > 0               -> "בעוד $minutes דק׳"
        else                       -> "בעוד פחות מדקה"
    }
}

/**
 * The edit screen's "next ring" line: "היום בשעה 07:00", "מחר בשעה 07:00", or the
 * full date for anything further out.
 *
 * Pure, and taking [nowMillis], because the naive version of this lived inline in
 * AlarmEditViewModel and compared `Calendar.DAY_OF_YEAR` on its own. Two consequences,
 * neither visible in a normal week: a date exactly one year away shares its
 * day-of-year with today and was announced as "היום" — reachable straight from the
 * specific-dates feature, which exists for birthdays and anniversaries — and on the
 * 31st of December no date was ever "מחר", because the 1st of January is day 1, not
 * day 366.
 */
fun formatNextFireAt(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
    val today = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val tomorrow = Calendar.getInstance().apply { timeInMillis = nowMillis; add(Calendar.DAY_OF_YEAR, 1) }
    val dateStr = when {
        isSameCalendarDay(target, today)    -> "היום"
        isSameCalendarDay(target, tomorrow) -> "מחר"
        else -> "%02d/%02d/%04d".format(
            target.get(Calendar.DAY_OF_MONTH), target.get(Calendar.MONTH) + 1, target.get(Calendar.YEAR))
    }
    return "הצלצול הבא: $dateStr בשעה %02d:%02d".format(
        target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
}

/** Same calendar day means the same year *and* the same day within it. */
private fun isSameCalendarDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
