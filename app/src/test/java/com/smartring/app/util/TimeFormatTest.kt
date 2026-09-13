package com.smartring.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure string-formatting logic — no Android dependencies. */
class TimeFormatTest {

    // ── formatDurationSeconds ────────────────────────────────────────

    @Test
    fun `formatDurationSeconds under a minute shows seconds only`() {
        assertEquals("45 שנ׳", formatDurationSeconds(45))
        assertEquals("0 שנ׳", formatDurationSeconds(0))
        assertEquals("59 שנ׳", formatDurationSeconds(59))
    }

    @Test
    fun `formatDurationSeconds on an exact minute boundary omits seconds`() {
        assertEquals("1 דק׳", formatDurationSeconds(60))
        assertEquals("2 דק׳", formatDurationSeconds(120))
    }

    @Test
    fun `formatDurationSeconds with a remainder shows both minutes and seconds`() {
        assertEquals("1 דק׳ 30 שנ׳", formatDurationSeconds(90))
    }

    // ── formatCountdownUntil ──────────────────────────────────────────

    @Test
    fun `formatCountdownUntil under a minute away`() {
        val now = 1_000_000L
        assertEquals("בעוד פחות מדקה", formatCountdownUntil(targetMillis = now + 30_000, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil minutes only`() {
        val now = 1_000_000L
        assertEquals("בעוד 20 דק׳", formatCountdownUntil(targetMillis = now + 20 * 60_000L, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil hours only on an exact hour boundary`() {
        val now = 1_000_000L
        assertEquals("בעוד 3 שע׳", formatCountdownUntil(targetMillis = now + 3 * 3_600_000L, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil hours and minutes`() {
        val now = 1_000_000L
        val target = now + 3 * 3_600_000L + 20 * 60_000L
        assertEquals("בעוד 3 שע׳ 20 דק׳", formatCountdownUntil(targetMillis = target, nowMillis = now))
    }

    @Test
    fun `formatCountdownUntil never goes negative for a target already in the past`() {
        val now = 1_000_000L
        assertEquals("בעוד פחות מדקה", formatCountdownUntil(targetMillis = now - 60_000, nowMillis = now))
    }
}
