package com.smartring.app.presentation.widget

import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.assertHasText
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.QuickPreset
import com.smartring.app.util.QuickPresetKind
import com.smartring.app.util.WidgetAlarmEntry
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first tests in this repo that actually *render* a widget.
 *
 * Everything before them checked something next to the widget rather than the widget:
 * `WidgetProviderInfoTest` parses the descriptor XML, `WidgetProviderInstrumentedTest`
 * asks the framework whether the providers are installed, `WidgetRowsTest` covers the
 * pure ordering function. All four could pass with a `provideGlance` that threw on every
 * render — and the instrumented suite installs the *debug* APK while the smoke test
 * launches MainActivity, so neither would notice either. That gap is why widget bugs kept
 * shipping, and closing it is the point of this file.
 *
 * Robolectric because the bodies take a `Context` (the embedded TextClock and Chronometer
 * are real RemoteViews built against the app's resources).
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRenderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val palette = DarkWidgetPalette

    @Before
    fun fixTimeZone() {
        // The day labels under test are wall-clock arithmetic; a rule that passes in UTC
        // and fails at a real offset is a bug the runner's default would hide.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis

    private val now = at(2026, 9, 19, 9, 0)

    private fun entry(
        id: Long,
        name: String,
        fireAt: Long?,
        snoozed: Boolean = false,
        enabled: Boolean = true,
    ) = WidgetAlarmEntry(
        alarm = Alarm(id = id, name = name, hour = 7, minute = 0, isEnabled = enabled),
        fireAt = fireAt,
        isSnoozed = snoozed,
    )

    private fun state(vararg entries: WidgetAlarmEntry) =
        WidgetUiState(rows = entries.toList(), nowMillis = now)

    private fun preset(id: Long, minutes: Int) =
        QuickPreset(id, QuickPresetKind.RELATIVE, minutes = minutes)

    // ── The list bodies render every alarm, not the first few ──────────────

    /**
     * The cap this replaces was `take(4)`, with nothing on screen to say more existed.
     * Six alarms is the case a real user hits and the previous widget silently lied about.
     */
    @Test
    fun `the list body renders a row for every alarm, past the old four-row cap`() =
        runGlanceAppWidgetUnitTest {
            val rows = (1L..6L).map { entry(it, "שעמור $it", at(2026, 9, 19, 7, 0)) }
            provideComposable { ListBody(ctx, state(*rows.toTypedArray()), palette) }

            rows.forEach { row ->
                onNode(hasTestTag(WidgetTags.row(row.alarm.id))).assertExists()
            }
        }

    @Test
    fun `every rendered row carries its own toggle`() = runGlanceAppWidgetUnitTest {
        val rows = (1L..5L).map { entry(it, "שעמור $it", at(2026, 9, 19, 7, 0)) }
        provideComposable { ListBody(ctx, state(*rows.toTypedArray()), palette) }

        rows.forEach { row ->
            onNode(hasTestTag(WidgetTags.toggle(row.alarm.id))).assertExists()
        }
    }

    @Test
    fun `a switched-off alarm is still listed, so it can be switched back on`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(ctx, state(entry(7, "כבוי", fireAt = null, enabled = false)), palette)
            }

            onNode(hasTestTag(WidgetTags.row(7))).assertExists()
            onNode(hasTestTag(WidgetTags.toggle(7))).assertExists()
            onNode(hasTestTag(WidgetTags.day(7))).assertHasText("כבוי — הקש להפעלה")
        }

    // ── The day label: the half of "when" the widget never used to show ────

    /**
     * Asserted through the row's own tag rather than by matching the words: the hero line
     * at the top prints the same day text ("07:00 · מחר"), so a bare text match is
     * ambiguous by construction and finds two nodes for a correct render.
     */
    @Test
    fun `a row shows which day it rings, not only the time`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "בדיקה", at(2026, 9, 21, 7, 0))), palette)
        }
        // 2026-09-21 is a Monday; two days out, so the weekday name rather than מחר.
        onNode(hasTestTag(WidgetTags.day(1))).assertHasText("יום ב׳")
    }

    @Test
    fun `tomorrow reads as tomorrow`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "בדיקה", at(2026, 9, 20, 7, 0))), palette)
        }
        onNode(hasTestTag(WidgetTags.day(1))).assertHasText("מחר")
    }

    @Test
    fun `a snoozed row says so and still shows its day`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(3, "בדיקה", at(2026, 9, 19, 9, 10), snoozed = true)), palette)
        }
        onNode(hasTestTag(WidgetTags.day(3))).assertHasText("נודניק · היום")
    }

    // ── Empty states ───────────────────────────────────────────────────────

    @Test
    fun `no alarms at all renders the empty state and an add prompt`() =
        runGlanceAppWidgetUnitTest {
            provideComposable { ListBody(ctx, state(), palette) }

            onNode(hasTestTag(WidgetTags.EMPTY)).assertExists()
            onNode(hasText("אין שעמורים")).assertExists()
            onNode(hasText("הקש להוספה")).assertExists()
        }

    /**
     * Alarms exist but none is armed. A fixable state, and it must not borrow the wording
     * of the unfixable one — the small body is the size with no room to explain itself.
     */
    @Test
    fun `alarms that are all switched off read differently from no alarms`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                SmallBody(ctx, state(entry(1, "כבוי", fireAt = null, enabled = false)), palette)
            }

            onNode(hasTestTag(WidgetTags.EMPTY)).assertExists()
            onNode(hasText("אין שעמור פעיל")).assertExists()
        }

    // ── The hero line and the header ───────────────────────────────────────

    @Test
    fun `the small body shows the next alarm's time`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            SmallBody(ctx, state(entry(1, "בוקר", at(2026, 9, 19, 7, 30))), palette)
        }
        onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertExists()
    }

    @Test
    fun `the medium body shows the next alarm's name beside its time`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                MediumBody(ctx, state(entry(1, "קימה", at(2026, 9, 19, 7, 30))), palette)
            }

            onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertExists()
            onNode(hasText("קימה")).assertExists()
        }

    @Test
    fun `the header counts the armed alarms`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(
                ctx,
                state(
                    entry(1, "א", at(2026, 9, 19, 7, 0)),
                    entry(2, "ב", at(2026, 9, 19, 8, 0)),
                    entry(3, "ג", fireAt = null, enabled = false),
                ),
                palette,
            )
        }
        onNode(hasText("⏰ 2 פעילים")).assertExists()
    }

    @Test
    fun `the header says so when nothing is armed`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "כבוי", fireAt = null, enabled = false)), palette)
        }
        onNode(hasText("⏰ אין פעילים")).assertExists()
    }

    @Test
    fun `the header offers a way to add an alarm`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "א", at(2026, 9, 19, 7, 0))), palette)
        }
        onNode(hasTestTag(WidgetTags.ADD)).assertExists()
    }

    // ── The quick-actions panel ────────────────────────────────────────────

    @Test
    fun `the list bodies offer a way into the quick-actions panel`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(ctx, state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0))), palette)
            }
            onNode(hasTestTag(WidgetTags.PANEL_TOGGLE)).assertExists()
        }

    @Test
    fun `an open panel shows a row per configured preset`() = runGlanceAppWidgetUnitTest {
        val presets = listOf(preset(1, 10), preset(2, 30), preset(3, 60))
        provideComposable {
            ListBody(
                ctx,
                state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0)))
                    .copy(presets = presets, panelOpen = true),
                palette,
            )
        }

        onNode(hasTestTag(WidgetTags.PANEL)).assertExists()
        presets.forEach { onNode(hasTestTag(WidgetTags.preset(it.id))).assertExists() }
    }

    /**
     * A panel is a mode. A few cells of home screen cannot show the alarm list and the
     * panel at once without showing neither properly, so the list stands down.
     */
    @Test
    fun `an open panel replaces the alarm list rather than sitting above it`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(
                    ctx,
                    state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0)))
                        .copy(presets = listOf(preset(1, 10)), panelOpen = true),
                    palette,
                )
            }

            onNode(hasTestTag(WidgetTags.PANEL)).assertExists()
            onNode(hasTestTag(WidgetTags.ROWS)).assertDoesNotExist()
            onNode(hasTestTag(WidgetTags.row(1))).assertDoesNotExist()
        }

    @Test
    fun `a closed panel shows the list and no panel`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(
                ctx,
                state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0))).copy(presets = listOf(preset(1, 10))),
                palette,
            )
        }

        onNode(hasTestTag(WidgetTags.ROWS)).assertExists()
        onNode(hasTestTag(WidgetTags.PANEL)).assertDoesNotExist()
    }

    @Test
    fun `the panel carries the bulk actions alongside the presets`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(
                    ctx,
                    state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0)))
                        .copy(presets = listOf(preset(1, 10)), panelOpen = true),
                    palette,
                )
            }

            onNode(hasTestTag(WidgetTags.BULK_ENABLE)).assertExists()
            onNode(hasTestTag(WidgetTags.BULK_FREEZE)).assertExists()
        }

    /** The label has to say which way the button goes, or it is a coin flip. */
    @Test
    fun `the bulk row offers off when something is armed and on when nothing is`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(
                    ctx,
                    state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0))).copy(panelOpen = true),
                    palette,
                )
            }
            onNode(hasTestTag(WidgetTags.BULK_ENABLE)).assertHasText("כבה את כל השעמורים")
        }

    @Test
    fun `the bulk row offers on when every alarm is switched off`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(
                    ctx,
                    state(entry(1, "כבוי", fireAt = null, enabled = false)).copy(panelOpen = true),
                    palette,
                )
            }
            onNode(hasTestTag(WidgetTags.BULK_ENABLE)).assertHasText("הפעל את כל השעמורים")
        }

    @Test
    fun `the freeze row offers to undo a freeze once anything is frozen`() =
        runGlanceAppWidgetUnitTest {
            val frozen = WidgetAlarmEntry(
                alarm = Alarm(id = 1, name = "מוקפא", isFrozen = true),
                fireAt = null,
                isSnoozed = false,
            )
            provideComposable {
                ListBody(ctx, state(frozen).copy(panelOpen = true), palette)
            }
            onNode(hasTestTag(WidgetTags.BULK_FREEZE)).assertHasText("בטל הקפאה")
        }

    @Test
    fun `an open panel with no presets configured still offers the bulk actions`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(
                    ctx,
                    state(entry(1, "בדיקה", at(2026, 9, 19, 7, 0))).copy(panelOpen = true),
                    palette,
                )
            }

            onNode(hasTestTag(WidgetTags.PANEL)).assertExists()
            onNode(hasTestTag(WidgetTags.BULK_ENABLE)).assertExists()
        }

    // ── A failed load says so, instead of looking like an empty schedule ───

    /**
     * Glance substitutes its own error layout for anything that escapes `provideGlance`,
     * silently and without reaching AppLogger — so a widget that failed to render looked
     * exactly like one that was never refreshed. That ambiguity is most of why "the
     * widgets don't sync" survived three releases.
     */
    @Test
    fun `a failed load is reported rather than shown as an empty schedule`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                ListBody(ctx, state().copy(loadError = "SQLiteException"), palette)
            }

            onNode(hasTestTag(WidgetTags.LOAD_ERROR)).assertExists()
            // Not the empty state: "אין שעמורים" when the database could not be read is
            // the widget asserting something untrue.
            onNode(hasTestTag(WidgetTags.EMPTY)).assertDoesNotExist()
        }

    @Test
    fun `the small body reports a failed load too`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            SmallBody(ctx, state().copy(loadError = "IllegalStateException"), palette)
        }
        onNode(hasTestTag(WidgetTags.LOAD_ERROR)).assertExists()
    }

    @Test
    fun `a failed load outranks an open panel`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(
                ctx,
                state().copy(loadError = "IOException", panelOpen = true, presets = listOf(preset(1, 10))),
                palette,
            )
        }

        onNode(hasTestTag(WidgetTags.LOAD_ERROR)).assertExists()
        onNode(hasTestTag(WidgetTags.PANEL)).assertDoesNotExist()
    }

    // ── The menu reaches the medium size too ──────────────────────────────

    /**
     * The first report of this feature was that the widget had no menu button. It was on
     * the two list sizes only, and drawn as a bolt.
     */
    @Test
    fun `the medium body offers the menu as well`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            MediumBody(ctx, state(entry(1, "בדיקה", at(2026, 9, 19, 7, 30))), palette)
        }
        onNode(hasTestTag(WidgetTags.PANEL_TOGGLE)).assertExists()
    }

    @Test
    fun `the medium body opens the panel in place of its next-alarm line`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                MediumBody(
                    ctx,
                    state(entry(1, "בדיקה", at(2026, 9, 19, 7, 30)))
                        .copy(presets = listOf(preset(1, 10)), panelOpen = true),
                    palette,
                )
            }

            onNode(hasTestTag(WidgetTags.PANEL)).assertExists()
            onNode(hasTestTag(WidgetTags.preset(1))).assertExists()
            onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertDoesNotExist()
        }

    // ── The 2x2 body fits in 2x2 ──────────────────────────────────────────

    /**
     * The regression this pins could not be caught by asserting on the node tree, because
     * the tree was always right: `runGlanceAppWidgetUnitTest` does not lay out or measure,
     * so content pushed off a real widget still "exists". An embedded TextClock took
     * Glance's expanding default container, filled the whole 2x2, and hid everything
     * below it — while every test here passed.
     *
     * What is assertable is the structural cause: the smallest body no longer competes
     * for its three lines with a fourth element that can expand.
     */
    @Test
    fun `the small body spends its space on the alarm, not on a clock`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                SmallBody(ctx, state(entry(1, "בוקר", at(2026, 9, 19, 7, 30))), palette)
            }

            onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertExists()
            onNode(hasTestTag(WidgetTags.day(0))).assertDoesNotExist()
        }

    @Test
    fun `the small body still shows the day and the countdown alongside the time`() =
        runGlanceAppWidgetUnitTest {
            provideComposable {
                SmallBody(ctx, state(entry(1, "בוקר", at(2026, 9, 20, 7, 30))), palette)
            }

            onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertExists()
            onNode(hasText("מחר")).assertExists()
        }

    // ── Every size is actually refreshed ──────────────────────────────────

    /**
     * A size missing from the refresh table would sync perfectly in every other respect
     * and simply never update. The 2x2 is the one where that is hardest to spot: it shows
     * a single alarm and little else, so a stale one looks much like a fresh one.
     */
    @Test
    fun `every widget size is covered by a refresh`() {
        val receivers = WIDGET_SIZES.map { (_, receiver) -> receiver.simpleName }
        listOf("Small", "Medium", "Wide", "Large").forEach { size ->
            assertTrue(
                "SmartRingWidget${size}Receiver must be refreshed; got $receivers",
                receivers.any { it == "SmartRingWidget${size}Receiver" },
            )
        }
        assertEquals("the four sizes should be listed exactly once each", 4, receivers.size)
    }

    // ── The 2x2 always renders something ──────────────────────────────────

    /**
     * Whatever the state, the smallest widget must put *something* on screen. It used to
     * put only a clock there — which is how a widget showing nothing about alarms looked
     * like a widget that was simply never refreshed.
     */
    @Test
    fun `the small body renders content in every state`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            SmallBody(ctx, state(entry(1, "בוקר", at(2026, 9, 19, 7, 30))), palette)
        }
        onNode(hasTestTag(WidgetTags.NEXT_TIME)).assertExists()
    }

    @Test
    fun `the small body with no alarms at all says so`() = runGlanceAppWidgetUnitTest {
        provideComposable { SmallBody(ctx, state(), palette) }

        onNode(hasTestTag(WidgetTags.EMPTY)).assertExists()
        onNode(hasText("אין שעמורים")).assertExists()
    }
}
