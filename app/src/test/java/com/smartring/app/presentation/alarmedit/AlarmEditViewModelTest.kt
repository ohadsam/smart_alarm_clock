package com.smartring.app.presentation.alarmedit

import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.RecurrenceEnd
import com.smartring.app.domain.model.RecurrenceEndType
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Plain JUnit (no Robolectric/Android environment needed): every one of
 * AlarmEditViewModel's dependencies is mocked here.
 *
 * AlarmDefaultsRepository is stubbed to emit the shipped defaults, so these tests keep
 * describing the behaviour of a default new alarm rather than of whatever the user
 * happens to have configured.
 */
class AlarmEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var appLogger: AppLogger
    private lateinit var defaultsRepository: AlarmDefaultsRepository
    private lateinit var vm: AlarmEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)
        defaultsRepository = mockk(relaxed = true)
        every { defaultsRepository.defaults } returns flowOf(AlarmDefaults.BUILT_IN)
        vm = AlarmEditViewModel(repository, defaultsRepository, scheduler, appLogger)
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

    // ── A save that fails ─────────────────────────────────────────

    @Test
    fun `a failing save clears the spinner and reports the failure`() = runTest(testDispatcher) {
        // Before this had an error path at all, isSaving stayed true forever: the save
        // button span on a screen that never closed, and nothing told the user their
        // alarm had not been written.
        coEvery { repository.saveAlarm(any()) } throws IllegalStateException("disk full")
        vm.setName("Wake up")

        vm.save()
        advanceUntilIdle()

        assertFalse("the spinner must stop", vm.state.value.isSaving)
        assertTrue("the failure must be visible", vm.state.value.saveError)
        assertFalse("a failed save must not navigate away", vm.state.value.isSaved)
    }

    @Test
    fun `a save that fails while arming the alarm is reported too`() = runTest(testDispatcher) {
        // The row is written but the alarm is not armed — reporting success here would
        // leave an alarm sitting in the list that never rings.
        coEvery { repository.saveAlarm(any()) } returns 42L
        every { scheduler.schedule(any()) } throws SecurityException("too many alarms")
        vm.setName("Wake up")

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.saveError)
        assertFalse(vm.state.value.isSaved)
    }

    @Test
    fun `the edited values survive a failed save so they can be retried`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } throws IllegalStateException("disk full")
        vm.setName("Wake up")
        vm.setTime(6, 30)

        vm.save()
        advanceUntilIdle()

        assertEquals("Wake up", vm.state.value.name)
        assertEquals(6, vm.state.value.hour)
        assertEquals(30, vm.state.value.minute)
    }

    @Test
    fun `retrying after a failure clears the previous error`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } throws IllegalStateException("disk full")
        vm.setName("Wake up")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saveError)

        coEvery { repository.saveAlarm(any()) } returns 42L
        vm.save()
        advanceUntilIdle()

        assertFalse("a stale error banner would outlive the problem", vm.state.value.saveError)
        assertTrue(vm.state.value.isSaved)
    }

    // ── Fields the screen doesn't edit but must not reset ──────────

    @Test
    fun `editing an alarm preserves the state it does not show`() = runTest(testDispatcher) {
        // isEnabled/isFrozen/occurrencesFired are not on this screen, and rebuilding the
        // alarm from the form alone silently re-enabled a disabled alarm, un-froze a
        // frozen one, and reset a COUNT-limited recurrence's progress to zero — so a
        // recurrence set to run 10 times restarted from 1 on every edit.
        val existing = Alarm(id = 7, name = "בוקר", isEnabled = false, isFrozen = true,
            occurrencesFired = 6,
            recurrenceEnd = RecurrenceEnd(RecurrenceEndType.COUNT, count = 10))
        coEvery { repository.getAlarm(7) } returns existing
        coEvery { repository.saveAlarm(any()) } returns 7L

        vm.loadAlarm(7)
        advanceUntilIdle()
        vm.setName("בוקר מאוחר")
        vm.save()
        advanceUntilIdle()

        coVerify {
            repository.saveAlarm(match {
                it.name == "בוקר מאוחר" && !it.isEnabled && it.isFrozen && it.occurrencesFired == 6
            })
        }
    }

    @Test
    fun `saving trims whitespace from the name`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 1L
        vm.setName("   בוקר   ")

        vm.save()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { it.name == "בוקר" }) }
    }

    @Test
    fun `a name of only whitespace is rejected like a blank one`() = runTest(testDispatcher) {
        vm.setName("    ")

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.nameError)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
    }

    // ── Reviving an alarm that has already rung ─────────────────────────────
    //
    // The bug these cover was the worst one this app has had. A one-time alarm switches
    // itself off after ringing; the edit screen preserved that state and offered no way
    // to change it; so opening the alarm, giving it a new time and saving wrote the off
    // state straight back and schedule() cancelled it. It saved and could never ring,
    // and it disappeared from the widgets and the status bar at the same time, because
    // both only ever show armed alarms.

    private fun rungOneTimeAlarm() = Alarm(
        id = 5, name = "חד-פעמי", hour = 7, minute = 0,
        isEnabled = false, occurrencesFired = 1, repeatDaysBitmask = 0,
    )

    @Test
    fun `an alarm that rang loads with its off state visible`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()

        vm.loadAlarm(5L)
        advanceUntilIdle()

        assertFalse("the off state must be shown, not hidden", vm.state.value.isEnabled)
        assertTrue("and remembered, so saving it back on counts as a revival",
            vm.state.value.loadedDisabled)
    }

    @Test
    fun `switching a rung alarm back on and saving stores it enabled`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()
        coEvery { repository.saveAlarm(any()) } returns 5L
        vm.loadAlarm(5L)
        advanceUntilIdle()

        vm.setEnabled(true)
        vm.save()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { it.isEnabled }) }
    }

    /**
     * Re-enabling without this puts a COUNT-limited alarm straight back into
     * isRecurrenceExpired(), so schedule() cancels it again and the toggle appears to do
     * nothing at all.
     */
    @Test
    fun `reviving a rung alarm resets its occurrence counter`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()
        coEvery { repository.saveAlarm(any()) } returns 5L
        vm.loadAlarm(5L)
        advanceUntilIdle()

        vm.setEnabled(true)
        vm.save()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { it.occurrencesFired == 0 }) }
    }

    /** Editing an alarm that is mid-way through a count must not restart the count. */
    @Test
    fun `editing an already-enabled alarm keeps its occurrence counter`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(6L) } returns
            rungOneTimeAlarm().copy(id = 6, isEnabled = true, occurrencesFired = 3)
        coEvery { repository.saveAlarm(any()) } returns 6L
        vm.loadAlarm(6L)
        advanceUntilIdle()

        vm.setName("שם אחר")
        vm.save()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { it.occurrencesFired == 3 }) }
    }

    @Test
    fun `an alarm the user left switched off stays off when saved`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()
        coEvery { repository.saveAlarm(any()) } returns 5L
        vm.loadAlarm(5L)
        advanceUntilIdle()

        // No setEnabled(true): the original intent — never silently re-enable an alarm
        // the user deliberately turned off — still has to hold.
        vm.save()
        advanceUntilIdle()

        coVerify { repository.saveAlarm(match { !it.isEnabled }) }
    }

    // ── A specific date/time in the past is refused ─────────────────────────

    @Test
    fun `saving a specific date in the past is refused`() = runTest(testDispatcher) {
        vm.setName("אתמול")
        vm.setSpecificDateTime(System.currentTimeMillis() - 86_400_000L)

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.pastDateError)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
    }

    @Test
    fun `saving a specific date in the future is allowed`() = runTest(testDispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 1L
        vm.setName("מחר")
        vm.setSpecificDateTime(System.currentTimeMillis() + 86_400_000L)

        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.pastDateError)
        coVerify { repository.saveAlarm(any()) }
    }

    @Test
    fun `picking a new date clears the past-date refusal`() = runTest(testDispatcher) {
        vm.setName("תיקון")
        vm.setSpecificDateTime(System.currentTimeMillis() - 86_400_000L)
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.pastDateError)

        vm.setSpecificDateTime(System.currentTimeMillis() + 86_400_000L)

        assertFalse("the refusal must clear on the next pick, not linger until save",
            vm.state.value.pastDateError)
    }

    // ── Duplicating ────────────────────────────────────────────────────────

    @Test
    fun `a duplicate arrives enabled, uncounted, and renamed`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()

        vm.loadAsCopy(5L)
        advanceUntilIdle()

        val st = vm.state.value
        assertTrue("a copy of a finished alarm must arrive ready to run", st.isEnabled)
        assertEquals(0, st.occurrencesFired)
        assertEquals("חד-פעמי (עותק)", st.name)
    }

    @Test
    fun `duplicating writes a new alarm rather than overwriting the original`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm()
        coEvery { repository.saveAlarm(any()) } returns 9L
        vm.loadAsCopy(5L)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        // id 0 is what tells the DAO to insert; anything else updates alarm 5 in place.
        coVerify { repository.saveAlarm(match { it.id == 0L }) }
    }

    @Test
    fun `duplicating does not re-suffix a name that already says copy`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5L) } returns rungOneTimeAlarm().copy(name = "בוקר (עותק)")

        vm.loadAsCopy(5L)
        advanceUntilIdle()

        assertEquals("בוקר (עותק)", vm.state.value.name)
    }

    // ── New alarms start from the configured defaults ───────────────────────

    @Test
    fun `a new alarm uses the user's configured defaults`() = runTest(testDispatcher) {
        every { defaultsRepository.defaults } returns flowOf(
            AlarmDefaults.BUILT_IN.copy(
                hour = 5, minute = 45, ringDurationSeconds = 180,
                snoozeEnabled = true, snoozeMinutes = 7,
            ),
        )

        vm.startNew()
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(5, st.hour)
        assertEquals(45, st.minute)
        assertEquals(180, st.ringDurationSeconds)
        assertTrue(st.snoozeEnabled)
        assertEquals(7, st.snoozeMinutes)
    }

    @Test
    fun `the unnamed default fills the name in so the form is savable immediately`() = runTest(testDispatcher) {
        every { defaultsRepository.defaults } returns
            flowOf(AlarmDefaults.BUILT_IN.copy(unnamed = true))

        vm.startNew()
        advanceUntilIdle()

        assertTrue(vm.state.value.unnamed)
        assertEquals("כללי", vm.state.value.name)
    }

    /** A prefill from History names the alarm, so it must win over the unnamed default. */
    @Test
    fun `a prefilled name overrides the unnamed default`() = runTest(testDispatcher) {
        every { defaultsRepository.defaults } returns
            flowOf(AlarmDefaults.BUILT_IN.copy(unnamed = true))

        vm.startNew(name = "מהיסטוריה")
        advanceUntilIdle()

        assertEquals("מהיסטוריה", vm.state.value.name)
        assertFalse(vm.state.value.unnamed)
    }

    // ── The unnamed toggle ─────────────────────────────────────────────────

    @Test
    fun `switching unnamed on fills the generic name and off clears it`() = runTest(testDispatcher) {
        vm.setUnnamed(true)
        assertEquals("כללי", vm.state.value.name)

        vm.setUnnamed(false)
        assertEquals("", vm.state.value.name)
    }

    @Test
    fun `switching unnamed off does not erase a name the user typed`() = runTest(testDispatcher) {
        vm.setUnnamed(true)
        vm.setName("שלי")

        vm.setUnnamed(false)

        assertEquals("שלי", vm.state.value.name)
    }
}
