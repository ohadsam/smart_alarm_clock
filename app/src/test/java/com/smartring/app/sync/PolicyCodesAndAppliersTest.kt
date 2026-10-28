package com.smartring.app.sync

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.sync.engine.AlarmScheduling
import com.smartring.app.sync.engine.ManagedAlarmStore
import com.smartring.app.sync.engine.RemoteAlarmApplier
import com.smartring.app.sync.engine.StoredTransfer
import com.smartring.app.sync.engine.TransferInbox
import com.smartring.app.sync.engine.TransferStore
import com.smartring.app.sync.pairing.PairingCodeSession
import com.smartring.app.sync.pairing.PairingCodes
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.toSyncAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PolicyCodesAndAppliersTest {

    // ── The permission matrix, as a table ────────────────────────────────────

    private data class Row(val mine: DeviceRole, val theirs: DeviceRole,
                           val manage: Boolean, val managed: Boolean, val copies: Boolean, val consistent: Boolean)

    private val matrix = listOf(
        //            mine                 theirs               manage  managed copies  consistent
        Row(DeviceRole.PARENT, DeviceRole.CHILD,  true,  false, false, true),
        Row(DeviceRole.CHILD,  DeviceRole.PARENT, false, true,  false, true),
        Row(DeviceRole.PEER,   DeviceRole.PEER,   false, false, true,  true),
        // Incoherent pairings grant nothing at all.
        Row(DeviceRole.PARENT, DeviceRole.PARENT, false, false, false, false),
        Row(DeviceRole.CHILD,  DeviceRole.CHILD,  false, false, false, false),
        Row(DeviceRole.PARENT, DeviceRole.PEER,   false, false, false, false),
        Row(DeviceRole.PEER,   DeviceRole.PARENT, false, false, false, false),
        Row(DeviceRole.CHILD,  DeviceRole.PEER,   false, false, false, false),
        Row(DeviceRole.PEER,   DeviceRole.CHILD,  false, false, false, false),
    )

    @Test
    fun `the permission matrix is exactly what it says`() {
        for (r in matrix) {
            val label = "${r.mine} -> ${r.theirs}"
            assertEquals("manage $label", r.manage, SyncPolicy.mayManagePeer(r.mine, r.theirs))
            assertEquals("managed $label", r.managed, SyncPolicy.mayBeManagedByPeer(r.mine, r.theirs))
            assertEquals("copies $label", r.copies, SyncPolicy.mayExchangeCopies(r.mine, r.theirs))
            assertEquals("consistent $label", r.consistent, SyncPolicy.isConsistent(r.mine, r.theirs))
        }
    }

    @Test
    fun `the matrix covers every combination of roles`() {
        assertEquals(DeviceRole.entries.size * DeviceRole.entries.size, matrix.size)
    }

    @Test
    fun `no combination lets both phones manage each other`() {
        for (a in DeviceRole.entries) for (b in DeviceRole.entries) {
            assertFalse("$a/$b", SyncPolicy.mayManagePeer(a, b) && SyncPolicy.mayManagePeer(b, a))
        }
    }

    @Test
    fun `counterparts are symmetric`() {
        for (r in DeviceRole.entries) assertEquals(r, r.counterpart.counterpart)
    }

    @Test
    fun `an unknown role name parses to nothing rather than a default`() {
        assertNull(DeviceRole.parse("ADMIN"))
        assertNull(DeviceRole.parse(null))
        assertEquals(DeviceRole.CHILD, DeviceRole.parse("CHILD"))
    }

    // ── Hex ──────────────────────────────────────────────────────────────────

    @Test
    fun `hex round-trips and is strict`() {
        val bytes = byteArrayOf(0, 1, 15, 16, 127, -128, -1)
        assertEquals("00010f107f80ff", bytes.toHex())
        assertEquals(bytes.toList(), "00010f107f80ff".hexToBytesOrNull()!!.toList())
        assertNull("odd length", "abc".hexToBytesOrNull())
        assertNull("not hex", "zz".hexToBytesOrNull())
    }

    @Test
    fun `an id is exactly 32 lowercase hex characters`() {
        assertTrue(isValidSyncId("a".repeat(32)))
        assertFalse(isValidSyncId("a".repeat(31)))
        assertFalse(isValidSyncId("A".repeat(32)))
        assertFalse(isValidSyncId("g".repeat(32)))
        assertFalse(isValidSyncId(null))
    }

    // ── Pairing codes ────────────────────────────────────────────────────────

    @Test
    fun `a generated code is eight digits`() {
        repeat(50) { assertTrue(Regex("\\d{8}").matches(PairingCodes.generate())) }
    }

    @Test
    fun `generated codes are not all alike`() {
        assertTrue(List(20) { PairingCodes.generate() }.toSet().size > 15)
    }

    @Test
    fun `leading zeros are preserved`() {
        // A code is a string of digits, not a number: "00000042" must stay eight characters.
        val rng = object : SecureRandom() { override fun nextInt(bound: Int) = 0 }
        assertEquals("00000000", PairingCodes.generate(rng))
    }

    @Test
    fun `the code is normalised from what people type`() {
        assertEquals("12345678", PairingCodes.normalize("12345678"))
        assertEquals("12345678", PairingCodes.normalize("1234 5678"))
        assertEquals("12345678", PairingCodes.normalize(" 1234-5678 "))
        assertEquals("12345678", PairingCodes.normalize("١٢٣٤٥٦٧٨"))   // Arabic-Indic digits
    }

    @Test
    fun `anything that is not eight digits is not a code`() {
        assertNull(PairingCodes.normalize("1234567"))
        assertNull(PairingCodes.normalize("123456789"))
        assertNull(PairingCodes.normalize("1234abcd"))
        assertNull(PairingCodes.normalize(""))
    }

    @Test
    fun `a code is displayed in two groups`() {
        assertEquals("1234 5678", PairingCodes.format("12345678"))
    }

    // ── A code's lifetime ────────────────────────────────────────────────────

    private class Clock(var now: Long = 1_000L) { operator fun invoke() = now }

    @Test
    fun `a fresh code is usable`() {
        assertTrue(PairingCodeSession("12345678", Clock()::invoke).isUsable)
    }

    @Test
    fun `a code expires`() {
        val clock = Clock()
        val s = PairingCodeSession("12345678", clock::invoke, ttlMillis = 1_000)
        clock.now += 1_001
        assertFalse(s.isUsable); assertTrue(s.isExpired)
    }

    @Test
    fun `a code is still good right up to its limit`() {
        val clock = Clock()
        val s = PairingCodeSession("12345678", clock::invoke, ttlMillis = 1_000)
        clock.now += 1_000
        assertTrue(s.isUsable)
    }

    @Test
    fun `three wrong guesses kill the code`() {
        val s = PairingCodeSession("12345678", Clock()::invoke, maxAttempts = 3)
        s.recordWrongCode(); s.recordWrongCode()
        assertTrue("two down, one left", s.isUsable)
        s.recordWrongCode()
        assertFalse(s.isUsable); assertEquals(0, s.attemptsLeft)
    }

    @Test
    fun `attempts never go negative`() {
        val s = PairingCodeSession("12345678", Clock()::invoke, maxAttempts = 1)
        repeat(5) { s.recordWrongCode() }
        assertEquals(0, s.attemptsLeft)
    }

    @Test
    fun `a code that has worked once never works again`() {
        val s = PairingCodeSession("12345678", Clock()::invoke)
        s.recordSuccess()
        assertFalse(s.isUsable); assertTrue(s.consumed)
    }

    @Test
    fun `remaining time counts down and stops at zero`() {
        val clock = Clock()
        val s = PairingCodeSession("12345678", clock::invoke, ttlMillis = 1_000)
        clock.now += 400
        assertEquals(600L, s.remainingMillis)
        clock.now += 10_000
        assertEquals(0L, s.remainingMillis)
    }

    // ── The child-side applier ───────────────────────────────────────────────

    private class Store : ManagedAlarmStore {
        val alarms = mutableMapOf<Long, Alarm>(); private var next = 1L
        override suspend fun findManaged(originDeviceId: String, syncId: String) =
            alarms.values.firstOrNull { it.sync.originDeviceId == originDeviceId && it.sync.syncId == syncId }
        override suspend fun countManagedFrom(originDeviceId: String) = alarms.values.count { it.sync.originDeviceId == originDeviceId }
        override suspend fun save(alarm: Alarm): Long { val id = if (alarm.id != 0L) alarm.id else next++; alarms[id] = alarm.copy(id = id); return id }
        override suspend fun delete(id: Long) { alarms.remove(id) }
    }

    private class Scheduling : AlarmScheduling {
        val armed = mutableSetOf<Long>()
        override fun schedule(alarm: Alarm) { if (alarm.isActive) armed += alarm.id else armed -= alarm.id }
        override fun cancel(id: Long) { armed -= id }
    }

    private val asChild = link('a', "אמא", DeviceRole.CHILD, DeviceRole.PARENT)
    private val asPeer = link('a', "א", DeviceRole.PEER, DeviceRole.PEER)
    private val asParent = link('c', "דני", DeviceRole.PARENT, DeviceRole.CHILD)
    private val dto = alarmOf("קום").toSyncAlarm()

    @Test
    fun `a pairing that does not make this phone a child applies nothing`() = runBlocking {
        for (peer in listOf(asPeer, asParent)) {
            val store = Store()
            val applier = RemoteAlarmApplier(store, Scheduling())
            assertEquals(AckStatus.REJECTED, applier.upsert(peer, idOf('1'), 1, dto))
            assertEquals(AckStatus.REJECTED, applier.delete(peer, idOf('1'), 1))
            assertTrue(store.alarms.isEmpty())
        }
    }

    @Test
    fun `a new alarm is stored locked, attributed, and armed`() = runBlocking {
        val store = Store(); val sched = Scheduling()
        assertEquals(AckStatus.OK, RemoteAlarmApplier(store, sched).upsert(asChild, idOf('1'), 4, dto))

        val a = store.alarms.values.single()
        assertTrue(a.sync.isRemoteManaged); assertEquals(4L, a.sync.revision)
        assertEquals(idOf('a'), a.sync.originDeviceId); assertEquals("אמא", a.sync.originDeviceName)
        assertTrue(a.id in sched.armed)
    }

    @Test
    fun `an equal revision is a replay and changes nothing`() = runBlocking {
        val store = Store(); val applier = RemoteAlarmApplier(store, Scheduling())
        applier.upsert(asChild, idOf('1'), 4, dto)
        assertEquals(AckStatus.STALE, applier.upsert(asChild, idOf('1'), 4, dto.copy(name = "אחר")))
        assertEquals("קום", store.alarms.values.single().name)
    }

    @Test
    fun `the ceiling counts per parent`() = runBlocking {
        val store = Store(); val applier = RemoteAlarmApplier(store, Scheduling(), maxManagedPerPeer = 2)
        assertEquals(AckStatus.OK, applier.upsert(asChild, idOf('1'), 1, dto))
        assertEquals(AckStatus.OK, applier.upsert(asChild, idOf('2'), 1, dto))
        assertEquals(AckStatus.BUSY, applier.upsert(asChild, idOf('3'), 1, dto))
        // Updating one that is already held is not "another alarm".
        assertEquals(AckStatus.OK, applier.upsert(asChild, idOf('2'), 2, dto))
    }

    @Test
    fun `an alarm from one parent cannot be touched by another`() = runBlocking {
        val store = Store(); val applier = RemoteAlarmApplier(store, Scheduling())
        applier.upsert(asChild, idOf('1'), 1, dto)
        val other = link('b', "זר", DeviceRole.CHILD, DeviceRole.PARENT)

        // Same sync id, different origin: it is a different alarm, not an update of the first.
        assertEquals(AckStatus.OK, applier.upsert(other, idOf('1'), 1, dto.copy(name = "של זר")))
        assertEquals(2, store.alarms.size)
        assertEquals(AckStatus.OK, applier.delete(other, idOf('1'), 1))
        assertEquals("the first parent's alarm is untouched", 1, store.alarms.size)
        assertEquals("קום", store.alarms.values.single().name)
    }

    // ── The peer-copy inbox ──────────────────────────────────────────────────

    private class Transfers : TransferStore {
        val items = linkedMapOf<String, StoredTransfer>()
        override suspend fun count() = items.size
        override suspend fun countFrom(deviceId: String) = items.values.count { it.fromDeviceId == deviceId }
        override suspend fun insert(transfer: StoredTransfer): Boolean { if (items.containsKey(transfer.transferId)) return false; items[transfer.transferId] = transfer; return true }
        override suspend fun get(transferId: String) = items[transferId]
        override suspend fun delete(transferId: String) { items.remove(transferId) }
    }

    @Test
    fun `only peers may leave a copy`() = runBlocking {
        for (peer in listOf(asChild, asParent)) {
            val inbox = TransferInbox(Transfers())
            assertEquals(AckStatus.REJECTED, inbox.receive(peer, idOf('e'), dto))
        }
    }

    @Test
    fun `a peer can only park so many copies`() = runBlocking {
        val store = Transfers(); val inbox = TransferInbox(store, maxPerPeer = 2)
        assertEquals(AckStatus.OK, inbox.receive(asPeer, idOf('1'), dto))
        assertEquals(AckStatus.OK, inbox.receive(asPeer, idOf('2'), dto))
        assertEquals(AckStatus.BUSY, inbox.receive(asPeer, idOf('3'), dto))
    }

    @Test
    fun `the inbox has an overall ceiling too`() = runBlocking {
        val inbox = TransferInbox(Transfers(), maxTotal = 2, maxPerPeer = 10)
        inbox.receive(asPeer, idOf('1'), dto); inbox.receive(asPeer, idOf('2'), dto)
        assertEquals(AckStatus.BUSY, inbox.receive(link('b', "ב", DeviceRole.PEER, DeviceRole.PEER), idOf('3'), dto))
    }

    @Test
    fun `a peer cannot swap a copy that is already waiting`() = runBlocking {
        val store = Transfers(); val inbox = TransferInbox(store)
        inbox.receive(asPeer, idOf('1'), dto)
        assertEquals(AckStatus.STALE, inbox.receive(asPeer, idOf('1'), dto.copy(name = "מוחלף")))
        assertEquals("קום", com.smartring.app.sync.protocol.decodeSyncAlarm(store.items.getValue(idOf('1')).payload).name)
    }

    @Test
    fun `accepting something that is no longer there does nothing`() = runBlocking {
        assertNull(TransferInbox(Transfers()).accept(idOf('9')) { 1L })
    }

    @Test
    fun `a stored copy that no longer validates is discarded instead of accepted`() = runBlocking {
        val store = Transfers()
        store.insert(StoredTransfer(idOf('1'), idOf('a'), "א", byteArrayOf(1, 2, 3), 0L))
        var sunk = false

        val id = TransferInbox(store).accept(idOf('1')) { sunk = true; 1L }

        assertNull(id); assertFalse("storage is not trusted either", sunk)
        assertEquals(0, store.items.size)
    }
}
