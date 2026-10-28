package com.smartring.app.sync

import com.smartring.app.sync.protocol.AckKind
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SecureMsg
import com.smartring.app.sync.protocol.WireReader
import com.smartring.app.sync.protocol.WireWriter
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.sync.protocol.decodeSecure
import com.smartring.app.sync.protocol.encodeHandshake
import com.smartring.app.sync.protocol.encodeSecure
import com.smartring.app.sync.protocol.toSyncAlarm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The wire format parses bytes from another phone, so these are mostly "what does it do with
 * something that is not exactly what was specified". The answer, every time, has to be a
 * [ProtocolException] — never a crash, never a guess, never a silent acceptance.
 */
class WireAndMessagesTest {

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected a ProtocolException") } catch (expected: ProtocolException) { }
    }

    // ── Reader / writer ─────────────────────────────────────────────────────

    @Test
    fun `every field type round-trips`() {
        val bytes = WireWriter().u8(200).u16(60000).i32(-5).i64(Long.MIN_VALUE).bool(true).bool(false)
            .string("שלום 😀", 100).optString(null, 10).optString("x", 10).optI64(null).optI64(42L)
            .fixed(byteArrayOf(1, 2, 3), 3).toByteArray()
        val r = WireReader(bytes)

        assertEquals(200, r.u8()); assertEquals(60000, r.u16()); assertEquals(-5, r.i32())
        assertEquals(Long.MIN_VALUE, r.i64()); assertTrue(r.bool()); assertFalse(r.bool())
        assertEquals("שלום 😀", r.string(100)); assertEquals(null, r.optString(10)); assertEquals("x", r.optString(10))
        assertEquals(null, r.optI64()); assertEquals(42L, r.optI64())
        assertArrayEquals(byteArrayOf(1, 2, 3), r.fixed(3))
        r.requireEnd()
    }

    @Test
    fun `negative and boundary integers survive`() {
        val r = WireReader(WireWriter().i32(Int.MIN_VALUE).i32(Int.MAX_VALUE).i64(-1L).i64(Long.MAX_VALUE).toByteArray())
        assertEquals(Int.MIN_VALUE, r.i32()); assertEquals(Int.MAX_VALUE, r.i32())
        assertEquals(-1L, r.i64()); assertEquals(Long.MAX_VALUE, r.i64())
    }

    @Test
    fun `a truncated message is rejected`() {
        assertRejected { WireReader(byteArrayOf(0, 5, 1)).bytes(100) }
        assertRejected { WireReader(ByteArray(3)).i32() }
        assertRejected { WireReader(ByteArray(0)).u8() }
    }

    @Test
    fun `a length prefix beyond the limit is rejected before anything is read`() {
        val bytes = WireWriter().bytes(ByteArray(50), 100).toByteArray()
        assertRejected { WireReader(bytes).bytes(10) }
    }

    @Test
    fun `a length prefix that promises more than is there is rejected`() {
        // Claims 1000 bytes, supplies 2.
        assertRejected { WireReader(byteArrayOf(0x03, 0xE8.toByte(), 1, 2)).bytes(2000) }
    }

    @Test
    fun `a boolean other than zero or one is rejected`() {
        assertRejected { WireReader(byteArrayOf(2)).bool() }
        assertRejected { WireReader(byteArrayOf(0xFF.toByte())).bool() }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val r = WireReader(byteArrayOf(1, 2))
        r.u8()
        assertRejected { r.requireEnd() }
    }

    @Test
    fun `invalid UTF-8 is rejected rather than replaced`() {
        // 0xC3 0x28 is a malformed two-byte sequence.
        val bytes = WireWriter().bytes(byteArrayOf(0xC3.toByte(), 0x28), 10).toByteArray()
        assertRejected { WireReader(bytes).string(10) }
    }

    @Test
    fun `the writer refuses what the reader would refuse`() {
        try { WireWriter().bytes(ByteArray(11), 10); fail() } catch (expected: IllegalArgumentException) { }
        try { WireWriter().u8(256); fail() } catch (expected: IllegalArgumentException) { }
        try { WireWriter().fixed(ByteArray(3), 4); fail() } catch (expected: IllegalArgumentException) { }
    }

    // ── Handshake messages ──────────────────────────────────────────────────

    private val key = ByteArray(91) { it.toByte() }
    private val nonce = ByteArray(16) { (it + 1).toByte() }
    private val mac = ByteArray(32) { (it + 2).toByte() }
    private val hint = ByteArray(16) { (it + 3).toByte() }

    @Test
    fun `every handshake message round-trips`() {
        (decodeHandshake(encodeHandshake(HandshakeMsg.PairHello(key, nonce))) as HandshakeMsg.PairHello).let {
            assertArrayEquals(key, it.ephemeralKey); assertArrayEquals(nonce, it.nonce)
        }
        (decodeHandshake(encodeHandshake(HandshakeMsg.PairReply(key, nonce))) as HandshakeMsg.PairReply).let {
            assertArrayEquals(key, it.ephemeralKey); assertArrayEquals(nonce, it.nonce)
        }
        (decodeHandshake(encodeHandshake(HandshakeMsg.PairConfirm(mac))) as HandshakeMsg.PairConfirm)
            .let { assertArrayEquals(mac, it.mac) }
        (decodeHandshake(encodeHandshake(HandshakeMsg.SessionHello(key, nonce, hint))) as HandshakeMsg.SessionHello).let {
            assertArrayEquals(key, it.ephemeralKey); assertArrayEquals(nonce, it.nonce); assertArrayEquals(hint, it.hint)
        }
        (decodeHandshake(encodeHandshake(HandshakeMsg.SessionReply(key, nonce, mac))) as HandshakeMsg.SessionReply).let {
            assertArrayEquals(key, it.ephemeralKey); assertArrayEquals(mac, it.proof)
        }
        (decodeHandshake(encodeHandshake(HandshakeMsg.SessionProof(mac))) as HandshakeMsg.SessionProof)
            .let { assertArrayEquals(mac, it.proof) }
        assertEquals(7, (decodeHandshake(encodeHandshake(HandshakeMsg.Abort(7))) as HandshakeMsg.Abort).code)
    }

    @Test
    fun `a handshake frame that is not ours is rejected at once`() {
        assertRejected { decodeHandshake(byteArrayOf(0x47, 0x45, 0x54, 0x20, 0x2F)) }   // "GET /"
        assertRejected { decodeHandshake(ByteArray(0)) }
    }

    @Test
    fun `an unsupported protocol version is rejected`() {
        val frame = encodeHandshake(HandshakeMsg.Abort(1))
        frame[2] = (Proto.VERSION + 1).toByte()
        assertRejected { decodeHandshake(frame) }
    }

    @Test
    fun `an unknown handshake type is rejected`() {
        val frame = encodeHandshake(HandshakeMsg.Abort(1))
        frame[3] = 99
        assertRejected { decodeHandshake(frame) }
    }

    @Test
    fun `a handshake message with trailing bytes is rejected`() {
        assertRejected { decodeHandshake(encodeHandshake(HandshakeMsg.Abort(1)) + byteArrayOf(0)) }
    }

    @Test
    fun `a nonce of the wrong size cannot even be encoded`() {
        try { encodeHandshake(HandshakeMsg.PairHello(key, ByteArray(15))); fail() }
        catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `an oversized frame is rejected before it is parsed`() {
        assertRejected { decodeHandshake(ByteArray(Proto.MAX_FRAME_BYTES + 1)) }
        assertRejected { decodeSecure(ByteArray(Proto.MAX_FRAME_BYTES + 1)) }
    }

    // ── Secure messages ─────────────────────────────────────────────────────

    private val id16 = ByteArray(16) { (it + 5).toByte() }

    @Test
    fun `hello round-trips with its role and a non-ASCII name`() {
        val msg = decodeSecure(encodeSecure(SecureMsg.Hello(id16, "הטלפון של אמא", DeviceRole.PARENT))) as SecureMsg.Hello
        assertArrayEquals(id16, msg.deviceId); assertEquals("הטלפון של אמא", msg.name); assertEquals(DeviceRole.PARENT, msg.role)
    }

    @Test
    fun `all three roles survive`() {
        for (role in DeviceRole.entries) {
            val back = decodeSecure(encodeSecure(SecureMsg.Hello(id16, "x", role))) as SecureMsg.Hello
            assertEquals(role, back.role)
        }
    }

    @Test
    fun `upsert, delete, transfer and ack round-trip`() {
        val alarm = alarmOf("קום").toSyncAlarm()

        (decodeSecure(encodeSecure(SecureMsg.Upsert(id16, 7L, alarm))) as SecureMsg.Upsert).let {
            assertArrayEquals(id16, it.syncId); assertEquals(7L, it.revision); assertEquals(alarm, it.alarm)
        }
        (decodeSecure(encodeSecure(SecureMsg.Delete(id16, 9L))) as SecureMsg.Delete).let {
            assertEquals(9L, it.revision)
        }
        (decodeSecure(encodeSecure(SecureMsg.Transfer(id16, alarm))) as SecureMsg.Transfer).let {
            assertEquals(alarm, it.alarm)
        }
        (decodeSecure(encodeSecure(SecureMsg.Ack(AckKind.UPSERT, id16, 7L, AckStatus.STALE))) as SecureMsg.Ack).let {
            assertEquals(AckKind.UPSERT, it.kind); assertEquals(AckStatus.STALE, it.status); assertEquals(7L, it.revision)
        }
    }

    @Test
    fun `the unit messages round-trip`() {
        assertEquals(SecureMsg.Done, decodeSecure(encodeSecure(SecureMsg.Done)))
        assertEquals(SecureMsg.Unpair, decodeSecure(encodeSecure(SecureMsg.Unpair)))
        assertEquals(SecureMsg.Bye, decodeSecure(encodeSecure(SecureMsg.Bye)))
        assertTrue((decodeSecure(encodeSecure(SecureMsg.PairFinish(true))) as SecureMsg.PairFinish).approved)
        assertFalse((decodeSecure(encodeSecure(SecureMsg.PairFinish(false))) as SecureMsg.PairFinish).approved)
    }

    @Test
    fun `an unknown secure type, role, ack kind or status is rejected`() {
        assertRejected { decodeSecure(byteArrayOf(99)) }
        val hello = encodeSecure(SecureMsg.Hello(id16, "x", DeviceRole.PEER))
        hello[hello.size - 1] = 9
        assertRejected { decodeSecure(hello) }
        val ack = encodeSecure(SecureMsg.Ack(AckKind.DELETE, id16, 1L, AckStatus.OK))
        ack[1] = 9
        assertRejected { decodeSecure(ack) }
        val ack2 = encodeSecure(SecureMsg.Ack(AckKind.DELETE, id16, 1L, AckStatus.OK))
        ack2[ack2.size - 1] = 9
        assertRejected { decodeSecure(ack2) }
    }

    @Test
    fun `a secure message with trailing bytes is rejected`() {
        assertRejected { decodeSecure(encodeSecure(SecureMsg.Done) + byteArrayOf(0)) }
    }

    @Test
    fun `a truncated alarm inside an upsert is rejected`() {
        val full = encodeSecure(SecureMsg.Upsert(id16, 1L, alarmOf().toSyncAlarm()))
        assertRejected { decodeSecure(full.copyOf(full.size - 3)) }
    }

    @Test
    fun `an id of the wrong size cannot be encoded`() {
        try { encodeSecure(SecureMsg.Delete(ByteArray(15), 1L)); fail() } catch (expected: IllegalArgumentException) { }
    }
}
