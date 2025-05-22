package io.pianosync.midi.data.model

/**
 * Represents user settings for the application
 */
data class AppSettings(
    val difficultyLevel: DifficultyLevel = DifficultyLevel.MEDIUM,
    val playbackOffsetMs: Long = 2000L, // Default offset for tablets
    val showKeyNames: Boolean = true, // Show key names on easy mode
    val metronomeVolume: Float = 1.0f,
    val autoStartRecording: Boolean = true
)

/**
 * Difficulty levels that affect note timing tolerance
 */
enum class DifficultyLevel(
    val displayName: String,
    val correctNoteWindowMs: Long,
    val description: String,
    val showKeyNames: Boolean = false
) {
    EASY(
        displayName = "Easy",
        correctNoteWindowMs = 500L, // 500ms tolerance
        description = "Generous timing window, key names shown",
        showKeyNames = true
    ),
    MEDIUM(
        displayName = "Medium",
        correctNoteWindowMs = 300L, // 300ms tolerance
        description = "Balanced timing window"
    ),
    HARD(
        displayName = "Hard",
        correctNoteWindowMs = 200L, // 200ms tolerance
        description = "Precise timing required"
    ),
    EXPERT(
        displayName = "Expert",
        correctNoteWindowMs = 100L, // 100ms tolerance
        description = "Professional-level precision"
    )
}