package com.smartring.app.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test: launches the real MainActivity on the emulator and checks the
 * app actually renders. Everything upstream of the first frame has to work for this to
 * pass — the Hilt graph, Room opening the database, the theme, the navigation graph
 * and the alarm list's own Flow plumbing — none of which the JVM suite exercises
 * together.
 *
 * Deliberately asserts on the empty state rather than on seeded alarms: the test runs
 * against a freshly installed app, and anything depending on pre-existing data would
 * be order-dependent between test classes.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmListScreenInstrumentedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * An emulator has battery optimization on and no exact-alarm/notification grants,
     * so ReliabilityGate's "הגדרות מומלצות לאמינות" dialog legitimately opens over the
     * list on launch. Dismissing it is part of what these tests verify works — and it
     * keeps them from depending on which of those OS settings a given emulator image
     * happens to start with.
     */
    @Before
    fun dismissReliabilityPromptIfShown() {
        composeRule.waitForIdle()
        val later = composeRule.onAllNodesWithText("אחר כך")
        if (later.fetchSemanticsNodes().isNotEmpty()) {
            later[0].performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun theAppLaunchesAndShowsTheAlarmList() {
        composeRule.onNodeWithText("SmartRing").assertIsDisplayed()
    }

    @Test
    fun anEmptyInstallExplainsHowToAddAnAlarm() {
        composeRule.onNodeWithText("אין שעמורים עדיין").assertIsDisplayed()
        composeRule.onNodeWithText("לחץ + להוספת שעמור").assertIsDisplayed()
    }
}
