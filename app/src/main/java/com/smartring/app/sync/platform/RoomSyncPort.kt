package com.smartring.app.sync.platform

import com.smartring.app.data.db.OutgoingTransferEntity
import com.smartring.app.data.db.PendingTransferEntity
import com.smartring.app.data.db.SyncDao
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.data.repository.toDomain
import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.PeerLink
import com.smartring.app.sync.engine.AlarmScheduling
import com.smartring.app.sync.engine.ManagedAlarmStore
import com.smartring.app.sync.engine.OutgoingItem
import com.smartring.app.sync.engine.RemoteAlarmApplier
import com.smartring.app.sync.engine.StoredTransfer
import com.smartring.app.sync.engine.SyncPort
import com.smartring.app.sync.engine.TransferInbox
import com.smartring.app.sync.engine.TransferStore
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.decodeSyncAlarm
import com.smartring.app.sync.protocol.encodeSyncAlarm
import com.smartring.app.sync.protocol.toSyncAlarm
import com.smartring.app.sync.protocol.validated
import com.smartring.app.sync.toHex
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The engine's view of the app: what is waiting to be sent, and where received things land.
 *
 * Deliberately thin. Every *decision* — who may do what, which revision wins, what is a valid
 * alarm — lives in the tested engine and applier. What is here is only the translation to Room
 * and the scheduler, so the part a JVM test cannot exercise is the part with the least in it.
 */
@Singleton
class RoomSyncPort @Inject constructor(
    private val syncDao: SyncDao,
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val pairedDevices: PairedDeviceRepository,
    private val appLogger: AppLogger,
) : SyncPort {

    private val applier = RemoteAlarmApplier(
        store = object : ManagedAlarmStore {
            override suspend fun findManaged(originDeviceId: String, syncId: String) =
                syncDao.getReceivedBySyncId(originDeviceId, syncId)?.toDomain()
            override suspend fun countManagedFrom(originDeviceId: String) = syncDao.countReceivedFrom(originDeviceId)
            override suspend fun save(alarm: Alarm) = alarms.saveRemoteAlarm(alarm)
            override suspend fun delete(id: Long) = alarms.deleteRemoteAlarm(id)
        },
        scheduling = object : AlarmScheduling {
            override fun schedule(alarm: Alarm) = scheduler.schedule(alarm)
            override fun cancel(id: Long) = scheduler.cancel(id)
        },
    )

    private val inbox = TransferInbox(
        object : TransferStore {
            override suspend fun count() = syncDao.countPendingTransfers()
            override suspend fun countFrom(deviceId: String) = syncDao.countPendingTransfersFrom(deviceId)
            override suspend fun insert(transfer: StoredTransfer) = syncDao.insertPendingTransfer(
                PendingTransferEntity(
                    transfer.transferId, transfer.fromDeviceId, transfer.fromDeviceName,
                    transfer.payload, transfer.receivedAt,
                )
            ) != -1L
            override suspend fun get(transferId: String) = syncDao.getPendingTransfer(transferId)?.let {
                StoredTransfer(it.transferId, it.fromDeviceId, it.fromDeviceName, it.payload, it.receivedAt)
            }
            override suspend fun delete(transferId: String) = syncDao.deletePendingTransfer(transferId)
        }
    )

    // ── Outgoing ──────────────────────────────────────────────────────────

    override suspend fun pendingFor(peer: PeerLink): List<OutgoingItem> {
        val items = mutableListOf<OutgoingItem>()

        for (tombstone in syncDao.getTombstones(peer.deviceId)) {
            // A delete whose alarm has since come back (an undo, or an edit that raced it) is stale:
            // sending it would remove something the user just restored.
            if (syncDao.getManagedBySyncId(peer.deviceId, tombstone.syncId) != null) {
                syncDao.deleteTombstone(tombstone.id)
                continue
            }
            items += OutgoingItem.Delete(tombstone.syncId, tombstone.revision, tombstone.id)
        }

        for (row in syncDao.getAwaitingDelivery(peer.deviceId)) {
            val alarm = row.toDomain()
            val syncId = alarm.sync.syncId ?: continue
            // Validated on the way OUT as well: the editor has no length limit on a name, and an
            // over-long one would otherwise fail to encode — taking the whole session, and every
            // other alarm waiting behind it, down with it.
            val dto = alarm.toSyncAlarm().validated()
            if (dto == null) {
                appLogger.log("Sync", "\"${alarm.name}\" (#${alarm.id}) אינו תקין להעברה — לא נשלח")
                continue
            }
            items += OutgoingItem.Upsert(syncId, alarm.sync.revision, dto)
        }

        for (transfer in syncDao.getOutgoingTransfers(peer.deviceId)) {
            val dto = try { decodeSyncAlarm(transfer.payload) } catch (e: ProtocolException) { null }
            if (dto == null) syncDao.deleteOutgoingTransfer(transfer.transferId)
            else items += OutgoingItem.Transfer(transfer.transferId, dto)
        }
        return items
    }

    override suspend fun onDelivered(peer: PeerLink, item: OutgoingItem, status: AckStatus) {
        val delivered = status == AckStatus.OK || status == AckStatus.STALE
        // A refusal that retrying cannot change is dropped for deletes and copies — an eternal
        // retry of something the other phone will never accept helps nobody. A refused *alarm* stays
        // awaiting delivery, because the person can fix it (edit it) and it should then go through.
        val permanent = status == AckStatus.REJECTED || status == AckStatus.INVALID
        when (item) {
            is OutgoingItem.Upsert -> if (delivered) syncDao.markDelivered(peer.deviceId, item.syncId, item.revision)
            is OutgoingItem.Delete -> if (delivered || permanent) syncDao.deleteTombstone(item.tombstoneId)
            is OutgoingItem.Transfer -> if (delivered || permanent) syncDao.deleteOutgoingTransfer(item.transferId)
        }
        if (!delivered) {
            val why = when (status) {
                AckStatus.BUSY -> "המכשיר השני מלא — ינסה שוב"
                AckStatus.REJECTED -> "המכשיר השני סירב"
                AckStatus.INVALID -> "המכשיר השני סירב: שעמור לא תקין"
                else -> "לא נמסר"
            }
            pairedDevices.recordSync(peer.deviceId, why)
        }
    }

    /**
     * Queues a copy of [alarm] for a peer, to go out the next time the two connect.
     * @return false if the alarm cannot be sent at all (it does not validate).
     */
    suspend fun queueCopy(alarm: Alarm, toDeviceId: String): Boolean {
        val dto = alarm.toSyncAlarm().validated() ?: return false
        syncDao.insertOutgoingTransfer(
            OutgoingTransferEntity(
                transferId = Crypto.randomBytes(16).toHex(),
                toDeviceId = toDeviceId,
                payload = encodeSyncAlarm(dto),
                createdAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    // ── Incoming ──────────────────────────────────────────────────────────

    override suspend fun applyUpsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm): AckStatus =
        applier.upsert(peer, syncId, revision, alarm).also {
            appLogger.log("Sync", "שעמור התקבל מ-\"${peer.name}\": ${alarm.name} (גרסה $revision) — $it")
        }

    override suspend fun applyDelete(peer: PeerLink, syncId: String, revision: Long): AckStatus =
        applier.delete(peer, syncId, revision).also {
            appLogger.log("Sync", "בקשת מחיקה התקבלה מ-\"${peer.name}\" — $it")
        }

    override suspend fun receiveTransfer(peer: PeerLink, transferId: String, alarm: SyncAlarm): AckStatus =
        inbox.receive(peer, transferId, alarm).also {
            appLogger.log("Sync", "עותק שעמור התקבל מ-\"${peer.name}\" וממתין לאישור — $it")
        }

    /** A copy handed over by NFC: nobody authenticated it, so it is held for approval and labelled as such. */
    suspend fun receiveUnverified(senderName: String, alarm: SyncAlarm): AckStatus {
        val dto = alarm.validated() ?: return AckStatus.INVALID
        return inbox.receiveUnverified(senderName, Crypto.randomBytes(16).toHex(), dto)
    }

    override suspend fun onPeerRenamed(peer: PeerLink, newName: String) {
        pairedDevices.rename(peer.deviceId, newName)
        appLogger.log("Sync", "\"${peer.name}\" שינה את שמו ל-\"$newName\"")
    }

    override suspend fun onPeerUnpaired(peer: PeerLink) {
        pairedDevices.unpair(peer.deviceId)
        appLogger.log("Sync", "\"${peer.name}\" ביטל את הצימוד")
    }

    // ── What the user does with copies that are waiting ───────────────────

    /** Turns a waiting copy into one of this user's own alarms. Null if it is gone or invalid. */
    suspend fun acceptTransfer(transferId: String): Long? =
        inbox.accept(transferId) { alarm ->
            val id = alarms.saveAlarm(alarm)
            scheduler.schedule(alarm.copy(id = id))
            id
        }

    suspend fun declineTransfer(transferId: String) = inbox.decline(transferId)
}
