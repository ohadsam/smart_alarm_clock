package com.smartring.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Everything the multi-device feature stores, plus the handful of `alarms` queries that
 * exist only for it.
 *
 * Kept apart from [AlarmDao] so the alarm DAO's contract — "alarms that ring on this phone"
 * — stays legible: several of its queries now filter out rows this phone only holds on a
 * child's behalf, and this is where those rows are reached on purpose.
 */
@Dao
interface SyncDao {

    // ── Paired devices ────────────────────────────────────────────

    @Query("SELECT * FROM paired_devices ORDER BY createdAt")
    fun observePairedDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices ORDER BY createdAt")
    suspend fun getPairedDevices(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :id")
    suspend fun getPairedDevice(id: String): PairedDeviceEntity?

    // REPLACE is safe here and nowhere near the alarms table: nothing holds a foreign key
    // to paired_devices, so replacing a row cannot cascade into anything.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairedDevice(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET name = :name WHERE deviceId = :id")
    suspend fun renamePairedDevice(id: String, name: String)

    @Query("UPDATE paired_devices SET isEnabled = :enabled WHERE deviceId = :id")
    suspend fun setPairedDeviceEnabled(id: String, enabled: Boolean)

    @Query("UPDATE paired_devices SET lastSyncAt = COALESCE(:at, lastSyncAt), lastError = :error WHERE deviceId = :id")
    suspend fun recordSyncResult(id: String, at: Long?, error: String?)

    @Query("UPDATE paired_devices SET bluetoothAddress = :address WHERE deviceId = :id")
    suspend fun setBluetoothAddress(id: String, address: String?)

    // ── Alarms, on the sync axis ──────────────────────────────────

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmEntity(id: Long): AlarmEntity?

    /** Unconditional row delete. Callers decide whether the delete is allowed. */
    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmRow(id: Long)

    /** Changes this phone made for [deviceId] that it has not heard back about. */
    @Transaction
    @Query("SELECT * FROM alarms WHERE managedForDeviceId = :deviceId AND syncRevision > syncedRevision ORDER BY id")
    suspend fun getAwaitingDelivery(deviceId: String): List<AlarmWithDetails>

    @Transaction
    @Query("SELECT * FROM alarms WHERE managedForDeviceId = :deviceId AND syncId = :syncId LIMIT 1")
    suspend fun getManagedBySyncId(deviceId: String, syncId: String): AlarmWithDetails?

    /** The copy of a parent's alarm held here, found by the *parent's* identity for it. */
    @Transaction
    @Query("SELECT * FROM alarms WHERE originDeviceId = :originDeviceId AND syncId = :syncId AND isRemoteManaged = 1 LIMIT 1")
    suspend fun getReceivedBySyncId(originDeviceId: String, syncId: String): AlarmWithDetails?

    @Query("UPDATE alarms SET syncedRevision = :revision WHERE managedForDeviceId = :deviceId AND syncId = :syncId AND syncedRevision < :revision")
    suspend fun markDelivered(deviceId: String, syncId: String, revision: Long)

    /**
     * Changes whenever anything this phone has to tell a child changes: an alarm kept for one is
     * added, edited (its revision moves) or removed. The coordinator watches it to know when to sync.
     */
    @Query("SELECT COALESCE(SUM(syncRevision), 0) + COUNT(*) * 1000003 FROM alarms WHERE managedForDeviceId IS NOT NULL")
    fun observeManagedFingerprint(): Flow<Long>

    @Query("SELECT COUNT(*) FROM sync_tombstones")
    fun observeTombstoneCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outgoing_transfers")
    fun observeOutgoingTransferCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM alarms WHERE managedForDeviceId = :deviceId")
    suspend fun countManagedFor(deviceId: String): Int

    @Query("SELECT COUNT(*) FROM alarms WHERE originDeviceId = :originDeviceId AND isRemoteManaged = 1")
    suspend fun countReceivedFrom(originDeviceId: String): Int

    @Query("UPDATE alarms SET isRemoteManaged = 0 WHERE originDeviceId = :originDeviceId")
    suspend fun releaseReceivedFrom(originDeviceId: String)

    @Query("DELETE FROM alarms WHERE managedForDeviceId = :deviceId")
    suspend fun deleteManagedFor(deviceId: String)

    // ── Tombstones ────────────────────────────────────────────────

    @Insert
    suspend fun insertTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE deviceId = :deviceId ORDER BY id")
    suspend fun getTombstones(deviceId: String): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE id = :id")
    suspend fun deleteTombstone(id: Long)

    @Query("DELETE FROM sync_tombstones WHERE deviceId = :deviceId AND syncId = :syncId")
    suspend fun clearTombstonesFor(deviceId: String, syncId: String)

    @Query("DELETE FROM sync_tombstones WHERE deviceId = :deviceId")
    suspend fun deleteTombstonesFor(deviceId: String)

    // ── Peer transfers ────────────────────────────────────────────

    @Query("SELECT * FROM pending_transfers ORDER BY receivedAt")
    fun observePendingTransfers(): Flow<List<PendingTransferEntity>>

    @Query("SELECT COUNT(*) FROM pending_transfers")
    suspend fun countPendingTransfers(): Int

    @Query("SELECT COUNT(*) FROM pending_transfers WHERE fromDeviceId = :deviceId")
    suspend fun countPendingTransfersFrom(deviceId: String): Int

    @Query("SELECT * FROM pending_transfers WHERE transferId = :id")
    suspend fun getPendingTransfer(id: String): PendingTransferEntity?

    // IGNORE, not REPLACE: a transfer id is chosen by the sender, so a peer that re-sends
    // the same id must not be able to overwrite what the user is about to read.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingTransfer(transfer: PendingTransferEntity): Long

    @Query("DELETE FROM pending_transfers WHERE transferId = :id")
    suspend fun deletePendingTransfer(id: String)

    @Query("DELETE FROM pending_transfers WHERE fromDeviceId = :deviceId")
    suspend fun deletePendingTransfersFrom(deviceId: String)

    @Query("SELECT * FROM outgoing_transfers WHERE toDeviceId = :deviceId ORDER BY createdAt")
    suspend fun getOutgoingTransfers(deviceId: String): List<OutgoingTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutgoingTransfer(transfer: OutgoingTransferEntity)

    @Query("DELETE FROM outgoing_transfers WHERE transferId = :id")
    suspend fun deleteOutgoingTransfer(id: String)

    @Query("DELETE FROM outgoing_transfers WHERE toDeviceId = :deviceId")
    suspend fun deleteOutgoingTransfersTo(deviceId: String)

    // ── Compound operations that must be atomic ───────────────────

    /**
     * A user's delete. Refuses a locked alarm, and for a record kept on a child's behalf
     * writes the tombstone **in the same transaction** as the row delete.
     *
     * The order matters and the transaction is what makes it safe: with the tombstone in a
     * separate store, a crash between the two writes leaves either a child that still holds
     * an alarm nobody can remove (delete first) or a live alarm with a pending delete
     * (tombstone first). Here both happen or neither does.
     *
     * @return true if a row was deleted.
     */
    @Transaction
    suspend fun deleteAlarmForUser(id: Long): Boolean {
        val row = getAlarmEntity(id) ?: return false
        if (row.isRemoteManaged) return false
        val target = row.managedForDeviceId
        val syncId = row.syncId
        if (target != null && syncId != null) {
            insertTombstone(
                SyncTombstoneEntity(deviceId = target, syncId = syncId, revision = row.syncRevision + 1)
            )
        }
        deleteAlarmRow(id)
        return true
    }

    /**
     * Removes a pairing and everything that only made sense because of it.
     *
     *  - Alarms a parent set **for this phone** stay, as ordinary alarms: they were rung for
     *    and relied on, and silently deleting someone's wake-up alarm because a link
     *    dropped is the worse failure. They are unlocked, and keep their origin note.
     *  - Records this phone kept **for a child** are deleted. Left behind they would either
     *    sit in the list pretending to reach a phone they no longer can, or — if "kept for"
     *    were cleared — start ringing on *this* phone, which nobody asked for.
     *  - Queued deliveries to and from that device are dropped.
     */
    @Transaction
    suspend fun unpair(deviceId: String) {
        releaseReceivedFrom(deviceId)
        deleteManagedFor(deviceId)
        deleteTombstonesFor(deviceId)
        deleteOutgoingTransfersTo(deviceId)
        deletePendingTransfersFrom(deviceId)
        deletePairedDeviceRow(deviceId)
    }

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deletePairedDeviceRow(deviceId: String)
}
