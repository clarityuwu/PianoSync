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
            currentBpm = bpm
            originalBpm = midiFile.bpm ?: 120
            startTimeOffset = offset

            // Calculate playback speed based on BPM ratio
            // If BPM is very fast, we'll slow down the audio but speed up the time tracking
            val bpmRatio = currentBpm.toFloat() / originalBpm.toFloat()
            playbackSpeed = if (bpmRatio > 2.0f) {
                // For very fast BPMs, slow down audio but compensate with faster time tracking
                2.0f
            } else if (bpmRatio < 0.5f) {
                // For very slow BPMs, ensure audio doesn't get too slow
                0.5f
            } else {
                bpmRatio
            }

            val uri = Uri.parse(midiFile.path)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()

                // Set playback speed
                PlaybackParams().apply {
                    speed = playbackSpeed
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

            // Start time tracking with BPM scaling and offset
            playbackJob = coroutineScope.launch {
                while (isActive && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    val elapsedTime = (now - lastPlayStartTime)

                    // Calculate scaled time based on BPM ratio
                    val timeScale = currentBpm.toFloat() / (originalBpm.toFloat() * playbackSpeed)
                    val scaledTime = (elapsedTime * timeScale).toLong()

                    _currentTimeMs.value = accumulatedPlayTime + scaledTime

                    Log.d("MidiPlayback", """
                        Elapsed: $elapsedTime
                        Scaled: $scaledTime
                        Current: ${_currentTimeMs.value}
                        BPM Scale: $timeScale
                        Speed: $playbackSpeed
                    """.trimIndent())

                    delay(16) // ~60 FPS update
                }
            }

            Log.d("MidiPlayback", """
                Started playback:
                Original BPM: $originalBpm
                Target BPM: $currentBpm
                Playback Speed: $playbackSpeed
                Time Offset: $startTimeOffset
            """.trimIndent())
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

        // Scale accumulated time based on BPM ratio
        val timeScale = currentBpm.toFloat() / (originalBpm.toFloat() * playbackSpeed)
        val elapsedTime = System.currentTimeMillis() - lastPlayStartTime
        accumulatedPlayTime += (elapsedTime * timeScale).toLong()
    }

    fun resetPlayback() {
        stopPlayback()
        _currentTimeMs.value = startTimeOffset
        accumulatedPlayTime = startTimeOffset
        lastPlayStartTime = System.currentTimeMillis()
    }

    fun cleanup() {
        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}