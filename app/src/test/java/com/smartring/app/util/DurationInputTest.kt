package com.smartring.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The duration dialog splits entry into minutes + seconds while storage stays in whole
 * seconds. That conversion is what decides how long an alarm actually rings, so it is
 * covered here rather than left to the composable: a UI bug shows a wrong number, this
 * arithmetic going wrong rings for the wrong length of time.
 */
class DurationInputTest {

    // ── Splitting a stored value into fields ────────────────────────────────

    @Test
    fun `a sub-minute duration is all seconds`() =
        assertEquals(DurationParts(0, 45), durationPartsOf(45))

    @Test
    fun `a whole number of minutes leaves no seconds`() =
        assertEquals(DurationParts(2, 0), durationPartsOf(120))

    @Test
    fun `a mixed duration splits across both fields`() =
        assertEquals(DurationParts(7, 30), durationPartsOf(450))

    @Test
    fun `zero is zero in both fields`() =
        assertEquals(DurationParts(0, 0), durationPartsOf(0))

    /** Nothing should be able to hand this a negative, but clamping beats crashing. */
    @Test
    fun `a negative stored value is treated as zero`() =
        assertEquals(DurationParts(0, 0), durationPartsOf(-30))

    // ── Carrying an overflowing seconds field ───────────────────────────────

    @Test
    fun `120 seconds becomes two minutes`() =
        assertEquals(DurationParts(2, 0), normalizeDurationParts(0, 120))

    @Test
    fun `90 seconds on top of a minute becomes two minutes thirty`() =
        assertEquals(DurationParts(2, 30), normalizeDurationParts(1, 90))

    @Test
    fun `a value already in range is left alone`() =
        assertEquals(DurationParts(3, 20), normalizeDurationParts(3, 20))

    @Test
    fun `59 seconds does not carry`() =
        assertEquals(DurationParts(0, 59), normalizeDurationParts(0, 59))

    @Test
    fun `carrying preserves the total`() {
        assertEquals(200, normalizeDurationParts(0, 200).totalSeconds)
        assertEquals(200, normalizeDurationParts(3, 20).totalSeconds)
    }

    /**
     * Both fields are free text capped at four digits, so 9999 in each is reachable by
     * typing. In Int, 9999 * 60 is fine, but the guard matters for the general case:
     * an overflow wraps negative, and a negative total would pass a `>= min` check and
     * store a duration the user never asked for.
     */
    @Test
    fun `an absurd minutes value saturates instead of overflowing`() {
        val parts = normalizeDurationParts(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, parts.totalSeconds)
    }

    @Test
    fun `negative input in either field is floored at zero`() {
        assertEquals(DurationParts(0, 30), normalizeDurationParts(-5, 30))
        assertEquals(DurationParts(5, 0), normalizeDurationParts(5, -30))
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun `a value inside the range has no error`() =
        assertNull(durationRangeError(300, 5, 600))

    @Test
    fun `both bounds are inclusive`() {
        assertNull(durationRangeError(5, 5, 600))
        assertNull(durationRangeError(600, 5, 600))
    }

    @Test
    fun `below the minimum reports the minimum in the units it is typed in`() {
        val error = durationRangeError(2, 5, 600)
        assertNotNull(error)
        assertEquals("המינימום הוא 5 שנ׳", error)
    }

    @Test
    fun `above the maximum reports the maximum as minutes, not raw seconds`() {
        // 600 seconds read back as "600" would be the same unhelpful number the
        // seconds-only box showed; the bound has to read the way the field does.
        assertEquals("המקסימום הוא 10 דק׳", durationRangeError(900, 5, 600))
    }

    @Test
    fun `zero is rejected when the minimum is above zero`() =
        assertNotNull(durationRangeError(0, 5, 600))

    /** The delay-after-round slider legitimately starts at 0. */
    @Test
    fun `zero is accepted when the minimum is zero`() =
        assertNull(durationRangeError(0, 0, 600))

    // ── Display ─────────────────────────────────────────────────────────────

    @Test
    fun `a sub-minute preview does not restate the total`() =
        assertEquals("45 שנ׳", durationPreview(45))

    @Test
    fun `a longer preview shows both the friendly form and the stored seconds`() =
        assertEquals("7 דק׳ 30 שנ׳  ·  450 שנ׳ סה״כ", durationPreview(450))

    @Test
    fun `the range hint reads in both units`() =
        assertEquals("טווח: 5 שנ׳ – 10 דק׳", durationRangeHint(5, 600))

    // ── Round trip ──────────────────────────────────────────────────────────

    /**
     * The property that actually matters: whatever the user types, the value stored is
     * the value the preview showed them.
     */
    @Test
    fun `splitting and recombining any duration returns it unchanged`() {
        for (seconds in 0..3600) {
            assertEquals(seconds, durationPartsOf(seconds).totalSeconds)
        }
    }
}
