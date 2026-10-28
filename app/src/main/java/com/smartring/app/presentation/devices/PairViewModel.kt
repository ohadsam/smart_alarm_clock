package com.smartring.app.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.platform.BluetoothScanner
import com.smartring.app.sync.platform.NearbyDevice
import com.smartring.app.sync.platform.PairingManager
import com.smartring.app.sync.platform.PairingUiState
import com.smartring.app.sync.platform.SyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairViewModel @Inject constructor(
    private val manager: PairingManager,
    private val scanner: BluetoothScanner,
    private val controller: SyncController,
) : ViewModel() {

    val state: StateFlow<PairingUiState> = manager.state

    private val _nearby = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearby: StateFlow<List<NearbyDevice>> = _nearby.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    init {
        // A stale result from an earlier visit must not greet the person with "failed" or "done".
        manager.reset()
    }

    fun startHosting(role: DeviceRole) = manager.startHosting(role)

    fun join(address: String, code: String) {
        stopScan()
        manager.join(address, code)
    }

    fun confirm(accept: Boolean) = manager.confirm(accept)

    fun cancel() = manager.cancel()

    /** Back to the start after a result. */
    fun reset() = manager.reset()

    /** Phones already known first, then whatever the search finds, each address once. */
    fun scan() {
        scanJob?.cancel()
        _nearby.value = scanner.knownDevices()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { }
                .onCompletion { _scanning.value = false }
                .collect { found ->
                    _nearby.update { list ->
                        if (list.any { it.address == found.address }) {
                            // A later sighting may carry the name an earlier one lacked.
                            list.map { if (it.address == found.address && it.name == null) found else it }
                        } else list + found
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    /** Paired (or not) — a new pairing changes who should be listening. */
    fun finished() = controller.refreshListener()

    override fun onCleared() {
        stopScan()
        // Leaving the screen abandons a pairing that has not finished; one that has stays.
        manager.cancel()
        super.onCleared()
    }
}
