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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    fun `DIAG bare SharedFlow sanity check`() = runTest(testDispatcher) {
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var received = false
        backgroundScope.launch { flow.collect { received = true } }
        runCurrent()
        println("DIAG bare: subscriptionCount=${flow.subscriptionCount.value}")
        val emitted = flow.tryEmit(Unit)
        println("DIAG bare: tryEmit returned $emitted")
        advanceUntilIdle()
        println("DIAG bare: received=$received")
        assertTrue(received)
    }

    @Test
    fun `save with a blank name sets nameError and emits a scroll request, without saving`() = runTest(testDispatcher) {
        var scrollRequested = false
        // backgroundScope (not a plain launch{}) so this indefinitely-collecting job is
        // cancelled automatically when the test ends instead of needing job.cancel().
        // runCurrent() right after starting it is required too: on StandardTestDispatcher,
        // launch{} only *schedules* the collector — it isn't actually subscribed to the
        // SharedFlow until the dispatcher is pumped, so an emit() before that pump is
        // missed entirely (replay = 0, and the extra buffer doesn't back-fill a
        // not-yet-subscribed collector).
        println("DIAG before launch")
        val job = backgroundScope.launch {
            println("DIAG collector coroutine starting")
            try {
                vm.scrollToNameRequests.collect {
                    println("DIAG collector received value")
                    scrollRequested = true
                }
                println("DIAG collect() RETURNED (should never happen for a SharedFlow)")
            } catch (e: Throwable) {
                println("DIAG collect() THREW: $e")
                throw e
            }
        }
        println("DIAG after launch, before runCurrent")
        runCurrent()
        println("DIAG after runCurrent, job.isActive=${job.isActive} job.isCompleted=${job.isCompleted} job.isCancelled=${job.isCancelled}")
        vm.setName("   ") // blank after trim

        vm.save()
        println("DIAG after save, before advanceUntilIdle")
        advanceUntilIdle()
        println("DIAG after advanceUntilIdle, scrollRequested=$scrollRequested")

        assertTrue(vm.state.value.nameError)
        assertTrue(scrollRequested)
        assertFalse(vm.state.value.isSaved)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
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
        backgroundScope.launch { vm.scrollToNameRequests.collect { events += it } }
        runCurrent()

        vm.save()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        assertEquals(2, events.size)
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
