package com.smartring.app.presentation.settings
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
data class SettingsUiState(val language: String = "he", val themeMode: String = "auto")

@HiltViewModel
class SettingsViewModel @Inject constructor(@ApplicationContext private val ctx: Context) : ViewModel() {
    private val kLang  = stringPreferencesKey("language")
    private val kTheme = stringPreferencesKey("theme_mode")

    // catch{} is not optional on a DataStore flow: it surfaces read failures (a
    // corrupted or unreadable preferences file) by throwing into the collector, and
    // this particular collector runs inside MainActivity's setContent to choose the
    // theme — so an unreadable settings file took the whole app down on launch rather
    // than costing the user their theme preference. Falling back to defaults is the
    // documented recovery.
    val state = ctx.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { SettingsUiState(it[kLang] ?: "he", it[kTheme] ?: "auto") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    // Writes can fail the same way; a preference that didn't stick is not worth
    // crashing over.
    fun setLanguage(v: String) = edit { it[kLang] = v }
    fun setThemeMode(v: String) = edit { it[kTheme] = v }

    private fun edit(block: suspend (MutablePreferences) -> Unit) = viewModelScope.launch {
        runCatching { ctx.dataStore.edit(block) }
    }
}
