package com.smartring.app.sync.nfc

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing this phone is currently offering over NFC, if anything.
 *
 * Card emulation answers *any* reader that selects this app's id, so the offer exists only while a
 * person has explicitly asked to hand something over, and expires on its own after [ttlMillis]. The
 * emulation service answers "nothing here" the rest of the time — a stranger brushing past a phone
 * in a pocket learns nothing, not even that the app is installed.
 */
@Singleton
class NfcOfferHolder @Inject constructor() {
    @Volatile private var bytes: ByteArray? = null
    @Volatile private var expiresAt: Long = 0L

    fun publish(offer: NfcOffer, ttlMillis: Long = DEFAULT_TTL_MILLIS) {
        expiresAt = System.currentTimeMillis() + ttlMillis
        bytes = NfcOffers.encode(offer)
    }

    fun clear() {
        bytes = null
        expiresAt = 0L
    }

    /** The encoded offer, or null if there is none or it has expired. */
    fun current(): ByteArray? {
        val b = bytes ?: return null
        if (System.currentTimeMillis() > expiresAt) {
            clear()
            return null
        }
        return b
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
    }
}
