package com.smartring.app.sync.crypto

import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.Proto
import java.io.IOException

/**
 * A reliable, ordered pipe of whole messages — and nothing more.
 *
 * Bluetooth, an in-memory queue in a test, anything: the protocol above neither knows nor
 * cares. Keeping this seam narrow is what lets the entire pairing and sync conversation be
 * exercised on the JVM between two simulated phones, with the radio reduced to a thin adapter
 * that only moves bytes.
 *
 * Not safe for concurrent senders. The protocol is strictly stop-and-wait, so there is never
 * more than one.
 */
interface FrameChannel {
    suspend fun send(frame: ByteArray)

    /** The next message, or throws [ChannelClosedException] when the other side is gone. */
    suspend fun receive(): ByteArray

    fun close()
}

class ChannelClosedException(message: String = "channel closed") : IOException(message)

/**
 * A [FrameChannel] wrapped in authenticated encryption.
 *
 *  - **Confidentiality and integrity:** AES-256-GCM. A tampered, truncated or forged frame
 *    fails authentication and ends the session — there is no "ignore it and continue".
 *  - **Replay and reordering:** the GCM nonce is a strictly increasing counter that is
 *    *implicit* — never sent. Each side counts what it has sent and received; a frame
 *    replayed, dropped or reordered is decrypted under the wrong nonce and fails the tag.
 *    Nothing has to be remembered beyond one counter per direction.
 *  - **Reflection:** the two directions use different keys *and* different nonce prefixes, so
 *    a frame cannot be bounced back at its sender.
 *
 * A fresh key pair is derived per session, so a counter never repeats under one key.
 */
class SecureChannel(
    private val raw: FrameChannel,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendPrefix: Int,
    private val receivePrefix: Int,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    suspend fun send(plaintext: ByteArray) {
        require(plaintext.size <= Proto.MAX_FRAME_BYTES) { "message too large" }
        check(sendCounter < MAX_MESSAGES) { "session message budget exhausted" }
        val sealed = Crypto.aeadSeal(sendKey, nonce(sendPrefix, sendCounter), AAD, plaintext)
        sendCounter++
        raw.send(sealed)
    }

    /** The next authenticated plaintext. Throws [ProtocolException] if the frame does not verify. */
    suspend fun receive(): ByteArray {
        val frame = raw.receive()
        if (frame.size > Proto.MAX_FRAME_BYTES + Crypto.TAG_BYTES) throw ProtocolException("frame too large")
        check(receiveCounter < MAX_MESSAGES) { "session message budget exhausted" }
        val plain = Crypto.aeadOpen(receiveKey, nonce(receivePrefix, receiveCounter), AAD, frame)
            ?: throw ProtocolException("message failed authentication")
        receiveCounter++
        return plain
    }

    fun close() {
        raw.close()
        // Best effort: the keys should not outlive the session in memory.
        sendKey.fill(0)
        receiveKey.fill(0)
    }

    private fun nonce(prefix: Int, counter: Long): ByteArray {
        val n = ByteArray(Crypto.NONCE_BYTES)
        n[0] = (prefix ushr 24).toByte(); n[1] = (prefix ushr 16).toByte()
        n[2] = (prefix ushr 8).toByte();  n[3] = prefix.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    companion object {
        /** Far above any real session and far below where a 64-bit counter could wrap. */
        const val MAX_MESSAGES = 1L shl 32

        /** Bound into every tag, so a frame from some other use of AES-GCM cannot be replayed here. */
        private val AAD = "SmartRing/sync/v1".toByteArray(Charsets.US_ASCII)

        const val PREFIX_INITIATOR_TO_RESPONDER = 0x01
        const val PREFIX_RESPONDER_TO_INITIATOR = 0x02
    }
}
