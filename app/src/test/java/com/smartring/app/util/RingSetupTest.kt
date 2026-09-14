package com.smartring.app.util

import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.VibrationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each case here is a setup the edit screen's own sliders allow. None of them crashes
 * or looks wrong while editing — they just make the alarm behave differently from
 * what its screen says, and the person finds out the next morning.
 */
class RingSetupTest {

    private fun warnings(
        vibrationMode: VibrationMode = VibrationMode.SOUND_ONLY,
        vibrationOnlySeconds: Int = 10,
        ringDurationSeconds: Int = 60,
        rings: List<AlarmRing> = listOf(AlarmRing(volumePercent = 100)),
        crescendoEnabled: Boolean = false,
        crescendoStartVolume: Int = 10,
        crescendoStepSeconds: Int = 15,
        crescendoStepPercent: Int = 10,
    ) = ringSetupWarnings(
        vibrationMode, vibrationOnlySeconds, ringDurationSeconds, rings,
        crescendoEnabled, crescendoStartVolume, crescendoStepSeconds, crescendoStepPercent,
    )

    @Test
    fun `a sane default setup warns about nothing`() {
        assertEquals(emptyList<String>(), warnings())
    }

    // ── Vibrate-first outlasting the ring ────────────────────────────────────

    @Test
    fun `a vibrate-first window longer than the ring is flagged`() {
        // fireAlarm() arms the auto-stop for ringDurationSeconds and only then waits
        // out vibrationOnlySeconds before any audio starts, so the sound never plays.
        // Reachable straight off the sliders: vibrate-first goes to 120s, ring
        // duration down to 5s.
        val w = warnings(
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 120, ringDurationSeconds = 30,
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("הצליל לא יתחיל להישמע כלל"))
    }

    @Test
    fun `an equal vibrate-first window and ring duration is still flagged`() {
        // The boundary is a real failure, not a near miss: the delay elapses at the
        // same instant the auto-stop fires, so nothing is ever audible.
        val w = warnings(
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 60, ringDurationSeconds = 60,
        )
        assertEquals(1, w.size)
    }

    @Test
    fun `a vibrate-first window that leaves time for sound is fine`() {
        assertEquals(emptyList<String>(), warnings(
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 59, ringDurationSeconds = 60,
        ))
    }

    @Test
    fun `the vibrate-first rule only applies to the mode that has one`() {
        // vibrationOnlySeconds keeps its stored value in every mode; only
        // VIBRATION_THEN_SOUND ever waits on it, so the other three must stay silent
        // even with a value that would be fatal there.
        listOf(VibrationMode.SOUND_ONLY, VibrationMode.VIBRATION_ONLY, VibrationMode.SOUND_AND_VIBRATION)
            .forEach { mode ->
                assertEquals("$mode", emptyList<String>(),
                    warnings(vibrationMode = mode, vibrationOnlySeconds = 120, ringDurationSeconds = 30))
            }
    }

    // ── Crescendo that cannot climb ──────────────────────────────────────────

    @Test
    fun `a crescendo starting at or above the round volume is flagged`() {
        // volumeAtSecond() clamps the ramp to the round's own volume, so an 80%
        // start on a 30% round plays flat at 30% — while the screen shows three
        // crescendo sliders as though they were doing something.
        val w = warnings(
            rings = listOf(AlarmRing(volumePercent = 30)),
            crescendoEnabled = true, crescendoStartVolume = 80,
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("לא יתחזק"))
    }

    @Test
    fun `the loudest round is what decides whether the crescendo can climb`() {
        // A quiet first round doesn't make the whole crescendo pointless if a later
        // round is loud enough to ramp into.
        assertEquals(emptyList<String>(), warnings(
            rings = listOf(AlarmRing(volumePercent = 20), AlarmRing(orderIndex = 1, volumePercent = 100)),
            crescendoEnabled = true, crescendoStartVolume = 40,
            crescendoStepSeconds = 5, crescendoStepPercent = 30,
        ))
    }

    @Test
    fun `crescendo warnings are suppressed entirely when crescendo is off`() {
        assertEquals(emptyList<String>(), warnings(
            rings = listOf(AlarmRing(volumePercent = 10)),
            crescendoEnabled = false, crescendoStartVolume = 80,
        ))
    }

    // ── Crescendo that cannot finish ─────────────────────────────────────────

    @Test
    fun `a ramp too slow to reach full volume before the alarm stops is flagged`() {
        // 5% start, 5% a step, a step a minute: 19 steps = 19 minutes to reach 100%,
        // while the ring duration maxes out at 10. All four numbers are inside their
        // own slider ranges.
        val w = warnings(
            ringDurationSeconds = 600,
            crescendoEnabled = true, crescendoStartVolume = 5,
            crescendoStepSeconds = 60, crescendoStepPercent = 5,
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("תגיע ל-100%"))
    }

    @Test
    fun `a ramp that finishes exactly as the alarm ends is not flagged`() {
        // 10 -> 100 at 10% a step is 9 steps; at 10s a step that is 90s, which fits
        // inside a 90s ring exactly. An off-by-one here would nag about every
        // perfectly-tuned alarm.
        assertEquals(emptyList<String>(), warnings(
            ringDurationSeconds = 90,
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 10, crescendoStepPercent = 10,
        ))
    }

    @Test
    fun `a ramp that needs a partial final step still counts that step`() {
        // 10 -> 100 is 90 points; at 25% a step that is 3.6 steps, i.e. 4 steps of
        // 30s = 120s. Truncating instead of rounding up would call 90s enough.
        val w = warnings(
            ringDurationSeconds = 90,
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 30, crescendoStepPercent = 25,
        )
        assertEquals(1, w.size)
    }

    @Test
    fun `the two crescendo warnings never fire together`() {
        // A ramp that cannot climb at all has no meaningful time-to-full, so telling
        // the user both things at once would be contradictory noise.
        val w = warnings(
            ringDurationSeconds = 5,
            rings = listOf(AlarmRing(volumePercent = 10)),
            crescendoEnabled = true, crescendoStartVolume = 80,
            crescendoStepSeconds = 60, crescendoStepPercent = 5,
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("לא יתחזק"))
    }

    @Test
    fun `a vibrate-first problem and a crescendo problem are both reported`() {
        val w = warnings(
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 120, ringDurationSeconds = 30,
            crescendoEnabled = true, crescendoStartVolume = 5,
            crescendoStepSeconds = 60, crescendoStepPercent = 5,
        )
        assertEquals(2, w.size)
    }

    @Test
    fun `an alarm with no rounds configured is measured against the full-volume default`() {
        // AlarmFiringService substitutes a single 100% round for an empty list, so
        // the warnings have to assume the same thing rather than reading "no rounds"
        // as "no volume".
        assertEquals(emptyList<String>(), warnings(
            rings = emptyList(),
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 5, crescendoStepPercent = 30,
        ))
    }
}
