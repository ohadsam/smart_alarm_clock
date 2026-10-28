package com.smartring.app.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.domain.model.Alarm
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.nfc.NfcOffer
import com.smartring.app.sync.nfc.NfcOfferHolder
import com.smartring.app.sync.platform.DeviceIdentityRepository
import com.smartring.app.sync.platform.PairedDevice
import com.smartring.app.sync.platform.PairedDeviceRepository
import com.smartring.app.sync.platform.RoomSyncPort
import com.smartring.app.sync.protocol.toSyncAlarm
import com.smartring.app.sync.protocol.validated
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Sending a copy of one of this phone's alarms to an equal peer, or handing it over by touch. */
@HiltViewModel
class ShareViewModel @Inject constructor(
    pairedDevices: PairedDeviceRepository,
    private val identity: DeviceIdentityRepository,
    private val port: RoomSyncPort,
    private val nfc: NfcOfferHolder,
) : ViewModel() {

    /** Phones this one is equal to and may send copies to. */
    val peers: StateFlow<List<PairedDevice>> = pairedDevices.devices
        .map { all -> all.filter { it.isEnabled && it.myRole == DeviceRole.PEER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Queues a copy; it goes out the next time the two phones are in range. False if it cannot be sent at all. */
    suspend fun sendCopy(alarm: Alarm, peer: PairedDevice): Boolean = port.queueCopy(alarm, peer.deviceId)

    /** Starts offering [alarm] over NFC. False if it does not validate. */
    suspend fun offerOverNfc(alarm: Alarm): Boolean {
        val dto = alarm.toSyncAlarm().validated() ?: return false
        nfc.publish(NfcOffer.AlarmShare(identity.current().name, dto))
        return true
    }

    fun stopOffer() = nfc.clear()

    override fun onCleared() {
        nfc.clear()
        super.onCleared()
    }
}
