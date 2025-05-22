package io.pianosync.midi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.RecordedMidiEvent
import io.pianosync.midi.ui.screens.player.HandMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.midiRecordingsDataStore by preferencesDataStore(name = "midi_recordings")
private val MIDI_RECORDINGS_KEY = stringPreferencesKey("midi_recordings")

class MidiRecordingRepository(private val context: Context) {

    /**
     * Flow of all MIDI recordings
     */
    val allRecordings: Flow<List<MidiRecording>> = context.midiRecordingsDataStore.data
        .map { preferences ->
            preferences[MIDI_RECORDINGS_KEY]?.let { jsonStr ->
                parseMidiRecordingsFromJson(jsonStr)
            } ?: emptyList()
        }

    /**
     * Save a new MIDI recording
     */
    suspend fun saveRecording(recording: MidiRecording) {
        val currentRecordings = allRecordings.first().toMutableList()

        // Add new recording to the beginning
        currentRecordings.add(0, recording)

        // Keep only last 5 recordings per MIDI file (unless they're explicitly saved)
        val recordingsForThisFile = currentRecordings.filter {
            it.originalMidiFilePath == recording.originalMidiFilePath
        }

        val unsavedRecordings = recordingsForThisFile.filter { !it.isSaved }
        if (unsavedRecordings.size > 5) {
            // Remove oldest unsaved recordings beyond the limit
            val toRemove = unsavedRecordings.drop(5)
            currentRecordings.removeAll(toRemove)
        }

        // Save to DataStore
        context.midiRecordingsDataStore.edit { preferences ->
            preferences[MIDI_RECORDINGS_KEY] = convertMidiRecordingsToJson(currentRecordings)
        }
    }

    /**
     * Mark a recording as saved with a custom title
     */
    suspend fun saveRecordingPermanently(recordingId: String, title: String) {
        val currentRecordings = allRecordings.first().toMutableList()
        val recordingIndex = currentRecordings.indexOfFirst { it.id == recordingId }

        if (recordingIndex != -1) {
            currentRecordings[recordingIndex] = currentRecordings[recordingIndex].copy(
                isSaved = true,
                title = title
            )

            context.midiRecordingsDataStore.edit { preferences ->
                preferences[MIDI_RECORDINGS_KEY] = convertMidiRecordingsToJson(currentRecordings)
            }
        }
    }

    /**
     * Delete a recording
     */
    suspend fun deleteRecording(recordingId: String) {
        val currentRecordings = allRecordings.first().toMutableList()
        currentRecordings.removeAll { it.id == recordingId }

        context.midiRecordingsDataStore.edit { preferences ->
            preferences[MIDI_RECORDINGS_KEY] = convertMidiRecordingsToJson(currentRecordings)
        }
    }

    /**
     * Get recordings for a specific MIDI file
     */
    suspend fun getRecordingsForFile(midiFilePath: String): List<MidiRecording> {
        return allRecordings.first().filter { it.originalMidiFilePath == midiFilePath }
    }

    /**
     * Get saved recordings only
     */
    suspend fun getSavedRecordings(): List<MidiRecording> {
        return allRecordings.first().filter { it.isSaved }
    }

    /**
     * Parse recordings from JSON
     */
    private fun parseMidiRecordingsFromJson(jsonStr: String): List<MidiRecording> {
        return try {
            val jsonArray = JSONArray(jsonStr)
            List(jsonArray.length()) { index ->
                val obj = jsonArray.getJSONObject(index)

                // Parse recorded events
                val eventsArray = obj.getJSONArray("recordedEvents")
                val recordedEvents = List(eventsArray.length()) { eventIndex ->
                    val eventObj = eventsArray.getJSONObject(eventIndex)
                    RecordedMidiEvent(
                        timestamp = eventObj.getLong("timestamp"),
                        midiCommand = eventObj.getInt("midiCommand"),
                        note = eventObj.getInt("note"),
                        velocity = eventObj.getInt("velocity"),
                        channel = eventObj.optInt("channel", 0)
                    )
                }

                MidiRecording(
                    id = obj.getString("id"),
                    originalMidiFilePath = obj.getString("originalMidiFilePath"),
                    originalMidiFileName = obj.getString("originalMidiFileName"),
                    timestamp = obj.getLong("timestamp"),
                    durationMs = obj.getLong("durationMs"),
                    recordedEvents = recordedEvents,
                    bpm = obj.getInt("bpm"),
                    handMode = HandMode.valueOf(obj.getString("handMode")),
                    score = obj.optInt("score").takeIf { it != 0 },
                    isSaved = obj.optBoolean("isSaved", false),
                    title = obj.optString("title", "")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Convert recordings to JSON
     */
    private fun convertMidiRecordingsToJson(recordings: List<MidiRecording>): String {
        val jsonArray = JSONArray()

        recordings.forEach { recording ->
            val recordingObj = JSONObject().apply {
                put("id", recording.id)
                put("originalMidiFilePath", recording.originalMidiFilePath)
                put("originalMidiFileName", recording.originalMidiFileName)
                put("timestamp", recording.timestamp)
                put("durationMs", recording.durationMs)
                put("bpm", recording.bpm)
                put("handMode", recording.handMode.toString())
                put("score", recording.score ?: 0)
                put("isSaved", recording.isSaved)
                put("title", recording.title)

                // Convert recorded events to JSON array
                val eventsArray = JSONArray()
                recording.recordedEvents.forEach { event ->
                    eventsArray.put(JSONObject().apply {
                        put("timestamp", event.timestamp)
                        put("midiCommand", event.midiCommand)
                        put("note", event.note)
                        put("velocity", event.velocity)
                        put("channel", event.channel)
                    })
                }
                put("recordedEvents", eventsArray)
            }

            jsonArray.put(recordingObj)
        }

        return jsonArray.toString()
    }
}