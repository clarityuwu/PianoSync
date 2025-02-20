package io.pianosync.midi.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import io.pianosync.midi.ui.screens.player.MidiNote
import java.io.InputStream

/**
 * Utility object for parsing MIDI files
 */
object MidiParser {
    private const val HEADER_CHUNK_ID = "MThd"
    private const val TRACK_CHUNK_ID = "MTrk"

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
        val midiBytes = inputStream.readBytes()
        val activeNotes = mutableMapOf<Pair<Int, Int>, ActiveNote>()
        val completedNotes = mutableListOf<MidiNote>()

        try {
            var index = 14 // Skip header
            val ticksPerBeat = ((midiBytes[12].toInt() and 0xFF) shl 8) or (midiBytes[13].toInt() and 0xFF)
            val microsPerBeat = 60_000_000L / bpm
            var currentTrack = 0

            while (index < midiBytes.size - 4 && currentTrack < 2) {
                if (String(midiBytes.slice(index..index + 3).toByteArray()) == TRACK_CHUNK_ID) {
                    val trackLength = ((midiBytes[index + 4].toInt() and 0xFF) shl 24) or
                            ((midiBytes[index + 5].toInt() and 0xFF) shl 16) or
                            ((midiBytes[index + 6].toInt() and 0xFF) shl 8) or
                            (midiBytes[index + 7].toInt() and 0xFF)
                    var trackIndex = index + 8
                    val trackEnd = trackIndex + trackLength

                    var runningStatus = 0
                    var absoluteTimeMs = 0L
                    var absoluteTicks = 0L

                    while (trackIndex < trackEnd) {
                        var deltaTicks = 0L
                        var byte: Int
                        do {
                            byte = midiBytes[trackIndex++].toInt() and 0xFF
                            deltaTicks = (deltaTicks shl 7) or (byte and 0x7F).toLong()
                        } while (byte and 0x80 != 0 && trackIndex < trackEnd)

                        absoluteTicks += deltaTicks
                        absoluteTimeMs = (absoluteTicks * microsPerBeat) / (ticksPerBeat * 1000)

                        byte = midiBytes[trackIndex].toInt() and 0xFF
                        if (byte and 0x80 != 0) {
                            runningStatus = byte
                            trackIndex++
                        }

                        when (runningStatus and 0xF0) {
                            0x90 -> {
                                if (trackIndex + 1 >= trackEnd) break
                                val note = midiBytes[trackIndex++].toInt() and 0xFF
                                val velocity = midiBytes[trackIndex++].toInt() and 0xFF
                                if (velocity > 0) {
                                    activeNotes[Pair(note, currentTrack)] = ActiveNote(
                                        note = note,
                                        startTime = absoluteTimeMs,
                                        velocity = velocity,
                                        track = currentTrack
                                    )
                                } else {
                                    handleNoteOff(note, absoluteTimeMs, currentTrack, activeNotes, completedNotes)
                                }
                            }
                            0x80 -> {
                                if (trackIndex + 1 >= trackEnd) break
                                val note = midiBytes[trackIndex++].toInt() and 0xFF
                                trackIndex++
                                handleNoteOff(note, absoluteTimeMs, currentTrack, activeNotes, completedNotes)
                            }
                            else -> {
                                if ((runningStatus and 0xF0) == 0xF0 || runningStatus == 0xFF) {
                                    val length = midiBytes[trackIndex++].toInt() and 0xFF
                                    trackIndex += length
                                } else {
                                    trackIndex += 2
                                }
                            }
                        }
                    }
                    currentTrack++
                    index = trackEnd
                } else {
                    index++
                }
            }

            val finalTime = completedNotes.maxOfOrNull { it.startTime + it.duration }
            activeNotes.forEach { (key, activeNote) ->
                handleNoteOff(key.first, finalTime, activeNote.track, activeNotes, completedNotes)
            }

            Log.d("MidiParser", "Parsed ${completedNotes.size} notes from 2 tracks")
            return completedNotes.sortedBy { it.startTime }
        } catch (e: Exception) {
            Log.e("MidiParser", "Error parsing MIDI notes", e)
            return emptyList()
        }
    }

    private data class ActiveNote(
        val note: Int,
        val startTime: Long,
        val velocity: Int,
        val track: Int
    )

    private fun handleNoteOff(
        note: Int,
        time: Long?,
        currentTrack: Int,
        activeNotes: MutableMap<Pair<Int, Int>, ActiveNote>,
        completedNotes: MutableList<MidiNote>
    ) {
        val key = Pair(note, currentTrack)
        activeNotes[key]?.let { activeNote ->
            val duration = time?.minus(activeNote.startTime)
            if (duration != null) {
                if (duration > 0) {
                    completedNotes.add(
                        MidiNote(
                            note = note,
                            startTime = activeNote.startTime,
                            duration = duration,
                            isLeftHand = currentTrack == 1,
                            velocity = activeNote.velocity
                        )
                    )
                }
            }
            activeNotes.remove(key)
        }
    }
}