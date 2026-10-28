package com.smartring.app.sync

import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.SecureChannel
import com.smartring.app.sync.pairing.SessionAuth
import com.smartring.app.sync.pairing.SessionCandidate
import com.smartring.app.sync.pairing.SessionProtocol
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.sync.protocol.encodeHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What runs every time two already-paired phones meet, and the encrypted pipe it produces.
 */
class SessionAndChannelTest {

    private val session = SessionProtocol(stepTimeoutMs = 5_000)
    private val key = Crypto.randomBytes(32)
    private val otherKey = Crypto.randomBytes(32)

    private class Pair2(val initiator: SessionAuth, val responder: SessionAuth)

    private fun connect(
        initiatorKey: ByteArray = key,
        candidates: List<SessionCandidate> = listOf(SessionCandidate(idOf('b'), key)),
        setup: (MemoryFrameChannel, MemoryFrameChannel) -> Unit = { _, _ -> },
    ): Pair2 = runBlocking {
        val (callerEnd, listenerEnd) = memoryChannels()
        setup(callerEnd, listenerEnd)
        val i = async { session.initiate(callerEnd, idOf('b'), initiatorKey) }
        val r = async { session.respond(listenerEnd, candidates) }
        Pair2(i.await(), r.await())
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    fun `two phones holding the same pairing key authenticate each other`() {
        val c = connect()
        assertTrue(c.initiator is SessionAuth.Authenticated)
        assertTrue(c.responder is SessionAuth.Authenticated)
    }

    @Test
    fun `the listener learns which pairing it is from the key alone`() {
        val c = connect()
        assertEquals(idOf('b'), (c.responder as SessionAuth.Authenticated).deviceId)
    }

    @Test
    fun `the right pairing is picked from several`() {
        val c = connect(candidates = listOf(
            SessionCandidate(idOf('c'), otherKey),
            SessionCandidate(idOf('b'), key),
            SessionCandidate(idOf('d'), Crypto.randomBytes(32)),
        ))
        assertEquals(idOf('b'), (c.responder as SessionAuth.Authenticated).deviceId)
    }

    @Test
    fun `the authenticated channel carries data both ways`() = runBlocking {
        val (callerEnd, listenerEnd) = memoryChannels()
        val i = async { session.initiate(callerEnd, idOf('b'), key) }
        val r = async { session.respond(listenerEnd, listOf(SessionCandidate(idOf('b'), key))) }
        val ci = (i.await() as SessionAuth.Authenticated).channel
        val cr = (r.await() as SessionAuth.Authenticated).channel

        ci.send("hello from caller".toByteArray())
        assertEquals("hello from caller", String(cr.receive()))
        cr.send("and back".toByteArray())
        assertEquals("and back", String(ci.receive()))
    }

    @Test
    fun `the wrong key is refused on both sides`() {
        val c = connect(initiatorKey = otherKey)
        assertEquals(SessionAuth.NotAuthorized, c.responder)
        assertEquals(SessionAuth.NotAuthorized, c.initiator)
    }

    @Test
    fun `a phone with no matching pairing is refused`() {
        val c = connect(candidates = emptyList())
        assertEquals(SessionAuth.NotAuthorized, c.responder)
    }

    /**
     * A scanner must not be able to tell "paired to this phone" from "not": both get the same answer.
     */
    @Test
    fun `an unknown device and a wrong key are indistinguishable`() {
        val unknown = connect(candidates = emptyList())
        val wrongKey = connect(initiatorKey = otherKey)
        assertEquals(unknown.responder, wrongKey.responder)
        assertEquals(unknown.initiator, wrongKey.initiator)
    }

    // ── Nothing stable on the air ────────────────────────────────────────────

    @Test
    fun `the identifying hint is different every session`() = runBlocking {
        val hints = (1..3).map {
            val (callerEnd, listenerEnd) = memoryChannels()
            val i = async { session.initiate(callerEnd, idOf('b'), key) }
            val r = async { session.respond(listenerEnd, listOf(SessionCandidate(idOf('b'), key))) }
            i.await(); r.await()
            (decodeHandshake(callerEnd.sent[0]) as HandshakeMsg.SessionHello).hint.toHex()
        }
        assertEquals("a repeating hint would let anyone in range track the phone", 3, hints.toSet().size)
    }

    @Test
    fun `no device id travels in the handshake`() = runBlocking {
        val (callerEnd, listenerEnd) = memoryChannels()
        val i = async { session.initiate(callerEnd, idOf('b'), key) }
        val r = async { session.respond(listenerEnd, listOf(SessionCandidate(idOf('b'), key))) }
        i.await(); r.await()
        val onTheAir = (callerEnd.sent + listenerEnd.sent).joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertFalse(onTheAir.contains(idOf('b')))
    }

    // ── Active attacks ───────────────────────────────────────────────────────

    @Test
    fun `altering the responder's public key is detected`() {
        val attacker = Crypto.generateEphemeral().publicBytes
        val c = connect(setup = { _, listener ->
            listener.interfere = { index, frame ->
                if (index == 0) {
                    val r = decodeHandshake(frame) as HandshakeMsg.SessionReply
                    encodeHandshake(HandshakeMsg.SessionReply(attacker, r.nonce, r.proof))
                } else frame
            }
        })
        assertFalse(c.initiator is SessionAuth.Authenticated)
    }

    @Test
    fun `altering the initiator's public key is detected`() {
        val attacker = Crypto.generateEphemeral().publicBytes
        val c = connect(setup = { caller, _ ->
            caller.interfere = { index, frame ->
                if (index == 0) {
                    val h = decodeHandshake(frame) as HandshakeMsg.SessionHello
                    encodeHandshake(HandshakeMsg.SessionHello(attacker, h.nonce, h.hint))
                } else frame
            }
        })
        assertFalse(c.responder is SessionAuth.Authenticated)
        assertFalse(c.initiator is SessionAuth.Authenticated)
    }

    @Test
    fun `a recorded initiator proof does not work in a new session`() = runBlocking {
        val (caller1, listener1) = memoryChannels()
        val i1 = async { session.initiate(caller1, idOf('b'), key) }
        val r1 = async { session.respond(listener1, listOf(SessionCandidate(idOf('b'), key))) }
        i1.await(); r1.await()
        val recorded = caller1.sent.map { it.copyOf() }

        val (attacker, listener2) = memoryChannels()
        val r2 = async { session.respond(listener2, listOf(SessionCandidate(idOf('b'), key))) }
        attacker.send(recorded[0])     // the old hello (and its hint)
        attacker.receive()             // the new reply
        attacker.send(recorded[1])     // the old proof
        val result = r2.await()

        // The hint is a MAC of the old nonce and *does* match the key, so this reaches the proof
        // check — and the proof, bound to the old transcript, fails there.
        assertEquals(SessionAuth.NotAuthorized, result)
    }

    @Test
    fun `garbage instead of a hello is refused cleanly`() = runBlocking {
        val (attacker, listener) = memoryChannels()
        val r = async { session.respond(listener, listOf(SessionCandidate(idOf('b'), key))) }
        attacker.send("not our protocol".toByteArray())
        assertTrue(r.await() is SessionAuth.Failed)
    }

    @Test
    fun `a failed authentication closes the connection straight away`() = runBlocking {
        val (callerEnd, listenerEnd) = memoryChannels()
        val i = async { session.initiate(callerEnd, idOf('b'), otherKey) }
        val r = async { session.respond(listenerEnd, listOf(SessionCandidate(idOf('b'), key))) }
        i.await(); r.await()
        // The listener refused and closed: a further read sees the end, not a hang.
        try { callerEnd.receive(); fail("expected the connection to be closed") }
        catch (expected: ChannelClosedException) { }
    }

    // ── The encrypted channel ────────────────────────────────────────────────

    private fun channelPair(): kotlin.Pair<SecureChannel, SecureChannel> {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        return SecureChannel(a, k1, k2, 1, 2) to SecureChannel(b, k2, k1, 2, 1)
    }

    @Test
    fun `messages arrive intact and in order`() = runBlocking {
        val (x, y) = channelPair()
        for (i in 1..5) x.send("m$i".toByteArray())
        for (i in 1..5) assertEquals("m$i", String(y.receive()))
    }

    @Test
    fun `an empty message is fine`() = runBlocking {
        val (x, y) = channelPair()
        x.send(ByteArray(0))
        assertEquals(0, y.receive().size)
    }

    @Test
    fun `a replayed frame is rejected`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        val y = SecureChannel(b, k2, k1, 2, 1)
        x.send("pay".toByteArray())
        val frame = a.sent[0].copyOf()
        assertEquals("pay", String(y.receive()))

        // The attacker re-sends the same ciphertext. Nonce counters have moved on, so it is not
        // valid under the one the receiver now expects.
        a.send(frame)
        try { y.receive(); fail("a replayed frame must not be accepted") }
        catch (expected: ProtocolException) { }
    }

    @Test
    fun `reordered frames are rejected`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        val y = SecureChannel(b, k2, k1, 2, 1)
        val held = ArrayList<ByteArray>()
        a.interfere = { _, frame -> held += frame; null }   // withhold both
        x.send("one".toByteArray()); x.send("two".toByteArray())
        a.interfere = { _, frame -> frame }
        a.send(held[1]); a.send(held[0])                    // deliver swapped

        try { y.receive(); fail("out-of-order delivery must be detected") }
        catch (expected: ProtocolException) { }
    }

    @Test
    fun `a dropped frame is detected at the next one`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        val y = SecureChannel(b, k2, k1, 2, 1)
        a.interfere = { index, frame -> if (index == 0) null else frame }
        x.send("first".toByteArray()); x.send("second".toByteArray())

        try { y.receive(); fail("the gap must be detected") }
        catch (expected: ProtocolException) { }
    }

    @Test
    fun `a tampered frame is rejected`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        val y = SecureChannel(b, k2, k1, 2, 1)
        a.interfere = { _, frame -> frame.also { it[0] = (it[0].toInt() xor 1).toByte() } }
        x.send("data".toByteArray())

        try { y.receive(); fail() } catch (expected: ProtocolException) { }
    }

    @Test
    fun `a frame bounced back at its sender is rejected`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        SecureChannel(b, k2, k1, 2, 1)
        x.send("hello".toByteArray())
        val frame = a.sent[0].copyOf()

        // Reflection attack: feed the sender its own message back as if it came from the peer.
        b.send(frame)
        try { x.receive(); fail("a reflected frame must not authenticate") }
        catch (expected: ProtocolException) { }
    }

    @Test
    fun `an oversized message cannot be sent`() = runBlocking {
        val (x, _) = channelPair()
        try { x.send(ByteArray(16 * 1024 + 1)); fail() } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        SecureChannel(b, k2, k1, 2, 1)
        x.send("a very recognisable plaintext".toByteArray())
        assertFalse(String(a.sent[0], Charsets.ISO_8859_1).contains("recognisable"))
    }

    @Test
    fun `identical messages encrypt differently`() = runBlocking {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        SecureChannel(b, k2, k1, 2, 1)
        x.send("same".toByteArray()); x.send("same".toByteArray())
        assertFalse("a repeated nonce would be a catastrophic AES-GCM failure",
            a.sent[0].contentEquals(a.sent[1]))
    }

    @Test
    fun `closing the channel wipes its keys`() = runBlocking {
        val (a, _) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val x = SecureChannel(a, k1, k2, 1, 2)
        x.close()
        assertArrayEquals(ByteArray(32), k1)
        assertArrayEquals(ByteArray(32), k2)
    }
}
