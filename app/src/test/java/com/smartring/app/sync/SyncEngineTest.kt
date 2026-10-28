package com.smartring.app.sync

import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.SecureChannel
import com.smartring.app.sync.engine.AlarmScheduling
import com.smartring.app.sync.engine.ManagedAlarmStore
import com.smartring.app.sync.engine.OutgoingItem
import com.smartring.app.sync.engine.RemoteAlarmApplier
import com.smartring.app.sync.engine.StoredTransfer
import com.smartring.app.sync.engine.SyncEngine
import com.smartring.app.sync.engine.SyncOutcome
import com.smartring.app.sync.engine.SyncPort
import com.smartring.app.sync.engine.TransferInbox
import com.smartring.app.sync.engine.TransferStore
import com.smartring.app.sync.protocol.AckKind
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.SecureMsg
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.decodeSecure
import com.smartring.app.sync.protocol.encodeSecure
import com.smartring.app.sync.protocol.toSyncAlarm
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole sync conversation between two simulated phones — the real encrypted channel, the
 * real engine on both sides, the real child-side applier — with only storage faked.
 */
class SyncEngineTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    /** A parent's view: what is waiting, and what it learned about delivery. */
    private class ParentPort(val items: MutableList<OutgoingItem> = mutableListOf()) : SyncPort {
        val delivered = mutableListOf<Pair<OutgoingItem, AckStatus>>()
        var renamed: String? = null
        override suspend fun pendingFor(peer: PeerLink) = items.toList()
        override suspend fun onDelivered(peer: PeerLink, item: OutgoingItem, status: AckStatus) { delivered += item to status }
        override suspend fun applyUpsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm) = AckStatus.REJECTED
        override suspend fun applyDelete(peer: PeerLink, syncId: String, revision: Long) = AckStatus.REJECTED
        override suspend fun receiveTransfer(peer: PeerLink, transferId: String, alarm: SyncAlarm) = AckStatus.REJECTED
        override suspend fun onPeerRenamed(peer: PeerLink, newName: String) { renamed = newName }
        override suspend fun onPeerUnpaired(peer: PeerLink) {}
    }

    private class FakeStore : ManagedAlarmStore {
        val alarms = mutableMapOf<Long, Alarm>()
        private var next = 1L
        override suspend fun findManaged(originDeviceId: String, syncId: String) =
            alarms.values.firstOrNull { it.sync.originDeviceId == originDeviceId && it.sync.syncId == syncId }
        override suspend fun countManagedFrom(originDeviceId: String) =
            alarms.values.count { it.sync.originDeviceId == originDeviceId }
        override suspend fun save(alarm: Alarm): Long {
            val id = if (alarm.id != 0L) alarm.id else next++
            alarms[id] = alarm.copy(id = id)
            return id
        }
        override suspend fun delete(id: Long) { alarms.remove(id) }
    }

    private class FakeScheduling : AlarmScheduling {
        val armed = mutableSetOf<Long>()
        override fun schedule(alarm: Alarm) { if (alarm.isActive) armed += alarm.id else armed -= alarm.id }
        override fun cancel(id: Long) { armed -= id }
    }

    /** A child's view: applies through the real [RemoteAlarmApplier]. */
    private class ChildPort(
        val store: FakeStore = FakeStore(),
        val scheduling: FakeScheduling = FakeScheduling(),
        maxManaged: Int = RemoteAlarmApplier.DEFAULT_MAX_MANAGED,
    ) : SyncPort {
        private val applier = RemoteAlarmApplier(store, scheduling, maxManaged)
        var unpaired = false
        override suspend fun pendingFor(peer: PeerLink) = emptyList<OutgoingItem>()
        override suspend fun onDelivered(peer: PeerLink, item: OutgoingItem, status: AckStatus) {}
        override suspend fun applyUpsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm) =
            applier.upsert(peer, syncId, revision, alarm)
        override suspend fun applyDelete(peer: PeerLink, syncId: String, revision: Long) =
            applier.delete(peer, syncId, revision)
        override suspend fun receiveTransfer(peer: PeerLink, transferId: String, alarm: SyncAlarm) = AckStatus.REJECTED
        override suspend fun onPeerRenamed(peer: PeerLink, newName: String) {}
        override suspend fun onPeerUnpaired(peer: PeerLink) { unpaired = true }
    }

    private class PeerPort(val inbox: TransferInbox, val outgoing: MutableList<OutgoingItem> = mutableListOf()) : SyncPort {
        val delivered = mutableListOf<Pair<OutgoingItem, AckStatus>>()
        override suspend fun pendingFor(peer: PeerLink) = outgoing.toList()
        override suspend fun onDelivered(peer: PeerLink, item: OutgoingItem, status: AckStatus) { delivered += item to status }
        override suspend fun applyUpsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm) = AckStatus.REJECTED
        override suspend fun applyDelete(peer: PeerLink, syncId: String, revision: Long) = AckStatus.REJECTED
        override suspend fun receiveTransfer(peer: PeerLink, transferId: String, alarm: SyncAlarm) =
            inbox.receive(peer, transferId, alarm)
        override suspend fun onPeerRenamed(peer: PeerLink, newName: String) {}
        override suspend fun onPeerUnpaired(peer: PeerLink) {}
    }

    private class FakeTransferStore : TransferStore {
        val items = linkedMapOf<String, StoredTransfer>()
        override suspend fun count() = items.size
        override suspend fun countFrom(deviceId: String) = items.values.count { it.fromDeviceId == deviceId }
        override suspend fun insert(transfer: StoredTransfer): Boolean {
            if (items.containsKey(transfer.transferId)) return false
            items[transfer.transferId] = transfer; return true
        }
        override suspend fun get(transferId: String) = items[transferId]
        override suspend fun delete(transferId: String) { items.remove(transferId) }
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    private val parentId = identity("אמא", 'a')
    private val childId = identity("דני", 'c')
    private val key = Crypto.randomBytes(32)

    private fun secureChannels(): kotlin.Pair<SecureChannel, SecureChannel> {
        val (a, b) = memoryChannels()
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        return SecureChannel(a, k1, k2, 1, 2) to SecureChannel(b, k2, k1, 2, 1)
    }

    private val parentLink = link('c', "דני", DeviceRole.PARENT, DeviceRole.CHILD)   // what the parent has recorded
    private val childLink = link('a', "אמא", DeviceRole.CHILD, DeviceRole.PARENT)    // what the child has recorded

    private class Outcomes(val initiator: SyncOutcome, val responder: SyncOutcome)

    private fun converse(
        initiatorIdentity: LocalIdentity, initiatorPort: SyncPort, initiatorLink: PeerLink,
        responderIdentity: LocalIdentity, responderPort: SyncPort, responderLink: PeerLink,
        unpair: Boolean = false,
    ): Outcomes = runBlocking {
        val (ci, cr) = secureChannels()
        val i = async { SyncEngine(initiatorIdentity, initiatorPort, stepTimeoutMs = 5_000).runAsInitiator(ci, initiatorLink, unpair) }
        val r = async { SyncEngine(responderIdentity, responderPort, stepTimeoutMs = 5_000).runAsResponder(cr, responderLink) }
        Outcomes(i.await(), r.await())
    }

    private fun upsert(syncChar: Char, revision: Long, alarm: Alarm) =
        OutgoingItem.Upsert(idOf(syncChar), revision, alarm.toSyncAlarm())

    // ── A parent sets an alarm on a child ────────────────────────────────────

    @Test
    fun `an alarm a parent set arrives on the child, locked and armed`() {
        val parent = ParentPort(mutableListOf(upsert('1', 1, alarmOf("קום", 6, 45))))
        val child = ChildPort()

        val r = converse(parentId, parent, parentLink, childId, child, childLink)

        assertTrue(r.initiator is SyncOutcome.Completed)
        assertTrue(r.responder is SyncOutcome.Completed)
        val stored = child.store.alarms.values.single()
        assertEquals("קום", stored.name)
        assertEquals(6, stored.hour); assertEquals(45, stored.minute)
        assertTrue("a parent's alarm is locked against the child's user", stored.isLockedForUser)
        assertEquals(parentId.deviceId, stored.sync.originDeviceId)
        assertEquals("אמא", stored.sync.originDeviceName)
        assertEquals(idOf('1'), stored.sync.syncId)
        assertTrue("and it actually rings", stored.id in child.scheduling.armed)
    }

    @Test
    fun `the parent learns the alarm was delivered`() {
        val item = upsert('1', 1, alarmOf())
        val parent = ParentPort(mutableListOf(item))

        converse(parentId, parent, parentLink, childId, ChildPort(), childLink)

        assertEquals(1, parent.delivered.size)
        assertEquals(AckStatus.OK, parent.delivered.single().second)
        assertTrue(parent.delivered.single().first === item)
    }

    @Test
    fun `an update replaces the alarm in place and keeps its local id`() {
        val child = ChildPort()
        converse(parentId, ParentPort(mutableListOf(upsert('1', 1, alarmOf("ישן", 6, 0)))), parentLink, childId, child, childLink)
        val idBefore = child.store.alarms.values.single().id

        converse(parentId, ParentPort(mutableListOf(upsert('1', 2, alarmOf("חדש", 8, 15)))), parentLink, childId, child, childLink)

        val after = child.store.alarms.values.single()
        assertEquals("חדש", after.name); assertEquals(8, after.hour)
        assertEquals("the scheduler keys its registration off the id", idBefore, after.id)
        assertEquals(2L, after.sync.revision)
    }

    @Test
    fun `a replayed older revision never overwrites a newer one`() {
        val child = ChildPort()
        converse(parentId, ParentPort(mutableListOf(upsert('1', 5, alarmOf("חדש")))), parentLink, childId, child, childLink)

        val parent = ParentPort(mutableListOf(upsert('1', 3, alarmOf("ישן מאוד"))))
        converse(parentId, parent, parentLink, childId, child, childLink)

        assertEquals("חדש", child.store.alarms.values.single().name)
        assertEquals(AckStatus.STALE, parent.delivered.single().second)
    }

    @Test
    fun `an alarm the parent switched off stops ringing on the child`() {
        val child = ChildPort()
        converse(parentId, ParentPort(mutableListOf(upsert('1', 1, alarmOf()))), parentLink, childId, child, childLink)
        assertEquals(1, child.scheduling.armed.size)

        converse(parentId, ParentPort(mutableListOf(upsert('1', 2, alarmOf().copy(isEnabled = false)))), parentLink, childId, child, childLink)

        assertTrue(child.scheduling.armed.isEmpty())
        assertFalse(child.store.alarms.values.single().isEnabled)
    }

    @Test
    fun `a delete removes the alarm and its schedule`() {
        val child = ChildPort()
        converse(parentId, ParentPort(mutableListOf(upsert('1', 1, alarmOf()))), parentLink, childId, child, childLink)

        val parent = ParentPort(mutableListOf(OutgoingItem.Delete(idOf('1'), 2, tombstoneId = 9)))
        converse(parentId, parent, parentLink, childId, child, childLink)

        assertTrue(child.store.alarms.isEmpty())
        assertTrue(child.scheduling.armed.isEmpty())
        assertEquals(AckStatus.OK, parent.delivered.single().second)
    }

    @Test
    fun `deleting something that is already gone succeeds`() {
        val parent = ParentPort(mutableListOf(OutgoingItem.Delete(idOf('9'), 4, tombstoneId = 1)))
        converse(parentId, parent, parentLink, childId, ChildPort(), childLink)
        assertEquals(AckStatus.OK, parent.delivered.single().second)
    }

    @Test
    fun `a late delete for an older version does not remove a newer alarm`() {
        val child = ChildPort()
        converse(parentId, ParentPort(mutableListOf(upsert('1', 7, alarmOf()))), parentLink, childId, child, childLink)

        val parent = ParentPort(mutableListOf(OutgoingItem.Delete(idOf('1'), 3, tombstoneId = 1)))
        converse(parentId, parent, parentLink, childId, child, childLink)

        assertEquals(1, child.store.alarms.size)
        assertEquals(AckStatus.STALE, parent.delivered.single().second)
    }

    @Test
    fun `several items are delivered in one session`() {
        val parent = ParentPort(mutableListOf(
            upsert('1', 1, alarmOf("א")), upsert('2', 1, alarmOf("ב")), upsert('3', 1, alarmOf("ג")),
        ))
        val child = ChildPort()

        val r = converse(parentId, parent, parentLink, childId, child, childLink)

        assertEquals(3, child.store.alarms.size)
        assertEquals(3, (r.initiator as SyncOutcome.Completed).stats.sent)
        assertEquals(3, (r.responder as SyncOutcome.Completed).stats.received)
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `an alarm that is not valid is refused and not stored`() {
        val bad = OutgoingItem.Upsert(idOf('1'), 1, alarmOf().toSyncAlarm().copy(hour = 99))
        val parent = ParentPort(mutableListOf(bad))
        val child = ChildPort()

        converse(parentId, parent, parentLink, childId, child, childLink)

        assertTrue(child.store.alarms.isEmpty())
        assertEquals(AckStatus.INVALID, parent.delivered.single().second)
    }

    @Test
    fun `out-of-range tunables are repaired before they reach storage`() {
        val wild = alarmOf().toSyncAlarm().copy(ringDurationSeconds = 100_000, crescendoStepSeconds = 0)
        val child = ChildPort()

        converse(parentId, ParentPort(mutableListOf(OutgoingItem.Upsert(idOf('1'), 1, wild))), parentLink, childId, child, childLink)

        val stored = child.store.alarms.values.single()
        assertEquals(600, stored.ringDurationSeconds)
        assertEquals(5, stored.crescendoStepSeconds)
    }

    @Test
    fun `a revision of zero is refused`() {
        val parent = ParentPort(mutableListOf(upsert('1', 0, alarmOf())))
        converse(parentId, parent, parentLink, childId, ChildPort(), childLink)
        assertEquals(AckStatus.INVALID, parent.delivered.single().second)
    }

    @Test
    fun `a parent cannot fill the child with alarms`() {
        val parent = ParentPort(MutableList(5) { upsert('a' + it, 1, alarmOf("x$it")) })
        val child = ChildPort(maxManaged = 3)

        converse(parentId, parent, parentLink, childId, child, childLink)

        assertEquals(3, child.store.alarms.size)
        assertEquals(2, parent.delivered.count { it.second == AckStatus.BUSY })
    }

    // ── The permission matrix, enforced on both sides ────────────────────────

    @Test
    fun `a phone that is not a parent does not send management messages`() {
        // Both sides are peers, but one has an Upsert queued — as a bug or a tampered database might.
        val peerA = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))
        val peerB = ChildPort()
        val a = link('b', "ב", DeviceRole.PEER, DeviceRole.PEER)
        val b = link('a', "א", DeviceRole.PEER, DeviceRole.PEER)

        converse(identity("א", 'a'), peerA, a, identity("ב", 'b'), peerB, b)

        assertTrue("it was never sent", peerA.delivered.isEmpty())
        assertTrue(peerB.store.alarms.isEmpty())
    }

    @Test
    fun `a phone that is not a child refuses to apply management messages`() {
        // The sender believes it is a parent; the receiver's pairing says they are peers. Roles are
        // never taken from the message, so the receiver's own record decides.
        val sender = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))
        val receiver = ChildPort()
        val sendersView = link('b', "ב", DeviceRole.PARENT, DeviceRole.CHILD)
        val receiversView = link('a', "א", DeviceRole.PEER, DeviceRole.PEER)

        // The hello check catches the inconsistent roles before any alarm moves.
        val r = converse(identity("א", 'a'), sender, sendersView, identity("ב", 'b'), receiver, receiversView)

        assertTrue(receiver.store.alarms.isEmpty())
        assertTrue(r.responder is SyncOutcome.Failed)
    }

    @Test
    fun `a child cannot push an alarm to its parent`() {
        val child = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))      // queued on the child
        val parent = ChildPort()
        val childsView = link('a', "אמא", DeviceRole.CHILD, DeviceRole.PARENT)
        val parentsView = link('c', "דני", DeviceRole.PARENT, DeviceRole.CHILD)

        converse(childId, child, childsView, parentId, parent, parentsView)

        assertTrue(child.delivered.isEmpty())
        assertTrue(parent.store.alarms.isEmpty())
    }

    // ── Identity checks ──────────────────────────────────────────────────────

    @Test
    fun `a peer that is not who the pairing says is refused`() {
        val child = ChildPort()
        // The parent's pairing records the child as 'f', but the phone on the other end is 'c'.
        val wrongLink = link('f', "דני", DeviceRole.PARENT, DeviceRole.CHILD)
        val parent = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))

        val r = converse(parentId, parent, wrongLink, childId, child, childLink)

        assertTrue(r.initiator is SyncOutcome.Failed)
        assertTrue(child.store.alarms.isEmpty())
    }

    @Test
    fun `a peer claiming a different role than the pairing recorded is refused`() {
        val parent = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))
        val child = ChildPort()
        // The parent believes the child is a PEER; the child introduces itself as CHILD.
        val parentThinksPeers = link('c', "דני", DeviceRole.PARENT, DeviceRole.PEER)

        val r = converse(parentId, parent, parentThinksPeers, childId, child, childLink)

        assertTrue(r.initiator is SyncOutcome.Failed)
        assertTrue(child.store.alarms.isEmpty())
    }

    @Test
    fun `a renamed peer is noticed`() {
        val parent = ParentPort()
        val renamed = identity("דניאל", 'c')

        converse(parentId, parent, parentLink, renamed, ChildPort(), childLink)

        assertEquals("דניאל", parent.renamed)
    }

    // ── Peers exchanging copies ──────────────────────────────────────────────

    private val peerA = link('b', "ב", DeviceRole.PEER, DeviceRole.PEER)
    private val peerB = link('a', "א", DeviceRole.PEER, DeviceRole.PEER)

    @Test
    fun `a copy sent by a peer waits for approval and is not an alarm yet`() {
        val sender = PeerPort(TransferInbox(FakeTransferStore()), mutableListOf(
            OutgoingItem.Transfer(idOf('e'), alarmOf("מתנה").toSyncAlarm())))
        val store = FakeTransferStore()
        val receiver = PeerPort(TransferInbox(store))

        converse(identity("א", 'a'), sender, peerA, identity("ב", 'b'), receiver, peerB)

        assertEquals("it is parked, visible, awaiting a yes", 1, store.items.size)
        assertEquals(AckStatus.OK, sender.delivered.single().second)
    }

    @Test
    fun `an accepted copy is an ordinary alarm that remembers who sent it`() {
        val store = FakeTransferStore()
        val inbox = TransferInbox(store)
        val sender = PeerPort(TransferInbox(FakeTransferStore()), mutableListOf(
            OutgoingItem.Transfer(idOf('e'), alarmOf("מתנה", 5, 5).toSyncAlarm())))
        converse(identity("א", 'a'), sender, peerA, identity("ב", 'b'), PeerPort(inbox), peerB)

        var saved: Alarm? = null
        val id = runBlocking { inbox.accept(idOf('e')) { saved = it; 42L } }

        assertEquals(42L, id)
        assertEquals("מתנה", saved!!.name)
        assertFalse("it is the user's own alarm now", saved!!.isLockedForUser)
        assertEquals("but it says where it came from", "א", saved!!.sync.originDeviceName)
        assertNull("and carries no link back", saved!!.sync.syncId)
        assertEquals("and is gone from the waiting list", 0, store.items.size)
    }

    @Test
    fun `a declined copy is simply removed`() {
        val store = FakeTransferStore()
        val inbox = TransferInbox(store)
        converse(identity("א", 'a'), PeerPort(TransferInbox(FakeTransferStore()), mutableListOf(
            OutgoingItem.Transfer(idOf('e'), alarmOf().toSyncAlarm()))), peerA,
            identity("ב", 'b'), PeerPort(inbox), peerB)

        runBlocking { inbox.decline(idOf('e')) }

        assertEquals(0, store.items.size)
    }

    @Test
    fun `a parent and child cannot exchange copies`() {
        val store = FakeTransferStore()
        converse(parentId, PeerPort(TransferInbox(FakeTransferStore()), mutableListOf(
            OutgoingItem.Transfer(idOf('e'), alarmOf().toSyncAlarm()))), parentLink,
            childId, PeerPort(TransferInbox(store)), childLink)

        assertTrue(store.items.isEmpty())
    }

    // ── Unpairing ────────────────────────────────────────────────────────────

    @Test
    fun `telling the other phone you removed the pairing reaches it`() {
        val child = ChildPort()
        val r = converse(parentId, ParentPort(), parentLink, childId, child, childLink, unpair = true)

        assertEquals(SyncOutcome.UnpairSent, r.initiator)
        assertEquals(SyncOutcome.PeerUnpaired, r.responder)
        assertTrue(child.unpaired)
    }

    // ── Abuse ────────────────────────────────────────────────────────────────

    @Test
    fun `a flood is cut off at the per-turn limit`() = runBlocking {
        val (ci, cr) = secureChannels()
        val child = ChildPort()
        val responder = async { SyncEngine(childId, child, stepTimeoutMs = 5_000, maxItemsPerTurn = 3).runAsResponder(cr, childLink) }

        // A hostile "parent" that skips the engine and just pours Upserts in.
        ci.send(encodeSecure(SecureMsg.Hello(childIdBytes('a'), "אמא", DeviceRole.PARENT)))
        decodeSecure(ci.receive())
        val alarm = alarmOf().toSyncAlarm()
        repeat(10) { i ->
            val idBytes = ByteArray(16) { (i + 1).toByte() }
            ci.send(encodeSecure(SecureMsg.Upsert(idBytes, 1L, alarm)))
            runCatching { ci.receive() }
        }

        assertTrue(responder.await() is SyncOutcome.Failed)
        assertTrue("it stopped well short of ten", child.store.alarms.size <= 4)
    }

    private fun childIdBytes(c: Char) = idOf(c).hex()

    @Test
    fun `an acknowledgement for the wrong item ends the session`() = runBlocking {
        val (ci, cr) = secureChannels()
        val parent = ParentPort(mutableListOf(upsert('1', 1, alarmOf())))
        val initiator = async { SyncEngine(parentId, parent, stepTimeoutMs = 5_000).runAsInitiator(ci, parentLink) }

        // A hostile "child": answer the hello, then acknowledge something that was never sent.
        decodeSecure(cr.receive())
        cr.send(encodeSecure(SecureMsg.Hello(idOf('c').hex(), "דני", DeviceRole.CHILD)))
        decodeSecure(cr.receive())   // the Upsert
        cr.send(encodeSecure(SecureMsg.Ack(AckKind.UPSERT, idOf('9').hex(), 1L, AckStatus.OK)))

        val outcome = initiator.await()
        assertTrue(outcome is SyncOutcome.Failed)
        assertTrue("and nothing is recorded as delivered", parent.delivered.isEmpty())
    }

    @Test
    fun `an unexpected message in the middle of a turn ends the session`() = runBlocking {
        val (ci, cr) = secureChannels()
        val child = ChildPort()
        val responder = async { SyncEngine(childId, child, stepTimeoutMs = 5_000).runAsResponder(cr, childLink) }
        ci.send(encodeSecure(SecureMsg.Hello(idOf('a').hex(), "אמא", DeviceRole.PARENT)))
        decodeSecure(ci.receive())
        ci.send(encodeSecure(SecureMsg.PairFinish(true)))   // not part of a sync turn

        assertTrue(responder.await() is SyncOutcome.Failed)
    }

    @Test
    fun `a peer that vanishes mid-session fails cleanly`() = runBlocking {
        val (ci, cr) = secureChannels()
        val responder = async { SyncEngine(childId, ChildPort(), stepTimeoutMs = 5_000).runAsResponder(cr, childLink) }
        ci.send(encodeSecure(SecureMsg.Hello(idOf('a').hex(), "אמא", DeviceRole.PARENT)))
        decodeSecure(ci.receive())
        ci.close()

        assertTrue(responder.await() is SyncOutcome.Failed)
    }

    @Test
    fun `nothing waiting is a successful empty session`() {
        val r = converse(parentId, ParentPort(), parentLink, childId, ChildPort(), childLink)
        assertEquals(0, (r.initiator as SyncOutcome.Completed).stats.sent)
        assertNotNull(r.responder)
    }
}
