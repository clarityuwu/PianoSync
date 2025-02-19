package io.pianosync.midi.data.model

/**
 * Represents a MIDI file in the application
 *
 * @property name The display name of the MIDI file
 * @property path The URI path to the MIDI file
 * @property dateImported The timestamp when the file was imported
 * @property bpm The tempo of the MIDI file in beats per minute (if available)
 */
data class MidiFile(
    val name: String,
    val path: String,
    val dateImported: Long,
    val bpm: Int? = null
)