package com.smartring.app.presentation.alarmedit

import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Plain JUnit (no Robolectric/Android environment needed): AlarmEditViewModel's
 * constructor only takes AlarmRepository/AlarmScheduler/AppLogger, all mocked here.
 */
class AlarmEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var appLogger: AppLogger
    private lateinit var vm: AlarmEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)
        vm = AlarmEditViewModel(repository, scheduler, appLogger)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save with a blank name sets nameError and emits a scroll request, without saving`() = runTest(testDispatcher) {
        var scrollRequested = false
        // Plain launch (not backgroundScope!) so this job is counted towards
        // advanceUntilIdle()'s notion of "idle" — advanceUntilIdle()/runCurrent()
        // stop advancing virtual time once only backgroundScope coroutines remain
        // unprocessed (by design, so an indefinitely-looping background job can't
        // hang them forever), so a value emitted via tryEmit() to a backgroundScope
        // collector is never actually delivered by either function, even though
        // subscriptionCount confirms the collector is genuinely subscribed and
        // tryEmit() returns true. runCurrent() right after launching is still
        // needed: on StandardTestDispatcher, launch{} only *schedules* the
        // collector — it isn't actually subscribed until the dispatcher is
        // pumped, so an emit() before that pump would be missed regardless.
        val job = launch { vm.scrollToNameRequests.collect { scrollRequested = true } }
        runCurrent()
        vm.setName("   ") // blank after trim

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.nameError)
        assertTrue(scrollRequested)
        assertFalse(vm.state.value.isSaved)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
        // cancel() alone doesn't guarantee the job reaches a terminal state before
        // runTest's own end-of-test completion check — pump once more so it does.
        job.cancel()
        runCurrent()
    }

    @Test
    fun `save with a real name clears the error and persists the alarm`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 42L
        vm.setName("Wake up")

        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.nameError)
        assertTrue(vm.state.value.isSaved)
        coVerify { repository.saveAlarm(match { it.name == "Wake up" }) }
    }

    @Test
    fun `typing a name after a failed save clears nameError`() = runTest(testDispatcher) {
        vm.save() // blank name -> nameError = true
        advanceUntilIdle()
        assertTrue(vm.state.value.nameError)

        vm.setName("Wake up")

        assertFalse(vm.state.value.nameError)
    }

    @Test
    fun `a repeated failed save re-emits the scroll request, not just the first time`() = runTest(testDispatcher) {
        val events = mutableListOf<Unit>()
        val job = launch { vm.scrollToNameRequests.collect { events += it } }
        runCurrent()

        vm.save()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        assertEquals(2, events.size)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `isDirty is false right after loading an alarm unchanged`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5) } returns Alarm(id = 5, name = "Existing")

        vm.loadAlarm(5)
        advanceUntilIdle()

        assertFalse(vm.isDirty)
    }

    @Test
    fun `isDirty is false right after loading an alarm whose next fire time is not null`() = runTest(testDispatcher) {
        // Regression guard: loadAlarm() used to capture originalState *before* computing
        // nextFireHint, then mutate _state with the computed hint afterward — so the two
        // diverged immediately whenever effectiveNextFireTime() didn't return null (i.e.
        // for essentially every real, active alarm), making isDirty true the instant an
        // existing alarm was opened for editing, with nothing actually edited yet.
        coEvery { repository.getAlarm(5) } returns Alarm(id = 5, name = "Existing")
        every { scheduler.effectiveNextFireTime(any()) } returns System.currentTimeMillis() + 3_600_000L

        vm.loadAlarm(5)
        advanceUntilIdle()

        assertFalse(vm.isDirty)
    }

    @Test
    fun `isDirty becomes true after an actual edit`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5) } returns Alarm(id = 5, name = "Existing")
        vm.loadAlarm(5)
        advanceUntilIdle()

        vm.setName("Changed")

        assertTrue(vm.isDirty)
    }

    // ── Specific datetime / until-date normalisation ─────────────────

    @Test
    fun `setSpecificDateTime syncs hour and minute to the picked time`() = runTest(testDispatcher) {
        // hour/minute are what the alarm list, the widgets and the notification all
        // read; leaving them at the 07:00 default while the alarm actually rings at
        // 21:30 showed the wrong time everywhere outside this screen.
        val at = Calendar.getInstance().apply {
            set(2030, Calendar.MARCH, 12, 21, 30, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        vm.setSpecificDateTime(at)

        assertEquals(21, vm.state.value.hour)
        assertEquals(30, vm.state.value.minute)
        assertEquals(at, vm.state.value.specificDateTime)
    }

    @Test
    fun `clearing the specific datetime leaves the time fields alone`() = runTest(testDispatcher) {
        vm.setTime(6, 15)
        vm.setSpecificDateTime(null)
        assertEquals(6, vm.state.value.hour)
        assertEquals(15, vm.state.value.minute)
        assertNull(vm.state.value.specificDateTime)
    }

    @Test
    fun `setRecurrenceUntilDate stores the end of the chosen local day`() = runTest(testDispatcher) {
        // Compose's DatePicker reports UTC midnight. isRecurrenceExpired() treats the
        // stored value as a hard cutoff, so storing it raw expired the alarm in the
        // small hours of the chosen day (UTC midnight is 02:00/03:00 local here) and
        // skipped that morning's ring — one day earlier than the user asked for.
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2030, Calendar.MARCH, 20, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        vm.setRecurrenceUntilDate(utcMidnight)

        val stored = Calendar.getInstance().apply { timeInMillis = vm.state.value.recurrenceUntilDate!! }
        assertEquals(2030, stored.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, stored.get(Calendar.MONTH))
        assertEquals(20, stored.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, stored.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, stored.get(Calendar.MINUTE))
        // The whole point: an alarm ringing that same morning is still in range.
        val thatMorning = Calendar.getInstance().apply {
            set(2030, Calendar.MARCH, 20, 7, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(vm.state.value.recurrenceUntilDate!! > thatMorning)
    }

    @Test
    fun `setRecurrenceUntilDate accepts null for clearing the end date`() = runTest(testDispatcher) {
        vm.setRecurrenceUntilDate(null)
        assertNull(vm.state.value.recurrenceUntilDate)
    }

    @Test
    fun `isDirty clears again after a failed validation attempt once the field is fixed back`() = runTest(testDispatcher) {
        // Regression guard: a validation-attempt counter briefly lived inside
        // AlarmEditUiState itself, which — since isDirty compares the whole state by
        // structural equality against originalState — permanently marked the screen
        // dirty after one failed Save even once every real field matched again.
        coEvery { repository.getAlarm(5) } returns Alarm(id = 5, name = "Existing")
        vm.loadAlarm(5)
        advanceUntilIdle()
        assertFalse(vm.isDirty)

        vm.setName("")           // genuinely dirty now (name changed)
        vm.save()                // fails validation: sets nameError, emits scroll request
        advanceUntilIdle()
        vm.setName("Existing")   // back to the original value

        assertFalse("re-typing the original name should clear isDirty entirely", vm.isDirty)
    }
}
