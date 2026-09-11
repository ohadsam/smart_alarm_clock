package com.smartring.app.util
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.smartring.app.domain.model.*
import com.smartring.app.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
    private val widgetRefresher: WidgetRefresher,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // A snooze's real AlarmManager trigger (armed by scheduleAt()) isn't otherwise
    // recorded anywhere nextFireTime() can see, so a just-snoozed alarm would appear
    // to next fire at its regular time (e.g. tomorrow) instead of in a few minutes.
    // Keyed by alarm id; self-expiring in effectiveNextFireTime() once the timestamp
    // is in the past, so no explicit cleanup is needed once the snooze actually fires.
    private val snoozePrefs = context.getSharedPreferences("pending_snooze", Context.MODE_PRIVATE)

    fun schedule(alarm: Alarm) {
        scheduleInternal(alarm)
        widgetRefresher.refresh()
    }

    private fun scheduleInternal(alarm: Alarm) {
        if (!alarm.isActive) return
        if (alarm.isRecurrenceExpired()) return
        val t = nextFireTime(alarm) ?: return
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, buildIntent(alarm.id))
        val fmt = Calendar.getInstance().apply { timeInMillis = t }
        appLogger.log("Scheduler", "תוזמן: \"${alarm.name}\" (#${alarm.id}) ל-%02d/%02d %02d:%02d".format(
            fmt.get(Calendar.DAY_OF_MONTH), fmt.get(Calendar.MONTH) + 1,
            fmt.get(Calendar.HOUR_OF_DAY), fmt.get(Calendar.MINUTE)))
    }

    fun scheduleAt(alarm: Alarm, at: Long) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at, buildSnoozePendingIntent(alarm.id))
        snoozePrefs.edit().putLong(alarm.id.toString(), at).apply()
        appLogger.log("Scheduler", "נודניק תוזמן: \"${alarm.name}\" (#${alarm.id})")
        widgetRefresher.refresh()
    }

    fun cancel(id: Long) {
        cancelInternal(id)
        widgetRefresher.refresh()
    }

    /** Cancels every id then refreshes widgets exactly once — use this instead of a
     *  `forEach { cancel(it) }` loop when cancelling several alarms in one user
     *  action (disable/freeze-all), which would otherwise fire one near-simultaneous
     *  full widget refresh per alarm. */
    fun cancelAll(ids: List<Long>) {
        ids.forEach { cancelInternal(it) }
        appLogger.log("Scheduler", "בוטלו ${ids.size} שעמורים")
        widgetRefresher.refresh()
    }

    private fun cancelInternal(id: Long) {
        alarmManager.cancel(buildIntent(id))
        alarmManager.cancel(buildSnoozePendingIntent(id))
        snoozePrefs.edit().remove(id.toString()).apply()
        appLogger.log("Scheduler", "בוטל: שעמור #$id")
    }

    fun rescheduleAll(alarms: List<Alarm>) {
        // Uses the *Internal variants so cancelling+rescheduling N alarms triggers one
        // widget refresh total instead of up to 2N near-simultaneous ones.
        alarms.forEach { cancelInternal(it.id); if (it.isActive && !it.isRecurrenceExpired()) scheduleInternal(it) }
        appLogger.log("Scheduler", "תוזמנו מחדש ${alarms.size} שעמורים")
        widgetRefresher.refresh()
    }

    /** The real AlarmManager trigger armed by a snooze (see [snoozePrefs]), if any is
     *  still in the future — null once it has fired or none is pending. Exposed
     *  separately from [effectiveNextFireTime] so callers that need to show an alarm
     *  even when its regular recurrence has expired (a COUNT-limited alarm's very
     *  last, now-snoozed occurrence) can check this first. */
    fun pendingSnoozeUntil(alarm: Alarm): Long? =
        snoozePrefs.getLong(alarm.id.toString(), -1L).takeIf { it > System.currentTimeMillis() }

    /** [nextFireTime] plus awareness of an in-flight snooze — see [snoozePrefs]. Used
     *  anywhere "when does this alarm next ring" is shown to the user (widgets, the
     *  edit screen's next-fire hint), so a just-snoozed alarm doesn't show its regular
     *  schedule while it's actually about to re-ring in a few minutes. */
    fun effectiveNextFireTime(alarm: Alarm): Long? =
        listOfNotNull(pendingSnoozeUntil(alarm), nextFireTime(alarm)).minOrNull()

    fun nextFireTime(alarm: Alarm): Long? {
        val now = System.currentTimeMillis()

        // 1. Exact datetime
        alarm.specificDateTime?.let { dt ->
            return if (dt > now) dt else null
        }

        // 2. Specific dates list
        alarm.specificDates
            .map { localDateTimeFor(it.date, alarm.hour, alarm.minute) }
            .filter { it > now }
            .minOrNull()?.let { return it }

        // 3. Repeat by weekday mask
        if (alarm.repeatDaysBitmask != 0) {
            val base = nextFromMask(alarm.hour, alarm.minute, alarm.repeatDaysBitmask, now)
                ?: return null
            return when (alarm.repeatFrequency) {
                RepeatFrequency.NONE     -> base
                RepeatFrequency.WEEKLY   -> base
                RepeatFrequency.BIWEEKLY -> {
                    // Parity is derived from the candidate fire date itself (days since
                    // epoch / 7), not from "now" vs. "base" via Calendar.WEEK_OF_YEAR:
                    // WEEK_OF_YEAR resets every January and Kotlin's `%` keeps the
                    // dividend's sign, so a now/base pair straddling a year boundary
                    // used to silently break the every-other-week cadence. An absolute,
                    // now-independent parity check keeps the cadence stable regardless
                    // of when this is (re)computed.
                    if (weekParity(base) == 0L) base else base + 7 * 24 * 3_600_000L
                }
                RepeatFrequency.MONTHLY -> {
                    // "Monthly" = only the first matching weekday in each calendar
                    // month fires. The previous now-vs-base month comparison degraded
                    // to weekly in the common case (base is always within ~2 weeks of
                    // now, so it was almost always "still this month").
                    var probe = firstMatchInMonth(alarm.hour, alarm.minute, alarm.repeatDaysBitmask, base)
                    if (probe <= now) {
                        val nextMonth = Calendar.getInstance().apply {
                            timeInMillis = probe
                            set(Calendar.DAY_OF_MONTH, 1)
                            add(Calendar.MONTH, 1)
                        }.timeInMillis
                        probe = firstMatchInMonth(alarm.hour, alarm.minute, alarm.repeatDaysBitmask, nextMonth)
                    }
                    probe
                }
            }
        }

        // 4. Simple time-of-day (one-time)
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE,      alarm.minute)
            set(Calendar.SECOND,      0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    private fun nextFromMask(h: Int, m: Int, mask: Int, now: Long): Long? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(14) {
            if ((mask shr (cal.get(Calendar.DAY_OF_WEEK) - 1)) and 1 == 1 && cal.timeInMillis > now)
                return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun weekParity(millis: Long): Long =
        Math.floorDiv(TimeUnit.MILLISECONDS.toDays(millis), 7L) % 2L

    /** Earliest day within [from]'s calendar month whose weekday matches [mask], at [h]:[m]. */
    private fun firstMatchInMonth(h: Int, m: Int, mask: Int, from: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val month = cal.get(Calendar.MONTH)
        while (cal.get(Calendar.MONTH) == month) {
            if ((mask shr (cal.get(Calendar.DAY_OF_WEEK) - 1)) and 1 == 1) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return from
    }

    /**
     * [utcMidnightMillis] is a date picked via Compose's DatePicker, reported as UTC
     * midnight of the chosen day. Re-anchor its year/month/day onto a device-local
     * calendar before applying [h]:[m] — combining the raw UTC millis with a local
     * time-of-day offset would shift the fire date by the device's UTC offset.
     */
    private fun localDateTimeFor(utcMidnightMillis: Long, h: Int, m: Int): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnightMillis }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun buildIntent(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildSnoozePendingIntent(id: Long) = PendingIntent.getBroadcast(
        context, (id + 100_000).toInt(),
        Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            .putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
