package io.pianosync.midi.data.manager

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.net.Uri
import android.os.Build
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.parser.MidiWriter
import io.pianosync.midi.ui.screens.player.MidiNote
import io.pianosync.midi.ui.screens.player.HandMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

class MidiPlaybackManager(
    private val context: Context,
    private val connectionManager: MidiConnectionManager
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()

    // Loop related state
    private val _isLoopEnabled = MutableStateFlow(false)
    val isLoopEnabled: StateFlow<Boolean> = _isLoopEnabled.asStateFlow()

    private val _loopStartMs = MutableStateFlow(0L)
    val loopStartMs: StateFlow<Long> = _loopStartMs.asStateFlow()

    private val _loopEndMs = MutableStateFlow(0L)
    val loopEndMs: StateFlow<Long> = _loopEndMs.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    private var onPlaybackCompletedCallback: (() -> Unit)? = null
    private var playbackJob: Job? = null
    private var currentBpm = 120
    private var originalBpm = 120
    private var startTimeOffset = 0L
    private var playbackSpeed = 1.0f
    private var playedNotes = mutableSetOf<MidiNote>()
    private var pausedPosition = 0L
    private var tempMidiFileUri: Uri? = null // Store the URI of the temp file
    private var currentMidiFile: MidiFile? = null
    private var currentAllMidiNotes: List<MidiNote> = emptyList()
    private var currentHandMode: HandMode = HandMode.BOTH_HANDS

    private fun adjustNotesForBpmChange(notes: List<MidiNote>, originalBpm: Int, targetBpm: Int): List<MidiNote> {
        if (originalBpm == targetBpm) return notes

        val timeRatio = originalBpm.toDouble() / targetBpm.toDouble()

        return notes.map { note ->
            note.copy(
                startTime = (note.startTime * timeRatio).toLong(),
                duration = (note.duration * timeRatio).toLong()
            )
        }
    }

    fun processNoteAtPlayLine(note: MidiNote, currentTime: Long) {
        if (!_isPlaying.value || note in playedNotes) return

        if (currentTime >= note.startTime && note !in playedNotes) {
            playedNotes.add(note)
        }
    }

    fun getOriginalBpm(): Int {
        return originalBpm
    }

    // Set loop points
    fun setLoopPoints(startMs: Long, endMs: Long) {
        if (startMs < endMs) {
            _loopStartMs.value = startMs
            _loopEndMs.value = endMs
        }
    }

    // Toggle loop mode
    fun toggleLoopMode(enabled: Boolean) {
        _isLoopEnabled.value = enabled
    }

    fun startPlayback(
        midiFile: MidiFile,
        bpm: Int,
        offset: Long = 0L,
        allMidiNotes: List<MidiNote>,
        handMode: HandMode
    ) {
        try {
            // Store these for potential looping
            currentMidiFile = midiFile
            currentHandMode = handMode

            // Stop any existing playback and clean up previous temp file
            stopPlayback()
            deleteTempFile()

            // Set BPM and calculate playback speed
            currentBpm = bpm
            originalBpm = midiFile.originalBpm ?: 120

            val uriToPlay: Uri
            val notesToUse: List<MidiNote>

            if (handMode == HandMode.BOTH_HANDS) {
                // Use original file and original notes
                uriToPlay = Uri.parse(midiFile.path)
                tempMidiFileUri = null
                notesToUse = allMidiNotes

                // Calculate playback speed for original file
                val bpmRatio = currentBpm.toFloat() / originalBpm.toFloat()
                playbackSpeed = when {
                    bpmRatio > 2.0f -> 2.0f
                    bpmRatio < 0.5f -> 0.5f
                    else -> bpmRatio
                }
            } else {
                // For filtered playback, create temp file with CURRENT BPM (not original)
                // This ensures audio and visual timing stay consistent

                val filteredNotes = when (handMode) {
                    HandMode.LEFT_HAND_ONLY -> allMidiNotes.filter { it.isLeftHand }
                    HandMode.RIGHT_HAND_ONLY -> allMidiNotes.filter { !it.isLeftHand }
                    HandMode.BOTH_HANDS -> allMidiNotes
                }

                // Write the MIDI file using the CURRENT BPM, not original BPM
                tempMidiFileUri = MidiWriter.writeFilteredMidiFile(
                    context,
                    filteredNotes,
                    currentBpm, // Use current BPM instead of original BPM
                    handMode
                )
                uriToPlay = tempMidiFileUri!!

                // Adjust the notes timing to match the current BPM for visual sync
                notesToUse = adjustNotesForBpmChange(filteredNotes, originalBpm, currentBpm)

                // No additional playback speed needed since file is already at correct BPM
                playbackSpeed = 1.0f

                Log.d("MidiPlayback", "Generated temporary MIDI file at ${currentBpm} BPM for hand mode: $handMode")
            }

            // Store the adjusted notes for visual sync
            currentAllMidiNotes = notesToUse

            // Initialize MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uriToPlay)
                prepare()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setPlaybackParams(PlaybackParams().apply {
                        speed = playbackSpeed
                        pitch = 1.0f
                    })
                }

                seekTo(offset.toInt())
                setOnCompletionListener {
                    if (_isLoopEnabled.value) {
                        seekToLoopStart()
                    } else {
                        stopPlayback()
                        deleteTempFile()
                        onPlaybackCompletedCallback?.invoke()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MidiPlayback", "MediaPlayer error: $what, $extra")
                    _playbackError.value = "Error playing audio"
                    stopPlayback()
                    deleteTempFile()
                    true
                }
            }

            _currentTimeMs.value = offset
            startTimeOffset = offset
            val startRealTime = System.currentTimeMillis()

            mediaPlayer?.start()
            _isPlaying.value = true

            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = offset + (elapsedRealTime * playbackSpeed).toLong()

                    // Check if we need to loop
                    if (_isLoopEnabled.value && _currentTimeMs.value >= _loopEndMs.value) {
                        seekToLoopStart()
                    }

                    delay(8)
                }
            }
        } catch (e: Exception) {
            _playbackError.value = "Error playing MIDI file: ${e.message}"
            Log.e("MidiPlayback", "Error starting playback", e)
            deleteTempFile()
        }
    }

    // Seek to the loop start point
    private fun seekToLoopStart() {
        if (!_isLoopEnabled.value) return

        val loopStartTime = _loopStartMs.value

        mediaPlayer?.apply {
            seekTo(loopStartTime.toInt())

            // Reset playback tracking
            startTimeOffset = loopStartTime
            val startRealTime = System.currentTimeMillis()

            playbackJob?.cancel()
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = loopStartTime + (elapsedRealTime * playbackSpeed).toLong()

                    // Check if we need to loop again
                    if (_isLoopEnabled.value && _currentTimeMs.value >= _loopEndMs.value) {
                        seekToLoopStart()
                        break
                    }

                    delay(8)
                }
            }
        }

        // Reset played notes for the loop region
        playedNotes.clear()
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        playbackJob?.cancel()
        pausedPosition = _currentTimeMs.value
    }

    fun resumePlayback(midiFile: MidiFile) {
        mediaPlayer?.apply {
            seekTo(pausedPosition.toInt())
            start()
            _isPlaying.value = true

            val startRealTime = System.currentTimeMillis()
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = pausedPosition + (elapsedRealTime * playbackSpeed).toLong()

                    // Check if we need to loop
                    if (_isLoopEnabled.value && _currentTimeMs.value >= _loopEndMs.value) {
                        seekToLoopStart()
                        break
                    }

                    delay(8)
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        _isPlaying.value = false
        playedNotes.clear()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            deleteTempFile() // Delete temp file on stop
        } catch (e: Exception) {
            Log.e("MidiPlayback", "Error stopping playback", e)
        }
    }

    fun resetPlayback() {
        stopPlayback()
        _currentTimeMs.value = 0L
        startTimeOffset = 0L
        pausedPosition = 0L
        playedNotes.clear()
        deleteTempFile() // Delete temp file on reset
    }

    // Manually seek to a specific position
    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        pausedPosition = positionMs
        _currentTimeMs.value = positionMs

        // If we were playing, update the start time for accurate tracking
        if (_isPlaying.value) {
            startTimeOffset = positionMs
            val startRealTime = System.currentTimeMillis()

            playbackJob?.cancel()
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = positionMs + (elapsedRealTime * playbackSpeed).toLong()

                    // Check if we need to loop
                    if (_isLoopEnabled.value && _currentTimeMs.value >= _loopEndMs.value) {
                        seekToLoopStart()
                        break
                    }

                    delay(8)
                }
            }
        }

        // Clear played notes to reset visual state
        playedNotes.clear()
    }

    fun cleanup() {
        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
        deleteTempFile() // Delete temp file on cleanup
    }

    private fun deleteTempFile() {
        tempMidiFileUri?.path?.let { path ->
            val file = File(path)
            if (file.exists()) {
                if (file.delete()) {
                    Log.d("MidiPlayback", "Deleted temporary MIDI file: $path")
                } else {
                    Log.w("MidiPlayback", "Failed to delete temporary MIDI file: $path")
                }
            }
        }
        tempMidiFileUri = null // Clear the URI reference
    }
}