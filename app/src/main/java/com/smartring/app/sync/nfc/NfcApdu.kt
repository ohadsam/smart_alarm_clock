package com.smartring.app.sync.nfc

/**
 * The tiny card-emulation protocol one phone uses to hand an [NfcOffer] to another.
 *
 *   reader → `00 A4 04 00 08 <AID>`          SELECT the service        → `90 00`, or `6A 82` if nothing is on offer
 *   reader → `80 B0 <offset:2> <length>`     READ a slice              → `<bytes> 90 00`
 *
 * The first two bytes of the payload are its length (big-endian), so the reader learns the total
 * from the first slice and keeps reading until it has it. A phone's NFC link carries a couple of
 * hundred bytes per exchange; an alarm takes a handful of them.
 *
 * Pure functions over byte arrays, so the framing — the part that is easy to get off by one —
 * is tested on the JVM; the Android service and the reader are thin wrappers around it.
 */
object NfcApdu {
    /** The application id the offering phone answers to. Proprietary range (`F0…`), fixed. */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x53, 0x4D, 0x52, 0x49, 0x4E, 0x47, 0x01)
    const val AID_HEX = "F0534D52494E4701"

    /** Largest offer, including nothing but the offer. */
    const val MAX_PAYLOAD = 4096
    const val SLICE = 240

    private const val CLA_ISO = 0x00
    private const val INS_SELECT = 0xA4
    private const val CLA_PROPRIETARY = 0x80
    private const val INS_READ = 0xB0

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_BAD_COMMAND = byteArrayOf(0x6D, 0x00)
    val SW_WRONG_PARAMS = byteArrayOf(0x6B, 0x00)

    fun selectCommand(): ByteArray =
        byteArrayOf(CLA_ISO.toByte(), INS_SELECT.toByte(), 0x04, 0x00, AID.size.toByte()) + AID

    fun readCommand(offset: Int, length: Int): ByteArray {
        require(offset in 0..0xFFFF && length in 1..SLICE)
        return byteArrayOf(
            CLA_PROPRIETARY.toByte(), INS_READ.toByte(),
            (offset shr 8).toByte(), offset.toByte(), length.toByte(),
        )
    }

    /** What the offering phone answers to [command], given the [offer] it has on show (null if none). */
    fun respond(command: ByteArray, offer: ByteArray?): ByteArray {
        if (command.size < 4) return SW_BAD_COMMAND
        val cla = command[0].toInt() and 0xFF
        val ins = command[1].toInt() and 0xFF
        if (cla == CLA_ISO && ins == INS_SELECT) {
            val data = command.drop(5).take(command.getOrElse(4) { 0 }.toInt() and 0xFF)
            return if (offer != null && data.toByteArray().contentEquals(AID)) SW_OK else SW_NOT_FOUND
        }
        if (cla == CLA_PROPRIETARY && ins == INS_READ && command.size >= 5) {
            if (offer == null) return SW_NOT_FOUND
            val framed = ByteArray(2 + offer.size).also {
                it[0] = (offer.size shr 8).toByte(); it[1] = offer.size.toByte()
                offer.copyInto(it, 2)
            }
            val offset = ((command[2].toInt() and 0xFF) shl 8) or (command[3].toInt() and 0xFF)
            val want = (command[4].toInt() and 0xFF).let { if (it == 0) SLICE else it }.coerceAtMost(SLICE)
            if (offset > framed.size) return SW_WRONG_PARAMS
            val end = minOf(framed.size, offset + want)
            return framed.copyOfRange(offset, end) + SW_OK
        }
        return SW_BAD_COMMAND
    }

    /** Whether [response] ends in success. */
    fun isOk(response: ByteArray): Boolean =
        response.size >= 2 && response[response.size - 2] == SW_OK[0] && response[response.size - 1] == SW_OK[1]

    /**
     * The reader side: drives [transceive] (one APDU out, one response back) to fetch a whole offer.
     * Null if the other phone is not offering anything, or answers nonsense.
     */
    fun fetch(transceive: (ByteArray) -> ByteArray): ByteArray? {
        if (!isOk(transceive(selectCommand()))) return null
        var total = -1
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        // Bounded: a hostile phone cannot keep the reader looping by lying about the length.
        repeat(MAX_PAYLOAD / SLICE + 4) {
            val response = transceive(readCommand(offset, SLICE))
            if (!isOk(response)) return null
            val chunk = response.copyOf(response.size - 2)
            if (chunk.isEmpty()) return null
            out.write(chunk)
            offset += chunk.size
            val all = out.toByteArray()
            if (total < 0 && all.size >= 2) {
                total = ((all[0].toInt() and 0xFF) shl 8) or (all[1].toInt() and 0xFF)
                if (total > MAX_PAYLOAD || total == 0) return null
            }
            if (total >= 0 && all.size >= total + 2) {
                return if (all.size == total + 2) all.copyOfRange(2, all.size) else null
            }
        }
        return null
    }
}
