package com.smartring.app.presentation

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The duration dialog's *wiring*, which the unit tests deliberately cannot reach.
 *
 * DurationInputTest covers the arithmetic — splitting, carrying, validating. What it
 * cannot cover is whether the dialog hands the computed total to the right callback, and
 * whether that total survives the save into Room. Getting the arithmetic right and the
 * wiring wrong produces an alarm that rings for a length of time the user never chose,
 * which looks correct everywhere except on the phone at 07:00.
 *
 * Typing 120 into the seconds field is the specific case worth an emulator: it must come
 * back as 2 minutes, not as 120 minutes and not as 120 seconds mis-scaled.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DurationInputInstrumentedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        hiltRule.inject()
        clearAlarms()
        composeRule.waitForIdle()
        val later = composeRule.onAllNodesWithText("אחר כך")
        if (later.fetchSemanticsNodes().isNotEmpty()) {
            later[0].performClick()
            composeRule.waitForIdle()
        }
    }

    /** Cancel before delete: saving arms a real, user-wide AlarmManager registration. */
    @After
    fun tearDown() = clearAlarms()

    private fun clearAlarms() = runBlocking {
        repository.observeAlarms().first().forEach {
            scheduler.cancel(it.id)
            repository.deleteAlarm(it.id)
        }
    }

    @Test
    // camelCase, not a backticked sentence. Backticked names with spaces are fine for
    // the JVM suite but these are dexed: D8 rejects spaces in method names below API 30,
    // and minSdk here is 26. It fails at compile time with no JUnit XML produced at all,
    // which is why the first run of this test reported nothing to look at.
    fun typing120SecondsIsStoredAsTwoMinutes() {
        composeRule.onNodeWithContentDescription("הוסף שעמור").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AlarmEditTags.NAME_FIELD).performTextInput("משך")
        composeRule.onNodeWithTag(AlarmEditTags.FORM_LIST)
            .performScrollToNode(hasText("משך צלצול"))
        composeRule.waitForIdle()

        // By tag, not by text: several sliders here legitimately read "1 דק׳" at their
        // defaults (ring duration, snooze length, per-round duration), so matching on the
        // text finds three nodes and the click fails with "more than one node matched".
        composeRule.onNodeWithTag(AlarmEditTags.RING_DURATION_BADGE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AlarmEditTags.DURATION_MINUTES).performTextClearance()
        composeRule.onNodeWithTag(AlarmEditTags.DURATION_MINUTES).performTextInput("0")
        composeRule.onNodeWithTag(AlarmEditTags.DURATION_SECONDS).performTextClearance()
        composeRule.onNodeWithTag(AlarmEditTags.DURATION_SECONDS).performTextInput("120")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("אישור").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("שמור").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repository.observeAlarms().first() }.isNotEmpty()
        }

        val saved = runBlocking { repository.observeAlarms().first() }.single()
        assertEquals("120 typed into the seconds field must store 120 seconds",
            120, saved.ringDurationSeconds)
    }
}
