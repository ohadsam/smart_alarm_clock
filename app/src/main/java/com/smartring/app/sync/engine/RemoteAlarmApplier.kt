package com.smartring.app.sync.engine

import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.sync.PeerLink
import com.smartring.app.sync.SyncPolicy
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.sync.protocol.SyncAlarm
import com.smartring.app.sync.protocol.toAlarm

/** The alarms a parent has set on this phone, as the applier needs to read and write them. */
interface ManagedAlarmStore {
    suspend fun findManaged(originDeviceId: String, syncId: String): Alarm?
    suspend fun countManagedFrom(originDeviceId: String): Int

    /** Writes with the owner's authority — the lock is how these are marked, not an obstacle. */
    suspend fun save(alarm: Alarm): Long
    suspend fun delete(id: Long)
}

/** The two things the applier needs from the scheduler. */
interface AlarmScheduling {
    fun schedule(alarm: Alarm)
    fun cancel(id: Long)
}

/**
 * The child's side of "a parent set an alarm on this phone."
 *
 * Every rule that decides whether an incoming change lands is here, as plain logic over two small
 * interfaces, so each can be pinned by a test:
 *
 *  - **A pairing that does not make this phone a child applies nothing.** (The engine checks too;
 *    this is the second lock on the same door.)
 *  - **Revisions only move forward.** An update at or below the revision already held is a replay or
 *    a late arrival, and is acknowledged as [AckStatus.STALE] rather than applied — an old message
 *    must never overwrite a newer instruction.
 *  - **A parent cannot fill the phone.** There is a ceiling on how many alarms one parent can hold
 *    here, so a buggy or hostile peer cannot create alarms without limit.
 *  - **An update keeps the alarm's local identity.** The row id survives, because the scheduler
 *    keys its AlarmManager registration off it; changing the id would leave the old registration
 *    armed.
 *  - **Applying schedules.** An alarm that arrives rings; one that is switched off or deleted stops.
 */
class RemoteAlarmApplier(
    private val store: ManagedAlarmStore,
    private val scheduling: AlarmScheduling,
    private val maxManagedPerPeer: Int = DEFAULT_MAX_MANAGED,
) {

    suspend fun upsert(peer: PeerLink, syncId: String, revision: Long, alarm: SyncAlarm): AckStatus {
        if (!SyncPolicy.mayBeManagedByPeer(peer.myRole, peer.theirRole)) return AckStatus.REJECTED
        if (revision < 1L) return AckStatus.INVALID

        val existing = store.findManaged(peer.deviceId, syncId)
        if (existing != null && revision <= existing.sync.revision) return AckStatus.STALE
        if (existing == null && store.countManagedFrom(peer.deviceId) >= maxManagedPerPeer) return AckStatus.BUSY

        val incoming = alarm.toAlarm(base = existing).copy(
            sync = AlarmSync(
                syncId = syncId,
                originDeviceId = peer.deviceId,
                originDeviceName = peer.name,
                isRemoteManaged = true,
                revision = revision,
            ),
        )
        val id = store.save(incoming)
        scheduling.schedule(incoming.copy(id = id))
        return AckStatus.OK
    }

    suspend fun delete(peer: PeerLink, syncId: String, revision: Long): AckStatus {
        if (!SyncPolicy.mayBeManagedByPeer(peer.myRole, peer.theirRole)) return AckStatus.REJECTED
        if (revision < 1L) return AckStatus.INVALID

        val existing = store.findManaged(peer.deviceId, syncId) ?: return AckStatus.OK   // already gone: idempotent
        // A delete older than what is held is a late arrival for a version that has since been
        // replaced; applying it would remove an alarm the parent re-created afterwards.
        if (revision < existing.sync.revision) return AckStatus.STALE

        store.delete(existing.id)
        scheduling.cancel(existing.id)
        return AckStatus.OK
    }

    companion object {
        const val DEFAULT_MAX_MANAGED = 50
    }
}
