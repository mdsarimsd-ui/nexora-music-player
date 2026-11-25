package com.lastwave.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.ai.AiAssistantRepository
import com.lastwave.app.data.ai.AiPlaylistSuggestion
import com.lastwave.app.data.local.AiPreferences
import com.lastwave.app.data.local.AiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Working : AiUiState
    data class Answer(val text: String) : AiUiState
    data class Playlist(val suggestion: AiPlaylistSuggestion) : AiUiState
    data class Error(val message: String) : AiUiState
    data class Saved(val name: String) : AiUiState
}

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val aiAssistantRepository: AiAssistantRepository,
) : ViewModel() {

    val settings: StateFlow<AiSettings> = aiPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings())

    private val _ui = MutableStateFlow<AiUiState>(AiUiState.Idle as AiUiState)
    val ui: StateFlow<AiUiState> = _ui

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { aiPreferences.setEnabled(enabled) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { aiPreferences.setApiKey(key) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { aiPreferences.setModel(model) }
    }

    fun saveApiKey() {
        // The sheet writes on each change; kept as a hook for future "save" UX.
    }

    fun buildPlaylist(request: String) {
        if (request.isBlank()) return
        _ui.value = AiUiState.Working
        viewModelScope.launch {
            aiAssistantRepository.suggestPlaylist(request).fold(
                onSuccess = { _ui.value = AiUiState.Playlist(it) },
                onFailure = { _ui.value = AiUiState.Error(it.message ?: "Something went wrong") },
            )
        }
    }

    fun diagnose() {
        _ui.value = AiUiState.Working
        viewModelScope.launch {
            aiAssistantRepository.diagnosePlayback().fold(
                onSuccess = { _ui.value = AiUiState.Answer(it) },
                onFailure = { _ui.value = AiUiState.Error(it.message ?: "Something went wrong") },
            )
        }
    }

    fun ask(prompt: String) {
        if (prompt.isBlank()) return
        _ui.value = AiUiState.Working
        viewModelScope.launch {
            aiAssistantRepository.ask(prompt).fold(
                onSuccess = { _ui.value = AiUiState.Answer(it) },
                onFailure = { _ui.value = AiUiState.Error(it.message ?: "Something went wrong") },
            )
        }
    }

    fun savePlaylist(suggestion: AiPlaylistSuggestion) {
        _ui.value = AiUiState.Working
        viewModelScope.launch {
            aiAssistantRepository.saveSuggestedPlaylist(suggestion).fold(
                onSuccess = { _ui.value = AiUiState.Saved(suggestion.name) },
                onFailure = { _ui.value = AiUiState.Error(it.message ?: "Could not save the playlist") },
            )
        }
    }

    fun reset() {
        _ui.value = AiUiState.Idle
    }
}
