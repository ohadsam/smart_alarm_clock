package com.smartring.app.sync.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.smartring.app.sync.crypto.FrameChannel
import com.smartring.app.sync.engine.SyncEngine
import com.smartring.app.sync.engine.SyncOutcome
import com.smartring.app.sync.pairing.SessionAuth
import com.smartring.app.sync.pairing.SessionProtocol
import com.smartring.app.sync.protocol.HandshakeMsg
import com.smartring.app.sync.protocol.ProtocolException
import com.smartring.app.sync.protocol.decodeHandshake
import com.smartring.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The one Bluetooth server socket this app listens on, shared by everything that needs to be
 * reachable.
 *
 * Two things want incoming connections — the background listener (a child receiving a parent's
 * alarms) and a pairing in progress (the phone showing the code) — and Android allows one server
 * socket per service UUID. So they *share* it: each takes a hold ([acquire]) and the socket is
 * open while anyone holds it. The first message on a connection says which of the two it is for,
 * and the connection is handed on accordingly.
 *
 * ## What a stranger can do
 *
 * Anything within Bluetooth range can open a connection to a listening RFCOMM service. So the
 * first thing every connection meets is a short deadline for its first message, then a message
 * check, then a handshake that needs a key or a code — nothing is read, allocated or stored on
 * behalf of a peer that has not passed one. Only [MAX_CONCURRENT] connections are served at once;
 * the rest are closed unread, so a flood cannot exhaust threads or memory.
 */
@Singleton
class SyncServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairedDevices: PairedDeviceRepository,
    private val identity: DeviceIdentityRepository,
    private val port: RoomSyncPort,
    private val locks: PeerLocks,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val holders = mutableSetOf<String>()
    private var serving: Job? = null
    private val slots = Semaphore(MAX_CONCURRENT)

    /**
     * Set by the pairing manager while it is showing a code; null otherwise. A connection that
     * opens with a pairing message when this is null is closed — nobody asked to be paired with.
     */
    @Volatile var pairingHost: (suspend (ReplayFirstFrame) -> Unit)? = null

    /** Starts listening if nothing was. Safe to call repeatedly with the same [owner]. */
    fun acquire(owner: String) {
        synchronized(lock) {
            holders += owner
            if (serving?.isActive != true) serving = scope.launch { serve() }
        }
    }

    fun release(owner: String) {
        synchronized(lock) {
            holders -= owner
            if (holders.isEmpty()) {
                serving?.cancel()
                serving = null
            }
        }
    }

    fun isHeldBy(owner: String): Boolean = synchronized(lock) { owner in holders }

    @SuppressLint("MissingPermission")   // guarded below: canConnect() before listening; a revoked grant throws SecurityException, caught
    private suspend fun serve() {
        if (!BluetoothSupport.canConnect(context) || !BluetoothSupport.isEnabled(context)) return
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            val server: BluetoothServerSocket = try {
                BluetoothSupport.adapter(context)
                    ?.listenUsingInsecureRfcommWithServiceRecord(BluetoothSupport.SERVICE_NAME, BluetoothSupport.SERVICE_UUID)
                    ?: return
            } catch (e: IOException) {
                appLogger.log("Sync", "לא ניתן להאזין ל-Bluetooth: ${e.message}")
                return
            } catch (e: SecurityException) {
                return
            }
            try {
                while (true) {
                    val socket = try {
                        blockingCancellable(onCancel = { runCatching { server.close() } }) { server.accept() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        coroutineContext.ensureActive()
                        // The socket died under us (Bluetooth switched off, say). Reopen after a pause,
                        // and give up if it keeps happening rather than spin.
                        if (++failures >= MAX_ACCEPT_FAILURES) return
                        break
                    }
                    failures = 0
                    if (slots.tryAcquire()) {
                        scope.launch { try { handle(socket) } finally { slots.release() } }
                    } else {
                        runCatching { socket.close() }
                    }
                }
            } finally {
                runCatching { server.close() }
            }
            delay(REOPEN_DELAY_MS)
        }
    }

    private suspend fun handle(socket: BluetoothSocket) {
        val channel = BluetoothFrameChannel(socket)
        try {
            val first = try {
                withTimeout(FIRST_MESSAGE_TIMEOUT_MS) { channel.receive() }
            } catch (e: Exception) {
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                channel.close()
                return
            }
            val kind = try {
                decodeHandshake(first)
            } catch (e: ProtocolException) {
                channel.close()
                return
            }
            val replay = ReplayFirstFrame(channel, first, channel.remoteAddress)
            when (kind) {
                is HandshakeMsg.PairHello -> {
                    val host = pairingHost
                    if (host == null) channel.close() else host(replay)
                }
                is HandshakeMsg.SessionHello -> respondToSession(replay, channel.remoteAddress)
                else -> channel.close()
            }
        } catch (e: CancellationException) {
            channel.close()
            throw e
        } catch (e: Exception) {
            appLogger.log("Sync", "חיבור נכנס נכשל: ${e.javaClass.simpleName}")
            channel.close()
        }
    }

    private suspend fun respondToSession(channel: FrameChannel, remoteAddress: String?) {
        val auth = SessionProtocol().respond(channel, pairedDevices.candidates())
        if (auth !is SessionAuth.Authenticated) return   // the protocol already closed the link
        val device = pairedDevices.get(auth.deviceId)
        if (device == null || !device.isEnabled) {
            auth.channel.close()
            return
        }
        // Remembered so this phone can connect back without a search. Not authenticated by itself —
        // a wrong address only means a connection attempt that fails its handshake.
        if (remoteAddress != null && remoteAddress != device.bluetoothAddress) {
            pairedDevices.setAddress(device.deviceId, remoteAddress)
        }
        val outcome = locks.tryRun(device.deviceId) {
            SyncEngine(identity.current(), port).runAsResponder(auth.channel, device.link)
        }
        auth.channel.close()
        when (outcome) {
            null -> {}   // already talking to this phone
            is SyncOutcome.Completed -> pairedDevices.recordSync(device.deviceId, null)
            is SyncOutcome.Failed -> pairedDevices.recordSync(device.deviceId, "נכשל: ${outcome.reason}")
            else -> {}   // the peer removed the pairing; the port has already dealt with it
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 2
        const val FIRST_MESSAGE_TIMEOUT_MS = 10_000L
        const val MAX_ACCEPT_FAILURES = 5
        const val REOPEN_DELAY_MS = 2_000L
    }
}

/**
 * A channel whose first read returns a message that was already consumed.
 *
 * The server has to read the first message to know where the connection goes; the handler it goes
 * to expects to read that message itself.
 */
class ReplayFirstFrame(
    private val inner: FrameChannel,
    first: ByteArray,
    /** The Bluetooth address of the phone on the other end, if known. */
    val remoteAddress: String?,
) : FrameChannel {
    private var pending: ByteArray? = first

    override suspend fun send(frame: ByteArray) = inner.send(frame)

    override suspend fun receive(): ByteArray {
        pending?.let { pending = null; return it }
        return inner.receive()
    }

    override fun close() = inner.close()
}
