package io.pianosync.midi.data.manager

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.RecordedMidiEvent
import io.pianosync.midi.ui.screens.player.MidiNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Manages playback of recorded MIDI performances using MediaPlayer for proper audio
 */
class RecordingPlaybackManager(
    private val context: Context
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var playbackJob: Job? = null
    private var visualUpdateJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tempMidiFile: File? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()

    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())
    val pressedKeys: StateFlow<Set<Int>> = _pressedKeys.asStateFlow()

    private var currentRecording: MidiRecording? = null
    private var playbackSpeed = 1.0f
    private var startTimeOffset = 0L
    private var playbackStartTime = 0L

    /**
     * Convert recorded MIDI events to a temporary MIDI file for MediaPlayer
     */
    private fun createTempMidiFile(recording: MidiRecording): File? {
        return try {
            val tempFile = File(context.cacheDir, "temp_recording_${System.currentTimeMillis()}.mid")

            // Convert recorded events to MidiNotes for easier processing
            val midiNotes = convertEventsToNotes(recording.recordedEvents)

            // Write MIDI file
            FileOutputStream(tempFile).use { fos ->
                writeMidiFile(fos, midiNotes, recording.bpm)
            }

            Log.d("RecordingPlayback", "Created temp MIDI file: ${tempFile.absolutePath} with ${midiNotes.size} notes")
            tempFile

        } catch (e: Exception) {
            Log.e("RecordingPlayback", "Error creating temp MIDI file", e)
            null
        }
    }

    /**
     * Convert recorded events to clean MIDI notes (similar to export function)
     */
    private fun convertEventsToNotes(events: List<RecordedMidiEvent>): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        val activeNotes = mutableMapOf<Int, RecordedMidiEvent>()

        val sortedEvents = events.sortedBy { it.timestamp }

        for (event in sortedEvents) {
            when {
                event.isNoteOn -> {
                    // Close any existing note for this key
                    activeNotes[event.note]?.let { noteOnEvent ->
                        val duration = (event.timestamp - noteOnEvent.timestamp).coerceAtLeast(50L)
                        notes.add(
                            MidiNote(
                                note = event.note,
                                startTime = noteOnEvent.timestamp,
                                duration = duration,
                                isLeftHand = event.note < 60,
                                velocity = noteOnEvent.velocity
                            )
                        )
                    }
                    activeNotes[event.note] = event
                }

                event.isNoteOff -> {
                    activeNotes.remove(event.note)?.let { noteOnEvent ->
                        val duration = (event.timestamp - noteOnEvent.timestamp).coerceAtLeast(50L)
                        if (duration in 50L..5000L) { // Reasonable duration
                            notes.add(
                                MidiNote(
                                    note = event.note,
                                    startTime = noteOnEvent.timestamp,
                                    duration = duration,
                                    isLeftHand = event.note < 60,
                                    velocity = noteOnEvent.velocity
                                )
                            )
                        }
                    }
                }
            }
        }

        // Close remaining active notes
        activeNotes.values.forEach { noteOnEvent ->
            val lastEventTime = sortedEvents.lastOrNull()?.timestamp ?: noteOnEvent.timestamp
            val duration = (lastEventTime - noteOnEvent.timestamp).coerceIn(50L, 1000L)
            notes.add(
                MidiNote(
                    note = noteOnEvent.note,
                    startTime = noteOnEvent.timestamp,
                    duration = duration,
                    isLeftHand = noteOnEvent.note < 60,
                    velocity = noteOnEvent.velocity
                )
            )
        }

        return notes.sortedBy { it.startTime }
    }

    /**
     * Write MIDI file (simplified version for playback)
     */
    private fun writeMidiFile(fos: FileOutputStream, notes: List<MidiNote>, bpm: Int) {
        // MIDI Header
        fos.write("MThd".toByteArray())
        fos.write(intToBytes(6, 4))
        fos.write(intToBytes(0, 2)) // Format 0
        fos.write(intToBytes(1, 2)) // 1 track
        fos.write(intToBytes(480, 2)) // Ticks per quarter note

        // Track data
        val trackData = mutableListOf<Byte>()
        val ticksPerMs = 480.0 * bpm / 60000.0

        // Tempo meta event
        trackData.addAll(encodeVariableLength(0))
        trackData.addAll(listOf(0xFF.toByte(), 0x51, 0x03))
        val microsecondsPerQuarter = 60000000 / bpm
        trackData.addAll(intToBytes(microsecondsPerQuarter, 3).toList())

        // Convert notes to MIDI events
        val events = mutableListOf<Pair<Long, List<Byte>>>() // tick, event data

        notes.forEach { note ->
            val startTick = (note.startTime * ticksPerMs).toLong()
            val endTick = ((note.startTime + note.duration) * ticksPerMs).toLong()

            // Note on
            events.add(startTick to listOf(0x90.toByte(), note.note.toByte(), note.velocity.toByte()))
            // Note off
            events.add(endTick to listOf(0x80.toByte(), note.note.toByte(), 0x00.toByte()))
        }

        // Sort by tick and write with delta times
        events.sortBy { it.first }
        var currentTick = 0L

        events.forEach { (tick, eventData) ->
            val deltaTime = tick - currentTick
            trackData.addAll(encodeVariableLength(deltaTime))
            trackData.addAll(eventData)
            currentTick = tick
        }

        // End of track
        trackData.addAll(encodeVariableLength(0))
        trackData.addAll(listOf(0xFF.toByte(), 0x2F, 0x00))

        // Write track
        fos.write("MTrk".toByteArray())
        fos.write(intToBytes(trackData.size, 4))
        fos.write(trackData.toByteArray())
    }

    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[numBytes - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        return bytes
    }

    private fun encodeVariableLength(value: Long): List<Byte> {
        if (value == 0L) return listOf(0x00)

        val result = mutableListOf<Byte>()
        var temp = value

        result.add((temp and 0x7F).toByte())
        temp = temp shr 7

        while (temp > 0) {
            result.add(0, ((temp and 0x7F) or 0x80).toByte())
            temp = temp shr 7
        }

        return result
    }

    /**
     * Start playback of a MIDI recording
     */
    fun startPlayback(recording: MidiRecording, speed: Float = 1.0f) {
        stopPlayback()

        currentRecording = recording
        playbackSpeed = speed.coerceIn(0.25f, 2.0f)

        // Create temporary MIDI file
        tempMidiFile = createTempMidiFile(recording)
        if (tempMidiFile == null) {
            Log.e("RecordingPlayback", "Failed to create temp MIDI file")
            return
        }

        try {
            // Initialize MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(tempMidiFile))
                prepare()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setPlaybackParams(PlaybackParams().apply {
                        pitch = 1.0f
                    })
                }

                setOnCompletionListener {
                    stopPlayback()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("RecordingPlayback", "MediaPlayer error: $what, $extra")
                    stopPlayback()
                    true
                }
            }

            // Start audio playback
            mediaPlayer?.start()
            _isPlaying.value = true

            playbackStartTime = System.currentTimeMillis()
            startTimeOffset = 0L
            _currentTimeMs.value = 0L

            Log.d("RecordingPlayback", "Started playback of recording with MediaPlayer")

            // Start visual updates (key presses) synchronized with audio
            startVisualUpdates(recording)

        } catch (e: Exception) {
            Log.e("RecordingPlayback", "Error starting playback", e)
            stopPlayback()
        }
    }

    /**
     * Handle visual updates (pressed keys) synchronized with MediaPlayer
     */
    private fun startVisualUpdates(recording: MidiRecording) {
        val events = recording.recordedEvents.sortedBy { it.timestamp }

        visualUpdateJob = coroutineScope.launch {
            val activeNotes = mutableSetOf<Int>()
            val startTime = System.currentTimeMillis()

            for (event in events) {
                if (!isActive || !_isPlaying.value) break

                // Calculate when this event should happen (adjusted for playback speed)
                val targetTime = (event.timestamp / playbackSpeed).toLong()
                val currentPlaybackTime = System.currentTimeMillis() - startTime
                val waitTime = targetTime - currentPlaybackTime

                if (waitTime > 0) {
                    delay(waitTime)
                }

                // Update current time based on MediaPlayer position if available
                mediaPlayer?.let { player ->
                    try {
                        val mediaPlayerPos = player.currentPosition.toLong()
                        _currentTimeMs.value = (mediaPlayerPos * playbackSpeed).toLong()
                    } catch (e: Exception) {
                        // Fallback to calculated time
                        _currentTimeMs.value = (event.timestamp / playbackSpeed).toLong()
                    }
                }

                // Update visual state
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
            }
        }
    }

    /**
     * Pause playback
     */
    fun pausePlayback() {
        _isPlaying.value = false
        mediaPlayer?.pause()
        playbackJob?.cancel()
        visualUpdateJob?.cancel()

        // Store current position
        mediaPlayer?.let { player ->
            try {
                startTimeOffset = (player.currentPosition * playbackSpeed).toLong()
            } catch (e: Exception) {
                startTimeOffset = _currentTimeMs.value
            }
        }
    }

    /**
     * Resume playback from current position
     */
    fun resumePlayback() {
        if (currentRecording != null && !_isPlaying.value) {
            try {
                mediaPlayer?.let { player ->
                    val seekPosition = (startTimeOffset / playbackSpeed).toInt()
                    player.seekTo(seekPosition)
                    player.start()
                    _isPlaying.value = true

                    playbackStartTime = System.currentTimeMillis() - (startTimeOffset / playbackSpeed).toLong()

                    // Resume visual updates from current position
                    startVisualUpdatesFromPosition(currentRecording!!, startTimeOffset)
                }
            } catch (e: Exception) {
                Log.e("RecordingPlayback", "Error resuming playback", e)
                stopPlayback()
            }
        }
    }

    private fun startVisualUpdatesFromPosition(recording: MidiRecording, fromTimeMs: Long) {
        // Filter events from the current position
        val remainingEvents = recording.recordedEvents
            .filter { it.timestamp >= fromTimeMs }
            .sortedBy { it.timestamp }

        // Calculate current active notes at this position
        val activeNotes = mutableSetOf<Int>()
        recording.recordedEvents
            .filter { it.timestamp <= fromTimeMs }
            .sortedBy { it.timestamp }
            .forEach { event ->
                when {
                    event.isNoteOn -> activeNotes.add(event.note)
                    event.isNoteOff -> activeNotes.remove(event.note)
                }
            }

        _pressedKeys.value = activeNotes.toSet()

        visualUpdateJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()

            for (event in remainingEvents) {
                if (!isActive || !_isPlaying.value) break

                val targetTime = ((event.timestamp - fromTimeMs) / playbackSpeed).toLong()
                val currentPlaybackTime = System.currentTimeMillis() - startTime
                val waitTime = targetTime - currentPlaybackTime

                if (waitTime > 0) {
                    delay(waitTime)
                }

                // Update time from MediaPlayer
                mediaPlayer?.let { player ->
                    try {
                        _currentTimeMs.value = (player.currentPosition * playbackSpeed).toLong()
                    } catch (e: Exception) {
                        _currentTimeMs.value = fromTimeMs + (System.currentTimeMillis() - startTime)
                    }
                }

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
        }
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        visualUpdateJob?.cancel()
        playbackJob = null
        visualUpdateJob = null

        _pressedKeys.value = emptySet()
        _currentTimeMs.value = 0L
        startTimeOffset = 0L

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w("RecordingPlayback", "Error stopping MediaPlayer", e)
        }

        // Clean up temp file
        tempMidiFile?.let { file ->
            if (file.exists()) {
                file.delete()
                Log.d("RecordingPlayback", "Deleted temp MIDI file")
            }
        }
        tempMidiFile = null

        Log.d("RecordingPlayback", "Playback stopped")
    }

    /**
     * Seek to a specific position in the recording
     */
    fun seekTo(positionMs: Long) {
        val recording = currentRecording ?: return
        val targetPosition = positionMs.coerceIn(0L, recording.durationMs)

        startTimeOffset = targetPosition
        _currentTimeMs.value = targetPosition

        try {
            mediaPlayer?.let { player ->
                val seekPosition = (targetPosition / playbackSpeed).toInt()
                player.seekTo(seekPosition)
            }
        } catch (e: Exception) {
            Log.w("RecordingPlayback", "Error seeking MediaPlayer", e)
        }

        // Update pressed keys for new position
        val activeNotes = mutableSetOf<Int>()
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
            // Restart visual updates from new position
            visualUpdateJob?.cancel()
            startVisualUpdatesFromPosition(recording, targetPosition)
        }
    }

    /**
     * Set playback speed
     */
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 2.0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.setPlaybackParams(
                    PlaybackParams().apply {
                        this.speed = playbackSpeed
                        pitch = 1.0f
                    }
                )
            } catch (e: Exception) {
                Log.w("RecordingPlayback", "Error setting playback speed", e)
            }
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