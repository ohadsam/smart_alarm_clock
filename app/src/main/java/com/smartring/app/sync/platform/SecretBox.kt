package com.smartring.app.sync.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seals a secret so that what reaches the database is useless anywhere but on this phone.
 *
 * An interface so the repository above it can be tested on the JVM, where there is no Android
 * Keystore, against a trivial fake — while the real implementation is exercised on an emulator,
 * where there is.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** The secret, or null if it cannot be recovered (the key is gone, or the blob was altered). */
    fun open(sealed: ByteArray): ByteArray?
}

/**
 * AES-256-GCM under a key that lives in the Android Keystore and never leaves it.
 *
 * The pairing key is the one long-lived secret in this feature: whoever holds it can pose as a
 * paired phone. Stored as plaintext it would travel with any copy of the database — a backup, a
 * rooted dump, a screenshot of a file manager. Wrapped by a hardware-backed key, a copy of the
 * database holds ciphertext only this phone's secure hardware can open.
 *
 * Layout: 12-byte IV, then ciphertext and tag. The Keystore chooses the IV; reusing one under
 * GCM would be catastrophic, so it is never supplied from here.
 *
 * If the Keystore key is gone — a factory reset restores nothing, the app has `allowBackup`
 * off — [open] returns null and the pairing reads as broken rather than crashing: the user pairs
 * again, which is the correct recovery.
 */
@Singleton
class KeystoreSecretBox @Inject constructor() : SecretBox {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "smartring_sync_pairing_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
