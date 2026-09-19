package com.smartring.app.util

import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmRing
import java.util.Calendar

/**
 * What a "quick alarm" *is*, in one place.
 *
 * Two surfaces now create them — the chips on the main screen and the widget's
 * quick-actions panel — and they must produce byte-for-byte the same alarm. Two copies of
 * this is exactly how the widget and the app start disagreeing: one picks up a new default
 * the other does not, and the user gets an alarm that rings differently depending on which
 * button they happened to press. This codebase has been bitten by a duplicated rule before
 * (`startAudioSequence`'s second copy of the round-walking logic, which meant a
 * multi-round alarm never advanced past round 1).
 *
 * Everything except the moment comes from the user's configured defaults, so a quick alarm
 * rings the way their alarms ring.
 */
fun buildQuickAlarm(at: Long, defaults: AlarmDefaults): Alarm {
    val cal = Calendar.getInstance().apply { timeInMillis = at }
    return Alarm(
        id = 0L,
        name = GENERIC_ALARM_NAME,
        hour = cal.get(Calendar.HOUR_OF_DAY),
        minute = cal.get(Calendar.MINUTE),
        // A specific datetime rather than a time-of-day, which is what makes it an ad-hoc
        // alarm: it rings once, and the card offers "תזמן ליום הבא" afterwards rather than
        // silently repeating tomorrow.
        specificDateTime = at,
        ringDurationSeconds = defaults.ringDurationSeconds,
        rings = listOf(AlarmRing(volumePercent = defaults.ringVolumePercent)),
        snoozeEnabled = defaults.snoozeEnabled,
        snoozeMinutes = defaults.snoozeMinutes,
        snoozeMaxCount = defaults.snoozeMaxCount,
        vibrationMode = defaults.vibrationMode,
        vibrationOnlySeconds = defaults.vibrationOnlySeconds,
        crescendoEnabled = defaults.crescendoEnabled,
        crescendoStartVolume = defaults.crescendoStartVolume,
        crescendoStepSeconds = defaults.crescendoStepSeconds,
        crescendoStepPercent = defaults.crescendoStepPercent,
        isShabbatMode = defaults.isShabbatMode,
    )
}
