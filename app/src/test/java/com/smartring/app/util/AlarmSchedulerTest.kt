package com.smartring.app.util

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmDate
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
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

    // ── Daylight saving ───────────────────────────────────────────────────
    // Alarms are armed as absolute timestamps derived from the local wall clock, so a
    // DST transition is the one day where "same time tomorrow" is not 24 hours away.
    // The alarm has to follow the wall clock, not the elapsed interval.

    @Test
    fun `a daily alarm keeps its local time across a spring-forward transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 2030-03-10 is the US spring-forward day: 02:00 jumps straight to 03:00.
        val now = Calendar.getInstance().apply {
            set(2030, Calendar.MARCH, 9, 9, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val alarm = Alarm(hour = 7, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY)

        val next = scheduler.nextFireTime(alarm, now) ?: error("expected a next fire time")
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals("must still ring on the 10th", 10, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals("must still ring at 07:00 local", 7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        // 21 elapsed hours, not 22: proof the transition really was crossed, and that
        // the alarm followed the wall clock rather than a fixed 24-hour offset.
        assertEquals("the DST hour must actually have been skipped",
            21L, (next - now) / 3_600_000L)
    }

    @Test
    fun `a daily alarm keeps its local time across a fall-back transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 2030-11-03: 02:00 happens twice.
        val now = Calendar.getInstance().apply {
            set(2030, Calendar.NOVEMBER, 2, 9, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val alarm = Alarm(hour = 7, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY)

        val next = scheduler.nextFireTime(alarm, now) ?: error("expected a next fire time")
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(3, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("the repeated hour must actually have been added",
            23L, (next - now) / 3_600_000L)
    }

    // ── Extra dates and the weekday recurrence run *together* ────────────
    // "תאריכים ספציפיים נוספים" means additional to the weekday schedule, not instead
    // of it. Returning the nearest extra date outright made adding one silently switch
    // the weekday schedule off until that date had passed.
    // Weekday bits are Calendar.DAY_OF_WEEK - 1, so Sunday is bit 0. In June 2030 the
    // 16th is a Sunday, which fixes every weekday used below.

    @Test
    fun `an extra date does not suspend the weekday schedule`() {
        val now = utcMillis(2030, 6, 16, 9, 0)          // Sunday morning, after 07:00
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b1111111,              // every day
            specificDates = listOf(AlarmDate(date = utcMillis(2030, 8, 15))),
        )
        assertEquals(
            "the daily schedule must keep running between now and the extra date",
            utcMillis(2030, 6, 17, 7, 0), scheduler.nextFireTime(alarm, now),
        )
    }

    @Test
    fun `an extra date wins when it lands before the next weekday occurrence`() {
        val now = utcMillis(2030, 6, 16, 9, 0)          // Sunday
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b0000001,              // Sundays only -> next is the 23rd
            specificDates = listOf(AlarmDate(date = utcMillis(2030, 6, 18))),  // Tuesday
        )
        assertEquals(utcMillis(2030, 6, 18, 7, 0), scheduler.nextFireTime(alarm, now))
    }

    @Test
    fun `the weekday schedule resumes once every extra date has passed`() {
        val now = utcMillis(2030, 6, 19, 9, 0)          // Wednesday, the extra date is behind us
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b0000001,              // Sundays
            specificDates = listOf(AlarmDate(date = utcMillis(2030, 6, 18))),
        )
        assertEquals(utcMillis(2030, 6, 23, 7, 0), scheduler.nextFireTime(alarm, now))
    }

    @Test
    fun `nextRecurringFireTime also treats extra dates as additional`() {
        // The firing service asks this to decide between re-arming and switching the
        // alarm off, so the same rule has to hold here or an alarm with an extra date
        // would keep the wrong one of its two schedules alive.
        val now = utcMillis(2030, 6, 16, 9, 0)
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b1111111,
            specificDates = listOf(AlarmDate(date = utcMillis(2030, 8, 15))),
        )
        assertEquals(utcMillis(2030, 6, 17, 7, 0), scheduler.nextRecurringFireTime(alarm, now))
    }

    // ── "Repeat until <date>" is a cutoff on the occurrence, not just on today ──
    // isRecurrenceExpired() only asks whether the cutoff has already passed, so on the
    // 19th an alarm set to repeat until the 20th was still allowed to arm its next
    // occurrence on the 21st — and rang once after the date the user picked.

    private fun endOfDay(year: Int, month: Int, day: Int): Long =
        utcMillis(year, month, day, 23, 59) + 59_999L

    @Test
    fun `a recurrence does not arm an occurrence past its until date`() {
        val now = utcMillis(2030, 6, 19, 9, 0)          // Wednesday
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b0100000,              // Fridays -> next is the 21st
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = endOfDay(2030, 6, 20)),
        )
        assertNull(
            "Friday the 21st is past the until date, so there is nothing left to arm",
            scheduler.nextFireTime(alarm, now),
        )
    }

    @Test
    fun `a recurrence still fires on the until date itself`() {
        val now = utcMillis(2030, 6, 19, 9, 0)          // Wednesday
        val alarm = Alarm(
            hour = 7, minute = 0,
            repeatDaysBitmask = 0b0010000,              // Thursdays -> the 20th
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.UNTIL, untilDate = endOfDay(2030, 6, 20)),
        )
        assertEquals(
            "the chosen end date is inclusive — that morning must still ring",
            utcMillis(2030, 6, 20, 7, 0), scheduler.nextFireTime(alarm, now),
        )
    }

    @Test
    fun `the until cutoff does not affect an alarm ending by count or never`() {
        val now = utcMillis(2030, 6, 19, 9, 0)
        val forever = Alarm(hour = 7, minute = 0, repeatDaysBitmask = 0b0100000)
        assertEquals(utcMillis(2030, 6, 21, 7, 0), scheduler.nextFireTime(forever, now))

        // A stale untilDate left over from switching the end type away from UNTIL must
        // be ignored, not silently applied.
        val byCount = forever.copy(
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, untilDate = endOfDay(2030, 6, 20), count = 5),
        )
        assertEquals(utcMillis(2030, 6, 21, 7, 0), scheduler.nextFireTime(byCount, now))
    }

    @Test
    fun `schedule() arms nothing once the recurrence has run past its until date`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))
        // untilDate in the past: isRecurrenceExpired() already covers this case, but it
        // is the pairing that matters — expired alarms must leave nothing armed.
        val alarm = Alarm(
            id = 21, hour = 7, minute = 0, repeatDaysBitmask = 0b1111111,
            recurrenceEnd = RecurrenceEnd(
                RecurrenceEndType.UNTIL,
                untilDate = System.currentTimeMillis() - 86_400_000L,
            ),
        )
        scheduler.schedule(alarm)
        assertNull(shadow.peekNextScheduledAlarm())
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

    // ── schedule() also has to *dis*arm when there is nothing left to arm ──
    // It used to plainly `return` in each of these cases, which left whatever had been
    // armed before still armed: editing an alarm into a configuration that never fires
    // again went on ringing at the old time, because nothing ever cancelled the
    // PendingIntent the previous save had registered.

    @Test
    fun `schedule() disarms a previously armed alarm that can no longer fire`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))

        val armed = Alarm(id = 9, hour = 8, minute = 0, repeatDaysBitmask = 0)
        scheduler.schedule(armed)
        assertNotNull("precondition: the alarm is armed", shadow.peekNextScheduledAlarm())

        // Same id, now pointing at a specific datetime that has already passed, for
        // which nextFireTime() returns null.
        scheduler.schedule(armed.copy(specificDateTime = System.currentTimeMillis() - 60_000L))
        assertNull(
            "re-scheduling an alarm with no future occurrence must cancel the old trigger",
            shadow.peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `schedule() disarms an alarm that has just been frozen`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))

        val armed = Alarm(id = 10, hour = 8, minute = 0, repeatDaysBitmask = 0)
        scheduler.schedule(armed)
        assertNotNull("precondition: the alarm is armed", shadow.peekNextScheduledAlarm())

        scheduler.schedule(armed.copy(isFrozen = true))
        assertNull("a frozen alarm must not stay armed", shadow.peekNextScheduledAlarm())
    }

    @Test
    fun `schedule() clears a pending snooze when the alarm can no longer fire`() {
        // pendingSnoozeUntil() is what the widgets and the edit screen's next-fire hint
        // read. A snooze left behind for an alarm that has since been switched off would
        // keep both of them advertising a ring that is never going to happen.
        val alarm = Alarm(id = 11, hour = 8, minute = 0, repeatDaysBitmask = 0)
        scheduler.scheduleAt(alarm, System.currentTimeMillis() + 5 * 60_000L)
        assertNotNull("precondition: a snooze is pending", scheduler.pendingSnoozeUntil(alarm))

        scheduler.schedule(alarm.copy(isEnabled = false))
        assertNull(scheduler.pendingSnoozeUntil(alarm))
    }

    // ── nextRecurringFireTime: "is there anything after the ring that just ended?" ──
    // This is what AlarmFiringService consults to decide between re-arming an alarm
    // and switching it off. It used to re-arm unconditionally, and since nextFireTime()
    // answers "same time tomorrow" for an alarm with no declared schedule, every
    // one-time alarm quietly became a daily one.

    // ── Recurrence that ends after N occurrences ─────────────────────
    // FOREVER and UNTIL were both covered; COUNT is the third option the edit screen
    // offers and the only one whose end condition advances on its own, from the
    // occurrencesFired the firing service increments on every ring.

    @Test
    fun `schedule() arms nothing once a COUNT recurrence has run out`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))

        val alarm = Alarm(
            id = 20, hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 3),
            occurrencesFired = 2,
        )
        scheduler.schedule(alarm)
        assertNotNull("precondition: the third occurrence is still due", shadow.peekNextScheduledAlarm())

        // The third ring has now happened, so the alarm is finished.
        scheduler.schedule(alarm.copy(occurrencesFired = 3))
        assertNull("a spent COUNT recurrence must not stay armed", shadow.peekNextScheduledAlarm())
    }

    @Test
    fun `a COUNT recurrence keeps arming while occurrences remain`() {
        val alarm = Alarm(
            id = 21, hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 10),
            occurrencesFired = 9,
        )
        // The last remaining occurrence still has to be armed — stopping one early is
        // the same bug as running one over.
        assertNotNull(scheduler.nextRecurringFireTime(alarm))
    }

    @Test
    fun `the COUNT cutoff does not touch nextFireTime itself`() {
        // isRecurrenceExpired() is the gate, applied in schedule(); nextFireTime() is
        // deliberately unaware of it, so the two can't both half-apply the rule.
        val alarm = Alarm(
            hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 1),
            occurrencesFired = 99,
        )
        assertNotNull(scheduler.nextFireTime(alarm, now = utcMillis(2030, 6, 20, 7, 0)))
    }

    @Test
    fun `an exhausted COUNT recurrence also clears a pending snooze`() {
        // The widgets read pendingSnoozeUntil() directly; a snooze left armed for an
        // alarm that has finished its run would keep advertising a ring that will
        // never come.
        val alarm = Alarm(
            id = 22, hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 2),
            occurrencesFired = 1,
        )
        scheduler.scheduleAt(alarm, System.currentTimeMillis() + 5 * 60_000L)
        assertNotNull("precondition: a snooze is pending", scheduler.pendingSnoozeUntil(alarm))

        scheduler.schedule(alarm.copy(occurrencesFired = 2))
        assertNull(scheduler.pendingSnoozeUntil(alarm))
    }

    @Test
    fun `nextRecurringFireTime is null for a plain one-time alarm`() {
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = 0)
        val now = utcMillis(2025, 3, 10, 8, 1) // just after it rang
        assertNull(scheduler.nextRecurringFireTime(alarm, now))
        // ...even though the plain next-fire calculation still has an answer, which is
        // exactly the trap this exists to avoid.
        assertNotNull(scheduler.nextFireTime(alarm, now))
    }

    @Test
    fun `nextRecurringFireTime is non-null for a weekly alarm`() {
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.WEEKLY)
        assertNotNull(scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 10, 8, 1)))
    }

    @Test
    fun `nextRecurringFireTime is null when weekdays are picked but the frequency is NONE`() {
        val alarm = Alarm(hour = 8, minute = 0, repeatDaysBitmask = 0b1111111,
            repeatFrequency = RepeatFrequency.NONE)
        assertNull(scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 10, 8, 1)))
    }

    @Test
    fun `nextRecurringFireTime follows the specific-dates list until it runs out`() {
        val alarm = Alarm(
            hour = 8, minute = 0, repeatDaysBitmask = 0,
            specificDates = listOf(AlarmDate(date = utcMillis(2025, 3, 12))),
        )
        assertNotNull("a date still ahead keeps the alarm armed",
            scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 10, 8, 1)))
        assertNull("once every listed date has passed there is nothing left",
            scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 13, 8, 1)))
    }

    @Test
    fun `nextRecurringFireTime is null once a specific datetime has passed`() {
        val alarm = Alarm(specificDateTime = utcMillis(2025, 3, 10, 8, 0))
        assertNotNull(scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 10, 7, 0)))
        assertNull(scheduler.nextRecurringFireTime(alarm, utcMillis(2025, 3, 10, 8, 1)))
    }

    // ── Boot reschedule keeps an in-flight snooze ────────────────────

    @Test
    fun `rescheduleAll re-arms a snooze that was still pending`() {
        val alarm = Alarm(id = 99, hour = 8, minute = 0, repeatDaysBitmask = 0b1111111)
        val snoozeAt = System.currentTimeMillis() + 5 * 60_000L
        scheduler.scheduleAt(alarm, snoozeAt)

        // Stands in for the reboot: AlarmManager itself is wiped, only the persisted
        // deadline survives, and RescheduleWorker re-arms everything from the DB.
        scheduler.rescheduleAll(listOf(alarm))

        // Only the snooze deadline is asserted here: whether it also wins
        // effectiveNextFireTime() depends on how far off this alarm's own 08:00
        // occurrence happens to be when the test runs, and that comparison already has
        // its own deterministic test above.
        assertEquals("the pending snooze must survive a reboot reschedule",
            snoozeAt, scheduler.pendingSnoozeUntil(alarm))
    }

    @Test
    fun `rescheduleAll drops a snooze deadline that has already passed`() {
        val alarm = Alarm(id = 98, hour = 8, minute = 0, repeatDaysBitmask = 0b1111111)
        scheduler.scheduleAt(alarm, System.currentTimeMillis() - 60_000L)
        scheduler.rescheduleAll(listOf(alarm))
        assertNull(scheduler.pendingSnoozeUntil(alarm))
    }

    private fun dayOfWeek(millis: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
}
