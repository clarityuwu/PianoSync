package io.pianosync.midi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.data.model.PlayedNote
import io.pianosync.midi.ui.screens.player.HandMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.performanceDataStore by preferencesDataStore(name = "performance_history")
private val PERFORMANCE_HISTORY_KEY = stringPreferencesKey("performance_history")

class PerformanceRepository(private val context: Context) {
    /**
     * Flow of all performance records
     */
    val performanceHistory: Flow<List<PerformanceRecord>> = context.performanceDataStore.data
        .map { preferences ->
            preferences[PERFORMANCE_HISTORY_KEY]?.let { jsonStr ->
                parsePerformanceHistoryFromJson(jsonStr)
            } ?: emptyList()
        }

    /**
     * Save a new performance record
     */
    suspend fun savePerformanceRecord(record: PerformanceRecord) {
        val currentRecords = performanceHistory.first()
        val updatedRecords = (listOf(record) + currentRecords).take(100) // Keep only last 100 records

        context.performanceDataStore.edit { preferences ->
            preferences[PERFORMANCE_HISTORY_KEY] = convertPerformanceHistoryToJson(updatedRecords)
        }
    }

    /**
     * Get recent performances for each MIDI file (last 5 per file)
     */
    suspend fun getRecentPerformancesByFile(): Map<String, List<PerformanceRecord>> {
        val allRecords = performanceHistory.first()
        return allRecords.groupBy { it.midiFilePath }
            .mapValues { (_, records) ->
                records.sortedByDescending { it.timestamp }.take(5)
            }
    }

    /**
     * Get performance history for a specific MIDI file
     */
    suspend fun getPerformanceHistoryForFile(midiFilePath: String): List<PerformanceRecord> {
        return performanceHistory.first()
            .filter { it.midiFilePath == midiFilePath }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Parse performance history from JSON string
     */
    private fun parsePerformanceHistoryFromJson(jsonStr: String): List<PerformanceRecord> {
        return try {
            val jsonArray = JSONArray(jsonStr)
            List(jsonArray.length()) { index ->
                val obj = jsonArray.getJSONObject(index)

                // Parse played notes
                val notesPlayedArray = obj.getJSONArray("notesPlayed")
                val notesPlayed = List(notesPlayedArray.length()) { noteIndex ->
                    val noteObj = notesPlayedArray.getJSONObject(noteIndex)
                    PlayedNote(
                        noteValue = noteObj.getInt("noteValue"),
                        wasCorrect = noteObj.getBoolean("wasCorrect"),
                        timestamp = noteObj.getLong("timestamp"),
                        isLeftHand = noteObj.getBoolean("isLeftHand")
                    )
                }

                PerformanceRecord(
                    midiFilePath = obj.getString("midiFilePath"),
                    midiFileName = obj.getString("midiFileName"),
                    timestamp = obj.getLong("timestamp"),
                    score = obj.getInt("score"),
                    notesHit = obj.getInt("notesHit"),
                    notesMissed = obj.getInt("notesMissed"),
                    totalNotes = obj.getInt("totalNotes"),
                    bpm = obj.getInt("bpm"),
                    handMode = HandMode.valueOf(obj.getString("handMode")),
                    durationMs = obj.getLong("durationMs"),
                    notesPlayed = notesPlayed
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Convert performance history to JSON string
     */
    private fun convertPerformanceHistoryToJson(records: List<PerformanceRecord>): String {
        val jsonArray = JSONArray()

        records.forEach { record ->
            val recordObj = JSONObject().apply {
                put("midiFilePath", record.midiFilePath)
                put("midiFileName", record.midiFileName)
                put("timestamp", record.timestamp)
                put("score", record.score)
                put("notesHit", record.notesHit)
                put("notesMissed", record.notesMissed)
                put("totalNotes", record.totalNotes)
                put("bpm", record.bpm)
                put("handMode", record.handMode.toString())
                put("durationMs", record.durationMs)

                // Convert notes played to JSON array
                val notesArray = JSONArray()
                record.notesPlayed.forEach { note ->
                    notesArray.put(JSONObject().apply {
                        put("noteValue", note.noteValue)
                        put("wasCorrect", note.wasCorrect)
                        put("timestamp", note.timestamp)
                        put("isLeftHand", note.isLeftHand)
                    })
                }
                put("notesPlayed", notesArray)
            }

            jsonArray.put(recordObj)
        }

        return jsonArray.toString()
    }
}