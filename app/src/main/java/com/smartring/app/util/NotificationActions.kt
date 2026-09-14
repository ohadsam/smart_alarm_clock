package com.smartring.app.util

import com.smartring.app.domain.model.Alarm

/**
 * What tapping "עצור" on the alarm notification should do.
 *
 * Pulled out of StopAlarmReceiver because the receiver itself is untestable in the
 * JVM suite — `goAsync()` needs a real pending broadcast result, and `@AndroidEntryPoint`
 * needs the Hilt graph — while the rules it encodes are among the app's most
 * load-bearing: Shabbat mode's whole promise is that *nothing* can be pressed while
 * the alarm rings, and that promise has to hold at the notification too, not just on
 * the ring screen.
 */
enum class StopDecision {
    /** Shabbat mode: accept no interaction at all. The alarm keeps ringing and stops
     *  on its own via ringDurationSeconds, which is not a user action. */
    IGNORE,

    /** Silence the alarm and record it as stopped. */
    STOP,
}

/**
 * [alarm] is null when the lookup failed — deliberately fails *open* and stops: a
 * database hiccup must not turn a tap on Stop into a no-op that leaves the phone
 * blaring, and Shabbat mode is the rare case.
 */
fun stopDecision(alarm: Alarm?): StopDecision =
    if (alarm != null && !alarm.acceptsInteraction) StopDecision.IGNORE else StopDecision.STOP

/** What tapping "נודניק" on the alarm notification should do. */
sealed interface SnoozeDecision {
    /** Shabbat mode — see [StopDecision.IGNORE]. The service keeps running. */
    data object Ignore : SnoozeDecision

    /** Silence it, but don't log anything: the alarm row is gone. */
    data object SilenceOnly : SnoozeDecision

    /**
     * Snooze is off for this alarm, or its snooze cap is already spent. The user did
     * press a button, so the alarm is silenced either way rather than left ringing —
     * but the two log as different things, because "I turned it off" and "it ran out
     * of snoozes" are different stories in the history.
     */
    data class StopInstead(val action: String) : SnoozeDecision

    /** Re-arm [minutes] from now. */
    data class Snooze(val minutes: Int) : SnoozeDecision
}

/**
 * [alreadySnoozed] is counted from persisted history rather than memory: the
 * notification action can be tapped long after the app's process (and any in-memory
 * counter) is gone, so snoozeMaxCount can only be enforced from the database.
 */
fun snoozeDecision(alarm: Alarm?, alreadySnoozed: Int): SnoozeDecision = when {
    alarm == null                     -> SnoozeDecision.SilenceOnly
    !alarm.acceptsInteraction         -> SnoozeDecision.Ignore
    !alarm.snoozeEnabled              -> SnoozeDecision.StopInstead("STOPPED")
    alreadySnoozed >= alarm.snoozeMaxCount -> SnoozeDecision.StopInstead("MISSED")
    else                              -> SnoozeDecision.Snooze(alarm.snoozeMinutes)
}
