package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val model: String = "gemini-2.0-flash",
) {
    val isUsable: Boolean get() = enabled && apiKey.isNotBlank()
}

@Singleton
class AiPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_aiEnabled")
        val PROVIDER = stringPreferencesKey("lw_aiProvider")
        val API_KEY = stringPreferencesKey("lw_aiApiKey")
        val MODEL = stringPreferencesKey("lw_aiModel")
    }

    val settings: Flow<AiSettings> = dataStore.data.map { prefs ->
        AiSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            provider = prefs[Keys.PROVIDER] ?: "gemini",
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL] ?: "gemini-2.0-flash",
        )
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
        dataStore.edit { it[Keys.MODEL] = model.trim().ifBlank { "gemini-2.0-flash" } }
    }
}
