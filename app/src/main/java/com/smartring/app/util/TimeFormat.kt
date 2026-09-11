package com.smartring.app.util

/** "90" -> "1 דק' 30 שנ'", "45" -> "45 שנ'", "120" -> "2 דק'". */
fun formatDurationSeconds(totalSeconds: Int): String {
    if (totalSeconds < 60) return "$totalSeconds שנ׳"
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (secs == 0) "$minutes דק׳" else "$minutes דק׳ $secs שנ׳"
}

/** Minute-granularity countdown from [nowMillis] to [targetMillis], e.g. "בעוד 3 שע' 20 דק'". */
fun formatCountdownUntil(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val totalMinutes = ((targetMillis - nowMillis).coerceAtLeast(0) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "בעוד $hours שע׳ $minutes דק׳"
        hours > 0                -> "בעוד $hours שע׳"
        minutes > 0               -> "בעוד $minutes דק׳"
        else                       -> "בעוד פחות מדקה"
    }
}
