package com.smartring.app.util

import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.VibrationMode

/**
 * Describes, in concrete numbers, what a given ring configuration will actually do.
 *
 * ## Why this exists
 *
 * "משך צלצול כולל" and a round's own "משך הסבב" are two different things, and no amount
 * of explanatory prose made that land — it was asked twice. The mechanics, from
 * AlarmFiringService:
 *
 *  - `ringDurationSeconds` starts **one stopwatch** when the alarm begins. When it
 *    expires the service calls `stopSelf()`. It is the total lifetime of the alarm and
 *    nothing else.
 *  - The rounds are a **playlist**. Each round plays for its own `durationSeconds`, then
 *    its `delayAfterSeconds` of silence, then the next round. When the list runs out it
 *    starts again from the top, and keeps going until that stopwatch fires.
 *
 * The honest consequence, which the UI previously hid: with exactly one round and no gap
 * after it, the round's duration makes no audible difference at all. The sound simply
 * continues until the total runs out. It only starts to matter with a second round, or
 * with a gap of silence between repeats.
 *
 * So rather than explaining harder, this computes the answer from the user's own numbers
 * — "2 דקות סה״כ · מחזור של 30 שנ׳ · 4 חזרות" — which is the thing that actually makes
 * the relationship obvious.
 */
fun describeRingPlan(
    totalSeconds: Int,
    rings: List<AlarmRing>,
    vibrationMode: VibrationMode,
    vibrationOnlySeconds: Int,
): String {
    val total = totalSeconds.coerceAtLeast(0)
    if (vibrationMode == VibrationMode.VIBRATION_ONLY) {
        return "רטט בלבד, ללא צליל, למשך ${formatDurationSeconds(total)}."
    }

    // VIBRATION_THEN_SOUND spends its opening seconds vibrating in silence, so the sound
    // gets whatever is left. Reporting the full total here would overstate it.
    val silentLead = if (vibrationMode == VibrationMode.VIBRATION_THEN_SOUND)
        vibrationOnlySeconds.coerceAtLeast(0) else 0
    val soundWindow = total - silentLead
    if (soundWindow <= 0) {
        return "הרטט (${formatDurationSeconds(silentLead)}) ארוך מהזמן הכולל " +
            "(${formatDurationSeconds(total)}) — הצליל לא יישמע כלל."
    }

    val playing = rings.ifEmpty { listOf(AlarmRing(volumePercent = 100)) }
    val cycle = playing.sumOf { it.durationSeconds.coerceAtLeast(0) + it.delayAfterSeconds.coerceAtLeast(0) }
    val lead = if (silentLead > 0) "רטט ${formatDurationSeconds(silentLead)}, ואז " else ""

    // A single round with no trailing gap restarts seamlessly, so the round length is not
    // something the user can hear. Saying so is the whole point of this function.
    if (playing.size == 1 && playing[0].delayAfterSeconds <= 0) {
        return lead + "הצליל יתנגן ברצף עד שיעברו ${formatDurationSeconds(soundWindow)} " +
            "ואז השעמור ייפסק מעצמו. עם סבב אחד ובלי השהיה, משך הסבב לא משנה את מה שנשמע."
    }

    if (cycle <= 0) {
        return lead + "משך הסבבים הוא אפס — לא ניתן לחשב מחזור."
    }

    val fullCycles = soundWindow / cycle
    val leftover = soundWindow % cycle
    val roundsText = if (playing.size == 1) "מחזור של ${formatDurationSeconds(cycle)}"
                     else "${playing.size} סבבים, מחזור של ${formatDurationSeconds(cycle)}"
    return buildString {
        append(lead)
        append("$roundsText. ")
        when {
            fullCycles == 0 ->
                append("הזמן הכולל (${formatDurationSeconds(soundWindow)}) קצר ממחזור שלם — " +
                    "השעמור ייפסק באמצע המחזור הראשון.")
            leftover == 0 ->
                append("המחזור יחזור $fullCycles פעמים בדיוק, ואז השעמור ייפסק.")
            else ->
                append("המחזור יחזור $fullCycles פעמים ועוד ${formatDurationSeconds(leftover)}, " +
                    "ואז השעמור ייפסק.")
        }
    }
}
