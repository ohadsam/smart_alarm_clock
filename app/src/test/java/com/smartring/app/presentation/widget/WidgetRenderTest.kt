package com.smartring.app.presentation.widget

import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.WidgetAlarmEntry
import java.util.Calendar
import java.util.TimeZone
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
            onNode(hasText("כבוי — הקש להפעלה")).assertExists()
        }

    // ── The day label: the half of "when" the widget never used to show ────

    @Test
    fun `a row shows which day it rings, not only the time`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "מחרתיים", at(2026, 9, 21, 7, 0))), palette)
        }
        // 2026-09-21 is a Monday; two days out, so the weekday name rather than מחר.
        onNode(hasText("יום ב׳")).assertExists()
    }

    @Test
    fun `tomorrow reads as tomorrow`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(1, "מחר", at(2026, 9, 20, 7, 0))), palette)
        }
        onNode(hasText("מחר")).assertExists()
    }

    @Test
    fun `a snoozed row says so and still shows its day`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            ListBody(ctx, state(entry(3, "נודניק", at(2026, 9, 19, 9, 10), snoozed = true)), palette)
        }
        onNode(hasText("נודניק · היום")).assertExists()
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
}
