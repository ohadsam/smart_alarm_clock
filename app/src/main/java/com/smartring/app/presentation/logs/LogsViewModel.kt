package com.smartring.app.presentation.logs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.AppLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(val logs: List<AppLogEntry> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val repository: AlarmRepository,
) : ViewModel() {
    val state = repository.observeAppLogs()
        .map { LogsUiState(it, false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun clearAll() = viewModelScope.launch { repository.deleteAllAppLogs() }
}
