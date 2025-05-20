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
            val originalBpm = extractBPMFromMidiFile(midiFile) ?: bpm

            // First pass: collect all note values by track to determine which tracks have notes
            val trackNotesData = mutableMapOf<Int, MutableList<Int>>()

            midiFile.tracks.forEachIndexed { trackIndex, track ->
                val trackNotes = mutableListOf<Int>()

                for (event in track.events) {
                    when (event) {
                        is NoteOn -> {
                            if (event.velocity > 0) {
                                trackNotes.add(event.noteValue)
                            }
                        }
                    }
                }

                if (trackNotes.isNotEmpty()) {
                    trackNotesData[trackIndex] = trackNotes
                }
            }

            // Determine hand assignment strategy
            val handAssignmentStrategy = when {
                // If we have exactly 2 tracks with notes, assume first is right, second is left
                // This is a common convention in piano MIDI files
                trackNotesData.size == 2 -> {
                    val sortedTracks = trackNotesData.keys.sorted()
                    mapOf(
                        sortedTracks[0] to false, // First track = right hand
                        sortedTracks[1] to true   // Second track = left hand
                    )
                }

                // If more than 2 tracks, use average pitch to determine
                trackNotesData.size > 2 -> {
                    // Calculate average pitch for each track
                    val trackAveragePitch = trackNotesData.mapValues { entry ->
                        entry.value.average()
                    }

                    // Sort tracks by average pitch (low to high)
                    val sortedTracks = trackAveragePitch.entries.sortedBy { it.value }

                    // Log the track data for debugging
                    sortedTracks.forEach { (trackIndex, avgPitch) ->
                        Log.d("MidiParser", "Track $trackIndex average pitch: $avgPitch")
                    }

                    // Assign left hand to lower half of tracks, right hand to upper half
                    sortedTracks.withIndex().associate { (index, entry) ->
                        entry.key to (index < sortedTracks.size / 2)
                    }
                }

                // If only 1 track or no tracks with notes, we'll use middle C detection
                else -> emptyMap()
            }

            // Log the hand assignment for debugging
            handAssignmentStrategy.forEach { (trackIndex, isLeftHand) ->
                Log.d("MidiParser", "Track $trackIndex assigned to ${if (isLeftHand) "left" else "right"} hand")
            }

            // Second pass: extract notes with proper hand assignment
            midiFile.tracks.forEachIndexed { trackIndex, track ->
                // Skip tracks without notes
                if (trackIndex !in trackNotesData.keys) {
                    return@forEachIndexed
                }

                val activeNotes = mutableMapOf<Int, Long>()
                var currentTick = 0L

                // Determine hand based on strategy or fall back to middle C
                val multipleTracksWithNotes = trackNotesData.size >= 2
                val isLeftHand = handAssignmentStrategy[trackIndex] ?: false

                for (event in track.events) {
                    currentTick += event.delta
                    val timeMs = (currentTick * 60_000) / (originalBpm * midiFile.resolution)

                    when (event) {
                        is NoteOn -> {
                            val note = event.noteValue
                            val velocity = event.velocity

                            if (velocity > 0) {
                                activeNotes[note] = timeMs
                            } else {
                                // Note On with velocity 0 is Note Off
                                handleNoteOff(note, timeMs, activeNotes, notes, isLeftHand, multipleTracksWithNotes)
                            }
                        }
                        is NoteOff -> {
                            val note = event.noteValue
                            handleNoteOff(note, timeMs, activeNotes, notes, isLeftHand, multipleTracksWithNotes)
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

    // Helper function to extract BPM directly from MIDI file
    private fun extractBPMFromMidiFile(midiFile: MidiFile): Int? {
        for (track in midiFile.tracks) {
            for (event in track.events) {
                if (event is com.pgf.mididroid.event.meta.Tempo) {
                    val mpqn = event.mpqn // Microseconds per quarter note
                    return (60_000_000 / mpqn) // Convert to BPM
                }
            }
        }
        return null
    }

    private fun handleNoteOff(
        note: Int,
        endTime: Long,
        activeNotes: MutableMap<Int, Long>,
        notes: MutableList<MidiNote>,
        isLeftHand: Boolean,
        multipleTracksWithNotes: Boolean
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
                        // If multiple tracks, use the track's hand assignment
                        // If single track, fall back to middle C detection
                        isLeftHand = if (multipleTracksWithNotes) isLeftHand else note < 60,
                        velocity = 100 // Default velocity
                    )
                )
            }
        }
    }
}