package io.pianosync.midi.data.model

/**
 * Represents a MIDI file in the application
 *
 * @property name The display name of the MIDI file
 * @property path The URI path to the MIDI file
 * @property dateImported The timestamp when the file was imported
 * @property originalBpm The original tempo of the MIDI file as parsed from the file
 * @property currentBpm The current user-set tempo for playback (defaults to originalBpm)
 */
data class MidiFile(
    val name: String,
    val path: String,
    val dateImported: Long,
    val originalBpm: Int? = null,
    val currentBpm: Int? = null
)