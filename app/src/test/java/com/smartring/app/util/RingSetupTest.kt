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
            // 300s so both 60s rounds comfortably fit — otherwise the "rounds that
            // never play" rule below fires too and this stops testing only crescendo.
            ringDurationSeconds = 300,
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

    // ── Ring rounds the alarm stops before reaching ──────────────────────────

    @Test
    fun `the edit screen's own default for a second round never plays`() {
        // Exactly what "הוסף סבב צלצול" produces on a fresh alarm: a 60s first round,
        // a 30s second one, against the default 60s ring duration. Round 2 is due to
        // start at t=60, which is the same instant the alarm stops.
        val w = warnings(
            ringDurationSeconds = 60,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 60, volumePercent = 100),
                AlarmRing(orderIndex = 1, durationSeconds = 30, volumePercent = 80, delayAfterSeconds = 300),
            ),
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("רק ל-1 מתוך 2"))
    }

    @Test
    fun `rounds that all fit are not flagged`() {
        assertEquals(emptyList<String>(), warnings(
            ringDurationSeconds = 120,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 30),
                AlarmRing(orderIndex = 1, durationSeconds = 30),
                AlarmRing(orderIndex = 2, durationSeconds = 30),
            ),
        ))
    }

    @Test
    fun `the silent gap between rounds counts against the ring duration`() {
        // Two 20s rounds fit inside 60s on their own; a 30s gap after the first one
        // pushes the second past the end.
        val w = warnings(
            ringDurationSeconds = 45,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 20, delayAfterSeconds = 30),
                AlarmRing(orderIndex = 1, durationSeconds = 20),
            ),
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("רק ל-1 מתוך 2"))
    }

    @Test
    fun `the vibrate-first window pushes rounds past the end of the alarm`() {
        // The sound sequence only starts after the vibration window, so the rounds
        // have that much less of the ring duration to fit into.
        // Vibration to 55s, then round 1 runs 55->85; round 2 would not start until 85,
        // past the 80s the alarm lasts. (Not 40/90: there round 2 *does* start, at 70,
        // and merely gets cut off — which is what "משך צלצול" is for, not a mistake.)
        val w = warnings(
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 55, ringDurationSeconds = 80,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 30),
                AlarmRing(orderIndex = 1, durationSeconds = 30),
            ),
        )
        assertEquals(1, w.size)
        assertTrue(w.single(), w.single().contains("רק ל-1 מתוך 2"))
    }

    @Test
    fun `a round that starts in time is not flagged just because it gets cut off`() {
        // Round 2 starts at 30s, inside the 45s the alarm lasts, and is silenced
        // mid-round. That is exactly what the ring-duration setting is for; warning
        // about it would nag about every ordinary alarm.
        assertEquals(emptyList<String>(), warnings(
            ringDurationSeconds = 45,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 30),
                AlarmRing(orderIndex = 1, durationSeconds = 30),
            ),
        ))
    }

    @Test
    fun `a single round is never flagged for not fitting`() {
        // A lone round longer than the ring duration isn't a mistake — it just gets
        // cut off, which is exactly what "משך צלצול" is for.
        assertEquals(emptyList<String>(), warnings(
            ringDurationSeconds = 10,
            rings = listOf(AlarmRing(orderIndex = 0, durationSeconds = 300)),
        ))
    }

    @Test
    fun `a vibration-only alarm is not told its rounds won't play`() {
        // Nothing plays in this mode by design; complaining that round 2 won't be
        // heard would be noise on top of a setting the user chose deliberately.
        assertEquals(emptyList<String>(), warnings(
            vibrationMode = VibrationMode.VIBRATION_ONLY,
            ringDurationSeconds = 30,
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 60),
                AlarmRing(orderIndex = 1, durationSeconds = 60),
            ),
        ))
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
