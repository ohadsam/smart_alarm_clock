package com.smartring.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartring.app.data.template.TemplateJson
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.LibraryResult
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.TemplateOps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.templateStore: DataStore<Preferences> by preferencesDataStore("alarm_templates")

/**
 * Where saved alarm templates and their folders live.
 *
 * One JSON document in a DataStore file of its own, rather than Room tables: a template is read and
 * written whole, there are at most a couple of hundred, and a read-modify-write inside
 * `DataStore.edit` is atomic — so a folder move and a rename can never interleave into half of each.
 * Every rule (limits, what a deleted folder does to its templates) lives in the pure [TemplateOps];
 * this class only persists the outcome.
 */
@Singleton
class TemplateRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val key = stringPreferencesKey("library")

    val library: Flow<TemplateLibrary> = ctx.templateStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { TemplateJson.decode(it[key]) }

    suspend fun current(): TemplateLibrary = library.first()

    /** Applies [op] to the stored library atomically; the result is returned whether or not it changed anything. */
    private suspend fun mutate(op: (TemplateLibrary) -> LibraryResult): LibraryResult {
        var result: LibraryResult = LibraryResult.Refused(com.smartring.app.domain.model.LibraryError.NOT_FOUND)
        ctx.templateStore.edit { prefs ->
            val outcome = op(TemplateJson.decode(prefs[key]))
            result = outcome
            if (outcome is LibraryResult.Ok) prefs[key] = TemplateJson.encode(outcome.library)
        }
        return result
    }

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    suspend fun saveNew(name: String, folderId: String?, alarm: Alarm) =
        mutate { TemplateOps.addTemplate(it, newId(), name, folderId, alarm, now()) }

    suspend fun update(id: String, name: String?, alarm: Alarm?) =
        mutate { TemplateOps.updateTemplate(it, id, name, alarm, now()) }

    suspend fun rename(id: String, name: String) = mutate { TemplateOps.renameTemplate(it, id, name, now()) }
    suspend fun move(id: String, folderId: String?) = mutate { TemplateOps.moveTemplate(it, id, folderId, now()) }
    suspend fun duplicate(id: String) = mutate { TemplateOps.duplicateTemplate(it, id, newId(), now()) }
    suspend fun delete(id: String) = mutate { TemplateOps.deleteTemplate(it, id) }

    suspend fun addFolder(name: String) = mutate { TemplateOps.addFolder(it, newId(), name, now()) }
    suspend fun renameFolder(id: String, name: String) = mutate { TemplateOps.renameFolder(it, id, name) }
    suspend fun deleteFolder(id: String, deleteContents: Boolean) = mutate { TemplateOps.deleteFolder(it, id, deleteContents) }
}
