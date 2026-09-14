package com.smartring.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The single place that converts between the two date conventions this app deals in.
 * Getting them confused has now caused four separate bugs, so they are spelled out:
 *
 *  - **A picked calendar day** — what Compose's `DatePicker` hands back, and what
 *    `AlarmDate.date` stores: midnight *UTC* of the chosen day. It names a day, not an
 *    instant, and must never be read with a device-local calendar or formatter.
 *  - **A real instant** — `Alarm.specificDateTime` and `RecurrenceEnd.untilDate`: an
 *    actual moment on the device's local clock.
 *
 * Mixing them shifts the date by the device's UTC offset, which is invisible in
 * Israel (a positive offset, where the error stays inside the same day) and off by a
 * full day anywhere west of Greenwich — so it survives casual testing here.
 */

/**
 * The local instant at [hour]:[minute] on the calendar day [utcMidnightMillis] names.
 * Re-anchors the day onto a device-local calendar rather than adding a local
 * time-of-day offset to a UTC instant, which would shift the fire date.
 */
fun localInstantOnPickedDay(utcMidnightMillis: Long, hour: Int, minute: Int): Long {
    val utc = utcCalendarOf(utcMidnightMillis)
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * 23:59:59.999 device-local on the calendar day [utcMidnightMillis] names — the end of
 * that day, used for "repeat until <date>".
 *
 * Storing the raw picked value instead made a recurrence set to run "until the 20th"
 * expire during the small hours of the 20th (UTC midnight is 02:00/03:00 in Israel)
 * and skip that morning's ring, a day earlier than asked for.
 */
fun endOfPickedDay(utcMidnightMillis: Long): Long {
    val utc = utcCalendarOf(utcMidnightMillis)
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}

/**
 * Formats a *picked calendar day* for display.
 *
 * Deliberately formats in UTC: the value is midnight UTC, so a device-local formatter
 * renders the previous day for any negative UTC offset. That is what the
 * specific-dates list used to do — showing "19/06" for a date the alarm would
 * correctly ring on the 20th.
 */
fun formatPickedDay(utcMidnightMillis: Long, pattern: String = "dd/MM/yyyy"): String =
    SimpleDateFormat(pattern, Locale.getDefault())
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(utcMidnightMillis))

/**
 * Turns a stored *local instant* back into the picked-day value a `DatePicker` expects
 * for `initialSelectedDateMillis`, which it interprets as UTC.
 *
 * Without this, reopening the "repeat until" picker on a stored 23:59:59.999 local
 * value landed on the following day for any negative UTC offset.
 */
fun localInstantToPickedDay(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun utcCalendarOf(millis: Long): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
