// io.pianosync.midi.data.manager.MidiPlaybackManager.kt
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
import io.pianosync.midi.ui.screens.player.MidiNote
import io.pianosync.midi.ui.screens.player.HandMode // Import HandMode
import io.pianosync.midi.data.parser.MidiWriter // Import MidiWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File // For deleting temp files

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


    fun processNoteAtPlayLine(note: MidiNote, currentTime: Long) {
        if (!_isPlaying.value || note in playedNotes) return

        if (currentTime >= note.startTime && note !in playedNotes) {
            playedNotes.add(note)
        }
    }

    fun getOriginalBpm(): Int {
        return originalBpm
    }

    // Modified startPlayback to accept all parsed notes and the hand mode
    fun startPlayback(midiFile: MidiFile,
                      bpm: Int,
                      offset: Long = 0L,
                      allMidiNotes: List<MidiNote>, // NEW: The full list of parsed MidiNotes
                      handMode: HandMode // NEW: The selected hand mode
    ) {
        try {
            // Stop any existing playback and clean up previous temp file
            stopPlayback()
            deleteTempFile() // Ensure any old temp file is removed

            // Set BPM and calculate playback speed
            currentBpm = bpm
            originalBpm = midiFile.originalBpm ?: 120
            val bpmRatio = currentBpm.toFloat() / originalBpm.toFloat()
            playbackSpeed = when {
                bpmRatio > 2.0f -> 2.0f
                bpmRatio < 0.5f -> 0.5f
                else -> bpmRatio
            }

            val uriToPlay: Uri
            if (handMode == HandMode.BOTH_HANDS) {
                // If playing both hands, use the original file path
                uriToPlay = Uri.parse(midiFile.path)
                tempMidiFileUri = null // No temp file needed
            } else {
                // Otherwise, generate a temporary MIDI file with filtered notes
                tempMidiFileUri = MidiWriter.writeFilteredMidiFile(
                    context,
                    allMidiNotes,
                    originalBpm, // Pass original BPM for tick calculation
                    handMode
                )
                uriToPlay = tempMidiFileUri!!
                Log.d("MidiPlayback", "Generated temporary MIDI file: $uriToPlay for hand mode: $handMode")
            }

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
                    stopPlayback()
                    deleteTempFile() // Delete temp file on completion
                    onPlaybackCompletedCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MidiPlayback", "MediaPlayer error: $what, $extra")
                    _playbackError.value = "Error playing audio"
                    stopPlayback()
                    deleteTempFile() // Delete temp file on error
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
                    // Adjust elapsed time by playback speed for accurate current time
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = offset + (elapsedRealTime * playbackSpeed).toLong()
                    delay(16) // Update roughly 60 times per second
                }
            }
        } catch (e: Exception) {
            _playbackError.value = "Error playing MIDI file: ${e.message}"
            Log.e("MidiPlayback", "Error starting playback", e)
            deleteTempFile() // Ensure temp file is cleaned up on error
        }
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        playbackJob?.cancel()
        pausedPosition = _currentTimeMs.value
    }

    // resumePlayback doesn't change the hand mode, it just continues.
    // If you want hand mode changes during pause to apply on resume,
    // you'd need to re-call startPlayback.
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
                    delay(16)
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