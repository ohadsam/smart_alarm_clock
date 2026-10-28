package com.smartring.app.sync.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Answers a reading phone with whatever [NfcOfferHolder] currently holds — see [NfcApdu] for the
 * exchange. Declared `exported` (the NFC system service must bind it) and protected by the
 * `BIND_NFC_SERVICE` permission only the platform holds; `requireDeviceUnlock` in its XML means a
 * locked phone offers nothing.
 */
@AndroidEntryPoint
class NfcOfferService : HostApduService() {

    @Inject lateinit var holder: NfcOfferHolder

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        NfcApdu.respond(commandApdu ?: ByteArray(0), holder.current())

    override fun onDeactivated(reason: Int) = Unit
}
