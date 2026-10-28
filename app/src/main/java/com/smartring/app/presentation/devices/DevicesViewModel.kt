package com.smartring.app.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.db.PendingTransferEntity
import com.smartring.app.data.db.SyncDao
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.engine.SyncOutcome
import com.smartring.app.sync.nfc.NfcOffer
import com.smartring.app.sync.platform.DeviceIdentityRepository
import com.smartring.app.sync.platform.PairedDevice
import com.smartring.app.sync.platform.PairedDeviceRepository
import com.smartring.app.sync.platform.RoomSyncPort
import com.smartring.app.sync.platform.SyncController
import com.smartring.app.sync.platform.SyncCoordinator
import com.smartring.app.sync.protocol.AckStatus
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Devices screen shows and does. */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val pairedDevices: PairedDeviceRepository,
    private val identity: DeviceIdentityRepository,
    private val coordinator: SyncCoordinator,
    private val controller: SyncController,
    private val syncDao: SyncDao,
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val port: RoomSyncPort,
) : ViewModel() {

    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val deviceName: StateFlow<String> = identity.name.hot("")
    val listenInBackground: StateFlow<Boolean> = identity.listenInBackground.hot(true)
    val devices: StateFlow<List<PairedDevice>> = pairedDevices.devices.hot(emptyList())
    val syncing: StateFlow<Set<String>> = coordinator.syncing
    val pendingCopies: StateFlow<List<PendingTransferEntity>> = syncDao.observePendingTransfers().hot(emptyList())

    /** The alarms this phone keeps for each child, by the child's device id. */
    val childAlarms: StateFlow<Map<String, List<Alarm>>> =
        alarms.observeManagedForChildren().map { list -> list.groupBy { it.sync.managedForDeviceId.orEmpty() } }.hot(emptyMap())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setDeviceName(name: String) = viewModelScope.launch { identity.setName(name) }

    fun setListen(enabled: Boolean) = viewModelScope.launch {
        identity.setListenInBackground(enabled)
        controller.refreshListener()
    }

    fun setEnabled(deviceId: String, enabled: Boolean) = viewModelScope.launch {
        pairedDevices.setEnabled(deviceId, enabled)
        controller.refreshListener()
    }

    fun unpair(device: PairedDevice) = viewModelScope.launch {
        coordinator.unpair(device.deviceId)
        controller.refreshListener()
        _messages.tryEmit("הצימוד עם \"${device.name}\" בוטל")
    }

    fun syncNow(device: PairedDevice) = viewModelScope.launch {
        val text = when (val o = coordinator.syncNow(device.deviceId)) {
            null -> "אין מה לסנכרן מהמכשיר הזה, או שהסנכרון כבר מתבצע"
            is SyncOutcome.Completed -> "הסנכרון הסתיים"
            is SyncOutcome.Failed -> "הסנכרון נכשל: ${o.reason}"
            SyncOutcome.PeerUnpaired -> "המכשיר השני ביטל את הצימוד"
            SyncOutcome.UnpairSent -> "הצימוד בוטל"
        }
        _messages.tryEmit(text)
    }

    // ── Alarms kept for a child ───────────────────────────────────────────

    fun setChildAlarmEnabled(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        alarms.setEnabled(alarm.id, enabled)
    }

    fun deleteChildAlarm(alarm: Alarm) = viewModelScope.launch {
        alarms.deleteAlarm(alarm.id)
        scheduler.cancel(alarm.id)
        _messages.tryEmit("השעמור יוסר מהמכשיר השני כשיתחבר")
    }

    // ── Copies waiting for a yes ──────────────────────────────────────────

    fun acceptCopy(transferId: String) = viewModelScope.launch {
        val id = port.acceptTransfer(transferId)
        _messages.tryEmit(if (id != null) "השעמור נוסף לרשימה" else "השעמור כבר לא זמין")
    }

    fun declineCopy(transferId: String) = viewModelScope.launch { port.declineTransfer(transferId) }

    // ── NFC ───────────────────────────────────────────────────────────────

    /** An offer read from a phone held against this one. */
    fun onNfcOffer(offer: NfcOffer) = viewModelScope.launch {
        when (offer) {
            is NfcOffer.AlarmShare -> {
                val status = port.receiveUnverified(offer.senderName, offer.alarm)
                _messages.tryEmit(
                    when (status) {
                        AckStatus.OK -> "התקבל שעמור מ-${offer.senderName} — מחכה לאישור שלך"
                        AckStatus.STALE -> "השעמור הזה כבר התקבל"
                        AckStatus.BUSY -> "יש יותר מדי שעמורים שמחכים לאישור"
                        else -> "השעמור לא תקין"
                    }
                )
            }
            is NfcOffer.PairInvite -> _messages.tryEmit("זו הזמנה לצימוד. פתחו \"הוסף מכשיר\" כדי להתחבר.")
        }
    }
}
