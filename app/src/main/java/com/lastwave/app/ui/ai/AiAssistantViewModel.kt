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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.lastwave.app.data.local.AiSession
import com.lastwave.app.data.local.AiStoredMessage
import javax.inject.Inject

/** One turn in the assistant transcript. */
data class AiChatMessage(
    val fromUser: Boolean,
    val text: String,
    val playlist: AiPlaylistSuggestion? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val aiAssistantRepository: AiAssistantRepository,
) : ViewModel() {

    val settings: StateFlow<AiSettings> = aiPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings())

    /** True once the first real saved-settings value has arrived. */
    val settingsReady: StateFlow<Boolean> = aiPreferences.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _chat = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val chat: StateFlow<List<AiChatMessage>> = _chat

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Saved conversations, newest first; shown as history chips in the sheet. */
    val sessions: StateFlow<List<AiSession>> = aiPreferences.sessions
        .map { it.sortedByDescending { s -> s.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Id of the conversation new messages are appended to; null = fresh chat. */
    private var activeSessionId: String? = null

    /** Writes the current transcript into the active session and persists it. */
    private fun persistChat() {
        val msgs = _chat.value
        if (msgs.isEmpty()) return
        val id = activeSessionId
            ?: java.util.UUID.randomUUID().toString().also { activeSessionId = it }
        val title = msgs.firstOrNull { it.fromUser }?.text?.take(48)?.trim().takeIf { t -> !t.isNullOrBlank() } ?: "Chat"
        val stored = msgs.map { AiStoredMessage(fromUser = it.fromUser, text = it.text, isError = it.isError) }
        val session = AiSession(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = stored,
        )
        viewModelScope.launch {
            val existing = aiPreferences.sessions.first().filterNot { it.id == id }
            aiPreferences.saveSessions((listOf(session) + existing).take(30))
        }
    }

    /** Start a fresh conversation; the old one stays in history. */
    fun newChat() {
        activeSessionId = null
        _chat.value = emptyList()
    }

    /** Reopen a saved conversation; new replies continue it. */
    fun openSession(id: String) {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        activeSessionId = id
        _chat.value = session.messages.map {
            AiChatMessage(fromUser = it.fromUser, text = it.text, isError = it.isError)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            val next = aiPreferences.sessions.first().filterNot { it.id == id }
            aiPreferences.saveSessions(next)
            if (activeSessionId == id) {
                activeSessionId = null
                _chat.value = emptyList()
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { aiPreferences.setEnabled(enabled) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { aiPreferences.setApiKey(key) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { aiPreferences.setModel(model) }
    }

    private fun append(message: AiChatMessage) {
        _chat.value = _chat.value + message
    }

    fun buildPlaylist(request: String) {
        if (request.isBlank() || _busy.value) return
        append(AiChatMessage(fromUser = true, text = request))
        _busy.value = true
        viewModelScope.launch {
            aiAssistantRepository.suggestPlaylist(request).fold(
                onSuccess = {
                    append(
                        AiChatMessage(
                            fromUser = false,
                            text = it.note.ifBlank { "Here's a playlist for that." },
                            playlist = it,
                        ),
                    )
                },
                onFailure = {
                    append(
                        AiChatMessage(
                            fromUser = false,
                            text = it.message ?: "Something went wrong",
                            isError = true,
                        ),
                    )
                },
            )
            persistChat()
            _busy.value = false
        }
    }

    fun diagnose() {
        if (_busy.value) return
        append(AiChatMessage(fromUser = true, text = "Why did songs skip?"))
        _busy.value = true
        viewModelScope.launch {
            aiAssistantRepository.diagnosePlayback().fold(
                onSuccess = { append(AiChatMessage(fromUser = false, text = it)) },
                onFailure = {
                    append(
                        AiChatMessage(
                            fromUser = false,
                            text = it.message ?: "Something went wrong",
                            isError = true,
                        ),
                    )
                },
            )
            persistChat()
            _busy.value = false
        }
    }

    fun ask(prompt: String) {
        if (prompt.isBlank() || _busy.value) return
        append(AiChatMessage(fromUser = true, text = prompt))
        _busy.value = true
        viewModelScope.launch {
            aiAssistantRepository.ask(prompt).fold(
                onSuccess = { append(AiChatMessage(fromUser = false, text = it)) },
                onFailure = {
                    append(
                        AiChatMessage(
                            fromUser = false,
                            text = it.message ?: "Something went wrong",
                            isError = true,
                        ),
                    )
                },
            )
            persistChat()
            _busy.value = false
        }
    }

    fun savePlaylist(suggestion: AiPlaylistSuggestion) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            aiAssistantRepository.saveSuggestedPlaylist(suggestion).fold(
                onSuccess = {
                    append(AiChatMessage(fromUser = false, text = "Saved \"${suggestion.name}\" to your playlists."))
                },
                onFailure = {
                    append(
                        AiChatMessage(
                            fromUser = false,
                            text = it.message ?: "Could not save the playlist",
                            isError = true,
                        ),
                    )
                },
            )
            persistChat()
            _busy.value = false
        }
    }
}
