package com.smartring.app.util
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.smartring.app.domain.model.*
import com.smartring.app.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(alarm: Alarm) {
        if (!alarm.isActive) return
        if (alarm.isRecurrenceExpired()) return
        val t = nextFireTime(alarm) ?: return
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, buildIntent(alarm.id))
    }

    fun scheduleAt(alarm: Alarm, at: Long) =
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at, buildSnoozePendingIntent(alarm.id))

    fun cancel(id: Long) = alarmManager.cancel(buildIntent(id))

    fun rescheduleAll(alarms: List<Alarm>) =
        alarms.forEach { cancel(it.id); if (it.isActive && !it.isRecurrenceExpired()) schedule(it) }

    fun nextFireTime(alarm: Alarm): Long? {
        val now = System.currentTimeMillis()

        // 1. Exact datetime
        alarm.specificDateTime?.let { dt ->
            return if (dt > now) dt else null
        }

        // 2. Specific dates list
        alarm.specificDates
            .map { it.date + timeOfDay(alarm.hour, alarm.minute) }
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
                    val calNow  = Calendar.getInstance().apply { timeInMillis = now }
                    val calBase = Calendar.getInstance().apply { timeInMillis = base }
                    val diff = calBase.get(Calendar.WEEK_OF_YEAR) - calNow.get(Calendar.WEEK_OF_YEAR)
                    if (diff % 2 == 0) base
                    else calBase.apply { add(Calendar.WEEK_OF_YEAR, 1) }.timeInMillis
                }
                RepeatFrequency.MONTHLY -> {
                    val calNow  = Calendar.getInstance().apply { timeInMillis = now }
                    val calBase = Calendar.getInstance().apply { timeInMillis = base }
                    if (calBase.get(Calendar.MONTH) == calNow.get(Calendar.MONTH) &&
                        calBase.get(Calendar.YEAR)  == calNow.get(Calendar.YEAR)) base
                    else nextFromMask(alarm.hour, alarm.minute, alarm.repeatDaysBitmask,
                            base + 7 * 24 * 3_600_000L) ?: base
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

    private fun timeOfDay(h: Int, m: Int) = h * 3_600_000L + m * 60_000L

    private fun buildIntent(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildSnoozePendingIntent(id: Long) = PendingIntent.getBroadcast(
        context, (id + 100_000).toInt(),
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
