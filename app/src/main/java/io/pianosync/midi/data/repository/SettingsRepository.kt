package io.pianosync.midi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.pianosync.midi.data.model.AppSettings
import io.pianosync.midi.data.model.DifficultyLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    private object PreferenceKeys {
        val DIFFICULTY_LEVEL = stringPreferencesKey("difficulty_level")
        val PLAYBACK_OFFSET_MS = longPreferencesKey("playback_offset_ms")
        val SHOW_KEY_NAMES = booleanPreferencesKey("show_key_names")
        val METRONOME_VOLUME = floatPreferencesKey("metronome_volume")
        val AUTO_START_RECORDING = booleanPreferencesKey("auto_start_recording")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .map { preferences ->
            AppSettings(
                difficultyLevel = DifficultyLevel.valueOf(
                    preferences[PreferenceKeys.DIFFICULTY_LEVEL] ?: DifficultyLevel.MEDIUM.name
                ),
                playbackOffsetMs = preferences[PreferenceKeys.PLAYBACK_OFFSET_MS] ?: getDefaultOffset(),
                showKeyNames = preferences[PreferenceKeys.SHOW_KEY_NAMES] ?: true,
                metronomeVolume = preferences[PreferenceKeys.METRONOME_VOLUME] ?: 1.0f,
                autoStartRecording = preferences[PreferenceKeys.AUTO_START_RECORDING] ?: true
            )
        }

    suspend fun updateDifficultyLevel(level: DifficultyLevel) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.DIFFICULTY_LEVEL] = level.name
        }
    }

    suspend fun updatePlaybackOffset(offsetMs: Long) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.PLAYBACK_OFFSET_MS] = offsetMs
        }
    }

    suspend fun updateShowKeyNames(show: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_KEY_NAMES] = show
        }
    }

    suspend fun updateMetronomeVolume(volume: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.METRONOME_VOLUME] = volume.coerceIn(0f, 1f)
        }
    }

    suspend fun updateAutoStartRecording(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_START_RECORDING] = enabled
        }
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferenceKeys.DIFFICULTY_LEVEL] = settings.difficultyLevel.name
            preferences[PreferenceKeys.PLAYBACK_OFFSET_MS] = settings.playbackOffsetMs
            preferences[PreferenceKeys.SHOW_KEY_NAMES] = settings.showKeyNames
            preferences[PreferenceKeys.METRONOME_VOLUME] = settings.metronomeVolume
            preferences[PreferenceKeys.AUTO_START_RECORDING] = settings.autoStartRecording
        }
    }

    private fun getDefaultOffset(): Long {
        // Get device configuration to determine default offset
        val configuration = context.resources.configuration
        val isTablet = minOf(configuration.screenWidthDp, configuration.screenHeightDp) >= 600
        return if (isTablet) 2000L else 4500L
    }
}