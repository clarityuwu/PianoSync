package io.pianosync.midi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.RecordedMidiEvent
import io.pianosync.midi.ui.screens.player.HandMode
import io.pianosync.midi.ui.screens.player.MidiNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private data class MidiEvent(
        val tick: Long,
        val status: Int,
        val data1: Int,
        val data2: Int
    )

    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[numBytes - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        return bytes
    }

    private fun encodeVariableLength(value: Long): List<Byte> {
        if (value == 0L) return listOf(0x00)

        val result = mutableListOf<Byte>()
        var temp = value

        // Get the lowest 7 bits
        result.add((temp and 0x7F).toByte())
        temp = temp shr 7

        // For remaining bits, add continuation bit
        while (temp > 0) {
            result.add(0, ((temp and 0x7F) or 0x80).toByte())
            temp = temp shr 7
        }

        return result
    }

    /**
     * Convert recorded MIDI events to clean, properly paired notes
     * This removes incomplete notes and fixes timing issues
     */
    private fun convertToCleanMidiNotes(events: List<RecordedMidiEvent>): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        val activeNotes = mutableMapOf<Int, RecordedMidiEvent>() // note -> note-on event

        // Sort events by timestamp to ensure proper ordering
        val sortedEvents = events.sortedBy { it.timestamp }

        for (event in sortedEvents) {
            when {
                event.isNoteOn -> {
                    // If there's already an active note, close it first (in case note-off was missed)
                    activeNotes[event.note]?.let { noteOnEvent ->
                        val duration = (event.timestamp - noteOnEvent.timestamp).coerceAtLeast(50L) // Min 50ms duration
                        notes.add(
                            MidiNote(
                                note = event.note,
                                startTime = noteOnEvent.timestamp,
                                duration = duration,
                                isLeftHand = event.note < 60, // Simple hand detection
                                velocity = noteOnEvent.velocity
                            )
                        )
                    }

                    // Start tracking this note
                    activeNotes[event.note] = event
                }

                event.isNoteOff -> {
                    // Find the corresponding note-on event
                    activeNotes.remove(event.note)?.let { noteOnEvent ->
                        val duration = (event.timestamp - noteOnEvent.timestamp).coerceAtLeast(50L) // Min 50ms duration

                        // Only add notes that have reasonable duration (not too short, not too long)
                        if (duration in 50L..5000L) { // Between 50ms and 5 seconds
                            notes.add(
                                MidiNote(
                                    note = event.note,
                                    startTime = noteOnEvent.timestamp,
                                    duration = duration,
                                    isLeftHand = event.note < 60,
                                    velocity = noteOnEvent.velocity
                                )
                            )
                        }
                    }
                }
            }
        }

        // Handle any remaining active notes (close them with reasonable duration)
        activeNotes.values.forEach { noteOnEvent ->
            val lastEventTime = sortedEvents.lastOrNull()?.timestamp ?: noteOnEvent.timestamp
            val duration = (lastEventTime - noteOnEvent.timestamp).coerceIn(50L, 1000L)

            notes.add(
                MidiNote(
                    note = noteOnEvent.note,
                    startTime = noteOnEvent.timestamp,
                    duration = duration,
                    isLeftHand = noteOnEvent.note < 60,
                    velocity = noteOnEvent.velocity
                )
            )
        }

        return notes.sortedBy { it.startTime }
    }

    private fun writeMidiFile(fos: FileOutputStream, notes: List<MidiNote>, bpm: Int) {
        // MIDI Header Chunk
        fos.write("MThd".toByteArray()) // Chunk type
        fos.write(intToBytes(6, 4)) // Chunk length
        fos.write(intToBytes(0, 2)) // Format type 0
        fos.write(intToBytes(1, 2)) // Number of tracks
        fos.write(intToBytes(480, 2)) // Ticks per quarter note (higher resolution)

        // Calculate track data
        val ticksPerQuarterNote = 480
        val ticksPerMs = ticksPerQuarterNote * bpm / 60000.0 // Ticks per millisecond

        val trackData = mutableListOf<Byte>()

        // Add tempo meta event
        trackData.addAll(encodeVariableLength(0)) // Delta time 0
        trackData.addAll(listOf(0xFF.toByte(), 0x51, 0x03)) // Tempo meta event
        val microsecondsPerQuarter = 60000000 / bpm
        trackData.addAll(intToBytes(microsecondsPerQuarter, 3).toList())

        // Convert notes to MIDI events with proper timing
        val midiEvents = mutableListOf<MidiEvent>()

        notes.forEach { note ->
            val startTick = (note.startTime * ticksPerMs).toLong()
            val endTick = ((note.startTime + note.duration) * ticksPerMs).toLong()

            // Note On event
            midiEvents.add(MidiEvent(startTick, 0x90, note.note, note.velocity))
            // Note Off event
            midiEvents.add(MidiEvent(endTick, 0x80, note.note, 0))
        }

        // Sort events by tick
        midiEvents.sortBy { it.tick }

        // Write MIDI events with delta times
        var currentTick = 0L
        midiEvents.forEach { event ->
            val deltaTime = event.tick - currentTick
            trackData.addAll(encodeVariableLength(deltaTime))
            trackData.add(event.status.toByte())
            trackData.add(event.data1.toByte())
            trackData.add(event.data2.toByte())
            currentTick = event.tick
        }

        // End of track
        trackData.addAll(encodeVariableLength(0))
        trackData.addAll(listOf(0xFF.toByte(), 0x2F, 0x00))

        // Write track chunk
        fos.write("MTrk".toByteArray()) // Chunk type
        fos.write(intToBytes(trackData.size, 4)) // Chunk length
        fos.write(trackData.toByteArray())
    }

    fun exportRecordingAsMidiFile(
        context: Context,
        recording: MidiRecording,
        fileName: String? = null
    ): File? {
        return try {
            // Create filename
            val actualFileName = fileName ?: "Recording_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(recording.timestamp))}.mid"

            // Create file in Music directory
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PianoSync")
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }

            val midiFile = File(musicDir, actualFileName)

            // Convert recorded events to well-formed MIDI notes
            val midiNotes = convertToCleanMidiNotes(recording.recordedEvents)

            Log.d("MidiExport", "Converting ${recording.recordedEvents.size} events to ${midiNotes.size} clean notes")

            // Write MIDI file
            FileOutputStream(midiFile).use { fos ->
                writeMidiFile(fos, midiNotes, recording.bpm)
            }

            Log.d("MidiExport", "Exported recording to: ${midiFile.absolutePath}")
            midiFile

        } catch (e: Exception) {
            Log.e("MidiExport", "Failed to export recording as MIDI file", e)
            null
        }
    }

    private fun generateTrackData(notes: List<MidiNote>, bpm: Int): ByteArray {
        // This is a simplified version - you'd want to use your existing MidiWriter logic
        val trackData = mutableListOf<Byte>()

        // Add tempo meta event
        trackData.addAll(listOf<Byte>(0x00, 0xFF.toByte(), 0x51, 0x03))
        val microsecondsPerQuarter = 60000000 / bpm
        trackData.addAll(microsecondsPerQuarter.toBigEndianByteArray(4).drop(1)) // 3 bytes

        // Add MIDI events for notes
        var currentTime = 0L
        notes.sortedBy { it.startTime }.forEach { note ->
            val deltaTime = note.startTime - currentTime
            trackData.addAll(encodeVariableLengthValue(deltaTime))

            // Note on
            trackData.addAll(listOf(0x90.toByte(), note.note.toByte(), note.velocity.toByte()))

            // Note off (after duration)
            trackData.addAll(encodeVariableLengthValue(note.duration))
            trackData.addAll(listOf(0x80.toByte(), note.note.toByte(), 0x00))

            currentTime = note.startTime + note.duration
        }

        trackData.addAll(listOf<Byte>(0x00, 0xFF.toByte(), 0x2F, 0x00))

        return trackData.toByteArray()
    }

    private fun encodeVariableLengthValue(value: Long): List<Byte> {
        if (value == 0L) return listOf(0x00)

        val result = mutableListOf<Byte>()
        var temp = value

        while (temp > 0) {
            result.add(0, (temp and 0x7F).toByte())
            temp = temp shr 7
        }

        // Set continuation bits
        for (i in 0 until result.size - 1) {
            result[i] = (result[i].toInt() or 0x80).toByte()
        }

        return result
    }

    // Extension function to convert Int to big-endian byte array
    private fun Int.toBigEndianByteArray(size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[size - 1 - i] = (this shr (i * 8)).toByte()
        }
        return bytes
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