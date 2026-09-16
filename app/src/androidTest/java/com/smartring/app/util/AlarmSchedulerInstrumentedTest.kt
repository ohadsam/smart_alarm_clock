package com.smartring.app.util

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.RepeatFrequency
import java.util.Calendar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule

/**
 * Runs on a real emulator against the real [AlarmManager] — which is exactly the
 * point. The unit-test suite covers this class's date arithmetic against
 * Robolectric's *shadow* AlarmManager, and a shadow will happily record a call the
 * real framework would reject or treat differently.
 *
 * In particular this is the only place that proves the v1.5.0 switch from
 * `setExactAndAllowWhileIdle` to `setAlarmClock` actually took: only a genuine
 * alarm-clock registration shows up in [AlarmManager.getNextAlarmClock], which is
 * also what drives the system's next-alarm indicator.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmSchedulerInstrumentedTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var repository: AlarmRepository

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        clearAlarms()
    }

    @After
    fun tearDown() = clearAlarms()

    /**
     * Clears this class's own ids *and* every alarm the app has stored.
     *
     * The second half matters because getNextAlarmClock() is device-wide. A UI test
     * elsewhere in this suite creates an alarm at the app's default 07:00, and on a run
     * starting in the evening that is sooner than anything armed here — so the OS
     * reported *that* alarm and these tests compared trigger times against an alarm they
     * never armed. Cancelling by id alone cannot catch it: the id is assigned by Room,
     * not chosen here.
     */
    private fun clearAlarms() {
        listOf(101L, 102L, 103L).forEach { scheduler.cancel(it) }
        runBlocking {
            repository.observeAlarms().first().forEach {
                scheduler.cancel(it.id)
                repository.deleteAlarm(it.id)
            }
        }
    }

    @Test
    fun scheduleRegistersARealAlarmClockWithTheSystem() {
        val alarm = dailyAlarmMinutesOut(id = 101, name = "Instrumented")
            .copy(repeatFrequency = RepeatFrequency.WEEKLY)

        scheduler.schedule(alarm)

        val next = alarmManager.nextAlarmClock
        assertNotNull(
            "schedule() must register a real alarm clock with the OS — that is what " +
                "makes it exempt from Doze and what shows the next-alarm indicator",
            next,
        )
        assertEquals(
            "the registered trigger time must be the one the scheduler computed",
            scheduler.nextFireTime(alarm),
            next!!.triggerTime,
        )
    }

    @Test
    fun cancelRemovesTheRegisteredAlarmClock() {
        val alarm = dailyAlarmMinutesOut(id = 102, name = "Cancel me")
        val ourTrigger = scheduler.nextFireTime(alarm)
        assertNotNull("the test alarm must resolve to a fire time", ourTrigger)

        scheduler.schedule(alarm)
        assertEquals(
            "schedule() must register this alarm before cancel can clear it",
            ourTrigger, alarmManager.nextAlarmClock?.triggerTime,
        )

        scheduler.cancel(alarm.id)

        // Compared against the time *we* computed, not against whatever the OS reported
        // a moment ago. getNextAlarmClock() is device-wide, so `armed` could have been
        // some other app's alarm entirely — and then this would assert that cancel()
        // removed a registration it never made. Asserting `== null` is worse still: that
        // claims no app on the device has any alarm set.
        assertNotEquals(
            "cancel() must clear this alarm's registration",
            ourTrigger,
            alarmManager.nextAlarmClock?.triggerTime,
        )
    }

    @Test
    fun snoozeIsArmedAndVisibleAsThePendingDeadline() {
        val alarm = dailyAlarmMinutesOut(id = 103, name = "Snoozed")
        val snoozeAt = System.currentTimeMillis() + 5 * 60_000L

        scheduler.scheduleAt(alarm, snoozeAt)

        assertEquals(snoozeAt, scheduler.pendingSnoozeUntil(alarm))
        assertEquals(
            "a pending snooze is sooner than the regular schedule, so it wins",
            snoozeAt, scheduler.effectiveNextFireTime(alarm),
        )
        assertEquals(snoozeAt, alarmManager.nextAlarmClock?.triggerTime)
    }

    /**
     * A daily alarm whose next occurrence is half a day out from whenever this runs.
     * Fixed times of day would make these assertions quietly depend on what time CI
     * happened to start: an alarm due in the next few minutes collides with the test
     * snooze deadlines below, and one due *right now* can recompute differently
     * between two calls a millisecond apart.
     */
    /**
     * A daily alarm [minutesOut] from now.
     *
     * Deliberately minutes rather than the twelve hours this used to use. The OS reports
     * a single, device-wide next alarm clock, so a test that reads it can only be talking
     * about its own alarm when its own alarm is the soonest one on the device. Twelve
     * hours out lost that race to an ordinary 07:00 alarm; ten minutes does not lose it
     * to anything this suite creates, now that [clearAlarms] also deletes stored alarms.
     *
     * Ten and not two: an alarm that actually fires mid-suite would launch the ring
     * screen over the next test. Each @After cancels within milliseconds of arming, so
     * the window is already tiny — this just keeps it impossible rather than unlikely.
     */
    private fun dailyAlarmMinutesOut(id: Long, name: String, minutesOut: Int = 10): Alarm {
        val target = Calendar.getInstance().apply { add(Calendar.MINUTE, minutesOut) }
        return Alarm(
            id = id, name = name,
            hour = target.get(Calendar.HOUR_OF_DAY),
            minute = target.get(Calendar.MINUTE),
            repeatDaysBitmask = 0b1111111,
        )
    }

    @Test
    fun aRebootRescheduleKeepsAnInFlightSnooze() {
        val alarm = dailyAlarmMinutesOut(id = 103, name = "Snoozed")
        val snoozeAt = System.currentTimeMillis() + 5 * 60_000L
        scheduler.scheduleAt(alarm, snoozeAt)

        // What RescheduleWorker does after BOOT_COMPLETED.
        scheduler.rescheduleAll(listOf(alarm))

        assertEquals(
            "rebooting mid-snooze must not silently drop that wake-up",
            snoozeAt, scheduler.pendingSnoozeUntil(alarm),
        )
    }

    @Test
    fun aOneTimeAlarmHasNothingLeftAfterItRings() {
        // The domain rule AlarmFiringService relies on to switch a one-time alarm off
        // instead of re-arming it for tomorrow, checked here against the real clock.
        val oneTime = Alarm(id = 101, hour = 6, minute = 30, repeatDaysBitmask = 0)
        val justAfterItRang = System.currentTimeMillis() + 1_000L
        assertNull(scheduler.nextRecurringFireTime(oneTime, justAfterItRang))

        val daily = oneTime.copy(repeatDaysBitmask = 0b1111111)
        assertTrue(scheduler.nextRecurringFireTime(daily, justAfterItRang)!! > justAfterItRang)
    }
}
