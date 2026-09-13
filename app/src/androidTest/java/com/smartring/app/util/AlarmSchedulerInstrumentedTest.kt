package com.smartring.app.util

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.RepeatFrequency
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun clearAlarms() {
        // Ids used by the tests below; cancel() is a no-op for anything not armed.
        listOf(101L, 102L, 103L).forEach { scheduler.cancel(it) }
    }

    @Test
    fun scheduleRegistersARealAlarmClockWithTheSystem() {
        val alarm = dailyAlarmTwelveHoursOut(id = 101, name = "Instrumented")
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
        val alarm = dailyAlarmTwelveHoursOut(id = 102, name = "Cancel me")
        scheduler.schedule(alarm)
        assertNotNull(alarmManager.nextAlarmClock)

        scheduler.cancel(alarm.id)

        assertNull("cancel() must clear the registration", alarmManager.nextAlarmClock)
    }

    @Test
    fun snoozeIsArmedAndVisibleAsThePendingDeadline() {
        val alarm = dailyAlarmTwelveHoursOut(id = 103, name = "Snoozed")
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
    private fun dailyAlarmTwelveHoursOut(id: Long, name: String): Alarm {
        val inTwelveHours = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 12) }
        return Alarm(
            id = id, name = name,
            hour = inTwelveHours.get(Calendar.HOUR_OF_DAY),
            minute = inTwelveHours.get(Calendar.MINUTE),
            repeatDaysBitmask = 0b1111111,
        )
    }

    @Test
    fun aRebootRescheduleKeepsAnInFlightSnooze() {
        val alarm = dailyAlarmTwelveHoursOut(id = 103, name = "Snoozed")
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
