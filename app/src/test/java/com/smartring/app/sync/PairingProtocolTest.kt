package com.smartring.app.sync

import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.pairing.PairingProtocol
import com.smartring.app.sync.pairing.PairingResult
import com.smartring.app.sync.pairing.PeerSummary
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.sync.protocol.encodeHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing handshake, run for real between two simulated phones.
 *
 * Real in every respect that matters — the actual key exchange, the actual MACs, the actual
 * encrypted channel — with only the radio replaced by an in-memory pipe. The attack cases sit in
 * the middle of that pipe, which is where an attacker would be.
 *
 * `runBlocking` rather than `runTest`: the key derivation runs on a real dispatcher, and virtual
 * time would let a handshake step "time out" while a real thread is still working.
 */
class PairingProtocolTest {

    // Small iteration count: the stretch is a cost parameter, not behaviour, and 200k of them per
    // test would only make the suite slow.
    private val protocol = PairingProtocol(kdfIterations = 1_000, stepTimeoutMs = 5_000, confirmTimeoutMs = 5_000)

    private val mum = identity("אמא", 'a')
    private val kid = identity("דני", 'b')

    private class Run(val host: PairingResult, val join: PairingResult,
                      val hostSaw: PeerSummary?, val joinSaw: PeerSummary?)

    /** Host = the phone showing the code, joiner = the phone typing it. */
    private fun pair(
        hostCode: String = "12345678",
        joinCode: String = "12345678",
        hostRole: DeviceRole = DeviceRole.PARENT,
        hostApproves: Boolean = true,
        joinApproves: Boolean = true,
        hostIdentity: LocalIdentity = mum,
        joinIdentity: LocalIdentity = kid,
        setup: (host: MemoryFrameChannel, joiner: MemoryFrameChannel) -> Unit = { _, _ -> },
    ): Run = runBlocking {
        val (hostEnd, joinEnd) = memoryChannels()
        setup(hostEnd, joinEnd)
        var hostSaw: PeerSummary? = null
        var joinSaw: PeerSummary? = null
        val h = async { protocol.host(hostEnd, hostCode, hostIdentity, hostRole) { hostSaw = it; hostApproves } }
        val j = async { protocol.join(joinEnd, joinCode, joinIdentity) { joinSaw = it; joinApproves } }
        val hr = h.await()
        val jr = j.await()
        Run(hr, jr, hostSaw, joinSaw)
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun `two phones with the same code pair and share a key`() {
        val r = pair()

        val host = r.host as PairingResult.Success
        val join = r.join as PairingResult.Success
        assertArrayEquals("both phones must end up with the same long-term key", host.pairingKey, join.pairingKey)
        assertEquals(32, host.pairingKey.size)
    }

    @Test
    fun `each phone learns the other's id and name`() {
        val r = pair()
        val host = r.host as PairingResult.Success
        val join = r.join as PairingResult.Success

        assertEquals(kid.deviceId, host.peerDeviceId); assertEquals("דני", host.peerName)
        assertEquals(mum.deviceId, join.peerDeviceId); assertEquals("אמא", join.peerName)
    }

    @Test
    fun `the roles come out as counterparts`() {
        val r = pair(hostRole = DeviceRole.PARENT)
        val host = r.host as PairingResult.Success
        val join = r.join as PairingResult.Success

        assertEquals(DeviceRole.PARENT, host.myRole); assertEquals(DeviceRole.CHILD, host.theirRole)
        assertEquals(DeviceRole.CHILD, join.myRole); assertEquals(DeviceRole.PARENT, join.theirRole)
    }

    @Test
    fun `a child can host and a peer can pair with a peer`() {
        pair(hostRole = DeviceRole.CHILD).let {
            assertEquals(DeviceRole.PARENT, (it.join as PairingResult.Success).myRole)
        }
        pair(hostRole = DeviceRole.PEER).let {
            assertEquals(DeviceRole.PEER, (it.join as PairingResult.Success).myRole)
        }
    }

    /**
     * The check that holds even if the code were somehow known to someone in the middle: both
     * people read the same six digits off their own screens.
     */
    @Test
    fun `both people are shown the same verification number`() {
        val r = pair()
        assertNotNull(r.hostSaw); assertNotNull(r.joinSaw)
        assertEquals(r.hostSaw!!.verification, r.joinSaw!!.verification)
        assertEquals(6, r.hostSaw!!.verification.length)
        assertTrue(r.hostSaw!!.verification.all { it.isDigit() })
    }

    @Test
    fun `each person is shown who they are pairing with and what it will mean`() {
        val r = pair(hostRole = DeviceRole.PARENT)

        assertEquals("דני", r.hostSaw!!.name); assertEquals(DeviceRole.CHILD, r.hostSaw!!.theirRole)
        assertEquals("אמא", r.joinSaw!!.name); assertEquals(DeviceRole.PARENT, r.joinSaw!!.theirRole)
        assertEquals("the child is told it will be managed", DeviceRole.CHILD, r.joinSaw!!.myRole)
    }

    @Test
    fun `two pairings with the same code produce different keys and numbers`() {
        val a = pair(); val b = pair()
        assertFalse((a.host as PairingResult.Success).pairingKey.contentEquals((b.host as PairingResult.Success).pairingKey))
        assertFalse("the verification number is per-session", a.hostSaw!!.verification == b.hostSaw!!.verification)
    }

    // ── The wrong code ──────────────────────────────────────────────────────

    @Test
    fun `a wrong code is refused and the host is told it was the code`() {
        val r = pair(joinCode = "87654321")

        assertEquals(PairingResult.WrongCode, r.host)
        assertEquals(PairingResult.WrongCode, r.join)
    }

    @Test
    fun `a wrong code never asks either person anything`() {
        val r = pair(joinCode = "87654321")
        assertNull("nobody is shown a confirmation for a pairing that failed", r.hostSaw)
        assertNull(r.joinSaw)
    }

    @Test
    fun `a code that differs in one digit is still wrong`() {
        assertEquals(PairingResult.WrongCode, pair(joinCode = "12345679").host)
        assertEquals(PairingResult.WrongCode, pair(joinCode = "02345678").host)
    }

    // ── Either person can say no ────────────────────────────────────────────

    @Test
    fun `if the host user declines nothing is paired on either side`() {
        val r = pair(hostApproves = false)
        assertEquals(PairingResult.Declined, r.host)
        assertEquals(PairingResult.Declined, r.join)
    }

    @Test
    fun `if the joining user declines nothing is paired on either side`() {
        val r = pair(joinApproves = false)
        assertEquals(PairingResult.Declined, r.host)
        assertEquals(PairingResult.Declined, r.join)
    }

    // ── Someone in the middle ───────────────────────────────────────────────

    @Test
    fun `swapping the host's public key in transit breaks the pairing`() {
        val attackerKey = Crypto.generateEphemeral().publicBytes
        val r = pair(setup = { host, _ ->
            host.interfere = { index, frame ->
                if (index == 0) {
                    val reply = decodeHandshake(frame) as HandshakeMsg.PairReply
                    encodeHandshake(HandshakeMsg.PairReply(attackerKey, reply.nonce))
                } else frame
            }
        })

        assertFalse("a substituted key must not yield a pairing", r.host is PairingResult.Success)
        assertFalse(r.join is PairingResult.Success)
    }

    @Test
    fun `swapping the joiner's public key in transit breaks the pairing`() {
        val attackerKey = Crypto.generateEphemeral().publicBytes
        val r = pair(setup = { _, joiner ->
            joiner.interfere = { index, frame ->
                if (index == 0) {
                    val hello = decodeHandshake(frame) as HandshakeMsg.PairHello
                    encodeHandshake(HandshakeMsg.PairHello(attackerKey, hello.nonce))
                } else frame
            }
        })

        assertFalse(r.host is PairingResult.Success)
        assertFalse(r.join is PairingResult.Success)
    }

    @Test
    fun `altering a nonce in transit breaks the pairing`() {
        val r = pair(setup = { host, _ ->
            host.interfere = { index, frame ->
                if (index == 0) {
                    val reply = decodeHandshake(frame) as HandshakeMsg.PairReply
                    encodeHandshake(HandshakeMsg.PairReply(reply.ephemeralKey, ByteArray(16) { 1 }))
                } else frame
            }
        })
        assertFalse(r.host is PairingResult.Success)
        assertFalse(r.join is PairingResult.Success)
    }

    @Test
    fun `a flipped bit in the encrypted identity exchange ends the pairing`() {
        val r = pair(setup = { host, _ ->
            host.interfere = { index, frame ->
                // Frame 0 is the reply, 1 the host's confirmation, 2 the first encrypted message.
                if (index == 2) frame.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } else frame
            }
        })
        assertFalse(r.host is PairingResult.Success)
        assertFalse(r.join is PairingResult.Success)
    }

    @Test
    fun `a flipped bit in the final decision ends the pairing`() {
        val r = pair(setup = { host, joiner ->
            joiner.interfere = { index, frame ->
                // Joiner frames: 0 hello, 1 confirm, 2 encrypted identity, 3 decision.
                if (index == 3) frame.also { it[0] = (it[0].toInt() xor 1).toByte() } else frame
            }
        })
        assertFalse(r.host is PairingResult.Success)
    }

    /**
     * An eavesdropper who recorded a complete earlier pairing and replays the joiner's frames against a
     * fresh host. The new host's nonce differs, so the recorded confirmation does not verify.
     */
    @Test
    fun `a recorded joiner confirmation does not work against a new session`() = runBlocking {
        val (hostEnd1, joinEnd1) = memoryChannels()
        val h1 = async { protocol.host(hostEnd1, "12345678", mum, DeviceRole.PARENT) { true } }
        val j1 = async { protocol.join(joinEnd1, "12345678", kid) { true } }
        h1.await(); j1.await()
        val recorded = joinEnd1.sent.map { it.copyOf() }

        val (hostEnd2, attacker) = memoryChannels()
        val h2 = async { protocol.host(hostEnd2, "12345678", mum, DeviceRole.PARENT) { true } }
        attacker.send(recorded[0])           // replay the old hello…
        attacker.receive()                    // …take the new reply…
        attacker.send(recorded[1])           // …and replay the old confirmation
        val result = h2.await()

        assertEquals(PairingResult.WrongCode, result)
    }

    /**
     * Guessing without the code: an attacker who runs a perfectly valid key exchange but invents a
     * confirmation. This is the "one attempt per connection" the host's counter is built around.
     */
    @Test
    fun `a stranger who does not know the code is refused`() = runBlocking {
        val (hostEnd, attacker) = memoryChannels()
        val h = async { protocol.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true } }

        val own = Crypto.generateEphemeral()
        attacker.send(encodeHandshake(HandshakeMsg.PairHello(own.publicBytes, Crypto.randomBytes(16))))
        attacker.receive()
        attacker.send(encodeHandshake(HandshakeMsg.PairConfirm(Crypto.randomBytes(32))))

        assertEquals(PairingResult.WrongCode, h.await())
        // Told "no", and nothing else: the refusal carries no hint about what was close.
        val reply = decodeHandshake(attacker.receive())
        assertTrue(reply is HandshakeMsg.Abort)
    }

    @Test
    fun `nothing on the wire after the first two messages is readable`() {
        // What an eavesdropper recorded, from both directions.
        val all = runBlocking {
            val (hostEnd, joinEnd) = memoryChannels()
            val h = async { protocol.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true } }
            val j = async { protocol.join(joinEnd, "12345678", kid) { true } }
            h.await(); j.await()
            hostEnd.sent + joinEnd.sent
        }
        val blob = all.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertFalse("the device name must not travel in the clear", blob.contains("אמא".toByteArray().toString(Charsets.ISO_8859_1)))
        assertFalse(blob.contains(mum.deviceId)); assertFalse(blob.contains(kid.deviceId))
    }

    // ── Hostile or broken peers ─────────────────────────────────────────────

    @Test
    fun `a phone cannot pair with itself`() {
        val r = pair(joinIdentity = identity("אותו", 'a'))
        assertFalse(r.host is PairingResult.Success)
        assertFalse(r.join is PairingResult.Success)
    }

    @Test
    fun `a name full of control and override characters is cleaned before it is shown`() {
        val r = pair(joinIdentity = identity("‮אבא\u0000", 'b'))
        assertEquals("אבא", r.hostSaw!!.name)
    }

    @Test
    fun `an empty name is replaced by a placeholder rather than shown blank`() {
        val r = pair(joinIdentity = identity("\u0000", 'b'))
        assertEquals(PairingProtocol.FALLBACK_NAME, r.hostSaw!!.name)
    }

    @Test
    fun `garbage instead of a hello fails cleanly`() = runBlocking {
        val (hostEnd, attacker) = memoryChannels()
        val h = async { protocol.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true } }
        attacker.send("GET / HTTP/1.1".toByteArray())
        assertTrue(h.await() is PairingResult.Failed)
    }

    @Test
    fun `a peer that disconnects mid-handshake fails cleanly`() = runBlocking {
        val (hostEnd, attacker) = memoryChannels()
        val h = async { protocol.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true } }
        attacker.close()
        assertTrue(h.await() is PairingResult.Failed)
    }

    @Test
    fun `a peer that says nothing times out instead of hanging`() = runBlocking {
        val quick = PairingProtocol(kdfIterations = 10, stepTimeoutMs = 100, confirmTimeoutMs = 100)
        val (hostEnd, _) = memoryChannels()
        val result = quick.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true }
        assertTrue(result is PairingResult.Failed)
    }

    @Test
    fun `a user who never answers the confirmation times out`() = runBlocking {
        val quick = PairingProtocol(kdfIterations = 10, stepTimeoutMs = 5_000, confirmTimeoutMs = 150)
        val (hostEnd, joinEnd) = memoryChannels()
        val h = async { quick.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { kotlinx.coroutines.delay(10_000); true } }
        val j = async { quick.join(joinEnd, "12345678", kid) { true } }
        assertTrue(h.await() is PairingResult.Failed)
        j.cancel()
    }

    @Test
    fun `a version this phone does not speak is refused`() = runBlocking {
        val (hostEnd, attacker) = memoryChannels()
        val h = async { protocol.host(hostEnd, "12345678", mum, DeviceRole.PARENT) { true } }
        val hello = encodeHandshake(HandshakeMsg.PairHello(Crypto.generateEphemeral().publicBytes, Crypto.randomBytes(16)))
        hello[2] = 99
        attacker.send(hello)
        assertTrue(h.await() is PairingResult.Failed)
    }
}
