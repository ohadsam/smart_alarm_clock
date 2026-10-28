package com.smartring.app.sync.engine

import com.smartring.app.sync.LocalIdentity
import com.smartring.app.sync.PeerLink
import com.smartring.app.sync.SyncPolicy
import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.CryptoException
import com.smartring.app.sync.crypto.SecureChannel
import com.smartring.app.sync.hexToBytesOrNull
import com.smartring.app.sync.isValidSyncId
import com.smartring.app.sync.protocol.AckKind
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SecureMsg
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.cleanText
import com.smartring.app.sync.protocol.decodeSecure
import com.smartring.app.sync.protocol.encodeSecure
import com.smartring.app.sync.protocol.validated
import com.smartring.app.sync.toHex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** One thing waiting to be delivered to a peer. */
sealed interface OutgoingItem {
    /** "Make your copy match this." [revision] is the sender's, and only ever grows. */
    class Upsert(val syncId: String, val revision: Long, val alarm: SyncAlarm) : OutgoingItem

    /** "Remove your copy." [tombstoneId] is this device's record of having to say so. */
    class Delete(val syncId: String, val revision: Long, val tombstoneId: Long) : OutgoingItem

    /** A copy for a peer to accept or decline. */
    class Transfer(val transferId: String, val alarm: SyncAlarm) : OutgoingItem
}

/**
 * Everything the engine needs from the rest of the app, and nothing it does not.
 *
 * An interface rather than the repositories themselves so the whole sync conversation runs
 * on the JVM against fakes — and so the engine cannot reach past what it is meant to touch.
 */
interface SyncPort {
    suspend fun pendingFor(peer: PeerLink): List<OutgoingItem>

    /** Called with the receiver's verdict. Marks the item delivered, or records why it was not. */
    suspend fun onDelivered(peer: PeerLink, item: OutgoingItem, status: AckStatus)

    /** [alarm] has already been validated and sanitized by the engine. */
    suspend fun applyUpsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm): AckStatus
    suspend fun applyDelete(peer: PeerLink, syncId: String, revision: Long): AckStatus
    suspend fun receiveTransfer(peer: PeerLink, transferId: String, alarm: SyncAlarm): AckStatus

    suspend fun onPeerRenamed(peer: PeerLink, newName: String)
    suspend fun onPeerUnpaired(peer: PeerLink)
}

data class SyncStats(val sent: Int, val received: Int, val refused: Int)

sealed interface SyncOutcome {
    data class Completed(val stats: SyncStats) : SyncOutcome

    /** The peer told us it removed the pairing. */
    data object PeerUnpaired : SyncOutcome

    /** We told the peer we removed the pairing. */
    data object UnpairSent : SyncOutcome

    data class Failed(val reason: String) : SyncOutcome
}

/**
 * The conversation two authenticated phones have: introduce, take turns delivering what is
 * waiting, say goodbye.
 *
 * ## Strictly stop-and-wait
 *
 * Every item is acknowledged before the next is sent, and the two directions never overlap:
 * the connecting phone has its turn, then the listening phone has its. There is no concurrent
 * reading and writing, no window, no reordering to reason about — a design that is slower than
 * it could be by a few milliseconds over a handful of small messages, and has nothing that can
 * deadlock or interleave. A protocol that carries *alarm clocks* should be boring.
 *
 * ## Permission, enforced twice
 *
 * The role matrix ([SyncPolicy]) is checked before sending *and* before applying. A phone that
 * is not a parent does not send management messages; a phone that is not a child refuses to
 * apply them. Neither side trusts the other to have checked.
 *
 * ## Everything received is untrusted
 *
 * Each alarm is validated and sanitized ([validated]) before any [SyncPort] method sees it, so no
 * implementation of the port can forget to.
 */
class SyncEngine(
    private val me: LocalIdentity,
    private val port: SyncPort,
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val maxItemsPerTurn: Int = DEFAULT_MAX_ITEMS,
) {

    /** The phone that opened the connection. With [unpair], the whole purpose is to say goodbye for good. */
    suspend fun runAsInitiator(channel: SecureChannel, peer: PeerLink, unpair: Boolean = false): SyncOutcome = guarded(channel) {
        channel.send(encodeSecure(helloOf(peer)))
        checkHello(step { channel.receive() }, peer)

        if (unpair) {
            channel.send(encodeSecure(SecureMsg.Unpair))
            return@guarded SyncOutcome.UnpairSent
        }

        val sent = sendTurn(channel, peer)
        val received = receiveTurn(channel, peer)
        if (received.unpaired) return@guarded SyncOutcome.PeerUnpaired
        channel.send(encodeSecure(SecureMsg.Bye))
        SyncOutcome.Completed(SyncStats(sent.delivered, received.applied, sent.refused + received.refused))
    }

    /** The phone that was connected to. */
    suspend fun runAsResponder(channel: SecureChannel, peer: PeerLink): SyncOutcome = guarded(channel) {
        checkHello(step { channel.receive() }, peer)
        channel.send(encodeSecure(helloOf(peer)))

        val received = receiveTurn(channel, peer)
        if (received.unpaired) return@guarded SyncOutcome.PeerUnpaired
        val sent = sendTurn(channel, peer)
        // The initiator ends the session; whether its goodbye arrives or the link simply drops, the
        // work is done and acknowledged, so neither is a failure.
        runCatching { step { channel.receive() } }
        SyncOutcome.Completed(SyncStats(sent.delivered, received.applied, sent.refused + received.refused))
    }

    // ── My turn: deliver what is waiting ──────────────────────────────────

    private class SendResult(val delivered: Int, val refused: Int)

    private suspend fun sendTurn(channel: SecureChannel, peer: PeerLink): SendResult {
        val canManage = SyncPolicy.mayManagePeer(peer.myRole, peer.theirRole)
        val canCopy = SyncPolicy.mayExchangeCopies(peer.myRole, peer.theirRole)
        val items = port.pendingFor(peer)
            .filter { item ->
                when (item) {
                    is OutgoingItem.Upsert, is OutgoingItem.Delete -> canManage
                    is OutgoingItem.Transfer -> canCopy
                }
            }
            .take(maxItemsPerTurn)

        var delivered = 0
        var refused = 0
        for (item in items) {
            val (message, kind, id, revision) = describe(item)
            channel.send(encodeSecure(message))
            val ack = decodeSecure(step { channel.receive() }) as? SecureMsg.Ack
                ?: throw ProtocolException("expected an acknowledgement")
            // An acknowledgement for something other than what was just sent is a bug or an attack;
            // either way the session is not to be trusted any further.
            if (ack.kind != kind || !ack.id.contentEquals(id) || ack.revision != revision) {
                throw ProtocolException("acknowledgement does not match the item sent")
            }
            port.onDelivered(peer, item, ack.status)
            if (ack.status == AckStatus.OK || ack.status == AckStatus.STALE) delivered++ else refused++
        }
        channel.send(encodeSecure(SecureMsg.Done))
        return SendResult(delivered, refused)
    }

    private data class Described(val message: SecureMsg, val kind: AckKind, val id: ByteArray, val revision: Long)

    private fun describe(item: OutgoingItem): Described = when (item) {
        is OutgoingItem.Upsert -> {
            val id = idBytes(item.syncId)
            Described(SecureMsg.Upsert(id, item.revision, item.alarm), AckKind.UPSERT, id, item.revision)
        }
        is OutgoingItem.Delete -> {
            val id = idBytes(item.syncId)
            Described(SecureMsg.Delete(id, item.revision), AckKind.DELETE, id, item.revision)
        }
        is OutgoingItem.Transfer -> {
            val id = idBytes(item.transferId)
            Described(SecureMsg.Transfer(id, item.alarm), AckKind.TRANSFER, id, 0L)
        }
    }

    private fun idBytes(hex: String): ByteArray =
        hex.takeIf { isValidSyncId(it) }?.hexToBytesOrNull() ?: throw ProtocolException("malformed id")

    // ── Their turn: apply what arrives ────────────────────────────────────

    private class ReceiveResult(val applied: Int, val refused: Int, val unpaired: Boolean)

    private suspend fun receiveTurn(channel: SecureChannel, peer: PeerLink): ReceiveResult {
        val canBeManaged = SyncPolicy.mayBeManagedByPeer(peer.myRole, peer.theirRole)
        val canReceiveCopies = SyncPolicy.mayExchangeCopies(peer.myRole, peer.theirRole)
        var applied = 0
        var refused = 0
        var items = 0
        while (true) {
            val message = decodeSecure(step { channel.receive() })
            when (message) {
                SecureMsg.Done -> return ReceiveResult(applied, refused, unpaired = false)
                SecureMsg.Unpair -> {
                    port.onPeerUnpaired(peer)
                    return ReceiveResult(applied, refused, unpaired = true)
                }
                is SecureMsg.Upsert -> {
                    val status = when {
                        !canBeManaged -> AckStatus.REJECTED
                        message.revision < 1L -> AckStatus.INVALID
                        else -> message.alarm.validated()
                            ?.let { port.applyUpsert(peer, message.syncId.toHex(), message.revision, it) }
                            ?: AckStatus.INVALID
                    }
                    channel.send(encodeSecure(SecureMsg.Ack(AckKind.UPSERT, message.syncId, message.revision, status)))
                    if (status == AckStatus.OK || status == AckStatus.STALE) applied++ else refused++
                }
                is SecureMsg.Delete -> {
                    val status = when {
                        !canBeManaged -> AckStatus.REJECTED
                        message.revision < 1L -> AckStatus.INVALID
                        else -> port.applyDelete(peer, message.syncId.toHex(), message.revision)
                    }
                    channel.send(encodeSecure(SecureMsg.Ack(AckKind.DELETE, message.syncId, message.revision, status)))
                    if (status == AckStatus.OK || status == AckStatus.STALE) applied++ else refused++
                }
                is SecureMsg.Transfer -> {
                    val status = when {
                        !canReceiveCopies -> AckStatus.REJECTED
                        else -> message.alarm.validated()
                            ?.let { port.receiveTransfer(peer, message.transferId.toHex(), it) }
                            ?: AckStatus.INVALID
                    }
                    channel.send(encodeSecure(SecureMsg.Ack(AckKind.TRANSFER, message.transferId, 0L, status)))
                    if (status == AckStatus.OK || status == AckStatus.STALE) applied++ else refused++
                }
                // Anything else in the middle of a turn is not part of the conversation.
                else -> throw ProtocolException("unexpected message during a turn")
            }
            // Counted after handling, so a flood is cut off at a bound the sender cannot raise.
            if (++items > maxItemsPerTurn) throw ProtocolException("too many items in one turn")
        }
    }

    // ── Introductions ─────────────────────────────────────────────────────

    private fun helloOf(peer: PeerLink): SecureMsg.Hello {
        val id = me.deviceId.hexToBytesOrNull()
        require(isValidSyncId(me.deviceId) && id != null) { "device id must be 32 hex characters" }
        return SecureMsg.Hello(id, cleanText(me.name, Proto.DEVICE_NAME_MAX_CHARS).ifEmpty { "מכשיר" }, peer.myRole)
    }

    /**
     * The peer has already proved, cryptographically, that it holds this pairing's key. This is the
     * second, independent check that it is *who the pairing says* and claims *the role the pairing
     * recorded* — a phone that now says it is something else is refused, not accommodated.
     */
    private suspend fun checkHello(frame: ByteArray, peer: PeerLink) {
        val hello = decodeSecure(frame) as? SecureMsg.Hello
            ?: throw ProtocolException("expected an introduction")
        if (hello.deviceId.toHex() != peer.deviceId) throw ProtocolException("the peer is not who this pairing says")
        if (hello.role != peer.theirRole) throw ProtocolException("the peer claims a role this pairing does not grant")
        val name = cleanText(hello.name, Proto.DEVICE_NAME_MAX_CHARS)
        if (name.isNotEmpty() && name != peer.name) port.onPeerRenamed(peer, name)
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private suspend fun <T> step(block: suspend () -> T): T = withTimeout(stepTimeoutMs) { block() }

    /**
     * A session that fails closes its connection at once. Otherwise the peer sits in a read until
     * its own timeout — on a phone that is two minutes of a battery-draining socket, held open
     * for a conversation both sides already know is over. A session that succeeds is left for the
     * caller to close, because the caller still owns the link.
     */
    private suspend fun guarded(channel: SecureChannel, block: suspend () -> SyncOutcome): SyncOutcome {
        val outcome = try {
            block()
        } catch (e: TimeoutCancellationException) {
            SyncOutcome.Failed("timed out")
        } catch (e: ProtocolException) {
            SyncOutcome.Failed("protocol error: ${e.message}")
        } catch (e: CryptoException) {
            SyncOutcome.Failed("crypto error: ${e.message}")
        } catch (e: ChannelClosedException) {
            SyncOutcome.Failed("the connection closed")
        } catch (e: java.io.IOException) {
            SyncOutcome.Failed("connection error")
        }
        if (outcome is SyncOutcome.Failed) channel.close()
        return outcome
    }

    companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 20_000L

        /** Per turn. A real parent has a handful of alarms for a child; this is generous and finite. */
        const val DEFAULT_MAX_ITEMS = 200
    }
}
