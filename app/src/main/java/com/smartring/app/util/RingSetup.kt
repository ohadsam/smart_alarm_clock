package com.smartring.app.util

import com.smartring.app.domain.model.AlarmRing
import com.smartring.app.domain.model.VibrationMode
import kotlin.math.ceil

/**
 * Configurations the ring/vibration/crescendo sliders happily allow but that quietly
 * do not do what the screen says they do.
 *
 * Every one of these is reachable from the slider ranges the edit screen exposes, and
 * none of them fails loudly: the alarm still rings, just not the way it was set up.
 * That is the worst shape a bug can take here, because the person only finds out at
 * 06:30 the next morning. So they are surfaced as warnings at edit time rather than
 * corrected behind the user's back — the app does not know which of the two settings
 * they meant, and silently rewriting one of them is how an alarm ends up going off
 * differently from what its own screen shows.
 *
 * Pure and free of Android types so the rules are unit-tested directly
 * (RingSetupTest) rather than through a Compose screen.
 */
fun ringSetupWarnings(
    vibrationMode: VibrationMode,
    vibrationOnlySeconds: Int,
    ringDurationSeconds: Int,
    rings: List<AlarmRing>,
    crescendoEnabled: Boolean,
    crescendoStartVolume: Int,
    crescendoStepSeconds: Int,
    crescendoStepPercent: Int,
): List<String> {
    val warnings = mutableListOf<String>()

    // 1. "רטט→צלצול" where the vibrate-first window outlasts the whole ring.
    //    fireAlarm() starts the auto-stop timer for ringDurationSeconds and only then
    //    waits out vibrationOnlySeconds before any audio begins, so the sound is not
    //    merely late — it never plays at all. Reachable: vibrate-first goes up to
    //    120s and ring duration down to 5s.
    if (vibrationMode == VibrationMode.VIBRATION_THEN_SOUND &&
        vibrationOnlySeconds >= ringDurationSeconds
    ) {
        warnings += "הרטט נמשך ${formatDurationSeconds(vibrationOnlySeconds)}, לא פחות ממשך הצלצול " +
            "(${formatDurationSeconds(ringDurationSeconds)}) — הצליל לא יתחיל להישמע כלל. " +
            "קצר את זמן הרטט או הארך את משך הצלצול."
    }

    if (crescendoEnabled) {
        // The ceiling the ramp is actually aimed at. volumeAtSecond() clamps the
        // crescendo to each round's own volume, so a round quieter than the starting
        // volume simply plays flat at its own level.
        val loudest = rings.maxOfOrNull { it.volumePercent } ?: 100

        // 2. Starting volume at or above every round's volume: the ramp has nowhere
        //    to go and the alarm plays at a constant level, while the screen still
        //    shows three crescendo sliders as if they did something.
        if (crescendoStartVolume >= loudest) {
            warnings += "העוצמה ההתחלתית ($crescendoStartVolume%) אינה נמוכה מעוצמת הסבבים ($loudest%), " +
                "כך שהצלצול יישמע בעוצמה קבועה ולא יתחזק. הורד את העוצמה ההתחלתית או העלה את עוצמת הסבבים."
        } else {
            // 3. The ramp is too slow to finish inside the ring. Same arithmetic the
            //    playback uses (Alarm.volumeAtSecond): full volume is reached at
            //    ceil((target - start) / stepPercent) * stepSeconds seconds after the
            //    alarm starts.
            val steps = ceil((loudest - crescendoStartVolume).toDouble() / crescendoStepPercent).toInt()
            val secondsToFull = steps * crescendoStepSeconds
            if (secondsToFull > ringDurationSeconds) {
                warnings += "בקצב הנוכחי העוצמה תגיע ל-$loudest% רק אחרי ${formatDurationSeconds(secondsToFull)}, " +
                    "אחרי שהצלצול כבר נעצר (${formatDurationSeconds(ringDurationSeconds)}). " +
                    "הגדל את העלייה בכל צעד, קצר את המרווח בין הצעדים, או הארך את משך הצלצול."
            }
        }
    }

    // 4. Ring rounds that the alarm stops before ever reaching. Each round starts at
    //    the cumulative length of everything before it (durations plus the silent
    //    gaps), and the whole alarm ends at ringDurationSeconds — so a round whose
    //    start time is already past that simply never plays, however carefully it was
    //    configured. The edit screen's own defaults land here: "הוסף סבב צלצול" adds a
    //    round after a 60s first round while the default ring duration is also 60s, so
    //    the very first round anyone adds is silent until they also lengthen the alarm.
    val playable = playableRoundCount(vibrationMode, vibrationOnlySeconds, ringDurationSeconds, rings)
    if (rings.size > 1 && playable < rings.size) {
        warnings += "משך הצלצול (${formatDurationSeconds(ringDurationSeconds)}) מספיק רק ל-$playable מתוך " +
            "${rings.size} סבבי הצלצול — " +
            (if (playable == 0) "אף סבב לא יספיק להתנגן. " else "הסבבים שאחריו לא יתנגנו. ") +
            "הארך את משך הצלצול, או קצר את משכי הסבבים ואת ההשהיות ביניהם."
    }

    return warnings
}

/**
 * How many of [rings] actually get to play before the alarm's auto-stop, mirroring
 * AlarmFiringService.startAudioSequence(): rounds run in order, each followed by its
 * own silent gap, and in "רטט→צלצול" the whole sequence only begins once the
 * vibrate-first window is over.
 */
private fun playableRoundCount(
    vibrationMode: VibrationMode,
    vibrationOnlySeconds: Int,
    ringDurationSeconds: Int,
    rings: List<AlarmRing>,
): Int {
    if (vibrationMode == VibrationMode.VIBRATION_ONLY) return rings.size   // nothing plays; rule 1's territory
    var at = if (vibrationMode == VibrationMode.VIBRATION_THEN_SOUND) vibrationOnlySeconds else 0
    var count = 0
    for (ring in rings.sortedBy { it.orderIndex }) {
        if (at >= ringDurationSeconds) break
        count++
        at += ring.durationSeconds + ring.delayAfterSeconds
    }
    return count
}
