package com.smartring.app.sync.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The primitives the pairing protocol is built from — and nothing else.
 *
 * Deliberately thin and deliberately standard: AES-256-GCM, HMAC-SHA-256, HKDF-SHA-256,
 * PBKDF2-HMAC-SHA-256 and ECDH on P-256, every one of them provided by the platform's own
 * JCE on every API level this app supports (26+). There is no home-made primitive here, no
 * third-party crypto dependency, and no code path that differs between the JVM unit tests
 * and a phone — so what the tests run is what ships.
 *
 * What is built *from* them — the handshake, the message framing — lives next door, in
 * `PairingProtocol`, `SessionProtocol` and `SecureChannel`, and is where the design
 * decisions are. This file is the bricks.
 */
object Crypto {

    private val random = SecureRandom()

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "an empty HMAC key is a bug, not a key" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Compares in time independent of where the first difference is. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ── HKDF (RFC 5869) ───────────────────────────────────────────────────

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(KEY_BYTES) else salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * KEY_BYTES)) { "HKDF length out of range" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, take)
            produced += take
            counter++
        }
        return out
    }

    // ── Password stretching ───────────────────────────────────────────────

    /**
     * PBKDF2-HMAC-SHA-256 of the pairing code.
     *
     * The code is eight digits — about 26 bits — which is far too little to stand on its own
     * against someone who can try guesses offline. The stretch is what keeps a man in the
     * middle, who gets exactly one live attempt per connection, from simply trying all 10^8
     * codes inside the handshake's timeout. The iteration count is a protocol constant that
     * both phones must agree on; tests pass a small one for speed.
     */
    fun pbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations >= 1)
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ── Elliptic-curve Diffie-Hellman on P-256 ────────────────────────────

    /** A throwaway key pair, used for one handshake and then dropped — which is what gives forward secrecy. */
    class EphemeralKey internal constructor(internal val pair: KeyPair) {
        /** X.509 SubjectPublicKeyInfo — what goes on the wire. */
        val publicBytes: ByteArray get() = pair.public.encoded
    }

    fun generateEphemeral(): EphemeralKey {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), random)
        return EphemeralKey(gen.generateKeyPair())
    }

    /**
     * The shared secret from our private key and the peer's public one.
     *
     * The peer's key is untrusted input and is checked before use: it must decode, be on the
     * *same curve* as ours (a key on a weak curve is the classic invalid-curve attack), and
     * actually lie on that curve. The platform providers do much of this themselves; doing it
     * here as well costs nothing and means the guarantee does not depend on which provider
     * happens to be installed.
     */
    fun agree(own: EphemeralKey, peerPublicBytes: ByteArray): ByteArray {
        if (peerPublicBytes.size !in 64..200) throw CryptoException("public key has an implausible size")
        val peer = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicBytes))
        } catch (e: Exception) {
            throw CryptoException("public key does not decode", e)
        }
        if (peer !is ECPublicKey) throw CryptoException("not an EC key")
        val ours = own.pair.public as ECPublicKey
        if (peer.params.curve != ours.params.curve || peer.params.order != ours.params.order) {
            throw CryptoException("public key is on a different curve")
        }
        if (!isOnCurve(peer)) throw CryptoException("public key is not on the curve")
        return try {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(own.pair.private)
            ka.doPhase(peer, true)
            ka.generateSecret()
        } catch (e: Exception) {
            throw CryptoException("key agreement failed", e)
        }
    }

    /** y² = x³ + ax + b (mod p), and not the point at infinity. */
    internal fun isOnCurve(key: ECPublicKey): Boolean {
        val point = key.w
        if (point == java.security.spec.ECPoint.POINT_INFINITY) return false
        val curve = key.params.curve
        val p = (curve.field as java.security.spec.ECFieldFp).p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        // BigInteger.valueOf(2), not BigInteger.TWO: TWO is Java 9 and missing on API 26-32.
        val lhs = y.modPow(BigInteger.valueOf(2), p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(curve.a.multiply(x)).add(curve.b).mod(p)
        return lhs == rhs
    }

    // ── AES-256-GCM ───────────────────────────────────────────────────────

    fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** The plaintext, or null if the tag does not verify — never a partial or unauthenticated result. */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        if (ciphertext.size < TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}
