package com.smartring.app.sync.platform

import android.content.Context
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.nfc.NfcOffer
import com.smartring.app.sync.nfc.NfcOfferHolder
import com.smartring.app.sync.pairing.PairingCodeSession
import com.smartring.app.sync.pairing.PairingCodes
import com.smartring.app.sync.pairing.PairingProtocol
import com.smartring.app.sync.pairing.PairingResult
import com.smartring.app.sync.pairing.PeerSummary
import com.smartring.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the pairing screens show. */
sealed interface PairingUiState {
    data object Idle : PairingUiState

    /** This phone is showing [code] and waiting for the other to enter it. */
    data class Hosting(val code: String, val myRole: DeviceRole, val expiresAtMillis: Long, val attemptsLeft: Int) : PairingUiState

    /** A connection is open and the keys are being agreed. */
    data class Working(val message: String) : PairingUiState

    /** The cryptography succeeded. Nothing is stored until both people say yes. */
    data class Confirm(val summary: PeerSummary) : PairingUiState

    data class Done(val name: String, val myRole: DeviceRole, val theirRole: DeviceRole) : PairingUiState

    data class Failed(val message: String, val retryable: Boolean) : PairingUiState
}

/**
 * Runs one pairing at a time, from either end, and tells the UI what is happening.
 *
 * **Hosting** (the phone that shows the code): opens the shared Bluetooth server, publishes the NFC
 * invitation, and waits. A wrong code costs one of [PairingCodeSession.maxAttempts]; when they run
 * out, or the code expires, hosting stops and the code is dead — nobody can keep trying it.
 *
 * **Joining** (the phone that types the code): connects out to the address the person picked and
 * runs the handshake.
 *
 * Either way nothing reaches the database until *both* people have confirmed on their own screen.
 */
@Singleton
class PairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: SyncServer,
    private val identity: DeviceIdentityRepository,
    private val pairedDevices: PairedDeviceRepository,
    private val nfcOffers: NfcOfferHolder,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var code: PairingCodeSession? = null
    private var hostRole: DeviceRole = DeviceRole.PEER
    private val busy = AtomicBoolean(false)
    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    // ── The phone showing the code ────────────────────────────────────────

    /**
     * Starts showing a code. [role] is what *this* phone will be to the other: choosing PARENT here
     * makes the other phone the child. The other person sees that on their own screen before they
     * agree to anything.
     */
    fun startHosting(role: DeviceRole) {
        cancel()
        if (!BluetoothSupport.hasAll(context, BluetoothSupport.connectPermissions()) || !BluetoothSupport.isEnabled(context)) {
            _state.value = PairingUiState.Failed("יש להפעיל Bluetooth ולאשר הרשאה", retryable = true)
            return
        }
        val session = PairingCodeSession(PairingCodes.generate())
        code = session
        hostRole = role
        server.pairingHost = { channel -> hostOne(channel, session) }
        server.acquire(OWNER)
        publishInvite(session, role)
        publishHostingState(session)
        job = scope.launch {
            // The code's lifetime. The deadline is checked again whenever a connection arrives, so
            // this is only to tell the person it ran out rather than leave a dead code on screen.
            delay(session.ttlMillis)
            if (code === session && _state.value is PairingUiState.Hosting) {
                stopHosting()
                _state.value = PairingUiState.Failed("הקוד פג תוקף", retryable = true)
            }
        }
    }

    private fun publishHostingState(session: PairingCodeSession) {
        _state.value = PairingUiState.Hosting(
            PairingCodes.format(session.code), hostRole,
            System.currentTimeMillis() + session.remainingMillis, session.attemptsLeft,
        )
    }

    private fun publishInvite(session: PairingCodeSession, role: DeviceRole) {
        scope.launch {
            val name = identity.current().name
            val bt = BluetoothSupport.bluetoothName(context) ?: return@launch
            runCatching { nfcOffers.publish(NfcOffer.PairInvite(session.code, bt, name), session.ttlMillis) }
        }
    }

    private suspend fun hostOne(channel: ReplayFirstFrame, session: PairingCodeSession) {
        if (code !== session || !session.isUsable || !busy.compareAndSet(false, true)) {
            channel.close()
            return
        }
        try {
            _state.value = PairingUiState.Working("מתחבר…")
            val result = PairingProtocol().host(channel, session.code, identity.current(), hostRole) { askUser(it) }
            when (result) {
                is PairingResult.Success -> {
                    session.recordSuccess()
                    finishSuccess(result, channel.remoteAddress)
                    stopHosting()
                }
                PairingResult.WrongCode -> {
                    session.recordWrongCode()
                    appLogger.log("Pairing", "ניסיון צימוד עם קוד שגוי (נותרו ${session.attemptsLeft})")
                    if (session.isUsable) {
                        publishHostingState(session)
                    } else {
                        stopHosting()
                        _state.value = PairingUiState.Failed("הוזן קוד שגוי מספר פעמים — הקוד בוטל", retryable = true)
                    }
                }
                PairingResult.Declined -> { stopHosting(); _state.value = declinedState() }
                is PairingResult.Failed -> {
                    appLogger.log("Pairing", "צימוד נכשל: ${result.reason}")
                    // A stranger's botched connection must not end the person's own attempt.
                    if (session.isUsable) publishHostingState(session)
                    else { stopHosting(); _state.value = PairingUiState.Failed("הצימוד נכשל", retryable = true) }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private fun stopHosting() {
        server.pairingHost = null
        server.release(OWNER)
        nfcOffers.clear()
        code = null
        job?.cancel()
        job = null
    }

    // ── The phone typing the code ─────────────────────────────────────────

    /** Connects to [address] and pairs using [rawCode] as typed. */
    fun join(address: String, rawCode: String) {
        cancel()
        val normalized = PairingCodes.normalize(rawCode)
        if (normalized == null) {
            _state.value = PairingUiState.Failed("הקוד צריך להכיל ${PairingCodes.LENGTH} ספרות", retryable = true)
            return
        }
        if (!BluetoothSupport.hasAll(context, BluetoothSupport.connectPermissions()) || !BluetoothSupport.isEnabled(context)) {
            _state.value = PairingUiState.Failed("יש להפעיל Bluetooth ולאשר הרשאה", retryable = true)
            return
        }
        _state.value = PairingUiState.Working("מתחבר למכשיר השני…")
        job = scope.launch {
            run {
                val socket = try {
                    val s = BluetoothSupport.createSocket(context, address)
                    withTimeout(CONNECT_TIMEOUT_MS) { blockingCancellable(onCancel = { runCatching { s.close() } }) { s.connect() } }
                    s
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IOException) {
                    _state.value = PairingUiState.Failed("לא ניתן להתחבר. ודאו שהמכשיר השני מציג קוד וקרוב", retryable = true)
                    return@launch
                } catch (e: SecurityException) {
                    _state.value = PairingUiState.Failed("חסרה הרשאת Bluetooth", retryable = true)
                    return@launch
                } catch (e: IllegalArgumentException) {
                    _state.value = PairingUiState.Failed("כתובת לא תקינה", retryable = true)
                    return@launch
                }
                _state.value = PairingUiState.Working("מאמת את הקוד…")
                val channel = BluetoothFrameChannel(socket)
                when (val result = PairingProtocol().join(channel, normalized, identity.current()) { askUser(it) }) {
                    is PairingResult.Success -> finishSuccess(result, address)
                    PairingResult.WrongCode -> _state.value = PairingUiState.Failed("הקוד שגוי", retryable = true)
                    PairingResult.Declined -> _state.value = declinedState()
                    is PairingResult.Failed -> {
                        appLogger.log("Pairing", "צימוד נכשל: ${result.reason}")
                        _state.value = PairingUiState.Failed("הצימוד נכשל: ${friendly(result.reason)}", retryable = true)
                    }
                }
            }
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    /** The user's answer on the confirmation screen. */
    fun confirm(accept: Boolean) {
        decision?.complete(accept)
    }

    /** Abandons whatever is in progress. Nothing has been stored unless it already says Done. */
    fun cancel() {
        decision?.complete(false)
        decision = null
        if (code != null) stopHosting()
        job?.cancel()
        job = null
        if (_state.value !is PairingUiState.Done) _state.value = PairingUiState.Idle
    }

    /** Back to the start, from a result screen. */
    fun reset() {
        cancel()
        _state.value = PairingUiState.Idle
    }

    private suspend fun askUser(summary: PeerSummary): Boolean {
        val d = CompletableDeferred<Boolean>()
        decision = d
        _state.value = PairingUiState.Confirm(summary)
        return try {
            d.await()
        } finally {
            decision = null
        }
    }

    private suspend fun finishSuccess(result: PairingResult.Success, address: String?) {
        try {
            pairedDevices.add(result, address)
        } finally {
            result.pairingKey.fill(0)
        }
        appLogger.log("Pairing", "צומד עם \"${result.peerName}\" (${result.myRole.name})")
        _state.value = PairingUiState.Done(result.peerName, result.myRole, result.theirRole)
    }

    private fun declinedState() = PairingUiState.Failed("הצימוד בוטל — לא נשמר דבר", retryable = false)

    private fun friendly(reason: String) = when {
        reason.contains("timed out") -> "פג הזמן"
        reason.contains("closed") -> "החיבור נותק"
        else -> "תקלה בפרוטוקול"
    }

    private companion object {
        const val OWNER = "pairing"
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
