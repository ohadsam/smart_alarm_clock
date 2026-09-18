package com.smartring.app.util
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartring.app.MainActivity
import com.smartring.app.domain.model.*
import com.smartring.app.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long a test ring waits before firing.
 *
 * Long enough to put the phone down and watch it happen, short enough that nobody
 * wanders off. Deliberately not zero: firing instantly would skip the part people
 * actually doubt — that the OS wakes the device and delivers the alarm.
 */
const val TEST_RING_DELAY_SECONDS = 5

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

    // schedule()/cancel()/cancelAll() below don't refresh widgets themselves: every
    // real call site writes to the alarms table immediately before calling them
    // (setEnabled/deleteAlarm/disableAll/freezeAll/saveAlarm/incrementOccurrences),
    // which SmartRingApp.onCreate()'s observeAlarms() collector already reacts to —
    // an explicit refresh() here on top of that just doubled every single alarm
    // mutation's widget-rebuild work for no benefit. scheduleAt() and rescheduleAll()
    // are the two exceptions that keep their own explicit refresh — see their comments.
    /**
     * Arms this alarm's next occurrence — or, when there is no occurrence left to arm,
     * makes sure the previous one is gone.
     *
     * That second half matters: this used to just `return` in all three of the "can't
     * arm" cases below, which quietly left whatever was armed before still armed.
     * Editing an alarm into a configuration that never fires again (switching it to a
     * specific date in the past, cutting a recurrence short, freezing it) therefore
     * still rang at the *old* time, because nothing ever cancelled the PendingIntent
     * the previous save had registered. Cancelling here rather than at each call site
     * keeps it true for every caller — the edit screen, the list toggles, the boot
     * reschedule and the firing service all go through this one function.
     */
    fun schedule(alarm: Alarm) {
        val t = when {
            !alarm.isActive            -> null
            alarm.isRecurrenceExpired() -> null
            else                        -> nextFireTime(alarm)
        }
        if (t == null) {
            cancelQuiet(alarm.id)
            appLogger.log("Scheduler",
                "אין מועד צלצול הבא ל-\"${alarm.name}\" (#${alarm.id}) — תזמון קודם (אם היה) בוטל")
            return
        }
        armExact(t, buildIntent(alarm.id), "\"${alarm.name}\" (#${alarm.id})")
        val fmt = Calendar.getInstance().apply { timeInMillis = t }
        appLogger.log("Scheduler", "תוזמן: \"${alarm.name}\" (#${alarm.id}) ל-%02d/%02d %02d:%02d".format(
            fmt.get(Calendar.DAY_OF_MONTH), fmt.get(Calendar.MONTH) + 1,
            fmt.get(Calendar.HOUR_OF_DAY), fmt.get(Calendar.MINUTE)))
    }

    /**
     * Arms one exact trigger, preferring [AlarmManager.setAlarmClock] over
     * `setExactAndAllowWhileIdle`. Both survive Doze, but only `setAlarmClock` is
     * fully exempt from it: `setExactAndAllowWhileIdle` is rate-limited to roughly
     * one delivery per app per 9 minutes while the device is idle, which is enough
     * to make a short snooze (the minutes slider goes down to 1) land late. It also
     * registers the alarm with the OS as a user-facing alarm clock, which is what
     * puts the next-alarm indicator in the status bar and on the lock screen.
     *
     * Falls back to the (inexact, but never-throwing) `setAndAllowWhileIdle` if the
     * exact-alarm permission has been revoked — on API 31/32 SCHEDULE_EXACT_ALARM is
     * user-revocable and every exact-alarm call throws SecurityException once it is,
     * which would otherwise crash whatever happened to be scheduling at the time
     * (saving an alarm, the boot reschedule, a snooze) instead of degrading. The
     * user-visible fix for that state is surfaced separately in Settings → אמינות
     * ברקע and on launch (ReliabilityGate).
     */
    private fun armExact(triggerAt: Long, operation: PendingIntent, label: String) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            runCatching {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent()), operation)
            }.onFailure {
                appLogger.log("Scheduler", "setAlarmClock נכשל עבור $label (${it.message}) — נעשה שימוש בתזמון חלופי")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } else {
            appLogger.log("Scheduler",
                "אין הרשאת שעמורים מדויקים — $label תוזמן בתזמון מקורב, ייתכן איחור. יש לאשר בהגדרות → אמינות ברקע")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    /** Opens the app when the user taps the system's next-alarm indicator. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Unlike schedule()/cancel(), a snooze never writes to the alarms table (it only
    // arms a separate AlarmManager trigger and records the deadline in snoozePrefs
    // below), so the observeAlarms()-based fallback can't see it — this must keep
    // refreshing explicitly.
    fun scheduleAt(alarm: Alarm, at: Long) {
        armExact(at, buildSnoozePendingIntent(alarm.id), "נודניק \"${alarm.name}\" (#${alarm.id})")
        snoozePrefs.edit().putLong(alarm.id.toString(), at).apply()
        appLogger.log("Scheduler", "נודניק תוזמן: \"${alarm.name}\" (#${alarm.id})")
        widgetRefresher.refresh()
    }

    /**
     * Arms a rehearsal of [alarm] a few seconds from now, through the real path.
     *
     * The point is that nothing here is simulated: the OS delivers it, AlarmReceiver
     * takes the hand-off wake lock, AlarmFiringService plays the configured rounds at the
     * configured volume with the configured vibration, and the ring screen appears. That
     * is the chain that fails at 06:30, and it is the only way to show someone it works
     * *before* they rely on it.
     *
     * Uses [buildTestPendingIntent] so the alarm's real trigger is untouched, and sets
     * EXTRA_IS_TEST so the service performs no bookkeeping. Returns the moment it will
     * fire so the caller can say when.
     */
    fun scheduleTestRing(alarm: Alarm, delaySeconds: Int = TEST_RING_DELAY_SECONDS): Long {
        val at = System.currentTimeMillis() + delaySeconds * 1_000L
        armExact(at, buildTestPendingIntent(alarm.id), "בדיקת צלצול \"${alarm.name}\" (#${alarm.id})")
        appLogger.log("Scheduler",
            "בדיקת צלצול תוזמנה ל-\"${alarm.name}\" (#${alarm.id}) בעוד $delaySeconds שניות")
        return at
    }

    /** Cancels a pending rehearsal without touching the alarm's real trigger. */
    fun cancelTestRing(id: Long) {
        alarmManager.cancel(buildTestPendingIntent(id))
    }

    fun cancel(id: Long) {
        cancelQuiet(id)
        appLogger.log("Scheduler", "בוטל: שעמור #$id")
    }

    private fun cancelQuiet(id: Long) {
        alarmManager.cancel(buildIntent(id))
        alarmManager.cancel(buildSnoozePendingIntent(id))
        snoozePrefs.edit().remove(id.toString()).apply()
    }

    /** Cancels every id with one combined log line instead of N separate ones. */
    fun cancelAll(ids: List<Long>) {
        ids.forEach { cancelQuiet(it) }
        appLogger.log("Scheduler", "בוטלו ${ids.size} שעמורים")
    }

    // refreshWidgets defaults to true for RescheduleWorker's boot-reschedule call,
    // which has no accompanying alarms-table write at all (it's just re-arming
    // AlarmManager, which the OS wipes on every reboot) for the observeAlarms()-based
    // fallback to react to. AlarmListViewModel.unfreezeAll()/enableAll() pass false:
    // their own repository writes (unfreezeAll()/enableAll()) already trigger that
    // fallback, so this would otherwise be a second, redundant full widget rebuild
    // for the same user action.
    fun rescheduleAll(alarms: List<Alarm>, refreshWidgets: Boolean = true) {
        alarms.forEach { alarm ->
            // A snooze armed before a reboot is gone from AlarmManager (the OS drops
            // every alarm on shutdown) but its deadline is still in snoozePrefs — read
            // it *before* cancelQuiet() clears it, and re-arm it below if it hasn't
            // already passed. Without this, rebooting during a snooze silently dropped
            // that wake-up entirely: the regular schedule re-armed for the alarm's next
            // ordinary occurrence (often the next morning), and the snooze the user was
            // actually relying on in a few minutes never rang.
            val pendingSnooze = snoozePrefs.getLong(alarm.id.toString(), -1L)
                .takeIf { it > System.currentTimeMillis() }
            cancelQuiet(alarm.id)
            if (alarm.isActive && !alarm.isRecurrenceExpired()) schedule(alarm)
            if (pendingSnooze != null && alarm.isActive) scheduleAt(alarm, pendingSnooze)
        }
        appLogger.log("Scheduler", "תוזמנו מחדש ${alarms.size} שעמורים")
        if (refreshWidgets) widgetRefresher.refresh()
    }

    /**
     * The next fire time that comes from a schedule the user actually declared — a
     * weekday mask, a specific-dates list, or a specific datetime — as opposed to
     * [nextFireTime]'s plain "same time tomorrow" fallback, which exists so a
     * brand-new alarm with nothing configured but a time still rings once.
     *
     * Null therefore means "this alarm has no occurrence left after the one that just
     * fired", which is what [com.smartring.app.service.AlarmFiringService] uses to
     * decide between re-arming the alarm and switching it off. Re-arming
     * unconditionally (what it used to do) silently turned every one-time alarm into
     * a daily one, because that same fallback answered "tomorrow" forever.
     */
    fun nextRecurringFireTime(alarm: Alarm, now: Long = System.currentTimeMillis()): Long? {
        if (alarm.specificDateTime != null) return alarm.specificDateTime.takeIf { it > now }
        // isRecurring (mask + a real frequency), not just a non-zero mask: picking
        // weekdays but setting the frequency to "ללא" means "fire on the next one of
        // those days, once", which the rest of the app already treats as non-recurring.
        val fromRecurrence = if (alarm.isRecurring) nextFromRecurrence(alarm, now) else null
        return listOfNotNull(futureExtraDates(alarm, now).minOrNull(), fromRecurrence).minOrNull()
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

    // [now] defaults to the real clock for every production caller; overridable so
    // this (and the recurrence math it drives — WEEKLY/BIWEEKLY/MONTHLY, including
    // the year/month-boundary cases that have broken before) can be tested
    // deterministically instead of depending on whatever day it happens to be when
    // the test runs.
    fun nextFireTime(alarm: Alarm, now: Long = System.currentTimeMillis()): Long? {
        // 1. Exact datetime
        alarm.specificDateTime?.let { dt ->
            return if (dt > now) dt else null
        }

        // 2 + 3. The extra-dates list and the weekday recurrence are two schedules for
        // the *same* alarm, so the next fire is whichever comes first. Returning the
        // nearest extra date outright (what this used to do) made the weekday schedule
        // stop dead the moment one was added: an alarm set for every weekday plus one
        // date in August rang on that August date and on no weekday in between — even
        // though the section is called "תאריכים ספציפיים נוספים", additional to rather
        // than instead of.
        listOfNotNull(futureExtraDates(alarm, now).minOrNull(), nextFromRecurrence(alarm, now))
            .minOrNull()?.let { return it }

        // A weekday mask that produced nothing means the recurrence is over (it ran past
        // its "repeat until" date), not that the one-time fallback below should step in.
        if (alarm.repeatDaysBitmask != 0) return null

        // 4. Simple time-of-day (one-time)
        return Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE,      alarm.minute)
            set(Calendar.SECOND,      0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    /** Future occurrences coming from the alarm's explicit extra-dates list. */
    private fun futureExtraDates(alarm: Alarm, now: Long): List<Long> =
        alarm.specificDates
            .map { localInstantOnPickedDay(it.date, alarm.hour, alarm.minute) }
            .filter { it > now }

    /**
     * The next occurrence of the weekday recurrence itself, or null when there is none —
     * including when the recurrence would run past its "repeat until" date.
     *
     * That cutoff has to be applied to the *candidate time*, not only through
     * `isRecurrenceExpired()`, which just asks whether the cutoff has already passed: on
     * the 19th, an alarm set to repeat until the 20th was therefore still allowed to arm
     * its next occurrence on the 21st, and rang once after the date the user chose.
     */
    private fun nextFromRecurrence(alarm: Alarm, now: Long): Long? {
        if (alarm.repeatDaysBitmask == 0) return null
        val base = nextFromMask(alarm.hour, alarm.minute, alarm.repeatDaysBitmask, now) ?: return null
        val candidate = when (alarm.repeatFrequency) {
            RepeatFrequency.NONE     -> base
            RepeatFrequency.WEEKLY   -> base
            RepeatFrequency.BIWEEKLY -> {
                // Parity is derived from the candidate fire date itself (days since
                // epoch / 7), not from "now" vs. "base" via Calendar.WEEK_OF_YEAR:
                // WEEK_OF_YEAR resets every January and Kotlin's `%` keeps the
                // dividend's sign, so a now/base pair straddling a year boundary used to
                // silently break the every-other-week cadence. An absolute,
                // now-independent parity check keeps the cadence stable regardless of
                // when this is (re)computed.
                if (weekParity(base) == 0L) base else base + 7 * 24 * 3_600_000L
            }
            RepeatFrequency.MONTHLY -> {
                // "Monthly" = only the first matching weekday in each calendar month
                // fires. The previous now-vs-base month comparison degraded to weekly in
                // the common case (base is always within ~2 weeks of now, so it was
                // almost always "still this month").
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
        val cutoff = alarm.recurrenceEnd.untilDate
            ?.takeIf { alarm.recurrenceEnd.type == RecurrenceEndType.UNTIL }
        return candidate.takeIf { cutoff == null || it <= cutoff }
    }

    private fun nextFromMask(h: Int, m: Int, mask: Int, now: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
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

    /**
     * A third request-code space, alongside the alarm's own (`id`) and its snooze
     * (`id + 100_000`).
     *
     * This is the whole reason a test ring is safe to offer: AlarmManager keys a
     * registration by its PendingIntent, and two PendingIntents with the same request
     * code and matching Intent are the *same* registration. Arming a rehearsal on the
     * alarm's own code would therefore silently overwrite the real trigger — the user
     * would test their alarm and, in doing so, cancel it. Its own code keeps the two
     * independent, so the real 06:30 stays armed while the rehearsal fires in ten
     * seconds.
     */
    private fun buildTestPendingIntent(id: Long) = PendingIntent.getBroadcast(
        context, (id + 200_000).toInt(),
        Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            .putExtra(AlarmReceiver.EXTRA_IS_TEST, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
