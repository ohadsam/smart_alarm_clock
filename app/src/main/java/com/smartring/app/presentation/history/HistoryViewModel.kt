package com.smartring.app.presentation.history
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.AlarmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val logs: List<AlarmLog>   = emptyList(),
    val isLoading: Boolean     = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: AlarmRepository,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = repository.observeLogs()
        .map { HistoryUiState(logs = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun deleteLog(id: Long)  = viewModelScope.launch { repository.deleteLog(id) }
    fun deleteAll()          = viewModelScope.launch { repository.deleteAllLogs() }
}
