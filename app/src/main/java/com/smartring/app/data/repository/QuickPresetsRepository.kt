package com.smartring.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartring.app.util.BUILT_IN_QUICK_PRESETS
import com.smartring.app.util.QuickPreset
import com.smartring.app.util.QuickPresetLimits
import com.smartring.app.util.decodeQuickPresets
import com.smartring.app.util.encodeQuickPresets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.quickPresetsStore: DataStore<Preferences> by preferencesDataStore("quick_presets")

/** The configured shortcuts and how many of them each surface shows. */
data class QuickPresetsConfig(
    val presets: List<QuickPreset> = BUILT_IN_QUICK_PRESETS,
    val limits: QuickPresetLimits = QuickPresetLimits.BUILT_IN,
)

/**
 * Where the one-tap shortcuts live.
 *
 * Its own DataStore file rather than a column in `alarm_defaults`: the defaults describe
 * what a *new alarm* looks like, while these describe what the *shortcut row* looks like —
 * different lifetimes, different reset semantics ("restore this default" should not wipe
 * someone's shortcuts), and a list does not belong wedged into a record of scalars.
 *
 * The `catch` is the same one every DataStore flow in this app carries: an unreadable
 * preferences file costs the user their shortcuts, not the ability to open the app.
 */
@Singleton
class QuickPresetsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val kPresets = stringPreferencesKey("presets")
    private val kMaxInApp = intPreferencesKey("max_in_app")
    private val kMaxInWidget = intPreferencesKey("max_in_widget")

    val config: Flow<QuickPresetsConfig> = ctx.quickPresetsStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            QuickPresetsConfig(
                presets = decodeQuickPresets(p[kPresets]),
                limits = QuickPresetLimits(
                    maxInApp = p[kMaxInApp] ?: QuickPresetLimits.BUILT_IN.maxInApp,
                    maxInWidget = p[kMaxInWidget] ?: QuickPresetLimits.BUILT_IN.maxInWidget,
                ).sanitized(),
            )
        }

    /**
     * Read-modify-write inside one `edit`, so two rapid taps cannot lose one another's
     * change — a plain "read the flow, then write" would let the second overwrite the
     * first with a value it read before the first landed.
     */
    suspend fun updatePresets(transform: (List<QuickPreset>) -> List<QuickPreset>) {
        runCatching {
            ctx.quickPresetsStore.edit { p ->
                p[kPresets] = encodeQuickPresets(transform(decodeQuickPresets(p[kPresets])))
            }
        }
    }

    suspend fun updateLimits(transform: (QuickPresetLimits) -> QuickPresetLimits) {
        runCatching {
            ctx.quickPresetsStore.edit { p ->
                val current = QuickPresetLimits(
                    maxInApp = p[kMaxInApp] ?: QuickPresetLimits.BUILT_IN.maxInApp,
                    maxInWidget = p[kMaxInWidget] ?: QuickPresetLimits.BUILT_IN.maxInWidget,
                )
                val next = transform(current).sanitized()
                p[kMaxInApp] = next.maxInApp
                p[kMaxInWidget] = next.maxInWidget
            }
        }
    }

    suspend fun resetAll() {
        runCatching {
            ctx.quickPresetsStore.edit { p ->
                p[kPresets] = encodeQuickPresets(BUILT_IN_QUICK_PRESETS)
                p[kMaxInApp] = QuickPresetLimits.BUILT_IN.maxInApp
                p[kMaxInWidget] = QuickPresetLimits.BUILT_IN.maxInWidget
            }
        }
    }
}
