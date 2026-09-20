package com.smartring.app.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.presentation.widget.SmartRingWidgetLarge
import com.smartring.app.presentation.widget.SmartRingWidgetMedium
import com.smartring.app.presentation.widget.SmartRingWidgetSmall
import com.smartring.app.presentation.widget.SmartRingWidgetWide
import com.smartring.app.presentation.widget.refreshAllWidgets
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The half of the widget that `WidgetRenderTest` cannot reach.
 *
 * That suite renders the Glance composables against a hand-built state object, which
 * proves the *content* is right and proves nothing about whether the widget can obtain
 * that content on a device. `provideGlance` gathers it through the Hilt entry point, a
 * real Room database and two real DataStore files — and if any of that throws, Glance
 * silently substitutes its own error layout. Nothing reaches AppLogger, the host shows
 * the `initialLayout` or an error box, and every other gate in this repo still passes:
 * the JVM suite never touches a device, and the smoke test launches MainActivity rather
 * than looking at a widget.
 *
 * That blind spot is why "the widgets don't work" has survived several releases without
 * ever being localised. This test closes it on all three API levels CI runs: it calls the
 * real data-gathering path against the real graph and fails, by name, if it throws.
 *
 * It deliberately does *not* claim the widget renders correctly on the user's home
 * screen — binding a real widget needs a host and a permission a test cannot grant. It
 * claims the narrower thing that was actually unverified: the data path works on a device.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetDataPathInstrumentedTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var scheduler: AlarmScheduler

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<Long>()

    @Before
    fun setUp() = hiltRule.inject()

    @After
    fun tearDown() = runBlocking {
        // Cancel before deleting: an armed registration outlives the row it came from,
        // and AlarmManager's next-alarm state is device-wide, so a leaked trigger would
        // follow this test into the next one.
        created.forEach { id ->
            scheduler.cancel(id)
            repository.deleteAlarm(id)
        }
        created.clear()
    }

    private fun givenAlarm(name: String, hour: Int): Long = runBlocking {
        val id = repository.saveAlarm(
            Alarm(name = name, hour = hour, minute = 0, repeatDaysBitmask = 0b1111111),
        )
        created += id
        id
    }

    /**
     * Every size, not just one.
     *
     * The four widgets share `widgetState`, so this looks redundant — but "shares the same
     * function today" is not a property a test should assume, and the size a user actually
     * has is the one that matters. The 2x2 in particular shows a single alarm and little
     * else, so a failure there is the hardest to notice and the easiest to mistake for
     * "it just doesn't sync".
     */
    @Test
    fun everyWidgetSizeLoadsItsStateOnADeviceWithoutThrowing() = runBlocking {
        givenAlarm("בוקר", 7)

        listOf(
            "Small" to SmartRingWidgetSmall(),
            "Medium" to SmartRingWidgetMedium(),
            "Wide" to SmartRingWidgetWide(),
            "Large" to SmartRingWidgetLarge(),
        ).forEach { (name, widget) ->
            val state = widget.widgetState(context)

            // loadError is how widgetState reports a caught exception. Non-null here means
            // the real graph, database or preferences threw on this API level — the exact
            // failure that is invisible everywhere else in this repo.
            assertNull(
                "$name widget reported a load failure: ${state.loadError}",
                state.loadError,
            )
            assertTrue(
                "$name widget should see the alarm just written",
                state.rows.isNotEmpty(),
            )
        }
    }

    @Test
    fun widgetStateReflectsAnAlarmWrittenThroughTheRepository() = runBlocking {
        val id = givenAlarm("ערב", 22)

        val state = SmartRingWidgetWide().widgetState(context)

        val row = state.rows.firstOrNull { it.alarm.id == id }
        assertTrue("the widget's rows should contain alarm #$id, got ${state.rows.map { it.alarm.id }}",
            row != null)
        assertEquals("ערב", row?.alarm?.name)
    }

    /** The shortcuts come from their own DataStore file, which is a second way to throw. */
    @Test
    fun widgetStateLoadsTheQuickPresets() = runBlocking {
        val state = SmartRingWidgetWide().widgetState(context)

        assertNull(state.loadError)
        assertTrue(
            "the built-in presets should reach the widget; got ${state.presets.size}",
            state.presets.isNotEmpty(),
        )
    }

    @Test
    fun anEmptyDatabaseIsAnEmptyWidgetRatherThanAFailure() = runBlocking {
        val state = SmartRingWidgetWide().widgetState(context)

        // Whatever the row count, "could not load" and "nothing to show" must stay
        // distinguishable — conflating them is what made a broken widget look correct.
        assertNull(state.loadError)
    }

    /**
     * The refresh path itself, on a device.
     *
     * No widget is placed on a test emulator, so the count is expected to be zero — the
     * assertion is that it *completes and reports*, because until v1.12.0 this path could
     * neither throw visibly nor say what it had done.
     */
    @Test
    fun refreshAllWidgetsCompletesAndReportsACount() = runBlocking {
        val count = refreshAllWidgets(context)

        assertTrue("a refresh must report a non-negative count, got $count", count >= 0)
    }
}
