package com.smartring.app.sync.platform

import android.content.Context
import com.smartring.app.data.db.SyncDao
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.engine.SyncEngine
import com.smartring.app.sync.engine.SyncOutcome
import com.smartring.app.sync.pairing.SessionAuth
import com.smartring.app.sync.pairing.SessionProtocol
import com.smartring.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone that opens connections: sends what is waiting to the phones it has something for.
 *
 * Who connects to whom is fixed by role. A parent connects to its children; a peer connects to
 * peers; a child never connects out (it has nothing to send and nothing to ask), it listens
 * ([SyncServer]). So exactly one phone of any pair is ever the initiator for management traffic.
 *
 * When it runs:
 *  - **On change.** The coordinator watches what is waiting (an alarm kept for a child edited or
 *    removed, a copy queued) and, a couple of seconds after the last change, connects — the delay
 *    folds a burst of edits into one conversation.
 *  - **On failure.** A phone that is out of range is retried a few times with growing gaps; after
 *    that the periodic worker ([SyncWorker]) carries on, so a child switched off overnight catches
 *    up in the morning without the app being open.
 *  - **On request** from the Devices screen.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairedDevices: PairedDeviceRepository,
    private val identity: DeviceIdentityRepository,
    private val syncDao: SyncDao,
    private val port: RoomSyncPort,
    private val locks: PeerLocks,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())

    /** Device ids a conversation is in progress with right now. */
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    /** Begins watching for changes. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val waiting = combine(
                syncDao.observeManagedFingerprint(),
                syncDao.observeTombstoneCount(),
                syncDao.observeOutgoingTransferCount(),
                // Only the facts that decide whom to talk to — not lastSyncAt/lastError, which this
                // very coordinator writes, or every attempt would trigger the next.
                pairedDevices.devices,
            ) { fingerprint, tombstones, transfers, devices ->
                listOf(fingerprint, tombstones.toLong(), transfers.toLong(), devices.map { it.deviceId to it.isEnabled }.hashCode().toLong())
            }.distinctUntilChanged()

            waiting.collectLatest {
                delay(DEBOUNCE_MS)
                for (gap in RETRY_GAPS_MS) {
                    if (!syncPending()) return@collectLatest
                    delay(gap)
                }
                syncPending()
            }
        }
    }

    /**
     * Connects to every enabled pairing this phone initiates for and has something waiting. Returns
     * true if any attempt failed (so a caller can retry later).
     */
    suspend fun syncPending(): Boolean {
        var failed = false
        for (device in pairedDevices.all()) {
            if (!device.isEnabled || !initiates(device)) continue
            if (port.pendingFor(device.link).isEmpty()) continue
            if (run(device, unpair = false) is SyncOutcome.Failed) failed = true
        }
        return failed
    }

    /** The Devices screen's "sync now": connects whether or not anything is waiting, to pick up the other side's too. */
    suspend fun syncNow(deviceId: String): SyncOutcome? {
        val device = pairedDevices.get(deviceId) ?: return null
        if (!device.isEnabled) return null
        if (!initiates(device)) return null
        return run(device, unpair = false)
    }

    /**
     * Removes a pairing. The other phone is told first if it can be reached (best effort, short): a
     * phone that never learns it was unpaired keeps a lock on alarms that nobody can now change.
     * The local removal happens regardless — a person must always be able to get out of a pairing.
     */
    suspend fun unpair(deviceId: String) {
        val device = pairedDevices.get(deviceId)
        if (device != null && device.isEnabled && BluetoothSupport.canConnect(context)) {
            withTimeoutOrNull(UNPAIR_NOTIFY_TIMEOUT_MS) { run(device, unpair = true) }
        }
        pairedDevices.unpair(deviceId)
    }

    private fun initiates(device: PairedDevice) = device.myRole != DeviceRole.CHILD

    private suspend fun run(device: PairedDevice, unpair: Boolean): SyncOutcome? {
        val outcome = locks.tryRun(device.deviceId) {
            _syncing.update { it + device.deviceId }
            try {
                connectAndRun(device, unpair)
            } finally {
                _syncing.update { it - device.deviceId }
            }
        } ?: return null
        if (!unpair) {
            when (outcome) {
                is SyncOutcome.Completed -> pairedDevices.recordSync(device.deviceId, null)
                is SyncOutcome.Failed -> pairedDevices.recordSync(device.deviceId, outcome.reason)
                else -> {}
            }
        }
        return outcome
    }

    private suspend fun connectAndRun(device: PairedDevice, unpair: Boolean): SyncOutcome {
        val address = device.bluetoothAddress ?: return SyncOutcome.Failed("כתובת המכשיר אינה ידועה")
        if (!BluetoothSupport.canConnect(context)) return SyncOutcome.Failed("חסרה הרשאת Bluetooth")
        if (!BluetoothSupport.isEnabled(context)) return SyncOutcome.Failed("Bluetooth כבוי")
        val key = pairedDevices.keyFor(device.deviceId) ?: return SyncOutcome.Failed("מפתח הצימוד אינו זמין")
        try {
            val socket = try {
                val s = BluetoothSupport.createSocket(context, address)
                withTimeout(CONNECT_TIMEOUT_MS) { blockingCancellable(onCancel = { runCatching { s.close() } }) { s.connect() } }
                s
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return SyncOutcome.Failed("המכשיר אינו בהישג יד")
            } catch (e: SecurityException) {
                return SyncOutcome.Failed("חסרה הרשאת Bluetooth")
            } catch (e: IllegalArgumentException) {
                return SyncOutcome.Failed("כתובת לא תקינה")
            }
            val channel = BluetoothFrameChannel(socket)
            return when (val auth = SessionProtocol().initiate(channel, device.deviceId, key)) {
                is SessionAuth.Authenticated -> try {
                    SyncEngine(identity.current(), port).runAsInitiator(auth.channel, device.link, unpair)
                } finally {
                    auth.channel.close()
                }
                SessionAuth.NotAuthorized -> SyncOutcome.Failed("המכשיר השני אינו מזהה את הצימוד")
                is SessionAuth.Failed -> SyncOutcome.Failed(auth.reason)
            }
        } finally {
            key.fill(0)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val UNPAIR_NOTIFY_TIMEOUT_MS = 25_000L
        val RETRY_GAPS_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L)
    }
}
