package com.smartring.app.util

/** "90" -> "1 דק' 30 שנ'", "45" -> "45 שנ'", "120" -> "2 דק'". */
fun formatDurationSeconds(totalSeconds: Int): String {
    if (totalSeconds < 60) return "$totalSeconds שנ׳"
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (secs == 0) "$minutes דק׳" else "$minutes דק׳ $secs שנ׳"
}
