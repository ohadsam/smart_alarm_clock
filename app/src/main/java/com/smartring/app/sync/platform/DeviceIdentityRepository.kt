package com.smartring.app.sync.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartring.app.sync.LocalIdentity
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.isValidSyncId
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.cleanText
import com.smartring.app.sync.toHex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceIdentityStore: DataStore<Preferences> by preferencesDataStore("device_identity")

/**
 * Who this phone is, to the phones it has paired with.
 *
 *  - **The id** is 16 random bytes, generated once and never changed. It is what a pairing is
 *    recorded against, so it must not be something an outsider could also know or choose: not the
 *    Bluetooth address, not the Android id, not the model.
 *  - **The name** is what people see — "Mum's phone" — and what the allow-list is keyed on in the
 *    UI. Editable; cleaned to the length and character rules every name from another phone is held
 *    to, so the same name cannot display one way here and another there.
 *  - **Listening** is whether this phone keeps a background connection open to receive alarms. It is
 *    a setting because it costs a persistent notification and some battery, and a phone that only
 *    ever *sends* (a parent's) should be able to say no.
 */
@Singleton
class DeviceIdentityRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val kId = stringPreferencesKey("device_id")
    private val kName = stringPreferencesKey("device_name")
    private val kListen = booleanPreferencesKey("listen_in_background")

    private val data: Flow<Preferences> = ctx.deviceIdentityStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** The identity, creating and persisting the id on first use. */
    suspend fun current(): LocalIdentity {
        var id: String? = null
        ctx.deviceIdentityStore.edit { p ->
            val existing = p[kId]
            id = if (isValidSyncId(existing)) existing else Crypto.randomBytes(Proto.ID_BYTES).toHex().also { p[kId] = it }
        }
        return LocalIdentity(id!!, name.first())
    }

    val name: Flow<String> = data.map { p -> p[kName]?.let { cleanName(it) }?.takeIf { it.isNotEmpty() } ?: defaultName() }

    val listenInBackground: Flow<Boolean> = data.map { it[kListen] ?: true }

    suspend fun listenInBackgroundNow(): Boolean = listenInBackground.first()

    suspend fun setName(newName: String) {
        val cleaned = cleanName(newName)
        if (cleaned.isEmpty()) return
        ctx.deviceIdentityStore.edit { it[kName] = cleaned }
    }

    suspend fun setListenInBackground(enabled: Boolean) {
        ctx.deviceIdentityStore.edit { it[kListen] = enabled }
    }

    private fun cleanName(raw: String) = cleanText(raw, Proto.DEVICE_NAME_MAX_CHARS)

    /** What the user already calls their phone, if the system knows; otherwise the model. */
    private fun defaultName(): String {
        val system = runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return cleanName(system ?: "").ifEmpty { cleanName(Build.MODEL ?: "") }.ifEmpty { "מכשיר" }
    }
}
