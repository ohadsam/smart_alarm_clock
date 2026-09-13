package com.smartring.app.util

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.RepeatFrequency
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Calendar
import java.util.TimeZone

/**
 * Robolectric (not a real emulator — a lightweight Android environment on the JVM)
 * so AlarmScheduler's Context/SharedPreferences/AlarmManager usage works without any
 * device. AppLogger/WidgetRefresher are relaxed mocks: their side effects (writing a
 * diagnostic log line, re-rendering widgets) aren't what these tests check.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {
    private lateinit var scheduler: AlarmScheduler
    private lateinit var defaultTimeZone: TimeZone

    @Before
    fun setUp() {
        // Pin the default timezone so Calendar.getInstance() inside AlarmScheduler
        // (and the utcMillis() helper below) agree on what "the same instant" means,
        // regardless of the machine/CI runner's own configured timezone.
        defaultTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        scheduler = AlarmScheduler(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultTimeZone)
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ── One-time (no recurrence, no specific date) ───────────────────

    @Test
    fun `one-time alarm fires today if its time hasn't passed yet`() {
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = 0)
        val now = utcMillis(2024, 6, 15, 7, 0)
        assertEquals(utcMillis(2024, 6, 15, 8, 0), scheduler.nextFireTime(alarm, now))
    }

    @Test
    fun `one-time alarm rolls to tomorrow once today's time has passed`() {
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = 0)
        val now = utcMillis(2024, 6, 15, 9, 0)
        assertEquals(utcMillis(2024, 6, 16, 8, 0), scheduler.nextFireTime(alarm, now))
    }

    // ── Specific datetime ─────────────────────────────────────────────

    @Test
    fun `specific datetime fires exactly at that instant while still future`() {
        val target = utcMillis(2024, 7, 4, 10, 0)
        val alarm = Alarm(specificDateTime = target)
        assertEquals(target, scheduler.nextFireTime(alarm, now = target - 1))
    }

    @Test
    fun `specific datetime is null once it's in the past`() {
        val target = utcMillis(2024, 7, 4, 10, 0)
        val alarm = Alarm(specificDateTime = target)
        assertNull(scheduler.nextFireTime(alarm, now = target + 1))
    }

    // ── Specific dates list ───────────────────────────────────────────

    @Test
    fun `specific dates list picks the earliest still-future date`() {
        val later = AlarmDate(date = utcMillis(2024, 8, 10))
        val sooner = AlarmDate(date = utcMillis(2024, 8, 1))
        val alarm = Alarm(hour = 8, minute = 0, specificDates = listOf(later, sooner))
        val now = utcMillis(2024, 8, 1, 0, 0)
        assertEquals(utcMillis(2024, 8, 1, 8, 0), scheduler.nextFireTime(alarm, now))
    }

    // ── WEEKLY ─────────────────────────────────────────────────────────

    @Test
    fun `WEEKLY fires today when picked day's time hasn't passed yet`() {
        val now = utcMillis(2024, 6, 15, 7, 0)
        val todayBit = 1 shl (dayOfWeek(now) - 1)
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = todayBit, repeatFrequency = RepeatFrequency.WEEKLY)
        assertEquals(utcMillis(2024, 6, 15, 8, 0), scheduler.nextFireTime(alarm, now))
    }

    @Test
    fun `WEEKLY rolls a full 7 days once today's occurrence has passed`() {
        val now = utcMillis(2024, 6, 15, 9, 0)
        val todayBit = 1 shl (dayOfWeek(now) - 1)
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = todayBit, repeatFrequency = RepeatFrequency.WEEKLY)
        val next = scheduler.nextFireTime(alarm, now)!!
        assertEquals(7L, (next - utcMillis(2024, 6, 15, 8, 0)) / (24 * 3_600_000L))
    }

    // ── BIWEEKLY — the regression this was fixed for ──────────────────

    @Test
    fun `BIWEEKLY cadence stays exactly 14 days apart across a year boundary`() {
        val mondayBit = 1 shl (Calendar.MONDAY - 1)
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = mondayBit, repeatFrequency = RepeatFrequency.BIWEEKLY)

        var now = utcMillis(2024, 12, 1, 0, 0) // comfortably before the year boundary
        val fires = mutableListOf<Long>()
        repeat(6) {
            val next = scheduler.nextFireTime(alarm, now) ?: error("expected a next fire time")
            fires += next
            now = next // simulate: the moment this occurrence fires, ask for the next one
        }

        for (i in 1 until fires.size) {
            val gapDays = (fires[i] - fires[i - 1]) / (24 * 3_600_000L)
            assertEquals("gap #$i should be exactly 14 days, not degrade to 7", 14L, gapDays)
        }
    }

    // ── MONTHLY ────────────────────────────────────────────────────────

    @Test
    fun `MONTHLY fires on the first matching weekday of the month, once per month`() {
        val mondayBit = 1 shl (Calendar.MONDAY - 1)
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = mondayBit, repeatFrequency = RepeatFrequency.MONTHLY)
        val now = utcMillis(2024, 3, 1, 0, 0)

        val first = scheduler.nextFireTime(alarm, now)!!
        val calFirst = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = first }
        assertEquals(Calendar.MONDAY, calFirst.get(Calendar.DAY_OF_WEEK))

        val second = scheduler.nextFireTime(alarm, first)!!
        val calSecond = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = second }
        assertTrue(
            "the second MONTHLY occurrence must land in a later month, not the very next week",
            calSecond.get(Calendar.YEAR) * 12 + calSecond.get(Calendar.MONTH) >
                calFirst.get(Calendar.YEAR) * 12 + calFirst.get(Calendar.MONTH),
        )
    }

    // ── Snooze awareness (effectiveNextFireTime / pendingSnoozeUntil) ──

    @Test
    fun `pendingSnoozeUntil reflects an armed snooze deadline`() {
        val alarm = Alarm(id = 42)
        val at = System.currentTimeMillis() + 60_000
        scheduler.scheduleAt(alarm, at)
        assertEquals(at, scheduler.pendingSnoozeUntil(alarm))
    }

    @Test
    fun `pendingSnoozeUntil is null once the deadline is in the past`() {
        val alarm = Alarm(id = 42)
        scheduler.scheduleAt(alarm, at = System.currentTimeMillis() - 1_000)
        assertNull(scheduler.pendingSnoozeUntil(alarm))
    }

    @Test
    fun `pendingSnoozeUntil is cleared by cancel()`() {
        val alarm = Alarm(id = 42)
        scheduler.scheduleAt(alarm, at = System.currentTimeMillis() + 60_000)
        scheduler.cancel(alarm.id)
        assertNull(scheduler.pendingSnoozeUntil(alarm))
    }

    @Test
    fun `effectiveNextFireTime prefers an armed snooze over the regular schedule`() {
        // A specificDateTime a year out guarantees the "regular" schedule is much
        // later than the snooze deadline, regardless of the real time the test runs.
        val alarm = Alarm(id = 42, specificDateTime = System.currentTimeMillis() + 365L * 24 * 3_600_000)
        val at = System.currentTimeMillis() + 30_000
        scheduler.scheduleAt(alarm, at)
        assertEquals(at, scheduler.effectiveNextFireTime(alarm))
    }

    // ── schedule()/cancel() actually (dis)arm a real AlarmManager alarm ─

    @Test
    fun `schedule() registers a real AlarmManager alarm, cancel() removes it`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)

        val alarm = Alarm(id = 7, hour = 8, minute = 0, repeatDaysBitmask = 0)
        scheduler.schedule(alarm)
        // peekNextScheduledAlarm(), not the deprecated consuming getNextScheduledAlarm(),
        // so checking it doesn't itself remove the alarm before cancel() gets a chance to.
        assertNotNull("schedule() should register a real AlarmManager alarm", shadow.peekNextScheduledAlarm())

        scheduler.cancel(alarm.id)
        assertNull("cancel() should remove the AlarmManager alarm", shadow.peekNextScheduledAlarm())
    }

    @Test
    fun `schedule() does nothing for a disabled alarm`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)

        scheduler.schedule(Alarm(id = 8, isEnabled = false, hour = 8, minute = 0, repeatDaysBitmask = 0))
        assertNull(shadow.peekNextScheduledAlarm())
    }

    private fun dayOfWeek(millis: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
}
