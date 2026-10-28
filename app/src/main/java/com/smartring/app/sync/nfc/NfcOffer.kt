package com.smartring.app.sync.nfc

import com.smartring.app.sync.pairing.PairingCodes
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.WireReader
import com.smartring.app.sync.protocol.WireWriter
import com.smartring.app.sync.protocol.alarm
import com.smartring.app.sync.protocol.cleanText
import com.smartring.app.sync.protocol.validated

/**
 * What one phone offers another when they are tapped together.
 *
 * NFC here is a *convenience*, never a credential. It reaches a few centimetres and nothing is
 * encrypted, so what travels is only what is safe for someone standing that close to see — and
 * everything is treated as untrusted on the way in.
 *
 *  - [PairInvite] saves typing the code and finding the phone in a list. The code is something the
 *    host is about to show on its own screen anyway; handing it over by touch is the same disclosure
 *    to the one person holding the other phone. The pairing handshake (code-bound key exchange, and
 *    both people confirming a verification number) still runs in full, so a tap that reached the
 *    wrong phone gets no further than the confirmation screen.
 *  - [AlarmShare] is an alarm handed over. Nothing authenticates the sender, so it is parked for
 *    the receiver's approval, labelled as an NFC hand-over, and never locks or manages anything.
 */
sealed interface NfcOffer {
    class PairInvite(
        val code: String,
        /** What the host's Bluetooth is called — how the joiner finds the right phone among those nearby. */
        val bluetoothName: String,
        val deviceName: String,
    ) : NfcOffer

    class AlarmShare(val senderName: String, val alarm: SyncAlarm) : NfcOffer
}

object NfcOffers {
    private const val MAGIC_0 = 0x53
    private const val MAGIC_1 = 0x4F   // "SO": an offer, not a protocol frame
    private const val VERSION = 1
    private const val T_PAIR_INVITE = 1
    private const val T_ALARM_SHARE = 2
    private const val BT_NAME_MAX_BYTES = 248

    fun encode(offer: NfcOffer): ByteArray {
        val w = WireWriter().u8(MAGIC_0).u8(MAGIC_1).u8(VERSION)
        when (offer) {
            is NfcOffer.PairInvite -> w.u8(T_PAIR_INVITE)
                .string(offer.code, PairingCodes.LENGTH * 4)
                .string(offer.bluetoothName, BT_NAME_MAX_BYTES)
                .string(offer.deviceName, Proto.DEVICE_NAME_MAX_BYTES)
            is NfcOffer.AlarmShare -> w.u8(T_ALARM_SHARE)
                .string(offer.senderName, Proto.DEVICE_NAME_MAX_BYTES)
                .alarm(offer.alarm)
        }
        return w.toByteArray().also { check(it.size <= NfcApdu.MAX_PAYLOAD) { "offer too large for NFC" } }
    }

    /**
     * The offer in [bytes], or null if it is not one this app understands. Everything is checked:
     * the code is eight digits, names are cleaned, an alarm must validate. A tag or a hostile phone
     * can put anything in these bytes.
     */
    fun decode(bytes: ByteArray): NfcOffer? = try {
        if (bytes.size > NfcApdu.MAX_PAYLOAD) throw ProtocolException("offer too large")
        val r = WireReader(bytes)
        if (r.u8() != MAGIC_0 || r.u8() != MAGIC_1) throw ProtocolException("not an offer")
        if (r.u8() != VERSION) throw ProtocolException("unsupported version")
        val offer = when (r.u8()) {
            T_PAIR_INVITE -> {
                val code = PairingCodes.normalize(r.string(PairingCodes.LENGTH * 4)) ?: throw ProtocolException("bad code")
                val bt = cleanText(r.string(BT_NAME_MAX_BYTES), BT_NAME_MAX_BYTES)
                val name = cleanText(r.string(Proto.DEVICE_NAME_MAX_BYTES), Proto.DEVICE_NAME_MAX_CHARS)
                NfcOffer.PairInvite(code, bt, name)
            }
            T_ALARM_SHARE -> {
                val sender = cleanText(r.string(Proto.DEVICE_NAME_MAX_BYTES), Proto.DEVICE_NAME_MAX_CHARS)
                val alarm = r.alarm().validated() ?: throw ProtocolException("invalid alarm")
                NfcOffer.AlarmShare(sender, alarm)
            }
            else -> throw ProtocolException("unknown offer")
        }
        r.requireEnd()
        offer
    } catch (e: ProtocolException) {
        null
    }
}
