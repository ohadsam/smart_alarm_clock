package com.smartring.app.presentation.alarmedit

import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.data.repository.TemplateRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.LibraryError
import com.smartring.app.domain.model.LibraryResult
import com.smartring.app.domain.model.TemplateFolder
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The editor's two newest jobs: an alarm for several phones at once, and templates. */
class AlarmEditTemplatesAndTargetsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AlarmRepository
    private lateinit var templates: TemplateRepository
    private lateinit var vm: AlarmEditViewModel

    private val childA = "a".repeat(32)
    private val childB = "b".repeat(32)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        templates = mockk(relaxed = true)
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        every { scheduler.effectiveNextFireTime(any()) } returns System.currentTimeMillis() + 3_600_000L
        val defaults = mockk<AlarmDefaultsRepository>(relaxed = true)
        every { defaults.defaults } returns flowOf(AlarmDefaults.BUILT_IN)
        vm = AlarmEditViewModel(repository, defaults, scheduler, mockk<AppLogger>(relaxed = true), templates)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    // ── several phones at once ───────────────────────────────────────────────

    @Test
    fun `a new alarm for this phone and two children becomes three independent alarms`() = runTest(dispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 1L
        vm.setName("בוקר")
        vm.toggleTargetChild(childA)
        vm.toggleTargetChild(childB)

        vm.save()
        advanceUntilIdle()

        val saved = mutableListOf<Alarm>()
        coVerify(exactly = 3) { repository.saveAlarm(capture(saved)) }
        assertEquals(setOf<String?>(null, childA, childB), saved.map { it.sync.managedForDeviceId }.toSet())
        assertTrue(saved.all { it.name == "בוקר" })
        assertTrue(vm.state.value.isSaved)
    }

    @Test
    fun `an alarm only for a child is not also saved for this phone`() = runTest(dispatcher) {
        coEvery { repository.saveAlarm(any()) } returns 1L
        vm.startNew(targetDeviceId = childA)
        advanceUntilIdle()
        vm.setName("x")

        vm.save()
        advanceUntilIdle()

        val saved = mutableListOf<Alarm>()
        coVerify(exactly = 1) { repository.saveAlarm(capture(saved)) }
        assertEquals(childA, saved.single().sync.managedForDeviceId)
    }

    @Test
    fun `at least one target always stays chosen`() {
        vm.toggleTargetThisDevice()
        assertTrue("the only target cannot be removed", vm.state.value.targetThisDevice)

        vm.toggleTargetChild(childA)
        vm.toggleTargetThisDevice()
        assertFalse(vm.state.value.targetThisDevice)
        vm.toggleTargetChild(childA)
        assertEquals(setOf(childA), vm.state.value.targetChildren)
    }

    // ── templates ────────────────────────────────────────────────────────────

    private fun template(alarm: Alarm, folder: String? = null) = AlarmTemplate("t1", "בוקר חול", folder, alarm)

    @Test
    fun `applying a template fills the form and reports a date that had passed`() = runTest(dispatcher) {
        val past = System.currentTimeMillis() - 5 * 86_400_000L
        val alarm = Alarm(name = "טיסה", hour = 5, minute = 15, specificDateTime = past, snoozeEnabled = true, snoozeMinutes = 7)
        coEvery { templates.current() } returns TemplateLibrary(templates = listOf(template(alarm)))
        vm.toggleTargetChild(childA)

        vm.applyTemplate("t1")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("טיסה", s.name)
        assertNull("a date in the past is dropped", s.specificDateTime)
        assertEquals(7, s.snoozeMinutes)
        assertEquals(1, s.templateNotices.size)
        assertEquals("the choice of phones is about this alarm, not a setting", setOf(childA), s.targetChildren)

        vm.dismissTemplateNotices()
        assertTrue(vm.state.value.templateNotices.isEmpty())
    }

    @Test
    fun `saving the form as a template stores what is on screen`() = runTest(dispatcher) {
        val slot = slot<Alarm>()
        coEvery { templates.saveNew("שגרה", null, capture(slot)) } returns LibraryResult.Ok(TemplateLibrary(), "id")
        vm.setName("קום")
        vm.setSnoozeEnabled(true)

        vm.saveAsTemplate("שגרה", null)
        advanceUntilIdle()

        assertEquals("קום", slot.captured.name)
        assertTrue(slot.captured.snoozeEnabled)
    }

    @Test
    fun `saving as a template can create the folder first`() = runTest(dispatcher) {
        coEvery { templates.addFolder("עבודה") } returns LibraryResult.Ok(TemplateLibrary(), "f9")
        coEvery { templates.saveNew(any(), "f9", any()) } returns LibraryResult.Ok(TemplateLibrary(), "id")
        vm.setName("x")

        vm.saveAsTemplate("תבנית", null, newFolderName = "עבודה")
        advanceUntilIdle()

        coVerify { templates.saveNew("תבנית", "f9", any()) }
    }

    @Test
    fun `a refused template save is reported and not announced as saved`() = runTest(dispatcher) {
        coEvery { templates.saveNew(any(), any(), any()) } returns LibraryResult.Refused(LibraryError.LIMIT_TEMPLATES)
        val messages = mutableListOf<String>()
        val job = launch { vm.templateMessages.collect { messages += it } }
        advanceUntilIdle()

        vm.saveAsTemplate("x", null)
        advanceUntilIdle()

        assertEquals(1, messages.size)
        assertFalse(messages[0].startsWith("נשמר"))
        job.cancel()
    }

    @Test
    fun `editing a template and saving writes the changes back to it`() = runTest(dispatcher) {
        coEvery { templates.current() } returns TemplateLibrary(
            folders = listOf(TemplateFolder("f", "תיקייה")),
            templates = listOf(template(Alarm(name = "א", hour = 6, minute = 0), "f")),
        )
        coEvery { templates.update("t1", any(), any()) } returns LibraryResult.Ok(TemplateLibrary(), "t1")
        vm.loadTemplate("t1")
        advanceUntilIdle()
        assertTrue(vm.state.value.isTemplateMode)
        assertEquals("בוקר חול", vm.state.value.templateName)

        vm.setTemplateName("שם חדש")
        vm.save()
        advanceUntilIdle()

        coVerify { templates.update("t1", "שם חדש", match { it.hour == 6 }) }
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
        assertTrue(vm.state.value.isSaved)
    }

    @Test
    fun `a new template needs a name, but its alarm does not`() = runTest(dispatcher) {
        coEvery { templates.saveNew(any(), any(), any()) } returns LibraryResult.Ok(TemplateLibrary(), "id")
        vm.startNewTemplate("f")
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.templateNameError)
        assertFalse(vm.state.value.isSaved)

        vm.setTemplateName("שלי")
        vm.save()
        advanceUntilIdle()
        coVerify { templates.saveNew("שלי", "f", any()) }
        assertTrue(vm.state.value.isSaved)
        coVerify(exactly = 0) { repository.saveAlarm(any()) }
    }

    @Test
    fun `saving a template as a copy leaves the original alone`() = runTest(dispatcher) {
        coEvery { templates.current() } returns TemplateLibrary(templates = listOf(template(Alarm(name = "א"))))
        coEvery { templates.saveNew(any(), any(), any()) } returns LibraryResult.Ok(TemplateLibrary(), "t2")
        vm.loadTemplate("t1")
        advanceUntilIdle()

        vm.saveTemplateAsCopy()
        advanceUntilIdle()

        coVerify { templates.saveNew("בוקר חול", null, any()) }
        coVerify(exactly = 0) { templates.update(any(), any(), any()) }
    }
}
