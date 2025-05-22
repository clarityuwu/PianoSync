package io.pianosync.midi.data.manager

import android.util.Log
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.RecordedMidiEvent
import io.pianosync.midi.ui.screens.player.HandMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages MIDI recording during performance sessions
 */
class MidiRecordingManager {
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordedEvents = mutableListOf<RecordedMidiEvent>()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Stopped)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    /**
     * Start recording MIDI input
     */
    fun startRecording() {
        if (!isRecording) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            recordedEvents.clear()
            _recordingState.value = RecordingState.Recording
            Log.d("MidiRecording", "Started MIDI recording")
        }
    }

    /**
     * Stop recording and return the recorded data
     */
    fun stopRecording(): List<RecordedMidiEvent> {
        if (isRecording) {
            isRecording = false
            _recordingState.value = RecordingState.Stopped
            Log.d("MidiRecording", "Stopped MIDI recording. Recorded ${recordedEvents.size} events")
        }
        // Always return events, even if already stopped
        return recordedEvents.toList()
    }

    /**
     * Record a MIDI event (called from MidiConnectionManager)
     */
    fun recordMidiEvent(status: Int, note: Int, velocity: Int, channel: Int = 0) {
        if (!isRecording) return

        val currentTime = System.currentTimeMillis()
        val relativeTime = currentTime - recordingStartTime

        val recordedEvent = RecordedMidiEvent(
            timestamp = relativeTime,
            midiCommand = status,
            note = note,
            velocity = velocity,
            channel = channel
        )

        recordedEvents.add(recordedEvent)

        Log.d("MidiRecording", "Recorded MIDI event: status=${status.toString(16)}, note=$note, velocity=$velocity, time=$relativeTime")
    }

    /**
     * Create a MidiRecording from the current session
     */
    fun createRecording(
        originalMidiFilePath: String,
        originalMidiFileName: String,
        bpm: Int,
        handMode: HandMode,
        score: Int? = null
    ): MidiRecording? {
        // Get events without stopping again
        val events = recordedEvents.toList()

        // Clear events after getting them
        recordedEvents.clear()

        if (events.isEmpty()) {
            Log.d("MidiRecording", "No events to create recording from")
            return null
        }

        val durationMs = if (events.isNotEmpty()) {
            events.maxOfOrNull { it.timestamp } ?: 0L
        } else 0L

        Log.d("MidiRecording", "Creating recording with ${events.size} events, duration: ${durationMs}ms")

        return MidiRecording(
            originalMidiFilePath = originalMidiFilePath,
            originalMidiFileName = originalMidiFileName,
            timestamp = recordingStartTime,
            durationMs = durationMs,
            recordedEvents = events,
            bpm = bpm,
            handMode = handMode,
            score = score
        )
    }

    /**
     * Check if currently recording
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Get recording duration in milliseconds
     */
    fun getRecordingDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else 0L
    }

    /**
     * Clear current recording without stopping
     */
    fun clearRecording() {
        recordedEvents.clear()
        Log.d("MidiRecording", "Cleared current recording")
    }

    /**
     * Check if there are recorded events available
     */
    fun hasRecordedEvents(): Boolean = recordedEvents.isNotEmpty()

    sealed class RecordingState {
        object Stopped : RecordingState()
        object Recording : RecordingState()
    }
}