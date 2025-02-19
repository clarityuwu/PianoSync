package io.pianosync.midi.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import io.pianosync.midi.ui.screens.player.MidiNote
import java.io.InputStream
import kotlin.math.abs

/**
 * Utility object for parsing MIDI files
 */
object MidiParser {
    /**
     * Extracts the BPM from a MIDI file
     *
     * @param context The application context
     * @param uri The URI of the MIDI file
     * @return The BPM value if found, null otherwise
     */
    fun extractBPM(context: Context, uri: Uri): Int? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val midiBytes = inputStream.readBytes()
                parseMidiBPM(midiBytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Analyzes the note range of a MIDI file
     *
     * @param inputStream The MIDI file input stream
     * @return Pair of (lowest note, highest note), or null if analysis fails
     */
    fun analyzeNoteRange(inputStream: InputStream): Pair<Int, Int>? {
        return try {
            val midiBytes = inputStream.readBytes()
            parseNoteRange(midiBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the MIDI data to find the tempo meta event
     *
     * @param midiData The raw MIDI file data
     * @return The calculated BPM if found, null otherwise
     */
    private fun parseMidiBPM(midiData: ByteArray): Int? {
        var index = 0
        while (index < midiData.size - 4) {
            // Look for tempo meta event
            if (midiData[index] == 0xFF.toByte() && // Meta event
                midiData[index + 1] == 0x51.toByte() && // Tempo type
                midiData[index + 2] == 0x03.toByte()) { // Length

                // Calculate tempo from microseconds per quarter note
                val microsPerQuarter = ((midiData[index + 3].toInt() and 0xFF) shl 16) or
                        ((midiData[index + 4].toInt() and 0xFF) shl 8) or
                        (midiData[index + 5].toInt() and 0xFF)

                return (60_000_000 / microsPerQuarter)
            }
            index++
        }
        return null
    }

    /**
     * Parses the MIDI data to find the note range
     *
     * @param midiData The raw MIDI file data
     * @return Pair of (lowest note, highest note), or null if parsing fails
     */
    private fun parseNoteRange(midiData: ByteArray): Pair<Int, Int>? {
        var index = 0
        var lowestNote = 127
        var highestNote = 0

        try {
            // Skip MIDI header
            while (index < midiData.size - 4) {
                // Look for note on/off events (status byte in range 0x80-0x9F)
                val status = midiData[index].toInt() and 0xFF
                if ((status and 0xE0) == 0x80) { // Note on/off events
                    if (index + 2 < midiData.size) {
                        val note = midiData[index + 1].toInt() and 0xFF
                        if (note < lowestNote) lowestNote = note
                        if (note > highestNote) highestNote = note
                    }
                }
                index++
            }

            // If we found any notes, return the range
            return if (lowestNote <= highestNote) {
                Pair(lowestNote, highestNote)
            } else {
                // Default to middle C range if no notes found
                Pair(60, 72)
            }
        } catch (e: Exception) {
            // Default to middle C range on error
            return Pair(60, 72)
        }
    }

    /**
     * Parses MIDI notes with hand separation
     * Assumes notes below middle C (60) are left hand
     * @param inputStream The MIDI file input stream
     * @return List of MidiNotes with timing and hand information
     */
    fun parseMidiNotes(inputStream: InputStream, bpm: Int): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        try {
            val midiBytes = inputStream.readBytes()
            var index = 0
            var time = 0L
            val originalMicrosecondsPerBeat = 60_000_000 / 120 // Assuming original is 120 BPM
            val targetMicrosecondsPerBeat = 60_000_000 / bpm

            while (index < midiBytes.size - 2) {
                // Parse delta time
                var deltaTime = 0L
                var byte = midiBytes[index].toInt() and 0xFF
                while (byte and 0x80 != 0) {
                    deltaTime = (deltaTime shl 7) + (byte and 0x7F)
                    index++
                    if (index >= midiBytes.size) break
                    byte = midiBytes[index].toInt() and 0xFF
                }
                deltaTime = (deltaTime shl 7) + (byte and 0x7F)
                index++

                // Scale time based on BPM ratio
                val scaledDeltaTime = (deltaTime * targetMicrosecondsPerBeat) / originalMicrosecondsPerBeat
                time += scaledDeltaTime

                if (index >= midiBytes.size - 2) break

                val status = midiBytes[index].toInt() and 0xFF
                if ((status and 0xF0) == 0x90) { // Note on event
                    val note = midiBytes[index + 1].toInt() and 0xFF
                    val velocity = midiBytes[index + 2].toInt() and 0xFF

                    if (velocity > 0) {
                        notes.add(MidiNote(
                            note = note,
                            isLeftHand = note < 60,
                            startTime = time,
                            duration = 0L
                        ))
                        Log.d("MidiParser", "Added note $note at scaled time $time (BPM: $bpm)")
                    }
                }
                index += 3
            }

            return notes.sortedBy { it.startTime }
        } catch (e: Exception) {
            Log.e("MidiParser", "Error parsing MIDI notes", e)
            e.printStackTrace()
        }
        return notes
    }

    /**
     * Check if a played note is in sync with the MIDI file
     * @param playedNote The MIDI note number that was played
     * @param currentTime The current playback time in milliseconds
     * @param notes The list of MIDI notes from the file
     * @param toleranceMs The timing tolerance in milliseconds
     * @return true if the note was played in sync
     */
    fun isNoteInSync(
        playedNote: Int,
        currentTime: Long,
        notes: List<MidiNote>,
        toleranceMs: Long = 150
    ): Boolean {
        return notes.any { midiNote ->
            midiNote.note == playedNote &&
                    abs(currentTime - midiNote.startTime) <= toleranceMs
        }
    }

}