package com.smartring.app.presentation.intro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.introDataStore: DataStore<Preferences> by preferencesDataStore("intro")

data class IntroUiState(
    val show: Boolean = false,
    /**
     * False until the stored flag has been read.
     *
     * The same role `checked` plays in WhatsNewViewModel, and for the same reason: three
     * things want the screen on launch — What's New, this, and the reliability prompt —
     * and they have to queue rather than race. A consumer cannot tell "no intro needed"
     * from "haven't looked yet" without this.
     */
    val checked: Boolean = false,
)

/**
 * The one-time introduction, shown on a fresh install.
 *
 * Three features in this app are not discoverable by poking at it: an ad-hoc alarm looks
 * like an ordinary one until you notice the date, ring rounds are behind a section most
 * people never open, and Shabbat mode deliberately removes the buttons you would press to
 * find out what it does. Everything else the UI can explain in place; these three were
 * being found by accident or not at all.
 *
 * Deliberately not a multi-page onboarding flow standing between the user and their first
 * alarm. It is one dismissible dialog, and Settings → "הסבר קצר" reopens it, because the
 * moment someone needs the explanation is usually not the moment they first installed.
 */
@HiltViewModel
class IntroViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val kSeen = booleanPreferencesKey("intro_seen")
    private val _state = MutableStateFlow(IntroUiState())
    val state: StateFlow<IntroUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Best-effort throughout, like WhatsNewViewModel: this is a dialog, and an
            // unreadable preferences file must not take the app down on launch nor leave
            // `checked` false forever, which would block the reliability prompt behind it.
            // Failing closed costs one explanation; failing open costs the app.
            val seen = runCatching { ctx.introDataStore.data.first()[kSeen] }.getOrNull() ?: false
            _state.update { it.copy(show = !seen, checked = true) }
        }
    }

    fun dismiss() {
        _state.update { it.copy(show = false) }
        viewModelScope.launch {
            runCatching { ctx.introDataStore.edit { it[kSeen] = true } }
        }
    }
}
