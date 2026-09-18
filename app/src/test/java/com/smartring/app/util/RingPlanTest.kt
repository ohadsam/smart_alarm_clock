package com.smartring.app.util

import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.VibrationMode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The computed ring plan.
 *
 * Tested because it is the answer to a question that prose failed to answer twice: what
 * "משך צלצול כולל" means next to a round's own duration. If this sentence is wrong it is
 * worse than absent — it would teach the user a mechanism the app does not have.
 *
 * The numbers here mirror AlarmFiringService: one stopwatch of ringDurationSeconds ends
 * the alarm, and the rounds are a playlist that loops inside it.
 */
class RingPlanTest {

    private fun round(duration: Int, delayAfter: Int = 0) =
        AlarmRing(durationSeconds = duration, delayAfterSeconds = delayAfter, volumePercent = 100)

    private fun plan(
        total: Int,
        rings: List<AlarmRing>,
        mode: VibrationMode = VibrationMode.SOUND_AND_VIBRATION,
        vibrationOnly: Int = 10,
    ) = describeRingPlan(total, rings, mode, vibrationOnly)

    // ── The case that caused the confusion ──────────────────────────────────

    /**
     * One round, no gap: the round length is genuinely inaudible, and the text has to say
     * so rather than imply the two numbers interact.
     */
    @Test
    fun `a single round with no gap says the round length does not matter`() {
        val text = plan(120, listOf(round(30)))
        assertTrue("must state the total", text.contains("2 דק׳"))
        assertTrue("must say the round length is not audible",
            text.contains("משך הסבב לא משנה את מה שנשמע"))
    }

    @Test
    fun `a single round with no gap does not claim a repeat count`() {
        // Claiming "4 repeats" here would be describing a restart nobody can hear.
        val text = plan(120, listOf(round(30)))
        assertTrue(!text.contains("יחזור"))
    }

    // ── Where the round length starts to matter ─────────────────────────────

    @Test
    fun `a gap after the round produces a countable cycle`() {
        val text = plan(120, listOf(round(20, delayAfter = 10)))
        assertTrue("cycle is 30s", text.contains("מחזור של 30 שנ׳"))
        assertTrue("120 / 30 = 4 exactly", text.contains("4 פעמים בדיוק"))
    }

    @Test
    fun `two rounds are described as a multi-round cycle`() {
        val text = plan(180, listOf(round(30), round(30)))
        assertTrue(text.contains("2 סבבים"))
        assertTrue("cycle is 60s", text.contains("מחזור של 1 דק׳"))
        assertTrue("180 / 60 = 3 exactly", text.contains("3 פעמים בדיוק"))
    }

    @Test
    fun `a partial final cycle is reported as a remainder`() {
        // 100s of window over a 30s cycle: three full cycles and 10s left.
        val text = plan(100, listOf(round(20, delayAfter = 10)))
        assertTrue(text.contains("3 פעמים"))
        assertTrue(text.contains("10 שנ׳"))
    }

    @Test
    fun `a total shorter than one cycle says the alarm stops mid-cycle`() {
        val text = plan(20, listOf(round(30), round(30)))
        assertTrue(text.contains("קצר ממחזור שלם"))
    }

    // ── Vibration modes change the sound window ────────────────────────────

    @Test
    fun `vibration-only says there is no sound at all`() {
        val text = plan(60, listOf(round(30)), mode = VibrationMode.VIBRATION_ONLY)
        assertTrue(text.contains("רטט בלבד"))
        assertTrue(text.contains("ללא צליל"))
    }

    /** The vibrate-first window is silent, so the sound gets less than the total. */
    @Test
    fun `vibration-then-sound reports the lead and the shortened sound window`() {
        val text = plan(
            70, listOf(round(20, delayAfter = 10)),
            mode = VibrationMode.VIBRATION_THEN_SOUND, vibrationOnly = 10,
        )
        assertTrue("names the vibrate lead", text.contains("רטט 10 שנ׳"))
        // 70 - 10 = 60s of sound over a 30s cycle.
        assertTrue(text.contains("2 פעמים בדיוק"))
    }

    @Test
    fun `a vibrate lead longer than the total says the sound never plays`() {
        val text = plan(
            10, listOf(round(30)),
            mode = VibrationMode.VIBRATION_THEN_SOUND, vibrationOnly = 30,
        )
        assertTrue(text.contains("הצליל לא יישמע כלל"))
    }

    // ── Degenerate input must not crash or divide by zero ──────────────────

    @Test
    fun `an empty round list falls back to the default round`() {
        val text = plan(60, emptyList())
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `zero and negative values do not divide by zero`() {
        assertTrue(plan(0, listOf(round(30))).isNotBlank())
        assertTrue(plan(60, listOf(round(0, delayAfter = 0))).isNotBlank())
        assertTrue(plan(-5, listOf(round(-5, delayAfter = -5))).isNotBlank())
    }
}
