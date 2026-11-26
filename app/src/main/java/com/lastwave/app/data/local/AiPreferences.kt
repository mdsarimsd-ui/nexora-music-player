package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings for the optional on-device AI assistant (Settings -> AI).
 * The user pastes their OWN API key; it is stored only in this app's
 * DataStore and is sent exclusively to the chosen AI provider's endpoint.
 */
data class AiSettings(
    val enabled: Boolean = false,
    /** Only "gemini" is wired for now; kept as a string for future providers. */
    val provider: String = "gemini",
    val apiKey: String = "",
    val model: String = "gemini-3.8-flash",
) {
    val isUsable: Boolean get() = enabled && apiKey.isNotBlank()
}

/** One stored assistant turn (playlist cards restore as text only). */
@Serializable
data class AiStoredMessage(
    val fromUser: Boolean = false,
    val text: String = "",
    val isError: Boolean = false,
)

/** One saved assistant conversation, selectable from history. */
@Serializable
data class AiSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val updatedAt: Long = 0L,
    val messages: List<AiStoredMessage> = emptyList(),
)

@Singleton
class AiPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_aiEnabled")
        val PROVIDER = stringPreferencesKey("lw_aiProvider")
        val API_KEY = stringPreferencesKey("lw_aiApiKey")
        val MODEL = stringPreferencesKey("lw_aiModel")
        val SESSIONS = stringPreferencesKey("lw_aiSessions")
    }

    val settings: Flow<AiSettings> = dataStore.data.map { prefs ->
        AiSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            provider = prefs[Keys.PROVIDER] ?: "gemini",
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL]?.takeIf { it != "gemini-2.0-flash" } ?: "gemini-3.8-flash",
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Saved assistant conversations, newest first after sorting in the UI. */
    val sessions: Flow<List<AiSession>> = dataStore.data.map { prefs ->
        runCatching {
            prefs[Keys.SESSIONS]?.let { json.decodeFromString<List<AiSession>>(it) }
        }.getOrNull().orEmpty()
    }

    suspend fun saveSessions(list: List<AiSession>) {
        dataStore.edit { it[Keys.SESSIONS] = json.encodeToString(list) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setProvider(provider: String) {
        dataStore.edit { it[Keys.PROVIDER] = provider.trim() }
    }

    suspend fun setApiKey(apiKey: String) {
        dataStore.edit { it[Keys.API_KEY] = apiKey.trim() }
    }

    suspend fun setModel(model: String) {
        dataStore.edit { it[Keys.MODEL] = model.trim().ifBlank { "gemini-3.8-flash" } }
    }
}
