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
    private var originalBpm = 120 // Store the original MIDI file's BPM

    fun startPlayback(midiFile: MidiFile, bpm: Int) {
        try {
            currentBpm = bpm
            originalBpm = midiFile.bpm ?: 120 // Get original BPM from MIDI file
            val speedRatio = currentBpm.toFloat() / originalBpm.toFloat()

            val uri = Uri.parse(midiFile.path)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()

                // Set playback speed based on BPM ratio
                PlaybackParams().apply {
                    speed = speedRatio
                    pitch = 1.0f  // Keep original pitch
                    setPlaybackParams(this)
                }

                setOnCompletionListener {
                    stopPlayback()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MidiPlayback", "MediaPlayer error: $what, $extra")
                    _playbackError.value = "Error playing audio"
                    stopPlayback()
                    true
                }
            }

            // Start playback and timing
            mediaPlayer?.start()
            _isPlaying.value = true
            lastPlayStartTime = System.currentTimeMillis()

            // Start time tracking without BPM scaling (since we're adjusting playback speed)
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    _currentTimeMs.value = accumulatedPlayTime + (now - lastPlayStartTime)
                    delay(16) // ~60 FPS update
                }
            }

            Log.d("MidiPlayback", "Started playback with speed ratio: $speedRatio (Original BPM: $originalBpm, Target BPM: $currentBpm)")
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

        accumulatedPlayTime += System.currentTimeMillis() - lastPlayStartTime
    }

    fun resetPlayback() {
        stopPlayback()
        _currentTimeMs.value = 0
        accumulatedPlayTime = 0
        lastPlayStartTime = System.currentTimeMillis()
    }

    fun cleanup() {
        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}