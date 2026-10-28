package com.smartring.app.sync.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep

/**
 * Reading an offer from the phone held against this one.
 *
 * Reader mode is enabled only while a screen that wants an offer is showing, and disabled when it
 * goes away: a phone that is always reading would pop up for every tag it passes and fight the
 * system's own handling of them.
 */
object NfcReader {

    fun adapter(context: Context): NfcAdapter? = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()

    fun isSupported(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /**
     * Starts reading. [onOffer] is called on a binder thread with each decoded offer — switch to the
     * main thread before touching UI.
     */
    fun enable(activity: Activity, onOffer: (NfcOffer) -> Unit) {
        val nfc = adapter(activity) ?: return
        runCatching {
            nfc.enableReaderMode(
                activity,
                { tag -> read(tag)?.let(onOffer) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }
    }

    fun disable(activity: Activity) {
        runCatching { adapter(activity)?.disableReaderMode(activity) }
    }

    private fun read(tag: Tag): NfcOffer? {
        val iso = IsoDep.get(tag) ?: return null
        return try {
            iso.connect()
            iso.timeout = READ_TIMEOUT_MS
            NfcApdu.fetch { iso.transceive(it) }?.let { NfcOffers.decode(it) }
        } catch (e: Exception) {
            null   // the phones moved apart mid-read, or it was not this app: nothing to report
        } finally {
            runCatching { iso.close() }
        }
    }

    private const val READ_TIMEOUT_MS = 3_000
}
