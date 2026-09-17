package com.smartring.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartring.app.domain.model.VibrationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a brand-new alarm starts out as.
 *
 * Every one of these was a constant compiled into `AlarmEditUiState`, which is fine until
 * someone's alarms are all 40 seconds long with vibration off — then every new alarm has
 * to be corrected by hand, every time. [BUILT_IN] keeps the original constants so any
 * single value can be put back without disturbing the rest.
 */
data class AlarmDefaults(
    val hour: Int = 7,
    val minute: Int = 0,
    val unnamed: Boolean = false,
    val ringDurationSeconds: Int = 60,
    val ringVolumePercent: Int = 100,
    val snoozeEnabled: Boolean = false,
    val snoozeMinutes: Int = 10,
    val snoozeMaxCount: Int = 3,
    val vibrationMode: VibrationMode = VibrationMode.SOUND_AND_VIBRATION,
    val vibrationOnlySeconds: Int = 10,
    val crescendoEnabled: Boolean = false,
    val crescendoStartVolume: Int = 10,
    val crescendoStepSeconds: Int = 15,
    val crescendoStepPercent: Int = 10,
    val isShabbatMode: Boolean = false,
) {
    companion object {
        /**
         * The app's own defaults, as shipped. Held as a value rather than only as
         * constructor defaults so "restore this one control" is a `copy()` at the call
         * site instead of fifteen reset methods on the repository.
         */
        val BUILT_IN = AlarmDefaults()
    }
}

private val Context.alarmDefaultsStore: DataStore<Preferences> by
    preferencesDataStore("alarm_defaults")

@Singleton
class AlarmDefaultsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val kHour = intPreferencesKey("hour")
    private val kMinute = intPreferencesKey("minute")
    private val kUnnamed = booleanPreferencesKey("unnamed")
    private val kRingDuration = intPreferencesKey("ring_duration_seconds")
    private val kRingVolume = intPreferencesKey("ring_volume_percent")
    private val kSnoozeEnabled = booleanPreferencesKey("snooze_enabled")
    private val kSnoozeMinutes = intPreferencesKey("snooze_minutes")
    private val kSnoozeMax = intPreferencesKey("snooze_max_count")
    private val kVibrationMode = stringPreferencesKey("vibration_mode")
    private val kVibrationOnly = intPreferencesKey("vibration_only_seconds")
    private val kCrescendoEnabled = booleanPreferencesKey("crescendo_enabled")
    private val kCrescendoStart = intPreferencesKey("crescendo_start_volume")
    private val kCrescendoStep = intPreferencesKey("crescendo_step_seconds")
    private val kCrescendoPercent = intPreferencesKey("crescendo_step_percent")
    private val kShabbat = booleanPreferencesKey("shabbat_mode")

    /**
     * The same `catch` as every other DataStore flow in this app, and for the same
     * reason: an unreadable preferences file must cost the user their defaults, not the
     * ability to open the app.
     */
    val defaults: Flow<AlarmDefaults> = ctx.alarmDefaultsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val b = AlarmDefaults.BUILT_IN
            AlarmDefaults(
                hour = p[kHour] ?: b.hour,
                minute = p[kMinute] ?: b.minute,
                unnamed = p[kUnnamed] ?: b.unnamed,
                ringDurationSeconds = p[kRingDuration] ?: b.ringDurationSeconds,
                ringVolumePercent = p[kRingVolume] ?: b.ringVolumePercent,
                snoozeEnabled = p[kSnoozeEnabled] ?: b.snoozeEnabled,
                snoozeMinutes = p[kSnoozeMinutes] ?: b.snoozeMinutes,
                snoozeMaxCount = p[kSnoozeMax] ?: b.snoozeMaxCount,
                // An unknown stored name falls back rather than throwing: the enum can
                // lose a constant across versions, and a stale preference must not be
                // able to crash the editor on open.
                vibrationMode = p[kVibrationMode]
                    ?.let { name -> VibrationMode.entries.firstOrNull { it.name == name } }
                    ?: b.vibrationMode,
                vibrationOnlySeconds = p[kVibrationOnly] ?: b.vibrationOnlySeconds,
                crescendoEnabled = p[kCrescendoEnabled] ?: b.crescendoEnabled,
                crescendoStartVolume = p[kCrescendoStart] ?: b.crescendoStartVolume,
                crescendoStepSeconds = p[kCrescendoStep] ?: b.crescendoStepSeconds,
                crescendoStepPercent = p[kCrescendoPercent] ?: b.crescendoStepPercent,
                isShabbatMode = p[kShabbat] ?: b.isShabbatMode,
            )
        }

    /**
     * Writes the whole set.
     *
     * One method instead of a setter per field, because the screen expresses both editing
     * and restoring as a `copy()`: `update { it.copy(snoozeMinutes = 5) }` to change one,
     * `update { it.copy(snoozeMinutes = BUILT_IN.snoozeMinutes) }` to put it back. That
     * keeps "restore just this control" from needing its own parallel API.
     */
    suspend fun update(transform: (AlarmDefaults) -> AlarmDefaults) {
        runCatching {
            ctx.alarmDefaultsStore.edit { p ->
                val current = AlarmDefaults(
                    hour = p[kHour] ?: AlarmDefaults.BUILT_IN.hour,
                    minute = p[kMinute] ?: AlarmDefaults.BUILT_IN.minute,
                    unnamed = p[kUnnamed] ?: AlarmDefaults.BUILT_IN.unnamed,
                    ringDurationSeconds = p[kRingDuration] ?: AlarmDefaults.BUILT_IN.ringDurationSeconds,
                    ringVolumePercent = p[kRingVolume] ?: AlarmDefaults.BUILT_IN.ringVolumePercent,
                    snoozeEnabled = p[kSnoozeEnabled] ?: AlarmDefaults.BUILT_IN.snoozeEnabled,
                    snoozeMinutes = p[kSnoozeMinutes] ?: AlarmDefaults.BUILT_IN.snoozeMinutes,
                    snoozeMaxCount = p[kSnoozeMax] ?: AlarmDefaults.BUILT_IN.snoozeMaxCount,
                    vibrationMode = p[kVibrationMode]
                        ?.let { name -> VibrationMode.entries.firstOrNull { it.name == name } }
                        ?: AlarmDefaults.BUILT_IN.vibrationMode,
                    vibrationOnlySeconds = p[kVibrationOnly] ?: AlarmDefaults.BUILT_IN.vibrationOnlySeconds,
                    crescendoEnabled = p[kCrescendoEnabled] ?: AlarmDefaults.BUILT_IN.crescendoEnabled,
                    crescendoStartVolume = p[kCrescendoStart] ?: AlarmDefaults.BUILT_IN.crescendoStartVolume,
                    crescendoStepSeconds = p[kCrescendoStep] ?: AlarmDefaults.BUILT_IN.crescendoStepSeconds,
                    crescendoStepPercent = p[kCrescendoPercent] ?: AlarmDefaults.BUILT_IN.crescendoStepPercent,
                    isShabbatMode = p[kShabbat] ?: AlarmDefaults.BUILT_IN.isShabbatMode,
                )
                val next = transform(current)
                p[kHour] = next.hour
                p[kMinute] = next.minute
                p[kUnnamed] = next.unnamed
                p[kRingDuration] = next.ringDurationSeconds
                p[kRingVolume] = next.ringVolumePercent
                p[kSnoozeEnabled] = next.snoozeEnabled
                p[kSnoozeMinutes] = next.snoozeMinutes
                p[kSnoozeMax] = next.snoozeMaxCount
                p[kVibrationMode] = next.vibrationMode.name
                p[kVibrationOnly] = next.vibrationOnlySeconds
                p[kCrescendoEnabled] = next.crescendoEnabled
                p[kCrescendoStart] = next.crescendoStartVolume
                p[kCrescendoStep] = next.crescendoStepSeconds
                p[kCrescendoPercent] = next.crescendoStepPercent
                p[kShabbat] = next.isShabbatMode
            }
        }
    }

    /** Puts every default back to the shipped value. */
    suspend fun resetAll() = update { AlarmDefaults.BUILT_IN }
}
