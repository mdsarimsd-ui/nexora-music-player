package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.playback.SpatialPath
import com.lastwave.app.playback.SpatializationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val SPATIAL_KEY_PREFIX = "lw_spatial_track_"
private const val SPATIAL_PATH_PREFIX = "lw_spatial_path_"
private const val SPATIAL_SPEED_PREFIX = "lw_spatial_speed_"
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

    /** Movement path (Classic / Orbit / Figure 8) saved under the same key space. */
    fun pathFor(trackKey: String): Flow<SpatialPath> = dataStore.data.map { prefs ->
        when (prefs[pathPreferenceKey(trackKey)]) {
            SpatialPath.ORBIT.name -> SpatialPath.ORBIT
            SpatialPath.FIGURE8.name -> SpatialPath.FIGURE8
            else -> SpatialPath.CLASSIC
        }
    }

    suspend fun setPath(trackKey: String, path: SpatialPath) {
        dataStore.edit { prefs ->
            prefs[pathPreferenceKey(trackKey)] = path.name
        }
    }

    /** Rotation / swing speed multiplier (0.5x slow .. 2x fast), default 1. */
    fun speedFor(trackKey: String): Flow<Float> = dataStore.data.map { prefs ->
        prefs[speedPreferenceKey(trackKey)]?.coerceIn(0.5f, 2f) ?: 1f
    }

    suspend fun setSpeed(trackKey: String, speed: Float) {
        dataStore.edit { prefs ->
            prefs[speedPreferenceKey(trackKey)] = speed.coerceIn(0.5f, 2f)
        }
    }

    private fun preferenceKey(trackKey: String) =
        stringPreferencesKey(SPATIAL_KEY_PREFIX + trackKey.take(SPATIAL_KEY_MAX_LENGTH))

    private fun pathPreferenceKey(trackKey: String) =
        stringPreferencesKey(SPATIAL_PATH_PREFIX + trackKey.take(SPATIAL_KEY_MAX_LENGTH))

    private fun speedPreferenceKey(trackKey: String) =
        floatPreferencesKey(SPATIAL_SPEED_PREFIX + trackKey.take(SPATIAL_KEY_MAX_LENGTH))

    private fun decode(stored: String?): SpatializationMode = when (stored) {
        SpatializationMode.D8.name -> SpatializationMode.D8
        SpatializationMode.D16.name -> SpatializationMode.D16
        else -> SpatializationMode.OFF
    }
}
