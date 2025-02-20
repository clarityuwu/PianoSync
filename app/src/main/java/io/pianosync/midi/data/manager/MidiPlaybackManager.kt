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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var playbackJob: Job? = null
    private var lastPlayStartTime = 0L
    private var accumulatedPlayTime = 0L
    private var currentBpm = 120
    private var originalBpm = 120
    private var startTimeOffset = 0L
    private var playbackSpeed = 1.0f

    fun startPlayback(midiFile: MidiFile, bpm: Int, offset: Long = 0L) {
        try {
            // Stop any existing playback
            stopPlayback()

            // Set BPM and calculate playback speed
            currentBpm = bpm
            originalBpm = midiFile.bpm ?: 120
            val bpmRatio = currentBpm.toFloat() / originalBpm.toFloat()
            playbackSpeed = when {
                bpmRatio > 2.0f -> 2.0f
                bpmRatio < 0.5f -> 0.5f
                else -> bpmRatio
            }

            // Initialize MediaPlayer
            val uri = Uri.parse(midiFile.path)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                // Set playback speed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setPlaybackParams(PlaybackParams().apply {
                        speed = playbackSpeed
                        pitch = 1.0f  // Preserve pitch
                    })
                }
                // Seek to the offset
                seekTo(offset.toInt())
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, what, extra ->
                    Log.e("MidiPlayback", "MediaPlayer error: $what, $extra")
                    _playbackError.value = "Error playing audio"
                    stopPlayback()
                    true
                }
            }

            // Initialize time tracking
            _currentTimeMs.value = offset
            val startRealTime = System.currentTimeMillis()

            // Start playback
            mediaPlayer?.start()
            _isPlaying.value = true

            // Time tracking coroutine
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedRealTime = now - startRealTime
                    _currentTimeMs.value = offset + (elapsedRealTime * playbackSpeed).toLong()
                    delay(16) // Approximately 60 FPS
                }
            }
        } catch (e: Exception) {
            _playbackError.value = "Error playing MIDI file: ${e.message}"
            Log.e("MidiPlayback", "Error starting playback", e)
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        _isPlaying.value = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("MidiPlayback", "Error stopping playback", e)
        }
        // No need to accumulate time here since startPlayback initializes anew
    }

    fun resetPlayback() {
        stopPlayback()
        _currentTimeMs.value = 0L  // Reset to start, or keep startTimeOffset if desired
    }

    fun cleanup() {
        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}