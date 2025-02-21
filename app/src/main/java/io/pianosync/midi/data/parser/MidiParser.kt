package io.pianosync.midi.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import io.pianosync.midi.ui.screens.player.MidiNote
import java.io.InputStream
import com.pgf.mididroid.MidiFile
import com.pgf.mididroid.event.MidiEvent
import com.pgf.mididroid.event.NoteOn
import com.pgf.mididroid.event.NoteOff

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
     * Parses MIDI notes with proper hand separation and note durations
     * @param inputStream The MIDI file input stream
     * @param bpm The tempo in beats per minute
     * @return List of MidiNotes with timing, duration, and hand information
     */

    fun parseMidiNotes(inputStream: InputStream, bpm: Int): List<MidiNote> {
        try {
            val midiFile = MidiFile(inputStream)
            val notes = mutableListOf<MidiNote>()

            // Process each track
            for (track in midiFile.tracks) {
                val activeNotes = mutableMapOf<Int, Long>()
                var currentTick = 0L

                for (event in track.events) {
                    currentTick += event.delta

                    // Convert ticks to milliseconds
                    val timeMs = (currentTick * 60_000) / (bpm * midiFile.resolution)

                    when (event) {
                        is NoteOn -> {
                            val note = event.noteValue
                            val velocity = event.velocity

                            if (velocity > 0) {
                                activeNotes[note] = timeMs
                            } else {
                                // Note On with velocity 0 is Note Off
                                handleNoteOff(note, timeMs, activeNotes, notes)
                            }
                        }
                        is NoteOff -> {
                            val note = event.noteValue
                            handleNoteOff(note, timeMs, activeNotes, notes)
                        }
                    }
                }
            }

            return notes.sortedBy { it.startTime }

        } catch (e: Exception) {
            Log.e("MidiParser", "Error parsing MIDI file", e)
            return emptyList()
        }
    }

    private fun handleNoteOff(
        note: Int,
        endTime: Long,
        activeNotes: MutableMap<Int, Long>,
        notes: MutableList<MidiNote>
    ) {
        val startTime = activeNotes.remove(note)
        if (startTime != null) {
            val duration = endTime - startTime
            if (duration > 0) {
                notes.add(
                    MidiNote(
                        note = note,
                        startTime = startTime,
                        duration = duration,
                        isLeftHand = note < 60, // Simple hand separation based on middle C
                        velocity = 100 // Default velocity
                    )
                )
            }
        }
    }

    private data class ActiveNote(
        val note: Int,
        val startTime: Long,
        val velocity: Int,
        val track: Int
    )
}