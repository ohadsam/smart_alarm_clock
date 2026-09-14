package com.smartring.app.presentation.alarmlist

import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * AlarmListViewModel had no coverage at all, despite owning every bulk operation in
 * the app — the four buttons behind "שליטה כללית" that can switch off, freeze or
 * re-arm every alarm at once. The ordering inside those is load-bearing and easy to
 * get wrong: disableAll()/freezeAll() must read the active set *before* the write
 * that clears it, or they cancel nothing.
 *
 * Plain JUnit, no Robolectric — this ViewModel touches no Android APIs of its own.
 */
class AlarmListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var appLogger: AppLogger

    private fun viewModel() = AlarmListViewModel(repository, scheduler, appLogger)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)
        stubAlarms(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubAlarms(alarms: List<Alarm>) {
        every { repository.observeAlarms() } returns flowOf(alarms)
    }

    @Test
    fun `uiState exposes the repository's alarms and clears the loading flag`() = runTest(testDispatcher) {
        val alarms = listOf(Alarm(id = 1, name = "a"), Alarm(id = 2, name = "b"))
        stubAlarms(alarms)
        val vm = viewModel()

        // A plain launch{} collector, cancelled by hand: a stateIn() flow only starts
        // collecting upstream while it has a subscriber, and a collector parked in
        // runTest's backgroundScope is never resumed by advanceUntilIdle() — the exact
        // trap that made an earlier round of these tests silently observe nothing.
        val seen = mutableListOf<AlarmListUiState>()
        val job = launch { vm.uiState.collect { seen += it } }
        runCurrent()
        job.cancel()

        assertEquals(alarms, seen.last().alarms)
        assertFalse(seen.last().isLoading)
    }

    @Test
    fun `toggling an alarm on persists the change and arms it`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 3, name = "a", isEnabled = false)
        val vm = viewModel()

        vm.toggle(alarm, enabled = true)
        advanceUntilIdle()

        coVerify { repository.setEnabled(3, true) }
        // Scheduled from a copy that already reflects the new state: schedule() checks
        // isActive, so passing the stale isEnabled=false object would arm nothing.
        verify { scheduler.schedule(match { it.id == 3L && it.isEnabled }) }
    }

    @Test
    fun `toggling an alarm off persists the change and cancels it`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.toggle(Alarm(id = 3, name = "a", isEnabled = true), enabled = false)
        advanceUntilIdle()

        coVerify { repository.setEnabled(3, false) }
        verify { scheduler.cancel(3) }
    }

    @Test
    fun `deleting an alarm also cancels its armed trigger`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.delete(Alarm(id = 4, name = "a"))
        advanceUntilIdle()

        coVerify { repository.deleteAlarm(4) }
        verify { scheduler.cancel(4) }
    }

    @Test
    fun `disableAll cancels the alarms that were active before the write`() = runTest(testDispatcher) {
        // The regression this pins down: getActiveAlarms() has to be read *first*.
        // Called after disableAll(), it returns an empty list and nothing at all gets
        // cancelled — every alarm stays armed in AlarmManager and still rings, while
        // the UI shows them all switched off.
        coEvery { repository.getActiveAlarms() } returns listOf(Alarm(id = 1), Alarm(id = 2))
        val vm = viewModel()

        vm.disableAll()
        advanceUntilIdle()

        coVerify { repository.disableAll() }
        verify { scheduler.cancelAll(listOf(1L, 2L)) }
    }

    @Test
    fun `freezeAll cancels the alarms that were active before the write`() = runTest(testDispatcher) {
        coEvery { repository.getActiveAlarms() } returns listOf(Alarm(id = 5))
        val vm = viewModel()

        vm.freezeAll()
        advanceUntilIdle()

        coVerify { repository.freezeAll() }
        verify { scheduler.cancelAll(listOf(5L)) }
    }

    @Test
    fun `unfreezeAll re-arms without a second widget refresh`() = runTest(testDispatcher) {
        // refreshWidgets = false on purpose: unfreezeAll()'s own write to the alarms
        // table already wakes SmartRingApp's observeAlarms() collector, so letting
        // rescheduleAll() refresh as well rebuilt every widget twice per tap.
        coEvery { repository.getActiveAlarms() } returns listOf(Alarm(id = 6))
        val vm = viewModel()

        vm.unfreezeAll()
        advanceUntilIdle()

        coVerify { repository.unfreezeAll() }
        verify { scheduler.rescheduleAll(listOf(Alarm(id = 6)), refreshWidgets = false) }
    }

    @Test
    fun `enableAll re-arms without a second widget refresh`() = runTest(testDispatcher) {
        coEvery { repository.getActiveAlarms() } returns listOf(Alarm(id = 7))
        val vm = viewModel()

        vm.enableAll()
        advanceUntilIdle()

        coVerify { repository.enableAll() }
        verify { scheduler.rescheduleAll(listOf(Alarm(id = 7)), refreshWidgets = false) }
    }
}
