package io.pianosync.midi.data.model

import io.pianosync.midi.ui.screens.player.HandMode
import java.util.Date

/**
 * Records a single practice session performance
 */
data class PerformanceRecord(
    val midiFilePath: String,
    val midiFileName: String,
    val timestamp: Long,
    val score: Int,
    val notesHit: Int,
    val notesMissed: Int,
    val totalNotes: Int,
    val bpm: Int,
    val handMode: HandMode,
    val durationMs: Long,
    val notesPlayed: List<PlayedNote>
) {
    val date: Date
        get() = Date(timestamp)
}

/**
 * Records a single note played during a performance
 */
data class PlayedNote(
    val noteValue: Int,
    val wasCorrect: Boolean,
    val timestamp: Long,
    val isLeftHand: Boolean
)