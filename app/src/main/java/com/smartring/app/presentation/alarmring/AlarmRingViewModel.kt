package com.smartring.app.presentation.alarmring
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmRingUiState(
    val alarm: Alarm?       = null,
    val elapsedSeconds: Int = 0,
    val snoozeCount: Int    = 0,
    val isDismissed: Boolean = false,
)

@HiltViewModel
class AlarmRingViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,          // ← injected for snooze reschedule
) : ViewModel() {
    private val _state = MutableStateFlow(AlarmRingUiState())
    val state: StateFlow<AlarmRingUiState> = _state.asStateFlow()

    fun loadAlarm(id: Long) = viewModelScope.launch {
        repository.getAlarm(id)?.let { alarm ->
            _state.update { it.copy(alarm = alarm) }
        }
    }

    fun tick() = _state.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }

    fun stop() = viewModelScope.launch {
        _state.value.alarm?.let {
            repository.log(it.id, it.name, System.currentTimeMillis(), "STOPPED")
        }
        _state.update { it.copy(isDismissed = true) }
    }

    fun snooze() = viewModelScope.launch {
        val s     = _state.value
        val alarm = s.alarm ?: return@launch
        if (s.snoozeCount >= alarm.snoozeMaxCount) { stop(); return@launch }
        // Reschedule via scheduler (critical: screen-snooze was missing this)
        scheduler.scheduleAt(alarm, System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L)
        repository.log(alarm.id, alarm.name, System.currentTimeMillis(), "SNOOZED")
        _state.update { it.copy(snoozeCount = it.snoozeCount + 1, isDismissed = true) }
    }
}
