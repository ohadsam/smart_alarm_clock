package com.smartring.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is actually coming out of the speaker at second N.
 *
 * This is the model AlarmRingScreen's crescendo readout draws from, and it has to
 * agree with AlarmFiringService second for second, because the screen is the only
 * place a user can check what their volume and vibration settings really do. The bug
 * it replaced: the bar charted volumeAtSecond(100, …) — a hard-coded full-volume base
 * — so a 50%-volume round crescendoing from 10% climbed 10→50 through the speaker
 * while the screen drew 10→100, and kept drawing a rising bar long after the sound
 * had levelled off. It also drew that bar over a vibration-only alarm, which makes no
 * sound at all.
 */
class AlarmPlaybackTest {

    private val ring100 = AlarmRing(orderIndex = 0, durationSeconds = 60, volumePercent = 100)

    // ── effectiveRings ───────────────────────────────────────────────────────

    @Test
    fun `an alarm with no rounds plays one full-volume round`() {
        // AlarmFiringService substitutes exactly this; both sides read it from here.
        val rings = Alarm(rings = emptyList()).effectiveRings
        assertEquals(1, rings.size)
        assertEquals(100, rings.single().volumePercent)
    }

    @Test
    fun `rounds play in orderIndex order, not list order`() {
        val alarm = Alarm(rings = listOf(
            AlarmRing(orderIndex = 2, volumePercent = 30),
            AlarmRing(orderIndex = 0, volumePercent = 10),
            AlarmRing(orderIndex = 1, volumePercent = 20),
        ))
        assertEquals(listOf(10, 20, 30), alarm.effectiveRings.map { it.volumePercent })
    }

    // ── Which round is audible ───────────────────────────────────────────────

    @Test
    fun `a single round is audible from the first second`() {
        val alarm = Alarm(rings = listOf(ring100))
        assertEquals(0, alarm.ringAtSecond(0)!!.index)
        assertEquals(0, alarm.ringAtSecond(59)!!.index)
    }

    @Test
    fun `the rounds advance and then loop`() {
        val alarm = Alarm(rings = listOf(
            AlarmRing(orderIndex = 0, durationSeconds = 10, volumePercent = 40),
            AlarmRing(orderIndex = 1, durationSeconds = 20, volumePercent = 90),
        ))
        assertEquals(0, alarm.ringAtSecond(9)!!.index)
        assertEquals(1, alarm.ringAtSecond(10)!!.index)
        assertEquals(1, alarm.ringAtSecond(29)!!.index)
        // The whole sequence restarts rather than stopping after the last round.
        assertEquals(0, alarm.ringAtSecond(30)!!.index)
        assertEquals(1, alarm.ringAtSecond(41)!!.index)
    }

    @Test
    fun `the silent gap after a round is silent`() {
        val alarm = Alarm(rings = listOf(
            AlarmRing(orderIndex = 0, durationSeconds = 10, delayAfterSeconds = 5, volumePercent = 40),
            AlarmRing(orderIndex = 1, durationSeconds = 10, volumePercent = 90),
        ))
        assertEquals(0, alarm.ringAtSecond(9)!!.index)
        assertNull("the delayAfterSeconds gap makes no sound", alarm.ringAtSecond(10))
        assertNull(alarm.ringAtSecond(14))
        assertEquals(1, alarm.ringAtSecond(15)!!.index)
    }

    // ── Vibration modes ──────────────────────────────────────────────────────

    @Test
    fun `a vibration-only alarm is never audible`() {
        val alarm = Alarm(rings = listOf(ring100), vibrationMode = VibrationMode.VIBRATION_ONLY)
        assertNull(alarm.ringAtSecond(0))
        assertNull(alarm.ringAtSecond(300))
        assertNull(alarm.audibleVolumeAtSecond(0))
    }

    @Test
    fun `vibrate-then-sound is silent for exactly the configured window`() {
        val alarm = Alarm(
            rings = listOf(ring100),
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 10,
        )
        assertNull(alarm.ringAtSecond(0))
        assertNull(alarm.ringAtSecond(9))
        assertEquals(0, alarm.ringAtSecond(10)!!.index)
    }

    @Test
    fun `vibrate-then-sound starts its round sequence when the sound starts`() {
        // The offset matters: startAudioSequence() is handed vibrationOnlySeconds as
        // its starting elapsed, so round 1 must run for its full length *after* the
        // vibration window, not be treated as already part-way through.
        val alarm = Alarm(
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 10, volumePercent = 40),
                AlarmRing(orderIndex = 1, durationSeconds = 10, volumePercent = 90),
            ),
            vibrationMode = VibrationMode.VIBRATION_THEN_SOUND,
            vibrationOnlySeconds = 15,
        )
        assertEquals(0, alarm.ringAtSecond(15)!!.index)
        assertEquals(0, alarm.ringAtSecond(24)!!.index)
        assertEquals(1, alarm.ringAtSecond(25)!!.index)
    }

    @Test
    fun `sound and vibration together are audible from the first second`() {
        val alarm = Alarm(rings = listOf(ring100), vibrationMode = VibrationMode.SOUND_AND_VIBRATION)
        assertEquals(0, alarm.ringAtSecond(0)!!.index)
    }

    // ── The volume the screen must show ──────────────────────────────────────

    @Test
    fun `the audible volume is the round's own volume, not a full-volume base`() {
        // The exact bug the ring screen had: this alarm's speaker goes 10% -> 50%,
        // and the bar used to draw 10% -> 100%.
        val alarm = Alarm(
            rings = listOf(AlarmRing(orderIndex = 0, durationSeconds = 600, volumePercent = 50)),
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 10, crescendoStepPercent = 10,
        )
        assertEquals(10, alarm.audibleVolumeAtSecond(0))
        assertEquals(20, alarm.audibleVolumeAtSecond(10))
        assertEquals(50, alarm.audibleVolumeAtSecond(40))
        // Held at the round's ceiling from there on, exactly where startCrescendo()
        // stops ramping.
        assertEquals(50, alarm.audibleVolumeAtSecond(300))
    }

    @Test
    fun `each round is measured against its own ceiling while the ramp keeps running`() {
        // startAudioSequence() carries one cumulative elapsed counter across rounds,
        // so the ramp does not restart per round — but it is re-clamped to whatever
        // the current round's volume is.
        val alarm = Alarm(
            rings = listOf(
                AlarmRing(orderIndex = 0, durationSeconds = 30, volumePercent = 100),
                AlarmRing(orderIndex = 1, durationSeconds = 30, volumePercent = 40),
            ),
            crescendoEnabled = true, crescendoStartVolume = 10,
            crescendoStepSeconds = 10, crescendoStepPercent = 10,
        )
        assertEquals(30, alarm.audibleVolumeAtSecond(20))   // round 1, ramping toward 100
        assertEquals(40, alarm.audibleVolumeAtSecond(40))   // round 2, clamped to its own 40
    }

    @Test
    fun `without crescendo the audible volume is simply the round's volume`() {
        val alarm = Alarm(rings = listOf(AlarmRing(orderIndex = 0, durationSeconds = 60, volumePercent = 35)))
        assertEquals(35, alarm.audibleVolumeAtSecond(0))
        assertEquals(35, alarm.audibleVolumeAtSecond(59))
    }

    @Test
    fun `a round quieter than the crescendo's starting volume just plays flat`() {
        // coerceIn(min, max) throws outright when min > max, which is reachable: the
        // round volume and the crescendo start are edited by two independent sliders.
        val alarm = Alarm(
            rings = listOf(AlarmRing(orderIndex = 0, durationSeconds = 60, volumePercent = 10)),
            crescendoEnabled = true, crescendoStartVolume = 80,
            crescendoStepSeconds = 10, crescendoStepPercent = 10,
        )
        assertEquals(10, alarm.audibleVolumeAtSecond(0))
        assertEquals(10, alarm.audibleVolumeAtSecond(50))
    }
}
