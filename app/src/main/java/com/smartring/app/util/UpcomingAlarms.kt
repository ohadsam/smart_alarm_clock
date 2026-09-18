package com.smartring.app.util

import java.util.Calendar

/**
 * Time-of-day formatting shared by the widgets.
 *
 * This file used to also hold `UpcomingAlarm` and `buildUpcomingAlarms`, which selected
 * only the alarms with a ring coming. [buildWidgetRows] in WidgetRows.kt supersedes them:
 * it is the same rule plus the idle alarms, which the widgets need in order to offer
 * switching one back on. Two selection rules living side by side is how they drift apart,
 * so the narrower one is gone rather than kept "just in case".
 */
/** Device-local "HH:mm" for an epoch-millis instant. */
fun formatTimeOfDay(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
