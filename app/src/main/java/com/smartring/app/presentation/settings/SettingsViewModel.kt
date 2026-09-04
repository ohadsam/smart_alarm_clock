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
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
data class SettingsUiState(val language: String = "he", val themeMode: String = "auto")

@HiltViewModel
class SettingsViewModel @Inject constructor(@ApplicationContext private val ctx: Context) : ViewModel() {
    private val kLang  = stringPreferencesKey("language")
    private val kTheme = stringPreferencesKey("theme_mode")

    val state = ctx.dataStore.data.map { SettingsUiState(it[kLang] ?: "he", it[kTheme] ?: "auto") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setLanguage(v: String) = viewModelScope.launch { ctx.dataStore.edit { it[kLang] = v } }
    fun setThemeMode(v: String) = viewModelScope.launch { ctx.dataStore.edit { it[kTheme] = v } }
}
