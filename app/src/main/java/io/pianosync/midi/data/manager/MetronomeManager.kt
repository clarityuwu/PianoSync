package io.pianosync.midi.data.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.pianosync.midi.R

/**
 * Manages metronome functionality with visual and audio feedback
 */
class MetronomeManager(private val context: Context) {
    private val soundPool: SoundPool
    private var tickSoundId: Int = 0
    private var tockSoundId: Int = 0  // For first beat emphasis
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var metronomeJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentBeat = MutableStateFlow(0)
    val currentBeat: StateFlow<Int> = _currentBeat.asStateFlow()

    private var bpm: Int = 120
    private var beatsPerMeasure: Int = 4
    private var playFirstBeatSound: Boolean = true
    private var volume: Float = 1.0f

    init {
        // Initialize SoundPool
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        // Load metronome sounds
        tickSoundId = soundPool.load(context, R.raw.metronome_tick, 1)
        tockSoundId = soundPool.load(context, R.raw.metronome_tock, 1)

        Log.d("MetronomeManager", "Initialized with sound IDs - tick: $tickSoundId, tock: $tockSoundId")
    }

    /**
     * Start the metronome with the given parameters
     */
    fun start(
        bpm: Int,
        beatsPerMeasure: Int = 4,
        playFirstBeatSound: Boolean = true,
        volume: Float = 1.0f
    ) {
        this.bpm = bpm
        this.beatsPerMeasure = beatsPerMeasure
        this.playFirstBeatSound = playFirstBeatSound
        this.volume = volume.coerceIn(0f, 1f)

        if (_isRunning.value) {
            stop()
        }

        _isRunning.value = true
        _currentBeat.value = 0

        // Calculate delay between beats in ms
        val beatDelayMs = (60000 / bpm).toLong()

        Log.d("MetronomeManager", "Starting metronome at $bpm BPM, delay: $beatDelayMs ms")

        metronomeJob = coroutineScope.launch {
            while (isActive && _isRunning.value) {
                // Play appropriate sound
                if (_currentBeat.value == 0 && playFirstBeatSound) {
                    soundPool.play(tockSoundId, volume, volume, 1, 0, 1f)
                } else {
                    soundPool.play(tickSoundId, volume, volume, 1, 0, 1f)
                }

                // Update beat counter
                _currentBeat.value = (_currentBeat.value + 1) % beatsPerMeasure

                // Wait for next beat
                delay(beatDelayMs)
            }
        }
    }

    /**
     * Stop the metronome
     */
    fun stop() {
        if (!_isRunning.value) return

        Log.d("MetronomeManager", "Stopping metronome")
        _isRunning.value = false
        metronomeJob?.cancel()
        metronomeJob = null
    }

    /**
     * Toggle metronome on/off
     */
    fun toggleMetronome(
        bpm: Int,
        beatsPerMeasure: Int = 4,
        playFirstBeatSound: Boolean = true,
        volume: Float = 1.0f
    ) {
        if (_isRunning.value) {
            stop()
        } else {
            start(bpm, beatsPerMeasure, playFirstBeatSound, volume)
        }
    }

    /**
     * Update BPM without stopping if already running
     */
    fun updateBpm(bpm: Int) {
        if (this.bpm != bpm && _isRunning.value) {
            // Restart with new BPM
            val wasRunning = _isRunning.value
            stop()
            if (wasRunning) {
                start(bpm, beatsPerMeasure, playFirstBeatSound, volume)
            }
        } else {
            this.bpm = bpm
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stop()
        soundPool.release()
    }
}