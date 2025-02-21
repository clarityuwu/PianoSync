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
    private var currentBpm = 120
    private var originalBpm = 120
    private var startTimeOffset = 0L
    private var playbackSpeed = 1.0f
    private var playedNotes = mutableSetOf<MidiNote>()
    private var pausedPosition = 0L

    fun processNoteAtPlayLine(note: MidiNote, currentTime: Long) {
        if (!_isPlaying.value || note in playedNotes) return

        if (currentTime >= note.startTime && note !in playedNotes) {
            playedNotes.add(note)
        }
    }

    fun startPlayback(midiFile: MidiFile, bpm: Int, offset: Long = 0L) {
        try {
            // Stop any existing playback
            stopPlayback()

            // Set BPM and calculate playback speed
            currentBpm = bpm
            originalBpm = midiFile.originalBpm ?: 120
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setPlaybackParams(PlaybackParams().apply {
                        speed = playbackSpeed
                        pitch = 1.0f
                    })
                }
                seekTo(offset.toInt())
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, what, extra ->
                    Log.e("MidiPlayback", "MediaPlayer error: $what, $extra")
                    _playbackError.value = "Error playing audio"
                    stopPlayback()
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
                    delay(16)
                }
            }
        } catch (e: Exception) {
            _playbackError.value = "Error playing MIDI file: ${e.message}"
            Log.e("MidiPlayback", "Error starting playback", e)
        }
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
    }

    fun cleanup() {
        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}