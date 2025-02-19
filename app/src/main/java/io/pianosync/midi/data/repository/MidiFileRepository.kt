package io.pianosync.midi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pianosync.midi.data.model.MidiFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "midi_files")
private val MIDI_FILES_KEY = stringPreferencesKey("midi_files")

class MidiFileRepository(private val context: Context) {
    val midiFiles: Flow<List<MidiFile>> = context.dataStore.data
        .map { preferences ->
            preferences[MIDI_FILES_KEY]?.let { jsonStr ->
                parseMidiFilesFromJson(jsonStr)
            } ?: emptyList()
        }

    suspend fun saveMidiFiles(files: List<MidiFile>) {
        val jsonArray = JSONArray().apply {
            files.forEach { file ->
                put(JSONObject().apply {
                    put("name", file.name)
                    put("path", file.path)
                    put("dateImported", file.dateImported)
                    put("bpm", file.bpm)
                })
            }
        }

        context.dataStore.edit { preferences ->
            preferences[MIDI_FILES_KEY] = jsonArray.toString()
        }
    }

    suspend fun updateMidiBpm(midiFile: MidiFile) {
        val currentFiles = midiFiles.first()
        val updatedFiles = currentFiles.map { file ->
            if (file.path == midiFile.path) {
                file.copy(bpm = midiFile.bpm)
            } else {
                file
            }
        }
        saveMidiFiles(updatedFiles)
    }

    suspend fun getMidiFileByPath(path: String): MidiFile? {
        return midiFiles.first().find { it.path == path }
    }

    private fun parseMidiFilesFromJson(jsonStr: String): List<MidiFile> {
        return try {
            val jsonArray = JSONArray(jsonStr)
            List(jsonArray.length()) { index ->
                val obj = jsonArray.getJSONObject(index)
                MidiFile(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    dateImported = obj.getLong("dateImported"),
                    bpm = if (obj.has("bpm")) obj.getDouble("bpm").toInt() else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}