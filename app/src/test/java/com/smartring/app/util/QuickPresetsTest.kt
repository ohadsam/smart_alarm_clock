package com.smartring.app.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The configurable one-tap shortcuts.
 *
 * The property that matters most, and is asserted for every shape: **a shortcut never
 * produces a time in the past.** A tap that silently schedules something already gone is
 * worse than a button that does nothing, because it looks like it worked — the same rule
 * the ad-hoc alarm arithmetic is pinned to.
 */
class QuickPresetsTest {

    @Before
    fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis

    private fun fieldsOf(millis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return Triple(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun relative(minutes: Int) = QuickPreset(1, QuickPresetKind.RELATIVE, minutes = minutes)
    private fun timeOfDay(hour: Int, minute: Int) =
        QuickPreset(1, QuickPresetKind.TIME_OF_DAY, hour = hour, minute = minute)

    // ── Relative presets ───────────────────────────────────────────────────

    @Test
    fun `a relative preset fires that many minutes from now`() {
        val now = at(2026, 9, 19, 14, 30)
        assertEquals(Triple(19, 14, 40), fieldsOf(quickPresetFireAt(relative(10), now)))
        assertEquals(Triple(19, 15, 0), fieldsOf(quickPresetFireAt(relative(30), now)))
        assertEquals(Triple(19, 22, 30), fieldsOf(quickPresetFireAt(relative(8 * 60), now)))
    }

    @Test
    fun `a relative preset crosses midnight`() {
        val now = at(2026, 9, 19, 23, 50)
        assertEquals(Triple(20, 0, 20), fieldsOf(quickPresetFireAt(relative(30), now)))
    }

    @Test
    fun `a relative preset rings on a whole minute`() {
        val now = at(2026, 9, 19, 14, 30)
        val c = Calendar.getInstance().apply { timeInMillis = quickPresetFireAt(relative(10), now) }
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(0, c.get(Calendar.MILLISECOND))
    }

    // ── Time-of-day presets: the next one, and the boundary ────────────────

    @Test
    fun `a time-of-day preset still ahead today fires today`() {
        val now = at(2026, 9, 19, 6, 0)
        assertEquals(Triple(19, 7, 0), fieldsOf(quickPresetFireAt(timeOfDay(7, 0), now)))
    }

    @Test
    fun `a time-of-day preset already passed fires tomorrow`() {
        val now = at(2026, 9, 19, 8, 0)
        assertEquals(Triple(20, 7, 0), fieldsOf(quickPresetFireAt(timeOfDay(7, 0), now)))
    }

    /**
     * Tapped at exactly the preset's own minute. "Now" is not in the future, and
     * `schedule()` cancels a trigger that is not — so the tap would appear to do nothing.
     */
    @Test
    fun `a time-of-day preset tapped exactly on the minute means the next one`() {
        val now = at(2026, 9, 19, 7, 0)
        assertEquals(Triple(20, 7, 0), fieldsOf(quickPresetFireAt(timeOfDay(7, 0), now)))
    }

    @Test
    fun `a time-of-day preset crosses a month boundary`() {
        val now = at(2026, 9, 30, 22, 0)
        assertEquals(Triple(1, 7, 0), fieldsOf(quickPresetFireAt(timeOfDay(7, 0), now)))
    }

    /** The property the whole feature rests on. */
    @Test
    fun `no preset of any shape ever produces a time in the past`() {
        val moments = listOf(
            at(2026, 9, 19, 0, 0), at(2026, 9, 19, 6, 59), at(2026, 9, 19, 7, 0),
            at(2026, 9, 19, 12, 0), at(2026, 9, 19, 23, 59), at(2026, 12, 31, 23, 59),
        )
        val presets = listOf(
            relative(1), relative(10), relative(30), relative(60), relative(8 * 60),
            timeOfDay(0, 0), timeOfDay(7, 0), timeOfDay(23, 59),
        )
        moments.forEach { now ->
            presets.forEach { p ->
                assertTrue(
                    "preset $p tapped at $now produced a time in the past",
                    quickPresetFireAt(p, now) > now,
                )
            }
        }
    }

    // ── Labels say what the chip will do ───────────────────────────────────

    @Test
    fun `relative labels read naturally at every scale`() {
        val now = at(2026, 9, 19, 14, 0)
        assertEquals("עוד 10 דק׳", presetLabel(relative(10), now))
        assertEquals("עוד שעה", presetLabel(relative(60), now))
        assertEquals("עוד שעתיים", presetLabel(relative(120), now))
        assertEquals("עוד 8 שעות", presetLabel(relative(480), now))
        assertEquals("עוד 1 שע׳ 30 דק׳", presetLabel(relative(90), now))
    }

    /**
     * The label must name the day the chip actually lands on. v1.8.1's chip said "מחר"
     * and always meant tomorrow; this one means the next occurrence, so it has to say
     * which that is — otherwise it is the same mismatch with the opposite sign.
     */
    @Test
    fun `a time-of-day label names the day it will really ring`() {
        assertEquals("היום 07:00", presetLabel(timeOfDay(7, 0), at(2026, 9, 19, 6, 0)))
        assertEquals("מחר 07:00", presetLabel(timeOfDay(7, 0), at(2026, 9, 19, 8, 0)))
    }

    @Test
    fun `a time-of-day label zero-pads`() =
        assertEquals("היום 07:05", presetLabel(timeOfDay(7, 5), at(2026, 9, 19, 6, 0)))

    // ── Sanitizing keeps a corrupt store from producing impossible chips ───

    @Test
    fun `out-of-range values are clamped rather than trusted`() {
        val wild = QuickPreset(1, QuickPresetKind.TIME_OF_DAY, minutes = -5, hour = 99, minute = 77)
        val s = wild.sanitized()
        assertEquals(QuickPreset.MIN_MINUTES, s.minutes)
        assertEquals(23, s.hour)
        assertEquals(59, s.minute)
    }

    @Test
    fun `a relative preset longer than a week is capped`() =
        assertEquals(QuickPreset.MAX_MINUTES, relative(999_999).sanitized().minutes)

    // ── Which chips each surface shows ─────────────────────────────────────

    @Test
    fun `each surface shows only the presets marked for it`() {
        val presets = listOf(
            relative(10).copy(id = 1, showInApp = true, showInWidget = false),
            relative(30).copy(id = 2, showInApp = false, showInWidget = true),
        )
        val limits = QuickPresetLimits(maxInApp = 8, maxInWidget = 5)
        assertEquals(listOf(1L), presetsForApp(presets, limits).map { it.id })
        assertEquals(listOf(2L), presetsForWidget(presets, limits).map { it.id })
    }

    @Test
    fun `each surface caps at its own limit`() {
        val presets = (1L..6L).map { relative(10).copy(id = it) }
        val limits = QuickPresetLimits(maxInApp = 2, maxInWidget = 4)
        assertEquals(2, presetsForApp(presets, limits).size)
        assertEquals(4, presetsForWidget(presets, limits).size)
    }

    @Test
    fun `a limit of zero hides the row entirely`() {
        val presets = (1L..3L).map { relative(10).copy(id = it) }
        val limits = QuickPresetLimits(maxInApp = 0, maxInWidget = 0)
        assertTrue(presetsForApp(presets, limits).isEmpty())
        assertTrue(presetsForWidget(presets, limits).isEmpty())
    }

    @Test
    fun `limits beyond their ceiling are clamped`() {
        val s = QuickPresetLimits(maxInApp = 99, maxInWidget = 99).sanitized()
        assertEquals(QuickPresetLimits.MAX_IN_APP_CEILING, s.maxInApp)
        assertEquals(QuickPresetLimits.MAX_IN_WIDGET_CEILING, s.maxInWidget)
    }

    // ── Storage round-trips, and survives a corrupt store ──────────────────

    @Test
    fun `presets survive an encode-decode round trip`() {
        val presets = listOf(
            QuickPreset(1, QuickPresetKind.RELATIVE, minutes = 25, showInWidget = false),
            QuickPreset(7, QuickPresetKind.TIME_OF_DAY, hour = 6, minute = 45, showInApp = false),
        )
        assertEquals(presets, decodeQuickPresets(encodeQuickPresets(presets)))
    }

    @Test
    fun `an empty or missing store yields the built-in presets`() {
        assertEquals(BUILT_IN_QUICK_PRESETS, decodeQuickPresets(null))
        assertEquals(BUILT_IN_QUICK_PRESETS, decodeQuickPresets(""))
        assertEquals(BUILT_IN_QUICK_PRESETS, decodeQuickPresets("   "))
    }

    /**
     * A settings screen that crashes on a bad preferences file is worse than one showing
     * the defaults — and the file can be bad for reasons that are nobody's fault, like a
     * downgrade from a future version that added a field.
     */
    @Test
    fun `unreadable records are dropped rather than failing the whole list`() {
        val good = encodeQuickPresets(listOf(QuickPreset(1, QuickPresetKind.RELATIVE, minutes = 25)))
        val decoded = decodeQuickPresets("garbage|$good|also,bad|3,NOPE,1,1,1,1,1")
        assertEquals(1, decoded.size)
        assertEquals(25, decoded.single().minutes)
    }

    @Test
    fun `a store with nothing usable falls back to the built-ins`() =
        assertEquals(BUILT_IN_QUICK_PRESETS, decodeQuickPresets("garbage|more,garbage"))

    @Test
    fun `an empty list round-trips to the built-ins rather than to nothing`() {
        // Encoding an empty list produces an empty string, which decode reads as "never
        // configured". That is the right reading: the user removes chips by unticking
        // them per surface, and a genuinely empty stored list would otherwise be
        // indistinguishable from a broken feature.
        assertEquals(BUILT_IN_QUICK_PRESETS, decodeQuickPresets(encodeQuickPresets(emptyList())))
    }

    @Test
    fun `a new preset gets an id that collides with nothing stored`() {
        val presets = listOf(relative(10).copy(id = 3), relative(20).copy(id = 9))
        assertEquals(10L, nextPresetId(presets))
        assertEquals(1L, nextPresetId(emptyList()))
    }

    @Test
    fun `the built-in presets are all distinct and usable`() {
        val ids = BUILT_IN_QUICK_PRESETS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val now = at(2026, 9, 19, 14, 0)
        BUILT_IN_QUICK_PRESETS.forEach {
            assertTrue("built-in $it must ring in the future", quickPresetFireAt(it, now) > now)
        }
    }
}
