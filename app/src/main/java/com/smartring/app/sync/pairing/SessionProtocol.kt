package com.smartring.app.sync.pairing

import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.CryptoException
import com.smartring.app.sync.crypto.FrameChannel
import com.smartring.app.sync.crypto.SecureChannel
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.sync.protocol.encodeHandshake
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer

/** A pairing the responding phone is willing to talk to: whose key to try, and under what id. */
class SessionCandidate(val deviceId: String, val pairingKey: ByteArray)

sealed interface SessionAuth {
    /** Both sides proved they hold the pairing key. [channel] is ready for use. */
    class Authenticated(val deviceId: String, val channel: SecureChannel) : SessionAuth

    /** Not one of our pairings, or the proof was wrong. Deliberately not distinguished. */
    data object NotAuthorized : SessionAuth

    data class Failed(val reason: String) : SessionAuth
}

/**
 * Authenticating an existing pairing, every time two paired phones connect.
 *
 * ## No stable identifier on the air
 *
 * The connecting phone has to tell the listening one *which* pairing this is, but sending a
 * device id would let anyone in range recognise, and track, the same phone across sessions.
 * Instead it sends a **hint**: a MAC of its fresh nonce under the pairing key. The listener
 * tries each of its (few) paired keys against it and finds the match; an eavesdropper sees 16
 * bytes that differ every time and mean nothing without the key.
 *
 * ## Mutual proof, fresh keys
 *
 * Each side proves it holds the pairing key by MACing the transcript, and the session keys come
 * from a *fresh* ephemeral Diffie-Hellman exchange keyed by it — so a later compromise of the
 * pairing key does not expose recorded sessions, and an old session's keys are useless for a new
 * one. A man in the middle can relay honestly but cannot alter anything: the transcript includes
 * both public keys, and the proofs verify under a key he does not have.
 *
 * ## Not an oracle
 *
 * An unknown device and a known one with the wrong key get the same answer. A scanner learns
 * nothing about which phones have been paired to this one.
 */
class SessionProtocol(
    private val stepTimeoutMs: Long = PairingProtocol.DEFAULT_STEP_TIMEOUT_MS,
) {

    /** The phone that opens the connection, using the key it stored when pairing with [deviceId]. */
    suspend fun initiate(channel: FrameChannel, deviceId: String, pairingKey: ByteArray): SessionAuth = guarded(channel) {
        val own = Crypto.generateEphemeral()
        val nonce = Crypto.randomBytes(Proto.NONCE_BYTES)
        val hello = HandshakeMsg.SessionHello(own.publicBytes, nonce, hintFor(pairingKey, nonce))
        val helloBytes = encodeHandshake(hello)
        channel.send(helloBytes)

        val replyBytes = step { channel.receive() }
        val reply = when (val m = decodeHandshake(replyBytes)) {
            is HandshakeMsg.SessionReply -> m
            is HandshakeMsg.Abort -> return@guarded SessionAuth.NotAuthorized
            else -> return@guarded SessionAuth.Failed("unexpected reply")
        }
        val keys = derive(pairingKey, helloBytes, reply.ephemeralKey, reply.nonce, own)
        if (!Crypto.constantTimeEquals(reply.proof, keys.responderProof)) {
            return@guarded SessionAuth.NotAuthorized
        }
        channel.send(encodeHandshake(HandshakeMsg.SessionProof(keys.initiatorProof(reply.proof))))

        SessionAuth.Authenticated(
            deviceId,
            SecureChannel(channel, keys.initiatorToResponder, keys.responderToInitiator,
                SecureChannel.PREFIX_INITIATOR_TO_RESPONDER, SecureChannel.PREFIX_RESPONDER_TO_INITIATOR),
        )
    }

    /** The phone that was connected to, trying each pairing it would accept. */
    suspend fun respond(channel: FrameChannel, candidates: List<SessionCandidate>): SessionAuth = guarded(channel) {
        val helloBytes = step { channel.receive() }
        val hello = decodeHandshake(helloBytes) as? HandshakeMsg.SessionHello
            ?: return@guarded SessionAuth.Failed("unexpected first message")
        if (hello.ephemeralKey.isEmpty() || hello.nonce.size != Proto.NONCE_BYTES) {
            return@guarded SessionAuth.Failed("malformed hello")
        }

        // Every candidate is tried, even after a match: how long this takes must not reveal which
        // pairing matched, or whether any did.
        var matched: SessionCandidate? = null
        for (c in candidates) {
            if (Crypto.constantTimeEquals(hello.hint, hintFor(c.pairingKey, hello.nonce)) && matched == null) {
                matched = c
            }
        }
        val candidate = matched ?: run {
            runCatching { channel.send(encodeHandshake(HandshakeMsg.Abort(PairingProtocol.ABORT_FAILED))) }
            return@guarded SessionAuth.NotAuthorized
        }

        val own = Crypto.generateEphemeral()
        val replyNonce = Crypto.randomBytes(Proto.NONCE_BYTES)
        val keys = derive(candidate.pairingKey, helloBytes, own.publicBytes, replyNonce, own, peerPublic = hello.ephemeralKey)
        channel.send(encodeHandshake(HandshakeMsg.SessionReply(own.publicBytes, replyNonce, keys.responderProof)))

        val proof = decodeHandshake(step { channel.receive() }) as? HandshakeMsg.SessionProof
            ?: return@guarded SessionAuth.Failed("unexpected message instead of proof")
        if (!Crypto.constantTimeEquals(proof.proof, keys.initiatorProof(keys.responderProof))) {
            return@guarded SessionAuth.NotAuthorized
        }

        SessionAuth.Authenticated(
            candidate.deviceId,
            SecureChannel(channel, keys.responderToInitiator, keys.initiatorToResponder,
                SecureChannel.PREFIX_RESPONDER_TO_INITIATOR, SecureChannel.PREFIX_INITIATOR_TO_RESPONDER),
        )
    }

    // ── Key schedule ──────────────────────────────────────────────────────

    private class Keys(
        private val authKey: ByteArray,
        val responderProof: ByteArray,
        val initiatorToResponder: ByteArray,
        val responderToInitiator: ByteArray,
        private val transcript: ByteArray,
    ) {
        /** Bound to the responder's proof, so it cannot be computed — or replayed — without seeing it. */
        fun initiatorProof(responderProof: ByteArray): ByteArray =
            Crypto.hmac(authKey, "I".toByteArray(), transcript, responderProof)
    }

    /**
     * Initiator and responder both call this. The responder passes its own public key where the
     * initiator passes the reply's, so [transcriptKey] is the *responder's* key on both sides.
     */
    private fun derive(
        pairingKey: ByteArray,
        helloBytes: ByteArray,
        transcriptKey: ByteArray,
        responderNonce: ByteArray,
        own: Crypto.EphemeralKey,
        peerPublic: ByteArray = transcriptKey,
    ): Keys {
        val transcript = Crypto.sha256(LABEL_TRANSCRIPT, helloBytes, transcriptKey, responderNonce)
        val shared = Crypto.agree(own, peerPublic)
        // The pairing key is the extraction salt and the fresh DH secret the input: knowing the
        // pairing key alone — say, from a stolen phone — does not unlock a recorded session.
        val prk = Crypto.hkdfExtract(pairingKey, shared)
        fun expand(label: String) =
            Crypto.hkdfExpand(prk, label.toByteArray(Charsets.US_ASCII) + transcript, Crypto.KEY_BYTES)
        val authKey = expand("auth")
        return Keys(
            authKey = authKey,
            responderProof = Crypto.hmac(authKey, "R".toByteArray(), transcript),
            initiatorToResponder = expand("key/initiator-to-responder"),
            responderToInitiator = expand("key/responder-to-initiator"),
            transcript = transcript,
        )
    }

    private fun hintFor(pairingKey: ByteArray, nonce: ByteArray): ByteArray =
        Crypto.hmac(pairingKey, "hint".toByteArray(Charsets.US_ASCII), nonce).copyOf(Proto.HINT_BYTES)

    private suspend fun <T> step(block: suspend () -> T): T = withTimeout(stepTimeoutMs) { block() }

    /**
     * Closes the connection unless authentication succeeded — in which case the caller owns it,
     * through the [SecureChannel] it was handed. A failed attempt must not leave the other phone
     * waiting out its timeout.
     */
    private suspend fun guarded(channel: FrameChannel, block: suspend () -> SessionAuth): SessionAuth {
        val result = try {
            block()
        } catch (e: TimeoutCancellationException) {
            SessionAuth.Failed("timed out")
        } catch (e: ProtocolException) {
            SessionAuth.Failed("protocol error: ${e.message}")
        } catch (e: CryptoException) {
            SessionAuth.Failed("crypto error: ${e.message}")
        } catch (e: ChannelClosedException) {
            SessionAuth.Failed("the connection closed")
        } catch (e: java.io.IOException) {
            SessionAuth.Failed("connection error")
        }
        if (result !is SessionAuth.Authenticated) channel.close()
        return result
    }

    private companion object {
        val LABEL_TRANSCRIPT = "SmartRing/session/transcript/v1".toByteArray(Charsets.US_ASCII)
    }
}
