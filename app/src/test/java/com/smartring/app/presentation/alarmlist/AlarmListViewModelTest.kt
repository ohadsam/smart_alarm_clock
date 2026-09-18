package com.smartring.app.presentation.alarmlist

import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmRing
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
    private lateinit var defaultsRepository: AlarmDefaultsRepository

    private fun viewModel() =
        AlarmListViewModel(repository, defaultsRepository, scheduler, appLogger)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)
        defaultsRepository = mockk(relaxed = true)
        every { defaultsRepository.defaults } returns flowOf(AlarmDefaults.BUILT_IN)
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

    // ── Undo delete ────────────────────────────────────────────────────────
    //
    // Deleting is immediate now, with an undo offer, rather than gated behind a
    // confirmation. That is faster for the deletes that were intended and is the only
    // thing that can actually rescue the one that was a mis-tap.

    @Test
    fun `deleting keeps the full alarm so it can be restored`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 7, name = "למחוק")
        coEvery { repository.getAlarm(7) } returns alarm
        val vm = viewModel()

        vm.delete(alarm)
        advanceUntilIdle()

        assertEquals(alarm, vm.undoableDelete.value)
    }

    /**
     * Re-reading before deleting is not tidiness: saveAlarm replaces an alarm's rings and
     * extra dates wholesale, so restoring a partially-populated copy would hand the user
     * back something quietly different from what they deleted.
     */
    @Test
    fun `deleting re-reads the alarm rather than trusting the list's copy`() = runTest(testDispatcher) {
        val listCopy = Alarm(id = 7, name = "למחוק")
        val full = listCopy.copy(rings = listOf(AlarmRing(durationSeconds = 45, volumePercent = 80)))
        coEvery { repository.getAlarm(7) } returns full
        val vm = viewModel()

        vm.delete(listCopy)
        advanceUntilIdle()

        assertEquals(full.rings, vm.undoableDelete.value?.rings)
    }

    @Test
    fun `undo re-saves the alarm and re-arms it`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 7, name = "למחוק")
        coEvery { repository.getAlarm(7) } returns alarm
        val vm = viewModel()
        vm.delete(alarm)
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { it.id == 7L }) }
        verify { scheduler.schedule(match { it.id == 7L }) }
    }

    @Test
    fun `undo clears the offer so it cannot be applied twice`() = runTest(testDispatcher) {
        val alarm = Alarm(id = 7, name = "למחוק")
        coEvery { repository.getAlarm(7) } returns alarm
        val vm = viewModel()
        vm.delete(alarm)
        advanceUntilIdle()

        vm.undoDelete()
        advanceUntilIdle()

        assertEquals(null, vm.undoableDelete.value)
    }

    @Test
    fun `undo does nothing when there is nothing to undo`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.undoDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.saveAlarm(any()) }
    }

    // ── Multi-select ───────────────────────────────────────────────────────

    @Test
    fun `selection toggles on and off`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.toggleSelection(1)
        assertEquals(setOf(1L), vm.selectedIds.value)
        vm.toggleSelection(1)
        assertEquals(emptySet<Long>(), vm.selectedIds.value)
    }

    @Test
    fun `enabling a selection schedules each one and clears the selection`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(any()) } answers { Alarm(id = firstArg(), name = "a") }
        val vm = viewModel()
        vm.toggleSelection(1); vm.toggleSelection(2)

        vm.setSelectedEnabled(true)
        advanceUntilIdle()

        coVerify { repository.setEnabled(1, true) }
        coVerify { repository.setEnabled(2, true) }
        verify(exactly = 2) { scheduler.schedule(any()) }
        assertEquals(emptySet<Long>(), vm.selectedIds.value)
    }

    @Test
    fun `disabling a selection cancels each one instead of scheduling`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(any()) } answers { Alarm(id = firstArg(), name = "a") }
        val vm = viewModel()
        vm.toggleSelection(3)

        vm.setSelectedEnabled(false)
        advanceUntilIdle()

        verify { scheduler.cancel(3) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `deleting a selection removes and cancels every one`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.toggleSelection(1); vm.toggleSelection(2)

        vm.deleteSelected()
        advanceUntilIdle()

        coVerify { repository.deleteAlarm(1) }
        coVerify { repository.deleteAlarm(2) }
        verify { scheduler.cancel(1) }
        verify { scheduler.cancel(2) }
        assertEquals(emptySet<Long>(), vm.selectedIds.value)
    }

    // ── Quick create ───────────────────────────────────────────────────────

    @Test
    fun `a quick alarm is an ad-hoc one at the requested moment`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 9L
        val vm = viewModel()
        val at = System.currentTimeMillis() + 8 * 3_600_000L

        vm.createQuickAlarm(at)
        advanceUntilIdle()

        coVerify {
            repository.saveAlarm(
                match {
                    // A specific datetime and no repeat days is exactly what makes it
                    // ad-hoc: it rings once and the card then offers the day stepper.
                    it.id == 0L && it.specificDateTime == at && it.repeatDaysBitmask == 0
                },
            )
        }
        verify { scheduler.schedule(any()) }
    }

    @Test
    fun `a quick alarm takes its ring settings from the configured defaults`() = runTest(testDispatcher) {
        every { defaultsRepository.defaults } returns flowOf(
            AlarmDefaults.BUILT_IN.copy(ringDurationSeconds = 240, snoozeEnabled = true, ringVolumePercent = 60),
        )
        coEvery { repository.saveAlarm(any()) } returns 9L
        val vm = viewModel()

        vm.createQuickAlarm(System.currentTimeMillis() + 3_600_000L)
        advanceUntilIdle()

        coVerify {
            repository.saveAlarm(
                match {
                    it.ringDurationSeconds == 240 &&
                        it.snoozeEnabled &&
                        it.rings.single().volumePercent == 60
                },
            )
        }
    }
}
