package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.playback.SpatializationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val SPATIAL_KEY_PREFIX = "lw_spatial_track_"
private const val SPATIAL_KEY_MAX_LENGTH = 160

/**
 * Per-song spatialization (8D / 16D) memory. Every stable track key stores
 * the mode the listener left it on, so the now-playing toggle comes back
 * when that song plays again.
 */
@Singleton
class SpatializerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun modeFor(trackKey: String): Flow<SpatializationMode> = dataStore.data.map { prefs ->
        decode(prefs[preferenceKey(trackKey)])
    }

    suspend fun setMode(trackKey: String, mode: SpatializationMode) {
        dataStore.edit { prefs ->
            prefs[preferenceKey(trackKey)] = mode.name
        }
    }

    private fun preferenceKey(trackKey: String) =
        stringPreferencesKey(SPATIAL_KEY_PREFIX + trackKey.take(SPATIAL_KEY_MAX_LENGTH))

    private fun decode(stored: String?): SpatializationMode = when (stored) {
        SpatializationMode.D8.name -> SpatializationMode.D8
        SpatializationMode.D16.name -> SpatializationMode.D16
        else -> SpatializationMode.OFF
    }
}
