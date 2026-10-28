package com.smartring.app.sync

import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.CryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The primitives, checked against published test vectors where one exists — so that "AES-GCM",
 * "HKDF" and "PBKDF2" here mean the standard algorithms and not something merely similar.
 */
class CryptoTest {

    // ── HKDF: RFC 5869, test case 1 ─────────────────────────────────────────

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hex()
        val info = "f0f1f2f3f4f5f6f7f8f9".hex()

        val prk = Crypto.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())

        val okm = Crypto.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `HKDF expand yields different output for different info`() {
        val prk = Crypto.randomBytes(32)
        assertFalse(Crypto.hkdfExpand(prk, "a".toByteArray(), 32)
            .contentEquals(Crypto.hkdfExpand(prk, "b".toByteArray(), 32)))
    }

    // ── PBKDF2-HMAC-SHA256 ───────────────────────────────────────────────────

    @Test
    fun `PBKDF2 matches the published SHA-256 vectors`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            Crypto.pbkdf2("password", "salt".toByteArray(), 1).toHex(),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            Crypto.pbkdf2("password", "salt".toByteArray(), 2).toHex(),
        )
    }

    @Test
    fun `PBKDF2 depends on the salt and on the secret`() {
        val a = Crypto.pbkdf2("12345678", "s1".toByteArray(), 10)
        assertFalse(a.contentEquals(Crypto.pbkdf2("12345678", "s2".toByteArray(), 10)))
        assertFalse(a.contentEquals(Crypto.pbkdf2("12345679", "s1".toByteArray(), 10)))
    }

    // ── HMAC ─────────────────────────────────────────────────────────────────

    @Test
    fun `HMAC matches RFC 4231 test case 1`() {
        val mac = Crypto.hmac(ByteArray(20) { 0x0b }, "Hi There".toByteArray())
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac.toHex())
    }

    @Test
    fun `HMAC over several parts equals HMAC over their concatenation`() {
        val key = Crypto.randomBytes(32)
        assertArrayEquals(
            Crypto.hmac(key, "ab".toByteArray(), "cd".toByteArray()),
            Crypto.hmac(key, "abcd".toByteArray()),
        )
    }

    @Test
    fun `an empty HMAC key is refused rather than treated as a key`() {
        try { Crypto.hmac(ByteArray(0), "x".toByteArray()); fail("expected a refusal") }
        catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `constant-time comparison agrees with equality`() {
        assertTrue(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    // ── AES-GCM ──────────────────────────────────────────────────────────────

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 1).toByte() }
    private val aad = "aad".toByteArray()

    @Test
    fun `AEAD round-trips`() {
        val sealed = Crypto.aeadSeal(key, nonce, aad, "שלום".toByteArray())
        assertArrayEquals("שלום".toByteArray(), Crypto.aeadOpen(key, nonce, aad, sealed))
    }

    @Test
    fun `AEAD rejects a flipped ciphertext bit`() {
        val sealed = Crypto.aeadSeal(key, nonce, aad, "hello".toByteArray())
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(Crypto.aeadOpen(key, nonce, aad, sealed))
    }

    @Test
    fun `AEAD rejects a flipped tag bit`() {
        val sealed = Crypto.aeadSeal(key, nonce, aad, "hello".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(Crypto.aeadOpen(key, nonce, aad, sealed))
    }

    @Test
    fun `AEAD rejects the wrong key, nonce or associated data`() {
        val sealed = Crypto.aeadSeal(key, nonce, aad, "hello".toByteArray())
        assertNull(Crypto.aeadOpen(ByteArray(32) { 9 }, nonce, aad, sealed))
        assertNull(Crypto.aeadOpen(key, ByteArray(12) { 9 }, aad, sealed))
        assertNull(Crypto.aeadOpen(key, nonce, "other".toByteArray(), sealed))
    }

    @Test
    fun `AEAD rejects input shorter than a tag instead of throwing`() {
        assertNull(Crypto.aeadOpen(key, nonce, aad, ByteArray(3)))
        assertNull(Crypto.aeadOpen(key, nonce, aad, ByteArray(0)))
    }

    @Test
    fun `AEAD output is the plaintext plus a 16-byte tag`() {
        assertEquals(5 + 16, Crypto.aeadSeal(key, nonce, aad, ByteArray(5)).size)
    }

    // ── ECDH ─────────────────────────────────────────────────────────────────

    @Test
    fun `both sides of an ECDH exchange reach the same secret`() {
        val a = Crypto.generateEphemeral()
        val b = Crypto.generateEphemeral()
        assertArrayEquals(Crypto.agree(a, b.publicBytes), Crypto.agree(b, a.publicBytes))
    }

    @Test
    fun `different exchanges produce different secrets`() {
        val a = Crypto.generateEphemeral()
        val b = Crypto.generateEphemeral()
        val c = Crypto.generateEphemeral()
        assertFalse(Crypto.agree(a, b.publicBytes).contentEquals(Crypto.agree(a, c.publicBytes)))
    }

    @Test
    fun `an ephemeral key is not reused`() {
        assertFalse(Crypto.generateEphemeral().publicBytes.contentEquals(Crypto.generateEphemeral().publicBytes))
    }

    @Test
    fun `a public key that does not decode is refused`() {
        val a = Crypto.generateEphemeral()
        try { Crypto.agree(a, ByteArray(91) { 7 }); fail("expected a refusal") }
        catch (expected: CryptoException) { }
    }

    @Test
    fun `a public key of implausible size is refused`() {
        val a = Crypto.generateEphemeral()
        try { Crypto.agree(a, ByteArray(5)); fail("expected a refusal") }
        catch (expected: CryptoException) { }
        try { Crypto.agree(a, ByteArray(5000)); fail("expected a refusal") }
        catch (expected: CryptoException) { }
    }

    /**
     * The invalid-curve attack: a key whose point is not on P-256 steers the shared secret into a
     * small subgroup. The last bytes of the X.509 encoding are the Y coordinate, so changing one
     * moves the point off the curve; whichever layer notices — the provider on decoding or our own
     * check — the result must be a refusal, never a secret.
     */
    @Test
    fun `a point that is not on the curve is refused`() {
        val a = Crypto.generateEphemeral()
        val b = Crypto.generateEphemeral()
        val corrupted = b.publicBytes.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }
        try { Crypto.agree(a, corrupted); fail("a corrupted point must not yield a secret") }
        catch (expected: CryptoException) { }
    }

    @Test
    fun `random bytes are the requested length and not constant`() {
        assertEquals(16, Crypto.randomBytes(16).size)
        assertFalse(Crypto.randomBytes(16).contentEquals(Crypto.randomBytes(16)))
        assertNotNull(Crypto.randomBytes(0))
    }
}
