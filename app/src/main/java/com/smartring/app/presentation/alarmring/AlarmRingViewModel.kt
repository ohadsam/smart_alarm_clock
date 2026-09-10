package com.smartring.app.presentation.alarmring
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.service.AlarmFiringService
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AlarmRingUiState())
    val state: StateFlow<AlarmRingUiState> = _state.asStateFlow()

    fun loadAlarm(id: Long) = viewModelScope.launch {
        repository.getAlarm(id)?.let { alarm ->
            _state.update { it.copy(alarm = alarm) }
        }
    }

    fun tick() = _state.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }

    private fun stopFiringService() = context.stopService(Intent(context, AlarmFiringService::class.java))

    fun stop() = viewModelScope.launch {
        // Shabbat-mode alarms accept no interaction; the UI already hides/disables
        // this button for them, but guard here too rather than trust the caller.
        if (_state.value.alarm?.acceptsInteraction == false) return@launch
        // The ring screen only reflects UI dismissal; without stopping the service
        // the ringtone/vibration kept playing in the background until ringDurationSeconds.
        stopFiringService()
        _state.value.alarm?.let {
            repository.log(it.id, it.name, System.currentTimeMillis(), "STOPPED")
            appLogger.log("AlarmRing", "נעצר ידנית: \"${it.name}\" (#${it.id})")
        }
        _state.update { it.copy(isDismissed = true) }
    }

    fun snooze() = viewModelScope.launch {
        val alarm = _state.value.alarm ?: return@launch
        if (!alarm.acceptsInteraction) return@launch
        // Snooze disabled for this alarm (button shouldn't be visible at all, but
        // guard defensively): degrade to a plain stop rather than doing nothing.
        if (!alarm.snoozeEnabled) { stop(); return@launch }
        // Derived from history, not the in-memory snoozeCount: this ViewModel (and
        // its counter) is recreated from scratch each time the ring screen reopens
        // for a new snooze wake-up, so an in-memory cap never actually triggered.
        val alreadySnoozed = repository.snoozeCountSinceLastFire(alarm.id)
        if (alreadySnoozed >= alarm.snoozeMaxCount) { stop(); return@launch }
        stopFiringService()
        scheduler.scheduleAt(alarm, System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L)
        repository.log(alarm.id, alarm.name, System.currentTimeMillis(), "SNOOZED")
        appLogger.log("AlarmRing", "נודניק ידני: \"${alarm.name}\" (#${alarm.id})")
        _state.update { it.copy(snoozeCount = it.snoozeCount + 1, isDismissed = true) }
    }
}
