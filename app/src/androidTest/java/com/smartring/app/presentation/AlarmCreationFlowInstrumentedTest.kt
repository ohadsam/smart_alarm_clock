package com.smartring.app.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.MainActivity
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.presentation.alarmedit.AlarmEditTags
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The one path every user takes and nothing had ever exercised end to end: tapping +,
 * naming an alarm, saving, and getting back to a list that shows it.
 *
 * The existing instrumented test launches MainActivity and asserts the *empty* state,
 * which proves the app starts but nothing about whether it can be used. Everything
 * between the two is unique to this test: navigation to the edit screen, the edit
 * ViewModel's validation, the repository's upsert against real SQLite, the Flow that
 * pushes the new row back to the list, and the scheduler arming it. A regression in
 * any one of those leaves an app that opens perfectly and cannot create an alarm.
 *
 * Cleans up in both @Before and @After, because the sibling test class asserts on the
 * empty state and the two share one installed app: leaving a row behind would make the
 * suite order-dependent, and a failure here would poison the other class rather than
 * just failing itself.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmCreationFlowInstrumentedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler

    private val alarmName = "בדיקת קצה לקצה"

    @Before
    fun setUp() {
        hiltRule.inject()
        clearAlarms()
        composeRule.waitForIdle()
        dismissReliabilityPromptIfShown()
    }

    @After
    fun tearDown() = clearAlarms()

    private fun clearAlarms() = runBlocking {
        repository.observeAlarms().first().forEach { repository.deleteAlarm(it.id) }
    }

    /**
     * An emulator has battery optimization on and no exact-alarm grant, so
     * ReliabilityGate's dialog legitimately opens over the list on launch and would
     * swallow the tap on the + button.
     */
    private fun dismissReliabilityPromptIfShown() {
        val later = composeRule.onAllNodesWithText("אחר כך")
        if (later.fetchSemanticsNodes().isNotEmpty()) {
            later[0].performClick()
            composeRule.waitForIdle()
        }
    }

    private fun createAlarmNamed(name: String) {
        composeRule.onNodeWithContentDescription("הוסף שעמור").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AlarmEditTags.NAME_FIELD).performTextInput(name)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("שמור").performClick()

        // The save is a suspend call into Room, so the list can take a frame or two to
        // receive it. waitUntil rather than waitForIdle: the Flow emission is not an
        // idle-blocking operation, so waitForIdle alone can return before the row lands.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anAlarmCreatedThroughTheUiAppearsInTheList() {
        createAlarmNamed(alarmName)
        composeRule.onNodeWithText(alarmName).assertIsDisplayed()
    }

    @Test
    fun theAlarmCreatedThroughTheUiIsPersistedAndArmedForTheFuture() {
        createAlarmNamed(alarmName)

        val saved = runBlocking { repository.observeAlarms().first() }
        assertEquals("exactly one alarm should have been created", 1, saved.size)
        assertEquals(alarmName, saved[0].name)
        assertTrue("a newly created alarm must be enabled", saved[0].isEnabled)

        // The row existing is not the same as the alarm ringing. A save that persists
        // but computes no next fire time is an alarm the user believes is set and that
        // never goes off — the worst failure this app has.
        val next = scheduler.nextFireTime(saved[0])
        assertNotNull("a new alarm must resolve to a next fire time", next)
        assertTrue("the next fire time must be in the future", next!! > System.currentTimeMillis())
    }
}
