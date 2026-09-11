package com.smartring.app.presentation.alarmlist
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.util.AlarmScheduler
import com.smartring.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmListUiState(val alarms: List<Alarm> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val appLogger: AppLogger,
) : ViewModel() {
    val uiState = repository.observeAlarms()
        .map { AlarmListUiState(it, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmListUiState())

    fun toggle(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(alarm.id, enabled)
        if (enabled) scheduler.schedule(alarm.copy(isEnabled = true)) else scheduler.cancel(alarm.id)
        appLogger.log("AlarmList", "\"${alarm.name}\" (#${alarm.id}) ${if (enabled) "הופעל" else "כובה"}")
    }
    fun delete(alarm: Alarm) = viewModelScope.launch {
        repository.deleteAlarm(alarm.id); scheduler.cancel(alarm.id)
        appLogger.log("AlarmList", "נמחק: \"${alarm.name}\" (#${alarm.id})")
    }
    fun disableAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.disableAll()
        scheduler.cancelAll(alarms.map { it.id })
        appLogger.log("AlarmList", "כל השעמורים כובו (${alarms.size})")
    }
    fun freezeAll() = viewModelScope.launch {
        val alarms = repository.getActiveAlarms()
        repository.freezeAll()
        scheduler.cancelAll(alarms.map { it.id })
        appLogger.log("AlarmList", "כל השעמורים הוקפאו (${alarms.size})")
    }
    fun unfreezeAll() = viewModelScope.launch {
        // refreshWidgets=false: unfreezeAll()'s own write above already triggers
        // SmartRingApp's observeAlarms()-based widget refresh.
        repository.unfreezeAll(); scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
        appLogger.log("AlarmList", "הקפאה בוטלה לכל השעמורים")
    }
    fun enableAll() = viewModelScope.launch {
        repository.enableAll(); scheduler.rescheduleAll(repository.getActiveAlarms(), refreshWidgets = false)
        appLogger.log("AlarmList", "כל השעמורים הופעלו")
    }
}
