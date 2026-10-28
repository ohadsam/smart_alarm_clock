package com.smartring.app.presentation.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartring.app.data.repository.TemplateRepository
import com.smartring.app.domain.model.AlarmTemplate
import com.smartring.app.domain.model.LibraryError
import com.smartring.app.domain.model.LibraryResult
import com.smartring.app.domain.model.TemplateLibrary
import com.smartring.app.domain.model.TemplateLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line describing a template: when it rings and on which days. */
fun AlarmTemplate.summary(): String = "${alarm.timeFormatted} · ${alarm.scheduleSummary()}"

/** The template library and everything the management screen does to it. */
@HiltViewModel
class TemplatesViewModel @Inject constructor(private val repository: TemplateRepository) : ViewModel() {

    val library: StateFlow<TemplateLibrary> =
        repository.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateLibrary())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun report(r: LibraryResult, ok: String? = null) {
        when (r) {
            is LibraryResult.Ok -> ok?.let { _messages.tryEmit(it) }
            is LibraryResult.Refused -> _messages.tryEmit(
                when (r.error) {
                    LibraryError.LIMIT_TEMPLATES -> "יש כבר ${TemplateLimits.MAX_TEMPLATES} תבניות — מחקו כמה כדי להוסיף"
                    LibraryError.LIMIT_FOLDERS -> "יש כבר ${TemplateLimits.MAX_FOLDERS} תיקיות"
                    LibraryError.NOT_FOUND -> "הפריט כבר לא קיים"
                    LibraryError.BLANK_NAME -> "נדרש שם"
                }
            )
        }
    }

    fun rename(id: String, name: String) = viewModelScope.launch { report(repository.rename(id, name)) }
    fun move(id: String, folderId: String?) = viewModelScope.launch { report(repository.move(id, folderId), "הועבר") }
    fun duplicate(id: String) = viewModelScope.launch { report(repository.duplicate(id), "נוצר עותק") }
    fun delete(id: String) = viewModelScope.launch { report(repository.delete(id), "התבנית נמחקה") }
    fun addFolder(name: String) = viewModelScope.launch { report(repository.addFolder(name)) }
    fun renameFolder(id: String, name: String) = viewModelScope.launch { report(repository.renameFolder(id, name)) }
    fun deleteFolder(id: String, deleteContents: Boolean) = viewModelScope.launch {
        report(repository.deleteFolder(id, deleteContents), if (deleteContents) "התיקייה והתבניות שבה נמחקו" else "התיקייה נמחקה; התבניות הועברו לראשי")
    }
}
