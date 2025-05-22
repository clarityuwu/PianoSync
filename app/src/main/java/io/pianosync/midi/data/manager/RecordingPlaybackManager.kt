package io.pianosync.midi.data.manager

import android.content.Context
import android.util.Log
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.RecordedMidiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages playback of recorded MIDI performances
 */
class RecordingPlaybackManager(
    private val context: Context
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()

    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())
    val pressedKeys: StateFlow<Set<Int>> = _pressedKeys.asStateFlow()

    private var currentRecording: MidiRecording? = null
    private var playbackSpeed = 1.0f

    /**
     * Start playback of a MIDI recording
     */
    fun startPlayback(recording: MidiRecording, speed: Float = 1.0f) {
        stopPlayback()

        currentRecording = recording
        playbackSpeed = speed.coerceIn(0.25f, 2.0f)

        _isPlaying.value = true
        _currentTimeMs.value = 0L
        _pressedKeys.value = emptySet()

        playbackJob = coroutineScope.launch {
            val events = recording.recordedEvents.sortedBy { it.timestamp }
            val startTime = System.currentTimeMillis()
            var lastEventTime = 0L
            val activeNotes = mutableSetOf<Int>()

            Log.d("RecordingPlayback", "Starting playback of ${events.size} MIDI events")

            for (event in events) {
                if (!isActive || !_isPlaying.value) break

                val targetTime = (event.timestamp / playbackSpeed).toLong()
                val currentPlaybackTime = System.currentTimeMillis() - startTime
                val waitTime = targetTime - currentPlaybackTime

                if (waitTime > 0) {
                    delay(waitTime)
                }

                // Update current time
                _currentTimeMs.value = (event.timestamp / playbackSpeed).toLong()

                // Process the MIDI event
                when {
                    event.isNoteOn -> {
                        activeNotes.add(event.note)
                        _pressedKeys.value = activeNotes.toSet()
                        Log.d("RecordingPlayback", "Note ON: ${event.note}")
                    }
                    event.isNoteOff -> {
                        activeNotes.remove(event.note)
                        _pressedKeys.value = activeNotes.toSet()
                        Log.d("RecordingPlayback", "Note OFF: ${event.note}")
                    }
                }

                lastEventTime = event.timestamp
            }

            // Wait for the recording to finish based on duration
            val remainingTime = ((recording.durationMs - lastEventTime) / playbackSpeed).toLong()
            if (remainingTime > 0 && isActive && _isPlaying.value) {
                delay(remainingTime)
            }

            // Playback finished
            stopPlayback()
        }
    }

    /**
     * Pause playback
     */
    fun pausePlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Resume playback from current position
     */
    fun resumePlayback() {
        if (currentRecording != null && !_isPlaying.value) {
            val currentTime = _currentTimeMs.value

            // Filter events that haven't been played yet
            val remainingEvents = currentRecording!!.recordedEvents
                .filter { it.timestamp >= currentTime }
                .sortedBy { it.timestamp }

            if (remainingEvents.isNotEmpty()) {
                _isPlaying.value = true

                playbackJob = coroutineScope.launch {
                    val startTime = System.currentTimeMillis() - (currentTime / playbackSpeed).toLong()
                    val activeNotes = _pressedKeys.value.toMutableSet()

                    for (event in remainingEvents) {
                        if (!isActive || !_isPlaying.value) break

                        val targetTime = (event.timestamp / playbackSpeed).toLong()
                        val currentPlaybackTime = System.currentTimeMillis() - startTime
                        val waitTime = targetTime - currentPlaybackTime

                        if (waitTime > 0) {
                            delay(waitTime)
                        }

                        _currentTimeMs.value = (event.timestamp / playbackSpeed).toLong()

                        when {
                            event.isNoteOn -> {
                                activeNotes.add(event.note)
                                _pressedKeys.value = activeNotes.toSet()
                            }
                            event.isNoteOff -> {
                                activeNotes.remove(event.note)
                                _pressedKeys.value = activeNotes.toSet()
                            }
                        }
                    }

                    stopPlayback()
                }
            }
        }
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
        _pressedKeys.value = emptySet()
        _currentTimeMs.value = 0L
        Log.d("RecordingPlayback", "Playback stopped")
    }

    /**
     * Seek to a specific position in the recording
     */
    fun seekTo(positionMs: Long) {
        val recording = currentRecording ?: return
        val targetPosition = positionMs.coerceIn(0L, recording.durationMs)

        _currentTimeMs.value = targetPosition

        // Update pressed keys based on the target position
        val activeNotes = mutableSetOf<Int>()

        // Find all note events up to the target position
        recording.recordedEvents
            .filter { it.timestamp <= targetPosition }
            .sortedBy { it.timestamp }
            .forEach { event ->
                when {
                    event.isNoteOn -> activeNotes.add(event.note)
                    event.isNoteOff -> activeNotes.remove(event.note)
                }
            }

        _pressedKeys.value = activeNotes.toSet()

        if (_isPlaying.value) {
            // If playing, restart from new position
            resumePlayback()
        }
    }

    /**
     * Set playback speed
     */
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 2.0f)

        if (_isPlaying.value) {
            // Restart playback at new speed
            val currentTime = _currentTimeMs.value
            pausePlayback()
            _currentTimeMs.value = currentTime
            resumePlayback()
        }
    }

    /**
     * Get the duration of the current recording
     */
    fun getRecordingDuration(): Long {
        return currentRecording?.durationMs ?: 0L
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopPlayback()
        currentRecording = null
    }
}