package com.smartring.app.sync.pairing

import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.LocalIdentity
import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.CryptoException
import com.smartring.app.sync.crypto.FrameChannel
import com.smartring.app.sync.crypto.SecureChannel
import com.smartring.app.sync.hexToBytesOrNull
import com.smartring.app.sync.isValidSyncId
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SecureMsg
import com.smartring.app.sync.protocol.cleanText
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.sync.protocol.decodeSecure
import com.smartring.app.sync.protocol.encodeHandshake
import com.smartring.app.sync.protocol.encodeSecure
import com.smartring.app.sync.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer

/** What the user is shown before they agree to pair. */
data class PeerSummary(
    val deviceId: String,
    val name: String,
    /** What the other phone will be to this one. */
    val theirRole: DeviceRole,
    /** What this phone will be to the other. */
    val myRole: DeviceRole,
    /**
     * Six digits both phones derived from the session. The two users compare them; if they
     * differ, someone is in the middle, whatever the code was.
     */
    val verification: String,
)

sealed interface PairingResult {
    class Success(
        val peerDeviceId: String,
        val peerName: String,
        val myRole: DeviceRole,
        val theirRole: DeviceRole,
        /** The long-term key. Secret: wrap it before it touches storage. */
        val pairingKey: ByteArray,
    ) : PairingResult

    /** The other phone did not know the code. The only result that costs a code attempt. */
    data object WrongCode : PairingResult

    /** One of the two people said no. Nothing was stored on either side. */
    data object Declined : PairingResult

    data class Failed(val reason: String) : PairingResult
}

/**
 * The pairing handshake: from "two phones, one code" to "two phones that share a key and
 * have each been told, and agreed, what they are to one another."
 *
 * ## What it has to withstand
 *
 *  - **Someone listening.** Bluetooth traffic can be recorded. Everything after the first two
 *    messages is encrypted under a key derived from an ephemeral Diffie-Hellman exchange the
 *    listener cannot compute, and the long-term key mixes that secret with the code — so even
 *    learning the code afterwards does not reveal it.
 *  - **Someone in the middle.** They can run a Diffie-Hellman exchange with each phone
 *    separately. What they cannot do is produce a valid confirmation without the code, which
 *    is mixed into the key the confirmations are made with. They get one attempt per
 *    connection and the host counts failures.
 *  - **A short code.** Eight digits is guessable offline *if a guess can be checked against
 *    recorded traffic*. Two things prevent that: the confirmation key depends on the
 *    Diffie-Hellman secret too (a recording alone cannot be tested against), and the code is
 *    stretched with PBKDF2 (a man in the middle, who *can* test guesses against his own
 *    session, would need 10^8 slow derivations inside the handshake's timeout).
 *  - **A wrong or hostile peer.** The joiner proves it knows the code *first*; the host only
 *    answers once that proof verifies, so a stranger who does not know it learns nothing that
 *    could be tested offline.
 *  - **Pairing with someone you did not mean to.** After the keys are agreed, both phones show
 *    the other's name, what the relationship will be, and a verification number. Both people
 *    must approve on their own screen. Nobody can be made a "child" of a phone they never
 *    agreed to.
 *
 * This is not a formal password-authenticated key exchange (SPAKE2, SRP); implementing one by
 * hand would be a worse idea than the construction here, and no maintained PAKE library is
 * available on every API level this app supports. The design leans on the limits above — one
 * attempt per connection, three per code, three minutes, single use, a stretch — and on the
 * human comparison of the verification number, which holds even if the code were somehow known.
 *
 * Runs over any [FrameChannel], so the whole conversation is testable between two simulated
 * phones with no radio.
 */
class PairingProtocol(
    private val kdfIterations: Int = DEFAULT_KDF_ITERATIONS,
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
) {

    // ── The phone showing the code ────────────────────────────────────────

    suspend fun host(
        channel: FrameChannel,
        code: String,
        me: LocalIdentity,
        myRole: DeviceRole,
        confirm: suspend (PeerSummary) -> Boolean,
    ): PairingResult = guarded(channel) {
        val helloBytes = step { channel.receive() }
        val hello = decodeHandshake(helloBytes) as? HandshakeMsg.PairHello
            ?: return@guarded PairingResult.Failed("unexpected first message")
        checkHello(hello.ephemeralKey, hello.nonce)

        val own = Crypto.generateEphemeral()
        val replyBytes = encodeHandshake(HandshakeMsg.PairReply(own.publicBytes, Crypto.randomBytes(Proto.NONCE_BYTES)))
        val reply = decodeHandshake(replyBytes) as HandshakeMsg.PairReply
        channel.send(replyBytes)

        val keys = derive(code, helloBytes, replyBytes, hello.nonce, reply.nonce, own, hello.ephemeralKey)

        val joinerConfirm = decodeHandshake(step { channel.receive() }) as? HandshakeMsg.PairConfirm
            ?: return@guarded PairingResult.Failed("unexpected message instead of confirmation")
        if (!Crypto.constantTimeEquals(joinerConfirm.mac, keys.joinerMac)) {
            // Told nothing except "no": the same message whatever was wrong.
            runCatching { channel.send(encodeHandshake(HandshakeMsg.Abort(ABORT_FAILED))) }
            return@guarded PairingResult.WrongCode
        }
        channel.send(encodeHandshake(HandshakeMsg.PairConfirm(keys.hostMac)))

        val secure = SecureChannel(
            channel, sendKey = keys.hostToJoiner, receiveKey = keys.joinerToHost,
            sendPrefix = SecureChannel.PREFIX_RESPONDER_TO_INITIATOR,
            receivePrefix = SecureChannel.PREFIX_INITIATOR_TO_RESPONDER,
        )
        secure.send(encodeSecure(helloOf(me, myRole)))
        val peer = decodeSecure(step { secure.receive() }) as? SecureMsg.Hello
            ?: return@guarded PairingResult.Failed("unexpected message instead of identity")
        if (peer.role != myRole.counterpart) return@guarded PairingResult.Failed("the other phone claims an incompatible role")
        val peerId = peer.deviceId.toHex()
        if (peerId == me.deviceId) return@guarded PairingResult.Failed("a phone cannot pair with itself")

        finish(secure, keys, PeerSummary(peerId, displayName(peer.name), theirRole = peer.role,
            myRole = myRole, verification = keys.verification), confirm)
    }

    // ── The phone typing the code ─────────────────────────────────────────

    suspend fun join(
        channel: FrameChannel,
        code: String,
        me: LocalIdentity,
        confirm: suspend (PeerSummary) -> Boolean,
    ): PairingResult = guarded(channel) {
        val own = Crypto.generateEphemeral()
        val nonce = Crypto.randomBytes(Proto.NONCE_BYTES)
        val helloBytes = encodeHandshake(HandshakeMsg.PairHello(own.publicBytes, nonce))
        channel.send(helloBytes)

        val replyBytes = step { channel.receive() }
        val reply = when (val m = decodeHandshake(replyBytes)) {
            is HandshakeMsg.PairReply -> m
            is HandshakeMsg.Abort -> return@guarded PairingResult.Failed("the other phone is not accepting pairing")
            else -> return@guarded PairingResult.Failed("unexpected reply")
        }
        checkHello(reply.ephemeralKey, reply.nonce)

        val keys = derive(code, helloBytes, replyBytes, nonce, reply.nonce, own, reply.ephemeralKey)
        // Proves knowledge of the code *first*. See the class comment.
        channel.send(encodeHandshake(HandshakeMsg.PairConfirm(keys.joinerMac)))

        val hostConfirm = when (val m = decodeHandshake(step { channel.receive() })) {
            is HandshakeMsg.PairConfirm -> m
            is HandshakeMsg.Abort -> return@guarded PairingResult.WrongCode
            else -> return@guarded PairingResult.Failed("unexpected message instead of confirmation")
        }
        if (!Crypto.constantTimeEquals(hostConfirm.mac, keys.hostMac)) {
            // The host did not prove it knows the code. Not "wrong code" — whoever answered is not
            // who generated it.
            return@guarded PairingResult.Failed("the other phone could not be verified")
        }

        val secure = SecureChannel(
            channel, sendKey = keys.joinerToHost, receiveKey = keys.hostToJoiner,
            sendPrefix = SecureChannel.PREFIX_INITIATOR_TO_RESPONDER,
            receivePrefix = SecureChannel.PREFIX_RESPONDER_TO_INITIATOR,
        )
        val peer = decodeSecure(step { secure.receive() }) as? SecureMsg.Hello
            ?: return@guarded PairingResult.Failed("unexpected message instead of identity")
        val myRole = peer.role.counterpart
        secure.send(encodeSecure(helloOf(me, myRole)))
        val peerId = peer.deviceId.toHex()
        if (peerId == me.deviceId) return@guarded PairingResult.Failed("a phone cannot pair with itself")

        finish(secure, keys, PeerSummary(peerId, displayName(peer.name), theirRole = peer.role,
            myRole = myRole, verification = keys.verification), confirm)
    }

    // ── Shared tail: both people decide ───────────────────────────────────

    private suspend fun finish(
        secure: SecureChannel,
        keys: Keys,
        summary: PeerSummary,
        confirm: suspend (PeerSummary) -> Boolean,
    ): PairingResult {
        // Neither phone commits anything until *both* people have said yes, and nothing is asked
        // of a person until the cryptography has already succeeded.
        val approved = withTimeout(confirmTimeoutMs) { confirm(summary) }
        secure.send(encodeSecure(SecureMsg.PairFinish(approved)))
        val theirs = decodeSecure(step { secure.receive() }) as? SecureMsg.PairFinish
            ?: return PairingResult.Failed("unexpected message instead of a decision")
        return if (approved && theirs.approved) {
            PairingResult.Success(summary.deviceId, summary.name, summary.myRole, summary.theirRole, keys.pairingKey)
        } else {
            PairingResult.Declined
        }
    }

    // ── Key schedule ──────────────────────────────────────────────────────

    private class Keys(
        val joinerMac: ByteArray,
        val hostMac: ByteArray,
        val hostToJoiner: ByteArray,
        val joinerToHost: ByteArray,
        val pairingKey: ByteArray,
        val verification: String,
    )

    /**
     * Both phones run this with the same inputs and must reach the same outputs.
     *
     * The transcript hash covers the exact bytes of both handshake messages, so every key and
     * every MAC is bound to *this* exchange: a message lifted from another session, or one whose
     * public key was swapped in transit, produces a different transcript and fails.
     */
    private suspend fun derive(
        code: String,
        helloBytes: ByteArray,
        replyBytes: ByteArray,
        joinerNonce: ByteArray,
        hostNonce: ByteArray,
        own: Crypto.EphemeralKey,
        peerPublic: ByteArray,
    ): Keys = withContext(Dispatchers.Default) {
        val transcript = Crypto.sha256(
            LABEL_TRANSCRIPT, lengthPrefixed(helloBytes), lengthPrefixed(replyBytes),
        )
        val salt = Crypto.sha256(LABEL_CODE_SALT, joinerNonce, hostNonce)
        val codeKey = Crypto.pbkdf2(code, salt, kdfIterations)
        val shared = Crypto.agree(own, peerPublic)
        // The code is the *salt* of the extraction and the DH secret the *input*: the output is
        // unpredictable without the DH secret, so a recording cannot be used to test code guesses.
        val prk = Crypto.hkdfExtract(codeKey, shared)

        fun expand(label: String, length: Int = Crypto.KEY_BYTES) =
            Crypto.hkdfExpand(prk, label.toByteArray(Charsets.US_ASCII) + transcript, length)

        val joinerMac = Crypto.hmac(expand("confirm/joiner"), "J".toByteArray(), transcript)
        val hostMac = Crypto.hmac(expand("confirm/host"), "H".toByteArray(), transcript, joinerMac)
        val sas = ByteBuffer.wrap(expand("verification", 4)).int.toLong() and 0xFFFFFFFFL
        Keys(
            joinerMac = joinerMac,
            hostMac = hostMac,
            hostToJoiner = expand("key/host-to-joiner"),
            joinerToHost = expand("key/joiner-to-host"),
            pairingKey = expand("pairing-key"),
            verification = (sas % 1_000_000L).toString().padStart(6, '0'),
        )
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private suspend fun <T> step(block: suspend () -> T): T = withTimeout(stepTimeoutMs) { block() }

    /**
     * Runs one side of the handshake and then closes the connection, whatever happened.
     *
     * Closing is not tidiness: a phone that gives up mid-handshake and leaves the link open leaves
     * the other phone sitting in a read until its timeout, which on a screen the user is looking at
     * is twenty seconds of "pairing…" after the answer is already known. Pairing is over once this
     * returns — success included, since a session starts afresh on its own connection.
     */
    private suspend fun guarded(channel: FrameChannel, block: suspend () -> PairingResult): PairingResult = try {
        block()
    } catch (e: TimeoutCancellationException) {
        PairingResult.Failed("timed out")
    } catch (e: ProtocolException) {
        PairingResult.Failed("protocol error: ${e.message}")
    } catch (e: CryptoException) {
        PairingResult.Failed("crypto error: ${e.message}")
    } catch (e: ChannelClosedException) {
        PairingResult.Failed("the connection closed")
    } catch (e: java.io.IOException) {
        PairingResult.Failed("connection error")
    } finally {
        channel.close()
    }

    private fun checkHello(ephemeralKey: ByteArray, nonce: ByteArray) {
        if (ephemeralKey.isEmpty()) throw ProtocolException("missing public key")
        if (nonce.size != Proto.NONCE_BYTES) throw ProtocolException("bad nonce")
    }

    private fun helloOf(me: LocalIdentity, role: DeviceRole): SecureMsg.Hello {
        val id = me.deviceId.hexToBytesOrNull()
        require(isValidSyncId(me.deviceId) && id != null) { "device id must be 32 hex characters" }
        return SecureMsg.Hello(id, cleanText(me.name, Proto.DEVICE_NAME_MAX_CHARS).ifEmpty { FALLBACK_NAME }, role)
    }

    private fun displayName(raw: String) = cleanText(raw, Proto.DEVICE_NAME_MAX_CHARS).ifEmpty { FALLBACK_NAME }

    private fun lengthPrefixed(b: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array()

    companion object {
        /**
         * 200k iterations of PBKDF2-HMAC-SHA-256 is a few hundred milliseconds on a mid-range
         * phone — unnoticeable once per pairing, and the whole point of the stretch for an
         * attacker who has to do it per guess.
         */
        const val DEFAULT_KDF_ITERATIONS = 200_000
        const val DEFAULT_STEP_TIMEOUT_MS = 20_000L
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 120_000L
        const val ABORT_FAILED = 1
        const val FALLBACK_NAME = "מכשיר"

        private val LABEL_TRANSCRIPT = "SmartRing/pair/transcript/v1".toByteArray(Charsets.US_ASCII)
        private val LABEL_CODE_SALT = "SmartRing/pair/code-salt/v1".toByteArray(Charsets.US_ASCII)
    }
}
