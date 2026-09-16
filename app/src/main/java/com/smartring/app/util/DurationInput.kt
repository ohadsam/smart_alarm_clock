package com.smartring.app.util

/**
 * The model behind the duration entry dialog.
 *
 * Every duration in this app is stored as a whole number of seconds, which is the right
 * storage form and the wrong entry form: the ring duration goes up to 600, and typing
 * "450" into a seconds-only box tells the user nothing about whether they just asked for
 * seven minutes or twelve. Splitting entry into minutes + seconds while keeping storage
 * in seconds is what this file is for — and it is pure Kotlin, separate from the dialog,
 * because getting the conversion wrong means an alarm that rings for the wrong length of
 * time, which is a correctness bug rather than a cosmetic one.
 */
data class DurationParts(val minutes: Int, val seconds: Int) {
    val totalSeconds: Int get() = minutes * 60 + seconds
}

/** Splits a stored duration into the two fields the dialog shows. */
fun durationPartsOf(totalSeconds: Int): DurationParts {
    val safe = totalSeconds.coerceAtLeast(0)
    return DurationParts(safe / 60, safe % 60)
}

/**
 * Re-spreads whatever was typed across the two fields, so an out-of-range seconds value
 * carries into minutes: 120 seconds becomes 2 minutes 0 seconds, and 1 minute 90 seconds
 * becomes 2 minutes 30 seconds.
 *
 * The arithmetic runs in Long and is clamped before narrowing. Both fields are free text,
 * so "999999" in each is reachable by typing, and `minutes * 60` would silently overflow
 * Int into a negative duration — which would then sail through a `>= min` check.
 */
fun normalizeDurationParts(minutes: Int, seconds: Int): DurationParts {
    val total = (minutes.coerceAtLeast(0).toLong() * 60 + seconds.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
    return durationPartsOf(total.toInt())
}

/**
 * The Hebrew reason [totalSeconds] cannot be accepted, or null when it can.
 *
 * Phrased with [formatDurationSeconds] rather than raw seconds so the bound reads in the
 * same units the user is typing in — "המקסימום הוא 10 דק׳", not "המקסימום הוא 600".
 */
fun durationRangeError(totalSeconds: Int, minSeconds: Int, maxSeconds: Int): String? = when {
    totalSeconds < minSeconds -> "המינימום הוא ${formatDurationSeconds(minSeconds)}"
    totalSeconds > maxSeconds -> "המקסימום הוא ${formatDurationSeconds(maxSeconds)}"
    else                      -> null
}

/** "טווח: 5 שנ׳ – 10 דק׳", for the dialog's hint line. */
fun durationRangeHint(minSeconds: Int, maxSeconds: Int): String =
    "טווח: ${formatDurationSeconds(minSeconds)} – ${formatDurationSeconds(maxSeconds)}"

/**
 * What the dialog shows under the fields before the user commits.
 *
 * Deliberately restates the total in plain seconds alongside the friendly form: the
 * sliders, the warnings in [RingSetup] and the stored value are all in seconds, so seeing
 * both is what lets someone confirm the two agree.
 */
fun durationPreview(totalSeconds: Int): String =
    if (totalSeconds < 60) formatDurationSeconds(totalSeconds)
    else "${formatDurationSeconds(totalSeconds)}  ·  $totalSeconds שנ׳ סה״כ"
