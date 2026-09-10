package com.smartring.app.presentation.whatsnew
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.BuildConfig
import com.smartring.app.data.repository.AlarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.whatsNewDataStore: DataStore<Preferences> by preferencesDataStore("whats_new")

data class WhatsNewUiState(val entriesToShow: List<WhatsNewEntry> = emptyList())

/**
 * Shows "what's new" once per upgrade — not on a brand-new install (nothing to
 * compare against yet) and not again once dismissed for the current version. Tracks
 * "last version the user actually saw" in its own DataStore file, separate from
 * SettingsViewModel's, so the two features stay independent.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    private val repository: AlarmRepository,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {
    private val kLastSeen = intPreferencesKey("last_seen_version_code")
    private val _state = MutableStateFlow(WhatsNewUiState())
    val state: StateFlow<WhatsNewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val lastSeen = ctx.whatsNewDataStore.data.first()[kLastSeen] ?: 0
            if (lastSeen == 0) {
                // last_seen is unset both for a genuine fresh install AND for anyone
                // upgrading from a version that predates this whole feature (this
                // DataStore file didn't exist yet) — tell them apart by whether any
                // alarm already exists. An existing user gets the full history since
                // we don't know exactly which version they were actually on.
                if (repository.observeAlarms().first().isNotEmpty()) {
                    _state.update { it.copy(entriesToShow = WHATS_NEW_HISTORY) }
                } else {
                    ctx.whatsNewDataStore.edit { it[kLastSeen] = BuildConfig.VERSION_CODE }
                }
            } else if (lastSeen < BuildConfig.VERSION_CODE) {
                _state.update { it.copy(entriesToShow = WHATS_NEW_HISTORY.filter { e -> e.versionCode > lastSeen }) }
            }
        }
    }

    fun dismiss() {
        _state.update { it.copy(entriesToShow = emptyList()) }
        viewModelScope.launch { ctx.whatsNewDataStore.edit { it[kLastSeen] = BuildConfig.VERSION_CODE } }
    }
}
