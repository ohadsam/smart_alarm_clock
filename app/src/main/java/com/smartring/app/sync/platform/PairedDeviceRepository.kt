package com.smartring.app.sync.platform

import com.smartring.app.data.db.PairedDeviceEntity
import com.smartring.app.data.db.SyncDao
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.PeerLink
import com.smartring.app.sync.pairing.PairingResult
import com.smartring.app.sync.pairing.SessionCandidate
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.cleanText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A phone this one has been paired with. Never carries the key — that stays in the repository. */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val myRole: DeviceRole,
    val theirRole: DeviceRole,
    val bluetoothAddress: String?,
    val isEnabled: Boolean,
    val createdAt: Long,
    val lastSyncAt: Long?,
    val lastError: String?,
) {
    val link: PeerLink get() = PeerLink(deviceId, name, myRole, theirRole)
}

/**
 * The pairings, and the only place their keys are ever unwrapped.
 *
 * Callers get [PairedDevice]s, which have no key on them, so a screen or a log line cannot leak
 * one by accident. The key is handed out only to the two things that must have it — the session
 * handshake (as a [SessionCandidate]) — and only for pairings that are switched on.
 */
@Singleton
class PairedDeviceRepository @Inject constructor(
    private val dao: SyncDao,
    private val box: SecretBox,
) {

    val devices: Flow<List<PairedDevice>> = dao.observePairedDevices().map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun all(): List<PairedDevice> = dao.getPairedDevices().mapNotNull { it.toDomain() }

    suspend fun get(deviceId: String): PairedDevice? = dao.getPairedDevice(deviceId)?.toDomain()

    /**
     * Records a successful pairing.
     *
     * Pairing with a phone that is already paired replaces the key — that is what re-pairing after
     * removing the pairing on only one side means. If the *relationship* is different this time
     * (it was a parent, it is now a child), everything recorded under the old one is cleared
     * first: alarms kept for a "child" that is now a parent would otherwise sit in the list
     * claiming to reach a phone that has the opposite idea.
     */
    suspend fun add(result: PairingResult.Success, address: String?) {
        val existing = dao.getPairedDevice(result.peerDeviceId)
        if (existing != null &&
            (existing.myRole != result.myRole.name || existing.theirRole != result.theirRole.name)
        ) {
            dao.unpair(result.peerDeviceId)
        }
        dao.upsertPairedDevice(
            PairedDeviceEntity(
                deviceId = result.peerDeviceId,
                name = cleanText(result.peerName, Proto.DEVICE_NAME_MAX_CHARS),
                myRole = result.myRole.name,
                theirRole = result.theirRole.name,
                bluetoothAddress = address,
                encryptedKey = box.seal(result.pairingKey),
                isEnabled = true,
                createdAt = System.currentTimeMillis(),
                lastSyncAt = null,
                lastError = null,
            )
        )
    }

    /**
     * The pairings a connecting phone may be one of: switched on, and with a key that can still be
     * read. A switched-off pairing is not tried at all, so an "allowed devices" switch means what it
     * says — the phone is refused at the handshake, not merely ignored afterwards.
     */
    suspend fun candidates(): List<SessionCandidate> =
        dao.getPairedDevices().filter { it.isEnabled }.mapNotNull { row ->
            box.open(row.encryptedKey)?.let { SessionCandidate(row.deviceId, it) }
        }

    /** The key for one pairing, for the phone that is about to connect out. Null if unreadable. */
    suspend fun keyFor(deviceId: String): ByteArray? =
        dao.getPairedDevice(deviceId)?.let { box.open(it.encryptedKey) }

    suspend fun rename(deviceId: String, name: String) {
        val cleaned = cleanText(name, Proto.DEVICE_NAME_MAX_CHARS)
        if (cleaned.isNotEmpty()) dao.renamePairedDevice(deviceId, cleaned)
    }

    suspend fun setEnabled(deviceId: String, enabled: Boolean) = dao.setPairedDeviceEnabled(deviceId, enabled)

    suspend fun setAddress(deviceId: String, address: String?) = dao.setBluetoothAddress(deviceId, address)

    /** Called after every attempt; [error] null means it worked. */
    suspend fun recordSync(deviceId: String, error: String?) =
        dao.recordSyncResult(deviceId, if (error == null) System.currentTimeMillis() else null, error)

    /** Removes the pairing and everything that only made sense because of it. See [SyncDao.unpair]. */
    suspend fun unpair(deviceId: String) = dao.unpair(deviceId)

    private fun PairedDeviceEntity.toDomain(): PairedDevice? {
        val mine = DeviceRole.parse(myRole) ?: return null
        val theirs = DeviceRole.parse(theirRole) ?: return null
        return PairedDevice(deviceId, name, mine, theirs, bluetoothAddress, isEnabled, createdAt, lastSyncAt, lastError)
    }
}
