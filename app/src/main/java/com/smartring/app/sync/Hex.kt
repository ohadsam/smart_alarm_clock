package com.smartring.app.sync

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }
    return out.toString()
}

/** Strict: even length, hex digits only. Null — never an exception — for anything else. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** A device or alarm identity: exactly 16 random bytes as 32 hex characters. */
fun isValidSyncId(s: String?): Boolean =
    s != null && s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }
