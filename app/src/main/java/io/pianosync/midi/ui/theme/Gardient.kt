package io.pianosync.midi.ui.theme

import androidx.compose.ui.graphics.Brush
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.PerformanceRecord
import kotlin.math.absoluteValue

/**
 * Utility object for selecting appropriate gradients for MIDI file cards
 */
object GradientSelector {

    /**
     * Select a gradient based on MIDI file characteristics and performance history
     */
    fun selectGradientForMidiFile(
        midiFile: MidiFile,
        recentPerformances: List<PerformanceRecord> = emptyList()
    ): Brush {
        return when {
            // If there are recent performances, base selection on performance quality
            recentPerformances.isNotEmpty() -> {
                val latestScore = recentPerformances.first().score
                val averageScore = recentPerformances.map { it.score }.average().toInt()
                selectGradientByPerformance(latestScore, averageScore, recentPerformances.size)
            }

            // If no performances, base on file characteristics
            else -> selectGradientByFileCharacteristics(midiFile)
        }
    }

    /**
     * Select gradient based on performance metrics
     */
    private fun selectGradientByPerformance(
        latestScore: Int,
        averageScore: Int,
        sessionCount: Int
    ): Brush {
        return when {
            // Excellent performance - warm, golden gradients
            latestScore >= 95 || (averageScore >= 90 && sessionCount >= 3) ->
                MusicGradients.JazzGold

            // Very good performance - fresh, energetic gradients
            latestScore >= 85 || (averageScore >= 80 && sessionCount >= 3) ->
                MusicGradients.SpringMelody

            // Good performance - elegant, stable gradients
            latestScore >= 75 || (averageScore >= 70 && sessionCount >= 2) ->
                MusicGradients.ElegantBlue

            // Improving performance - passionate, motivating gradients
            latestScore >= 65 && averageScore < latestScore ->
                MusicGradients.SunsetSymphony

            // Fair performance - gentle, encouraging gradients
            latestScore >= 50 ->
                MusicGradients.LavenderDream

            // Needs work - passionate, intense gradients for motivation
            else -> MusicGradients.RomanticRose
        }
    }

    /**
     * Select gradient based on file characteristics (name, BPM, etc.)
     */
    private fun selectGradientByFileCharacteristics(midiFile: MidiFile): Brush {
        val fileName = midiFile.name.lowercase()
        val bpm = midiFile.currentBpm ?: midiFile.originalBpm ?: 120

        // First, try to detect genre/mood from filename
        val gradientByName = when {
            // Classical music indicators
            containsAny(fileName, listOf("bach", "mozart", "beethoven", "chopin", "classical", "sonata", "symphony", "concerto")) ->
                MusicGradients.ClassicalPurple

            // Jazz/Blues indicators
            containsAny(fileName, listOf("jazz", "blues", "swing", "ragtime", "boogie")) ->
                MusicGradients.JazzGold

            // Romantic/Ballad indicators
            containsAny(fileName, listOf("love", "romantic", "ballad", "serenade", "nocturne", "dream")) ->
                MusicGradients.RomanticRose

            // Energetic/Rock indicators
            containsAny(fileName, listOf("rock", "metal", "energetic", "power", "thunder", "fire")) ->
                MusicGradients.SunsetSymphony

            // Peaceful/Ambient indicators
            containsAny(fileName, listOf("peaceful", "calm", "ocean", "water", "rain", "ambient", "meditation")) ->
                MusicGradients.OceanHarmony

            // Spring/Nature indicators
            containsAny(fileName, listOf("spring", "garden", "flower", "nature", "green", "fresh")) ->
                MusicGradients.SpringMelody

            // Dark/Mysterious indicators
            containsAny(fileName, listOf("dark", "night", "shadow", "mystery", "gothic", "minor")) ->
                MusicGradients.MidnightSerenade

            else -> null
        }

        // If we found a match by name, use it
        if (gradientByName != null) {
            return gradientByName
        }

        // Otherwise, use BPM to suggest mood
        return when {
            bpm <= 60 -> MusicGradients.LavenderDream // Very slow - dreamy
            bpm <= 80 -> MusicGradients.OceanHarmony // Slow - peaceful
            bpm <= 100 -> MusicGradients.ClassicalPurple // Moderate - classical
            bpm <= 120 -> MusicGradients.ElegantBlue // Standard - elegant
            bpm <= 140 -> MusicGradients.SpringMelody // Upbeat - energetic
            bpm <= 160 -> MusicGradients.SunsetSymphony // Fast - vibrant
            else -> MusicGradients.RomanticRose // Very fast - intense
        }
    }

    /**
     * Get a consistent gradient based on file hash (for files without performance data)
     */
    fun getConsistentGradientForFile(midiFile: MidiFile): Brush {
        val allGradients = MusicGradients.getAllGradients()
        val hash = midiFile.name.hashCode().absoluteValue
        val index = hash % allGradients.size
        return allGradients[index]
    }

    /**
     * Get gradient that represents improvement trend
     */
    fun getTrendGradient(performances: List<PerformanceRecord>): Brush? {
        if (performances.size < 2) return null

        val recent = performances.take(2).map { it.score }.average()
        val older = performances.drop(2).take(2).map { it.score }.average()

        return when {
            recent > older + 10 -> MusicGradients.SpringMelody // Strong improvement
            recent > older + 5 -> MusicGradients.SunsetSymphony // Moderate improvement
            recent < older - 10 -> MusicGradients.MidnightSerenade // Declining
            recent < older - 5 -> MusicGradients.LavenderDream // Slight decline
            else -> null // Stable - use default selection
        }
    }

    /**
     * Helper function to check if string contains any of the given keywords
     */
    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { keyword -> text.contains(keyword) }
    }
}

/**
 * Extension functions for easier gradient usage
 */
fun MidiFile.getGradient(performances: List<PerformanceRecord> = emptyList()): Brush {
    return GradientSelector.selectGradientForMidiFile(this, performances)
}

fun MidiFile.getConsistentGradient(): Brush {
    return GradientSelector.getConsistentGradientForFile(this)
}

/**
 * Gradient animation helpers for future use
 */
object GradientAnimations {

    /**
     * Create a shimmer effect gradient
     */
    fun createShimmerGradient(baseGradient: Brush, shimmerColor: androidx.compose.ui.graphics.Color): Brush {
        // This could be expanded to create animated shimmer effects
        return baseGradient
    }

    /**
     * Create a pulsing gradient effect
     */
    fun createPulsingGradient(baseGradient: Brush, intensity: Float = 0.1f): Brush {
        // This could be expanded to create pulsing effects based on BPM
        return baseGradient
    }
}