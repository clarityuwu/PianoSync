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
import io.pianosync.midi.ui.screens.player.HandMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.roundToLong


private fun Int.toBigEndianByteArray(size: Int): ByteArray {
    val bytes = ByteArray(size)
    for (i in 0 until size) {
        bytes[size - 1 - i] = (this ushr (i * 8)).toByte()
    }
    return bytes
}

// Helper to convert Short to Big-Endian byte array
// MOVED TO TOP LEVEL and specific for Short
private fun Short.toBigEndianByteArray(size: Int): ByteArray {
    if (size != 2) throw IllegalArgumentException("Short toBigEndianByteArray size must be 2")
    val value = this.toInt()
    return byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}


object MidiWriter {

    // Writes a variable-length quantity (used for delta times)
    private fun writeVariableLengthValue(outputStream: FileOutputStream, value: Long) {
        if (value < 0) throw IllegalArgumentException("VLQ value cannot be negative: $value")

        if (value == 0L) {
            outputStream.write(0)
            return
        }

        val buffer = ByteArray(4)
        var count = 0
        var tempValue = value

        do {
            if (count >= buffer.size && tempValue > 0) {
                Log.w("MidiWriter", "VLQ value $value may be too large for standard 4-byte MIDI delta-time.")
                if (count >= buffer.size) throw IllegalStateException("VLQ value too large for 4-byte buffer: $value")
            }
            buffer[count++] = (tempValue and 0x7F).toByte()
            tempValue = tempValue ushr 7
        } while (tempValue > 0)

        for (i in count - 1 downTo 0) {
            val byteToWrite = if (i > 0) {
                (buffer[i].toInt() and 0xFF) or 0x80
            } else {
                buffer[i].toInt() and 0xFF
            }
            outputStream.write(byteToWrite)
        }
    }


    fun writeFilteredMidiFile(
        context: Context,
        notes: List<MidiNote>,
        originalBpm: Int,
        handMode: HandMode
    ): Uri {
        val filteredNotes = when (handMode) {
            HandMode.LEFT_HAND_ONLY -> notes.filter { it.isLeftHand }
            HandMode.RIGHT_HAND_ONLY -> notes.filter { !it.isLeftHand }
            HandMode.BOTH_HANDS -> notes
        }

        val sortedNotes = filteredNotes.sortedWith(compareBy({ it.startTime }, { it.note }))
        val tempFile = File(context.cacheDir, "filtered_midi_${System.currentTimeMillis()}.mid")

        try {
            FileOutputStream(tempFile).use { fos ->
                // --- MIDI Header Chunk (MThd) ---
                fos.write(byteArrayOf(0x4D, 0x54, 0x68, 0x64)) // MThd
                fos.write(byteArrayOf(0x00, 0x00, 0x00, 0x06)) // Length (6 bytes)
                fos.write(byteArrayOf(0x00, 0x00))             // Format (Type 0: single track)
                fos.write(byteArrayOf(0x00, 0x01))             // Number of tracks (1 for Type 0)
                val ticksPerQuarterNote: Short = 120           // Example: 120 TPQN
                fos.write(ticksPerQuarterNote.toBigEndianByteArray(2)) // Division

                // --- MIDI Track Chunk (MTrk) ---
                val trackChunkStartPos = fos.channel.position()
                fos.write(byteArrayOf(0x4D, 0x54, 0x72, 0x6B)) // MTrk
                val trackLengthPos = fos.channel.position()
                fos.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // Placeholder for track length

                var currentMidiTickTime = 0L
                val ticksPerMs = ticksPerQuarterNote / (60000.0 / originalBpm)

                val microSecsPerQuarterNote = (60000000 / originalBpm)
                writeVariableLengthValue(fos, 0)
                fos.write(0xFF)
                fos.write(0x51)
                fos.write(0x03)
                // Corrected use of Int.toBigEndianByteArray
                val tempoBytes = microSecsPerQuarterNote.toBigEndianByteArray(4)
                fos.write(tempoBytes, 1, 3) // Write 3 MSBs of the 4-byte array

                for (note in sortedNotes) {
                    val noteStartTick = (note.startTime * ticksPerMs).roundToLong()
                    val noteEndTick = ((note.startTime + note.duration) * ticksPerMs).roundToLong()

                    val deltaTicksOn = (noteStartTick - currentMidiTickTime).coerceAtLeast(0)
                    writeVariableLengthValue(fos, deltaTicksOn)
                    fos.write(0x90)
                    fos.write(note.note)
                    fos.write(note.velocity)
                    currentMidiTickTime = noteStartTick

                    val deltaTicksOff = (noteEndTick - currentMidiTickTime).coerceAtLeast(0)
                    writeVariableLengthValue(fos, deltaTicksOff)
                    fos.write(0x80)
                    fos.write(note.note)
                    fos.write(0x00)
                    currentMidiTickTime = noteEndTick
                }

                writeVariableLengthValue(fos, 0)
                fos.write(0xFF)
                fos.write(0x2F)
                fos.write(0x00)

                val trackLength = fos.channel.position() - trackLengthPos - 4
                fos.channel.position(trackLengthPos)
                // Corrected use of Int.toBigEndianByteArray
                fos.write(trackLength.toInt().toBigEndianByteArray(4))
                fos.channel.position(trackChunkStartPos + 8 + trackLength)
            }
        } catch (e: IOException) {
            Log.e("MidiWriter", "Error writing MIDI file: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
        return Uri.fromFile(tempFile)
    }
}

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