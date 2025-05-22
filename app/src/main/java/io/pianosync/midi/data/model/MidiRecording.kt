package io.pianosync.midi.data.model

import io.pianosync.midi.ui.screens.player.HandMode
import java.util.Date

/**
 * Represents a recorded MIDI performance
 */
data class MidiRecording(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalMidiFilePath: String,
    val originalMidiFileName: String,
    val timestamp: Long,
    val durationMs: Long,
    val recordedEvents: List<RecordedMidiEvent>,
    val bpm: Int,
    val handMode: HandMode,
    val score: Int? = null, // Score from the performance if available
    val isSaved: Boolean = false, // Whether user has explicitly saved this recording
    val title: String = "" // User-given title for saved recordings
) {
    val date: Date
        get() = Date(timestamp)

    val displayName: String
        get() = if (title.isNotEmpty()) title else "Recording ${Date(timestamp).toString().substring(0, 19)}"
}

/**
 * Represents a single MIDI event that was recorded
 */
data class RecordedMidiEvent(
    val timestamp: Long, // Relative to recording start
    val midiCommand: Int, // 0x90 for note on, 0x80 for note off, etc.
    val note: Int,
    val velocity: Int,
    val channel: Int = 0
) {
    val isNoteOn: Boolean
        get() = (midiCommand and 0xF0) == 0x90 && velocity > 0

    val isNoteOff: Boolean
        get() = (midiCommand and 0xF0) == 0x80 || ((midiCommand and 0xF0) == 0x90 && velocity == 0)
}