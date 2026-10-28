package com.smartring.app.sync

import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.sync.engine.StoredTransfer
import com.smartring.app.sync.engine.TransferInbox
import com.smartring.app.sync.engine.TransferStore
import com.smartring.app.sync.nfc.NfcApdu
import com.smartring.app.sync.nfc.NfcOffer
import com.smartring.app.sync.nfc.NfcOffers
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.alarm
import com.smartring.app.sync.protocol.toSyncAlarm
import com.smartring.app.sync.protocol.validated
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What two phones say to each other when tapped. The framing is the part that is easy to get off by
 * one, and the part a hostile phone controls, so it is all here as plain byte arrays.
 */
class NfcTest {

    private val alarm = alarmOf("קום", 6, 15).toSyncAlarm()

    // ── The offer ────────────────────────────────────────────────────────────

    @Test
    fun `an invitation survives the trip`() {
        val bytes = NfcOffers.encode(NfcOffer.PairInvite("12345678", "Pixel 7", "הטלפון של אמא"))
        val offer = NfcOffers.decode(bytes) as NfcOffer.PairInvite
        assertEquals("12345678", offer.code)
        assertEquals("Pixel 7", offer.bluetoothName)
        assertEquals("הטלפון של אמא", offer.deviceName)
    }

    @Test
    fun `a shared alarm survives the trip`() {
        val offer = NfcOffers.decode(NfcOffers.encode(NfcOffer.AlarmShare("נועה", alarm))) as NfcOffer.AlarmShare
        assertEquals("נועה", offer.senderName)
        assertEquals("קום", offer.alarm.name)
        assertEquals(6, offer.alarm.hour)
    }

    @Test
    fun `garbage is not an offer`() {
        assertNull(NfcOffers.decode(ByteArray(0)))
        assertNull(NfcOffers.decode(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(NfcOffers.decode(ByteArray(5000) { 0x53 }))
    }

    @Test
    fun `a protocol frame is not an offer`() {
        // Same first byte, different second: an offer is never confused with a handshake message.
        assertNull(NfcOffers.decode(byteArrayOf(0x53, 0x52, 1, 1)))
    }

    @Test
    fun `an invitation whose code is not eight digits is refused`() {
        val bytes = NfcOffers.encode(NfcOffer.PairInvite("12345678", "x", "y"))
        // Re-encode with a short code by building it the same way a hostile phone could.
        val bad = com.smartring.app.sync.protocol.WireWriter().u8(0x53).u8(0x4F).u8(1).u8(1)
            .string("1234", 32).string("x", 248).string("y", 96).toByteArray()
        assertNotNull(NfcOffers.decode(bytes))
        assertNull(NfcOffers.decode(bad))
    }

    @Test
    fun `an offer with trailing bytes is refused`() {
        val bytes = NfcOffers.encode(NfcOffer.PairInvite("12345678", "x", "y")) + byteArrayOf(0)
        assertNull(NfcOffers.decode(bytes))
    }

    @Test
    fun `an impossible alarm in an offer is refused`() {
        val bad = alarm.copy(hour = 99)
        val bytes = com.smartring.app.sync.protocol.WireWriter().u8(0x53).u8(0x4F).u8(1).u8(2)
            .string("a", 96).alarm(bad).toByteArray()
        assertNull(NfcOffers.decode(bytes))
    }

    @Test
    fun `names in an offer are cleaned`() {
        val bytes = NfcOffers.encode(NfcOffer.AlarmShare("a‮b", alarm))
        val offer = NfcOffers.decode(bytes) as NfcOffer.AlarmShare
        assertFalse(offer.senderName.contains('‮'))
    }

    // ── The conversation ─────────────────────────────────────────────────────

    private fun fetchFrom(offer: ByteArray?, log: MutableList<ByteArray> = mutableListOf()) =
        NfcApdu.fetch { cmd -> NfcApdu.respond(cmd, offer).also { log += cmd } }

    @Test
    fun `a reader fetches exactly what was on offer`() {
        for (size in listOf(1, 2, 100, 237, 238, 239, 240, 241, 480, 1000, 4000)) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            assertArrayEquals("size $size", payload, fetchFrom(payload))
        }
    }

    @Test
    fun `nothing on offer means nothing fetched`() {
        assertNull(fetchFrom(null))
    }

    @Test
    fun `a real offer goes through the whole exchange`() {
        val bytes = NfcOffers.encode(NfcOffer.AlarmShare("נועה", alarm))
        val got = fetchFrom(bytes)
        assertNotNull(got)
        assertTrue(NfcOffers.decode(got!!) is NfcOffer.AlarmShare)
    }

    @Test
    fun `the offering side refuses another application id`() {
        val wrong = byteArrayOf(0, 0xA4.toByte(), 4, 0, 4, 1, 2, 3, 4)
        assertArrayEquals(NfcApdu.SW_NOT_FOUND, NfcApdu.respond(wrong, byteArrayOf(1)))
    }

    @Test
    fun `a read past the end is refused`() {
        val offer = ByteArray(10)
        val past = NfcApdu.readCommand(5000, 10)
        assertArrayEquals(NfcApdu.SW_WRONG_PARAMS, NfcApdu.respond(past, offer))
    }

    @Test
    fun `nonsense commands get an error and nothing else`() {
        for (cmd in listOf(ByteArray(0), byteArrayOf(1), byteArrayOf(1, 2, 3, 4), byteArrayOf(0x80.toByte(), 0xB0.toByte(), 0, 0))) {
            val r = NfcApdu.respond(cmd, byteArrayOf(9, 9, 9))
            assertTrue(r.size == 2 && !NfcApdu.isOk(r))
        }
    }

    @Test
    fun `a reader is not kept looping by a phone lying about the length`() {
        var calls = 0
        val liar: (ByteArray) -> ByteArray = { cmd ->
            calls++
            if (cmd[1] == 0xA4.toByte()) NfcApdu.SW_OK
            else ByteArray(240) { 0x7F } + NfcApdu.SW_OK   // claims 0x7F7F bytes, forever
        }
        assertNull(NfcApdu.fetch(liar))
        assertTrue("bounded, was $calls", calls < 40)
    }

    @Test
    fun `a reader refuses a payload longer than it was told`() {
        // total says 3, but the phone sends 10 bytes in one slice
        val odd: (ByteArray) -> ByteArray = { cmd ->
            if (cmd[1] == 0xA4.toByte()) NfcApdu.SW_OK
            else byteArrayOf(0, 3) + ByteArray(8) + NfcApdu.SW_OK
        }
        assertNull(NfcApdu.fetch(odd))
    }

    // ── What the inbox does with an unauthenticated hand-over ────────────────

    private class Transfers : TransferStore {
        val items = linkedMapOf<String, StoredTransfer>()
        override suspend fun count() = items.size
        override suspend fun countFrom(deviceId: String) = items.values.count { it.fromDeviceId == deviceId }
        override suspend fun insert(transfer: StoredTransfer): Boolean { if (items.containsKey(transfer.transferId)) return false; items[transfer.transferId] = transfer; return true }
        override suspend fun get(transferId: String) = items[transferId]
        override suspend fun delete(transferId: String) { items.remove(transferId) }
    }

    @Test
    fun `an nfc hand-over is parked, labelled, and tied to no device`() = runBlocking {
        val store = Transfers(); val inbox = TransferInbox(store)
        assertEquals(AckStatus.OK, inbox.receiveUnverified("נועה", idOf('1'), alarm))
        assertTrue(store.items.getValue(idOf('1')).fromDeviceName.contains("NFC"))

        var received: com.smartring.app.domain.model.Alarm? = null
        inbox.accept(idOf('1')) { received = it; 7L }
        val sync = received!!.sync
        assertNull("no device to remember", sync.originDeviceId)
        assertTrue(sync.cameFromAnotherDevice)
        assertFalse("never locked", sync.isRemoteManaged)
        assertEquals(AlarmSync.LOCAL.managedForDeviceId, sync.managedForDeviceId)
    }

    @Test
    fun `strangers tapping cannot crowd out paired phones`() = runBlocking {
        val store = Transfers(); val inbox = TransferInbox(store, maxTotal = 30, maxPerPeer = 2)
        assertEquals(AckStatus.OK, inbox.receiveUnverified("x", idOf('1'), alarm))
        assertEquals(AckStatus.OK, inbox.receiveUnverified("x", idOf('2'), alarm))
        assertEquals(AckStatus.BUSY, inbox.receiveUnverified("x", idOf('3'), alarm))
        // a real peer still has its own room
        val peer = link('a', "אבא", DeviceRole.PEER, DeviceRole.PEER)
        assertEquals(AckStatus.OK, inbox.receive(peer, idOf('4'), alarm))
    }

    @Test
    fun `an nfc hand-over that does not validate is refused by the port`() {
        // The port validates before calling the inbox; the contract is that the inbox never sees it.
        assertNull(alarm.copy(hour = 99).validated())
    }
}
