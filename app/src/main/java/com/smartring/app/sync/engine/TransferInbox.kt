package com.smartring.app.sync.engine

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.sync.PeerLink
import com.smartring.app.sync.SyncPolicy
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.decodeSyncAlarm
import com.smartring.app.sync.protocol.encodeSyncAlarm
import com.smartring.app.sync.protocol.toAlarm
import com.smartring.app.sync.protocol.validated

class StoredTransfer(
    val transferId: String,
    val fromDeviceId: String,
    val fromDeviceName: String,
    val payload: ByteArray,
    val receivedAt: Long,
)

interface TransferStore {
    suspend fun count(): Int
    suspend fun countFrom(deviceId: String): Int

    /** False if a transfer with this id is already waiting — the existing one is kept. */
    suspend fun insert(transfer: StoredTransfer): Boolean
    suspend fun get(transferId: String): StoredTransfer?
    suspend fun delete(transferId: String)
}

/**
 * Copies of alarms that peers have sent, waiting for the user to say yes.
 *
 * ## Why a copy needs a yes
 *
 * A parent's alarm is applied without asking — that is what being a parent is. A *peer's* is not:
 * nobody agreed that an equal could make this phone ring at 04:00. So a received copy is parked
 * here, visible, and becomes an alarm only when the user accepts it.
 *
 * What the user accepts is an ordinary alarm of their own. It remembers who sent it (so the list
 * can say so) and nothing else: no lock, no link back. It is theirs to edit or delete.
 *
 * ## Bounds
 *
 * A peer can only park so many, and cannot overwrite one already waiting. Otherwise "send a copy"
 * is a way to bury someone's screen or silently swap what they are about to read.
 */
class TransferInbox(
    private val store: TransferStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTotal: Int = DEFAULT_MAX_TOTAL,
    private val maxPerPeer: Int = DEFAULT_MAX_PER_PEER,
) {

    /** [alarm] arrives already validated; it is stored in the protocol's own encoding. */
    suspend fun receive(peer: PeerLink, transferId: String, alarm: SyncAlarm): AckStatus {
        if (!SyncPolicy.mayExchangeCopies(peer.myRole, peer.theirRole)) return AckStatus.REJECTED
        if (store.count() >= maxTotal || store.countFrom(peer.deviceId) >= maxPerPeer) return AckStatus.BUSY
        val stored = store.insert(
            StoredTransfer(transferId, peer.deviceId, peer.name, encodeSyncAlarm(alarm), clock())
        )
        // Already waiting: the sender is retrying something it never saw acknowledged. Fine.
        return if (stored) AckStatus.OK else AckStatus.STALE
    }

    /**
     * The alarm the user is about to keep, or null if the transfer is gone or its contents no longer
     * pass validation (they are re-validated on the way out: storage is not trusted either).
     */
    suspend fun accept(transferId: String, sink: suspend (Alarm) -> Long): Long? {
        val transfer = store.get(transferId) ?: return null
        val alarm = try {
            decodeSyncAlarm(transfer.payload).validated()
        } catch (e: ProtocolException) {
            null
        }
        if (alarm == null) {
            store.delete(transferId)
            return null
        }
        val local = alarm.toAlarm().copy(
            // The copy starts switched off if its sender had it off, but is otherwise a normal
            // alarm. Its origin is a note, not a tether.
            sync = AlarmSync(originDeviceId = transfer.fromDeviceId, originDeviceName = transfer.fromDeviceName),
        )
        val id = sink(local)
        store.delete(transferId)
        return id
    }

    suspend fun decline(transferId: String) = store.delete(transferId)

    companion object {
        const val DEFAULT_MAX_TOTAL = 30
        const val DEFAULT_MAX_PER_PEER = 10
    }
}
