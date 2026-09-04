package com.smartring.app.presentation.alarmlist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmListUiState(val alarms: List<Alarm> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {
    val uiState = repository.observeAlarms()
        .map { AlarmListUiState(it, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmListUiState())

    fun toggle(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(alarm.id, enabled)
        if (enabled) scheduler.schedule(alarm.copy(isEnabled = true)) else scheduler.cancel(alarm.id)
    }
    fun delete(alarm: Alarm) = viewModelScope.launch {
        scheduler.cancel(alarm.id); repository.deleteAlarm(alarm.id)
    }
    fun disableAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.disableAll()
        alarms.forEach { scheduler.cancel(it.id) }
    }
    fun freezeAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.freezeAll()
        alarms.forEach { scheduler.cancel(it.id) }
    }
    fun unfreezeAll() = viewModelScope.launch {
        repository.unfreezeAll(); scheduler.rescheduleAll(repository.getActiveAlarms())
    }
    fun enableAll() = viewModelScope.launch {
        repository.enableAll(); scheduler.rescheduleAll(repository.getActiveAlarms())
    }
}
