package com.smartring.app.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.platform.PairedDevice
import com.smartring.app.sync.platform.PairedDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The phones the alarm editor may offer as "for": the enabled children of this phone. */
@HiltViewModel
class TargetsViewModel @Inject constructor(pairedDevices: PairedDeviceRepository) : ViewModel() {
    val children: StateFlow<List<PairedDevice>> = pairedDevices.devices
        .map { all -> all.filter { it.isEnabled && it.myRole == DeviceRole.PARENT } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
