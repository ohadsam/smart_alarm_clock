package com.smartring.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Alarm.hasFinished] — the state AlarmFiringService leaves a one-time alarm in once it
 * has rung.
 *
 * Worth its own tests because the list renders it differently (struck through, with a
 * badge telling the user how to reschedule it), and because getting it wrong in the other
 * direction would strike through alarms that are merely paused.
 */
class AlarmFinishedTest {

    private val rungOnce = Alarm(
        id = 1, name = "חד-פעמי", isEnabled = false, occurrencesFired = 1,
        repeatDaysBitmask = 0,
    )

    @Test
    fun `a one-time alarm that rang and switched itself off has finished`() =
        assertTrue(rungOnce.hasFinished)

    @Test
    fun `an alarm that has never rung has not finished`() =
        assertFalse(rungOnce.copy(occurrencesFired = 0).hasFinished)

    @Test
    fun `an enabled alarm has not finished, however many times it has rung`() =
        assertFalse(rungOnce.copy(isEnabled = true, occurrencesFired = 9).hasFinished)

    /**
     * A repeating alarm switched off mid-schedule is paused, not finished: it has more
     * occurrences and turning it back on resumes them.
     */
    @Test
    fun `a disabled repeating alarm is paused rather than finished`() =
        assertFalse(
            rungOnce.copy(repeatDaysBitmask = 0b1111111, repeatFrequency = RepeatFrequency.WEEKLY)
                .hasFinished,
        )

    /**
     * Extra dates are a schedule of their own, so an alarm holding any still has
     * somewhere to go even with no repeat days.
     */
    @Test
    fun `an alarm with extra specific dates is not finished`() =
        assertFalse(rungOnce.copy(specificDates = listOf(AlarmDate(date = 0L))).hasFinished)

    @Test
    fun `a frozen but enabled alarm is not finished`() =
        assertFalse(rungOnce.copy(isEnabled = true, isFrozen = true).hasFinished)
}
