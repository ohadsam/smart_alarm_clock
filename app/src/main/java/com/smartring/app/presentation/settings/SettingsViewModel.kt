package com.smartring.app.presentation.settings
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import com.smartring.app.data.repository.AlarmDefaults
import com.smartring.app.data.repository.AlarmDefaultsRepository
import com.smartring.app.data.repository.QuickPresetsConfig
import com.smartring.app.data.repository.QuickPresetsRepository
import com.smartring.app.util.QuickPreset
import com.smartring.app.util.QuickPresetLimits
import com.smartring.app.util.nextPresetId
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
data class SettingsUiState(
    val language: String = "he",
    val themeMode: String = "auto",
    /**
     * Whether to warn when another app has an alarm set that will ring before ours.
     * Off by default: it is a niche need (the Shabbat / holiday case), and an app that
     * starts commenting on other apps' alarms uninvited is being nosy.
     */
    val warnForeignAlarms: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val alarmDefaults: AlarmDefaultsRepository,
    private val quickPresets: QuickPresetsRepository,
) : ViewModel() {

    /** The one-tap shortcut chips, and how many of them each surface shows. */
    val quickConfig = quickPresets.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, QuickPresetsConfig())

    fun addPreset(preset: QuickPreset) = viewModelScope.launch {
        quickPresets.updatePresets { it + preset.copy(id = nextPresetId(it)).sanitized() }
    }

    /** Replaces one preset in place, keeping its position in the row. */
    fun updatePreset(preset: QuickPreset) = viewModelScope.launch {
        quickPresets.updatePresets { list ->
            list.map { if (it.id == preset.id) preset.sanitized() else it }
        }
    }

    fun deletePreset(id: Long) = viewModelScope.launch {
        quickPresets.updatePresets { list -> list.filterNot { it.id == id } }
    }

    /**
     * Moves a preset one place up or down.
     *
     * Order is not decoration here: it is half of "which chips are shown", because each
     * surface takes the first N that are marked for it. Moving a preset up is how the
     * user chooses it over another without unticking anything.
     */
    fun movePreset(id: Long, up: Boolean) = viewModelScope.launch {
        quickPresets.updatePresets { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in list.indices) list
            else list.toMutableList().apply { add(j, removeAt(i)) }
        }
    }

    fun updateQuickLimits(transform: (QuickPresetLimits) -> QuickPresetLimits) =
        viewModelScope.launch { quickPresets.updateLimits(transform) }

    fun resetQuickPresets() = viewModelScope.launch { quickPresets.resetAll() }

    /** What a brand-new alarm starts out as; edited from the Settings screen. */
    val defaults = alarmDefaults.defaults
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlarmDefaults.BUILT_IN)

    /**
     * Both "change this default" and "restore this default" go through here, expressed as
     * a copy() at the call site — `{ it.copy(snoozeMinutes = 5) }` versus
     * `{ it.copy(snoozeMinutes = AlarmDefaults.BUILT_IN.snoozeMinutes) }`. Fifteen
     * controls times two operations would otherwise be thirty near-identical methods.
     */
    fun updateDefaults(transform: (AlarmDefaults) -> AlarmDefaults) =
        viewModelScope.launch { alarmDefaults.update(transform) }

    fun resetAllDefaults() = viewModelScope.launch { alarmDefaults.resetAll() }
    private val kLang  = stringPreferencesKey("language")
    private val kTheme = stringPreferencesKey("theme_mode")
    private val kWarnForeign = booleanPreferencesKey("warn_foreign_alarms")

    // catch{} is not optional on a DataStore flow: it surfaces read failures (a
    // corrupted or unreadable preferences file) by throwing into the collector, and
    // this particular collector runs inside MainActivity's setContent to choose the
    // theme — so an unreadable settings file took the whole app down on launch rather
    // than costing the user their theme preference. Falling back to defaults is the
    // documented recovery.
    val state = ctx.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { SettingsUiState(it[kLang] ?: "he", it[kTheme] ?: "auto", it[kWarnForeign] ?: false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    // Writes can fail the same way; a preference that didn't stick is not worth
    // crashing over.
    fun setLanguage(v: String) = edit { it[kLang] = v }
    fun setThemeMode(v: String) = edit { it[kTheme] = v }
    fun setWarnForeignAlarms(v: Boolean) = edit { it[kWarnForeign] = v }

    private fun edit(block: suspend (MutablePreferences) -> Unit) = viewModelScope.launch {
        runCatching { ctx.dataStore.edit(block) }
    }
}
