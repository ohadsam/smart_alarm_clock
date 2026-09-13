package com.smartring.app.presentation.alarmedit

import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val job = launch { vm.scrollToNameRequests.collect { scrollRequested = true } }
        vm.setName("   ") // blank after trim

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.nameError)
        assertTrue(scrollRequested)
        assertFalse(vm.state.value.isSaved)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
        job.cancel()
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

        vm.save()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        assertEquals(2, events.size)
        job.cancel()
    }

    @Test
    fun `isDirty is false right after loading an alarm unchanged`() = runTest(testDispatcher) {
        coEvery { repository.getAlarm(5) } returns Alarm(id = 5, name = "Existing")

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
