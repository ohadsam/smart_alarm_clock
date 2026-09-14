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

data class WhatsNewUiState(
    val entriesToShow: List<WhatsNewEntry> = emptyList(),
    // False until the async fresh-install-vs-upgrade determination below completes —
    // lets other on-launch prompts (ReliabilityGate in AlarmListScreen) tell "nothing
    // to show" apart from "haven't checked yet" instead of racing this ViewModel's
    // init and possibly firing before a real What's New would have shown.
    val checked: Boolean = false,
)

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
            // Everything in here is best-effort: this feature is a dialog. An
            // unreadable DataStore file or a database error must not take the app down
            // on launch, and — just as important — must not leave `checked` false
            // forever, since AlarmListScreen's ReliabilityGate waits on it before
            // offering its own prompt. Failing closed (show nothing, mark checked)
            // costs one "what's new" dialog; failing open costs the app.
            val lastSeen = runCatching { ctx.whatsNewDataStore.data.first()[kLastSeen] }
                .getOrNull() ?: 0
            // The alarms lookup only matters for the lastSeen == 0 case (see
            // whatsNewEntriesFor), so it isn't worth a DB read on every other launch.
            val hasExistingAlarms = lastSeen == 0 &&
                runCatching { repository.observeAlarms().first().isNotEmpty() }.getOrDefault(false)
            val entries = whatsNewEntriesFor(lastSeen, BuildConfig.VERSION_CODE, hasExistingAlarms)
            // Nothing to show on a fresh install, so record the current version straight
            // away — otherwise the *next* update would look like "upgrading from
            // nothing" all over again and replay the entire history.
            if (entries.isEmpty() && lastSeen == 0) markSeen()
            _state.update { it.copy(entriesToShow = entries, checked = true) }
        }
    }

    fun dismiss() {
        _state.update { it.copy(entriesToShow = emptyList()) }
        viewModelScope.launch { markSeen() }
    }

    private suspend fun markSeen() {
        runCatching { ctx.whatsNewDataStore.edit { it[kLastSeen] = BuildConfig.VERSION_CODE } }
    }
}
