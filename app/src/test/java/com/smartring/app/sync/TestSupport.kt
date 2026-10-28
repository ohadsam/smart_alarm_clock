package com.smartring.app.sync

import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.FrameChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException

/**
 * One end of an in-memory pipe between two simulated phones.
 *
 * The radio is reduced to "bytes go in one end and come out the other", which is the whole of
 * what the protocol assumes — so the real handshake, the real encryption and the real sync
 * conversation run here, byte for byte, with nothing mocked in between.
 *
 * It is also the attacker's position: [sent] is everything a passive eavesdropper would record,
 * and [interfere] is what an active one could do to a frame in flight.
 */
class MemoryFrameChannel internal constructor(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : FrameChannel {

    /** Every frame this end sent, in order. */
    val sent = mutableListOf<ByteArray>()

    /** Replace a frame in flight (or return null to drop it). Default: deliver unchanged. */
    var interfere: (index: Int, frame: ByteArray) -> ByteArray? = { _, frame -> frame }

    private var count = 0

    override suspend fun send(frame: ByteArray) {
        sent += frame
        val delivered = interfere(count++, frame) ?: return
        try {
            outgoing.send(delivered)
        } catch (e: ClosedSendChannelException) {
            throw ChannelClosedException()
        }
    }

    override suspend fun receive(): ByteArray = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw ChannelClosedException()
    }

    /** Closes what this end writes, so the other end sees the connection end. */
    override fun close() { outgoing.close() }
}

fun memoryChannels(): Pair<MemoryFrameChannel, MemoryFrameChannel> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return MemoryFrameChannel(incoming = bToA, outgoing = aToB) to
        MemoryFrameChannel(incoming = aToB, outgoing = bToA)
}

/** A valid 32-hex device id made of one repeated character. */
fun idOf(c: Char): String {
    // The protocol rejects ids that are not lowercase hex, correctly — so a test that used 'k' would
    // fail for a reason that has nothing to do with what it is testing. Fail here, by name, instead.
    require(c in '0'..'9' || c in 'a'..'f') { "test ids must be hex digits, got '$c'" }
    return c.toString().repeat(32)
}

fun identity(name: String, c: Char) = LocalIdentity(idOf(c), name)

fun link(peerChar: Char, name: String, myRole: DeviceRole, theirRole: DeviceRole) =
    PeerLink(idOf(peerChar), name, myRole, theirRole)

fun alarmOf(name: String = "בוקר", hour: Int = 7, minute: Int = 30) =
    Alarm(name = name, hour = hour, minute = minute, repeatDaysBitmask = 0b0111110)

fun String.hex(): ByteArray = requireNotNull(hexToBytesOrNull()) { "bad hex in test: $this" }
