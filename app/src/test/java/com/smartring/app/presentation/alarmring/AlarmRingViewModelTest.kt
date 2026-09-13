package com.smartring.app.presentation.alarmring

import androidx.test.core.app.ApplicationProvider
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class AlarmRingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var appLogger: AppLogger
    private lateinit var vm: AlarmRingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        vm = AlarmRingViewModel(repository, scheduler, appLogger, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAlarm populates state when the alarm exists`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, name = "Test")
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()

        vm.loadAlarm(1)
        advanceUntilIdle()

        assertEquals(alarm, vm.state.value.alarm)
        assertFalse(vm.state.value.isDismissed)
    }

    @Test
    fun `loadAlarm dismisses instead of hanging forever if the alarm is never found`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(any()) } returns null

        vm.loadAlarm(999)
        advanceUntilIdle()

        assertTrue(vm.state.value.isDismissed)
    }

    @Test
    fun `tick self-dismisses once ringDurationSeconds plus the grace period has elapsed`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, ringDurationSeconds = 10)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        vm.loadAlarm(1)
        advanceUntilIdle()

        // Before ringDurationSeconds(10) + the 2s grace buffer: still ringing.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        vm.tick()
        assertFalse(vm.state.value.isDismissed)

        // Past it: the safety net kicks in.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        vm.tick()
        assertTrue(vm.state.value.isDismissed)
    }

    @Test
    fun `stop is blocked for a Shabbat-mode alarm`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, isShabbatMode = true)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        vm.loadAlarm(1); advanceUntilIdle()

        vm.stop(); advanceUntilIdle()

        assertFalse(vm.state.value.isDismissed)
        coVerify(exactly = 0) { repository.log(any(), any(), any(), "STOPPED") }
    }

    @Test
    fun `stop logs STOPPED and dismisses for a normal alarm`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, name = "Wake up")
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        vm.loadAlarm(1); advanceUntilIdle()

        vm.stop(); advanceUntilIdle()

        assertTrue(vm.state.value.isDismissed)
        coVerify { repository.log(1, "Wake up", any(), "STOPPED") }
    }

    @Test
    fun `snooze is blocked for a Shabbat-mode alarm`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, isShabbatMode = true, snoozeEnabled = true)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        vm.loadAlarm(1); advanceUntilIdle()

        vm.snooze(); advanceUntilIdle()

        assertFalse(vm.state.value.isDismissed)
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    @Test
    fun `snooze degrades to a plain stop when snooze is disabled for this alarm`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, snoozeEnabled = false)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        vm.loadAlarm(1); advanceUntilIdle()

        vm.snooze(); advanceUntilIdle()

        assertTrue(vm.state.value.isDismissed)
        assertEquals(0, vm.state.value.snoozeCount)
        coVerify { repository.log(1, any(), any(), "STOPPED") }
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    @Test
    fun `snooze degrades to a plain stop once snoozeMaxCount is already reached`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, snoozeEnabled = true, snoozeMaxCount = 3)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        coEvery { repository.snoozeCountSinceLastFire(1) } returns 3
        vm.loadAlarm(1); advanceUntilIdle()

        vm.snooze(); advanceUntilIdle()

        coVerify { repository.log(1, any(), any(), "STOPPED") }
        verify(exactly = 0) { scheduler.scheduleAt(any(), any()) }
    }

    @Test
    fun `snooze schedules a re-fire and increments snoozeCount when allowed`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 1, snoozeEnabled = true, snoozeMaxCount = 3, snoozeMinutes = 10)
        coEvery { repository.getAlarm(1) } returns alarm
        coEvery { repository.lastFiredAt(1) } returns System.currentTimeMillis()
        coEvery { repository.snoozeCountSinceLastFire(1) } returns 0
        vm.loadAlarm(1); advanceUntilIdle()

        vm.snooze(); advanceUntilIdle()

        assertEquals(1, vm.state.value.snoozeCount)
        assertTrue(vm.state.value.isDismissed)
        verify { scheduler.scheduleAt(alarm, any()) }
        coVerify { repository.log(1, any(), any(), "SNOOZED") }
    }
}
