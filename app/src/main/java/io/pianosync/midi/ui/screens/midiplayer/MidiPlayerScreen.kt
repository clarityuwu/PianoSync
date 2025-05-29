package io.pianosync.midi.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.data.manager.MetronomeManager
import io.pianosync.midi.data.manager.MidiConnectionManager
import io.pianosync.midi.data.manager.MidiPlaybackManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.data.model.PlayedNote
import io.pianosync.midi.data.parser.MidiParser
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.data.repository.MidiRecordingRepository
import io.pianosync.midi.data.repository.PerformanceRepository
import io.pianosync.midi.data.repository.SettingsRepository
import io.pianosync.midi.data.model.AppSettings
import io.pianosync.midi.data.model.DifficultyLevel
import io.pianosync.midi.ui.screens.player.components.LoopControl
import io.pianosync.midi.ui.screens.player.components.MetronomeVisualizer
import io.pianosync.midi.ui.theme.AccentRose
import io.pianosync.midi.ui.theme.RoyalPurple40
import io.pianosync.midi.ui.theme.WarmGold60
import io.pianosync.midi.ui.theme.WarmGold80
import io.pianosync.midi.ui.theme.highlightAccentColor
import io.pianosync.midi.ui.theme.leftHandNoteColor
import io.pianosync.midi.ui.theme.rightHandNoteColor
import io.pianosync.midi.ui.theme.successAccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import io.pianosync.midi.R

/**
 * Get the note name for a MIDI note number
 */
fun getNoteNameForMidiNote(midiNote: Int): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = (midiNote / 12) - 1
    val noteIndex = midiNote % 12
    return "${noteNames[noteIndex]}$octave"
}

/**
 * Check if a MIDI note is a white key
 */
fun isWhiteKey(note: Int): Boolean {
    return when (note % 12) {
        0, 2, 4, 5, 7, 9, 11 -> true // C, D, E, F, G, A, B
        else -> false
    }
}

fun calculateNotePosition(
    note: Int,
    minNote: Int,
    keyWidth: Float,
    isBlackKey: Boolean
): Float {
    val whiteKeysBefore = (minNote until note).count { isWhiteKey(it) }
    return if (isBlackKey) {
        // Position black key relative to previous white key
        val prevWhiteKey = (note - 1 downTo minNote).first { isWhiteKey(it) }
        val whiteKeysBeforePrev = (minNote until prevWhiteKey).count { isWhiteKey(it) }
        (whiteKeysBeforePrev * keyWidth) + (keyWidth * 0.7f)
    } else {
        whiteKeysBefore * keyWidth
    }
}

@Composable
fun NoteFallVisualizer(
    modifier: Modifier = Modifier,
    notes: List<MidiNote>,
    currentTimeMs: Long,
    isPlaying: Boolean,
    bpm: Int,
    pianoConfig: PianoConfiguration,
    isPreLoading: Boolean,
    playbackManager: MidiPlaybackManager,
    correctlyPlayedNotes: MutableState<Set<Int>>,
    pressedKeys: Set<Int>,
    settings: AppSettings,
    onNoteProcessed: () -> Unit
) {
    // Get device configuration
    val configuration = LocalConfiguration.current

    // Use settings for timing values
    val PLAYBACK_OFFSET_MS = settings.playbackOffsetMs
    val CORRECT_NOTE_WINDOW = settings.difficultyLevel.correctNoteWindowMs

    val noteHeight = 16.dp
    val futureTimeWindow = 8000L
    val pastTimeWindow = 2000L
    val visualizerHeight = (configuration.screenHeightDp).dp
    val playLinePosition = visualizerHeight - noteHeight
    val processedNotes = remember { mutableStateOf<Set<MidiNote>>(emptySet()) }

    // Add manual X offset to align notes with keys
    val xOffset = 10.dp

    val whiteKeyWidth = pianoConfig.keyWidth
    val whiteNoteWidth = whiteKeyWidth * 0.6f
    val blackNoteWidth = whiteKeyWidth * 0.4f

    // Get original BPM from playback manager
    val originalBpm = playbackManager.getOriginalBpm()
    val speedRatio = if (originalBpm > 0) bpm.toFloat() / originalBpm.toFloat() else 1f

    // This function positions notes vertically based on their time
    fun timeToYPosition(noteTime: Long): Float {
        // Convert the note's time to playback time domain
        val playbackTime = (noteTime / speedRatio).toLong()

        // Add the fixed offset to compensate for the consistent delay
        val adjustedPlaybackTime = playbackTime + PLAYBACK_OFFSET_MS

        // Calculate position based on time difference to current playback time
        val timeDiff = adjustedPlaybackTime - currentTimeMs
        val pixelsPerMs = playLinePosition.value / futureTimeWindow.toFloat()

        return when {
            timeDiff <= -pastTimeWindow -> visualizerHeight.value + 135f
            timeDiff >= futureTimeWindow -> {
                // Position future notes off-screen based on how far in the future they are
                val extraOffset = ((timeDiff - futureTimeWindow) / 500f).coerceAtMost(200f)
                -noteHeight.value - extraOffset
            }
            else -> playLinePosition.value - (timeDiff * pixelsPerMs)
        }
    }

    fun calculateNoteXPosition(note: Int): Float {
        val whiteKeysBefore = (pianoConfig.minNote until note).count { isWhiteKey(it) }
        val isBlackKey = !isWhiteKey(note)

        return if (isBlackKey) {
            val prevWhiteKey = (note - 1 downTo pianoConfig.minNote).first { isWhiteKey(it) }
            val whiteKeysBeforePrev = (pianoConfig.minNote until prevWhiteKey).count { isWhiteKey(it) }
            val basePosition = whiteKeysBeforePrev * whiteKeyWidth + (whiteKeyWidth * 0.7f)
            basePosition - (blackNoteWidth / 2f) + (whiteKeyWidth * 0.1f)
        } else {
            whiteKeysBefore * whiteKeyWidth + ((whiteKeyWidth - whiteNoteWidth) / 2f)
        }
    }

    val totalWhiteKeys = (pianoConfig.minNote..pianoConfig.maxNote).count { isWhiteKey(it) }
    val totalWidth = whiteKeyWidth.dp * totalWhiteKeys

    val visibleNotes = if (isPreLoading) {
        emptyList()
    } else {
        notes.filter { note ->
            val startY = timeToYPosition(note.startTime)
            val endY = timeToYPosition(note.startTime + note.duration)

            // More efficient filtering: only render notes that are within or approaching the visible area
            // Extend the range slightly above screen (-300) to ensure smooth entry
            endY <= visualizerHeight.value + noteHeight.value &&
                    startY <= visualizerHeight.value + 300f &&
                    startY >= -300f &&  // Allow notes to start from further above
                    note.note in pianoConfig.minNote..pianoConfig.maxNote
        }
    }

    LaunchedEffect(currentTimeMs, pressedKeys, isPlaying) {
        if (isPlaying) {
            // Find notes that are currently at the play line (NO offset for input timing)
            val notesAtPlayLine = notes.filter { note ->
                val notePlaybackTime = (note.startTime / speedRatio).toLong()
                val timeDiff = currentTimeMs - notePlaybackTime

                // Simple timing log for notes at play line
                if (timeDiff in 0..CORRECT_NOTE_WINDOW && note !in processedNotes.value) {
                    Log.d("NoteTiming", "Note ${getNoteNameForMidiNote(note.note)} - Expected: ${notePlaybackTime}ms, Current: ${currentTimeMs}ms, Diff: ${timeDiff}ms")
                }

                timeDiff in 0..CORRECT_NOTE_WINDOW && // Within the correct timing window
                        note !in processedNotes.value // Not already processed
            }

            // Check if any of these notes match keys being pressed
            notesAtPlayLine.forEach { note ->
                // Mark this note as processed so we don't count it twice
                if (note !in processedNotes.value) {
                    processedNotes.value = processedNotes.value + note
                    onNoteProcessed() // Tell parent we processed a note

                    if (note.note in pressedKeys) {
                        // Note was correctly played!
                        correctlyPlayedNotes.value = correctlyPlayedNotes.value + note.note
                        Log.d("NoteTiming", "✅ ${getNoteNameForMidiNote(note.note)} HIT")
                    } else {
                        Log.d("NoteTiming", "❌ ${getNoteNameForMidiNote(note.note)} MISSED")
                    }
                }
            }

            // Also check for notes that have passed the play line without being played (NO offset)
            val passedNotes = notes.filter { note ->
                val notePlaybackTime = (note.startTime / speedRatio).toLong()
                val timeDiff = currentTimeMs - notePlaybackTime
                timeDiff > CORRECT_NOTE_WINDOW && // Past the correct timing window
                        note !in processedNotes.value // Not already processed
            }

            passedNotes.forEach { note ->
                // Mark as processed so we don't count it twice
                processedNotes.value = processedNotes.value + note
                onNoteProcessed() // Tell parent we processed a note
            }
        }
    }

    LaunchedEffect(currentTimeMs, isPlaying) {
        if (isPlaying) {
            visibleNotes.forEach { note ->
                // Convert note times to playback time with the same offset
                val playbackStartTime = (note.startTime / speedRatio).toLong() + PLAYBACK_OFFSET_MS
                val playbackEndTime = ((note.startTime + note.duration) / speedRatio).toLong() + PLAYBACK_OFFSET_MS

                // Check if note is crossing the play line in the playback time domain
                if (playbackStartTime <= currentTimeMs &&
                    playbackEndTime > currentTimeMs - 100) {
                    playbackManager.processNoteAtPlayLine(note, currentTimeMs)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background) // Use theme background
            .width(totalWidth)
            .horizontalScroll(rememberScrollState())
    ) {
        // Play line with theme accent
        Box(
            modifier = Modifier
                .offset(y = playLinePosition)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    color = highlightAccentColor(), // Use theme accent
                    shape = RoundedCornerShape(2.dp)
                )
        )

        // Notes
        visibleNotes.forEach { note ->
            val startY = timeToYPosition(note.startTime)
            val endY = timeToYPosition(note.startTime + note.duration)
            val topY = minOf(startY, endY)
            val baseHeight = maxOf((endY - startY).absoluteValue, noteHeight.value)
            val noteHeightPx = when {
                startY >= playLinePosition.value -> 0f // Hide notes below play line
                endY <= 0f -> 0f // Not yet visible
                else -> baseHeight
            }

            if (noteHeightPx > 0) {
                val isBlackKey = !isWhiteKey(note.note)
                val xPos = calculateNoteXPosition(note.note)
                val noteWidth = if (isBlackKey) blackNoteWidth.dp else whiteNoteWidth.dp

                Box(
                    modifier = Modifier
                        .offset(x = (xPos.dp + xOffset), y = topY.dp)
                        .width(noteWidth)
                        .height(noteHeightPx.dp)
                        .background(
                            color = if (!note.isLeftHand) {
                                rightHandNoteColor() // Use theme color instead of hardcoded
                            } else {
                                leftHandNoteColor() // Use theme color instead of hardcoded
                            },
                            shape = RoundedCornerShape(2.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = if (isBlackKey) 0.4f else 0.2f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

data class PianoConfiguration(
    val minNote: Int,
    val maxNote: Int,
    val keyWidth: Float
)

// Add this more compact StatisticItem composable
@Composable
fun CompactStatisticItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium, // Changed from titleLarge
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall, // Changed from bodyMedium
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

enum class HandMode {
    BOTH_HANDS,
    LEFT_HAND_ONLY,
    RIGHT_HAND_ONLY
}

private fun formatRecordingTime(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiPlayerScreen(
    midiFile: MidiFile,
    repository: MidiFileRepository,
    performanceRepository: PerformanceRepository,
    recordingRepository: MidiRecordingRepository, // This is passed from MainActivity
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())

    var showTopBar by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var countdownSeconds by remember { mutableStateOf(3) }
    var showCountdown by remember { mutableStateOf(true) }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val horizontalPadding = 16 // Total horizontal padding
    val scope = rememberCoroutineScope()
    var currentBpm by remember { mutableStateOf(midiFile.currentBpm) }
    var showBpmDialog by remember { mutableStateOf(false) }
    val midiConnectionManager = remember { MidiConnectionManager.getInstance(context) }
    val pressedKeys by midiConnectionManager.pressedKeys.collectAsState()
    var isPreLoading by remember { mutableStateOf(true) }
    var midiNotes by remember { mutableStateOf<List<MidiNote>>(emptyList()) }
    var currentHandMode by remember { mutableStateOf(HandMode.BOTH_HANDS) }
    var pianoConfig by remember { mutableStateOf<PianoConfiguration?>(null) }
    val playbackManager = remember { MidiPlaybackManager(context, midiConnectionManager) }
    var songDurationMs by remember { mutableStateOf(0L) }
    val isPlaybackActive by playbackManager.isPlaying.collectAsState()
    val isLoopEnabled by playbackManager.isLoopEnabled.collectAsState()
    val loopStartMs by playbackManager.loopStartMs.collectAsState()
    val loopEndMs by playbackManager.loopEndMs.collectAsState()
    val currentTimeMs by playbackManager.currentTimeMs.collectAsState()
    val metronomeManager = remember { MetronomeManager(context) }
    var metronomeEnabled by remember { mutableStateOf(false) }
    val currentMetronomeBeat by metronomeManager.currentBeat.collectAsState()
    val isMetronomeRunning by metronomeManager.isRunning.collectAsState()
    var metronomeBeatCount by remember { mutableStateOf(4) }
    var sessionStartTimeMs by remember { mutableStateOf(0L) }
    var isSavingPerformance by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    val recordingManager = remember { midiConnectionManager.getRecordingManager() }
    var wasManuallyPaused by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    val isConnected by midiConnectionManager.isConnected.collectAsState()

    val view = LocalView.current
    DisposableEffect(isPlaybackActive) {
        if (isPlaybackActive) {
            // Keep screen on when playback is active
            view.keepScreenOn = true
        } else {
            // Allow screen to turn off when playback is inactive
            view.keepScreenOn = false
        }
        onDispose {
            // Make sure to reset when leaving the screen
            view.keepScreenOn = false
        }
    }

    val activeNotes = remember(currentTimeMs, midiNotes) {
        midiNotes.filter { note ->
            note.startTime <= currentTimeMs &&
                    note.startTime + note.duration > currentTimeMs
        }
    }

    var showScoreDialog by remember { mutableStateOf(false) }
    var totalNotesPlayed by remember { mutableStateOf(0) }
    var totalNotesInSong by remember { mutableStateOf(0) }
    val correctlyPlayedNotes = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val missedNotes = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hasStartedPlaying by remember { mutableStateOf(false) }
    var isNearEndOfSong by remember { mutableStateOf(false) }
    var endOfSongTimerStarted by remember { mutableStateOf(false) }

    // Track which notes have passed the play line
    val processedNotes = remember { mutableStateOf<Set<MidiNote>>(emptySet()) }

    // Calculate score as a derived state
    val score = remember {
        derivedStateOf {
            if (totalNotesInSong == 0) 0
            else (correctlyPlayedNotes.value.size.toFloat() / totalNotesInSong * 100).roundToInt()
        }
    }

    LaunchedEffect(midiNotes, currentHandMode) {
        // Update the note count whenever the hand mode changes
        when (currentHandMode) {
            HandMode.LEFT_HAND_ONLY -> totalNotesInSong = midiNotes.count { it.isLeftHand }
            HandMode.RIGHT_HAND_ONLY -> totalNotesInSong = midiNotes.count { !it.isLeftHand }
            HandMode.BOTH_HANDS -> totalNotesInSong = midiNotes.size
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                recordingDuration = recordingManager.getRecordingDuration()
                delay(100) // Update every 100ms
            }
        }
    }

    // This monitors the playback position but doesn't restart on each time update
    LaunchedEffect(Unit) {
        while (true) {
            if (isPlaybackActive && midiNotes.isNotEmpty() && !isNearEndOfSong && !endOfSongTimerStarted) {
                // Calculate the total estimated song duration
                val lastNoteTime = midiNotes.maxOf { it.startTime + it.duration }
                val speedRatio = (currentBpm ?: 120).toFloat() / (midiFile.originalBpm ?: 120).toFloat()
                val estimatedDuration = lastNoteTime / speedRatio

                if (currentTimeMs > estimatedDuration * 0.999) {
                    isNearEndOfSong = true
                    Log.d("MidiPlayer", "Near end of song detected at $currentTimeMs / $estimatedDuration")
                }

            }
            delay(100) // Check every 100ms instead of every frame
        }
    }

    LaunchedEffect(isNearEndOfSong) {
        if (isNearEndOfSong && !endOfSongTimerStarted) {
            endOfSongTimerStarted = true
            Log.d("MidiPlayer", "Starting end of song timer")

            // Wait for 2 seconds
            delay(2000)

            // If we're still near the end, show the dialog and stop recording
            if (isPlaybackActive) {
                Log.d("MidiPlayer", "Song completion timer finished, showing score dialog")

                // Stop recording automatically when song ends
                if (isRecording) {
                    recordingManager.stopRecording()
                    isRecording = false
                    Log.d("MidiPlayer", "Stopped recording automatically - song ended")
                }

                playbackManager.pausePlayback()
                showScoreDialog = true
            }

            // Reset tracking variables
            isNearEndOfSong = false
            endOfSongTimerStarted = false
        }
    }

    LaunchedEffect(isPlaybackActive) {
        if (!isPlaybackActive && hasStartedPlaying && !isPreLoading && !showScoreDialog) {
            // Only show score dialog if the song actually ended naturally, not if manually paused
            if (!wasManuallyPaused) {
                hasStartedPlaying = false

                // Stop recording when playback stops after significant progress
                if (isRecording) {
                    recordingManager.stopRecording()
                    isRecording = false
                    Log.d("MidiPlayer", "Stopped recording automatically - playback ended")
                }

                // Check if we've played a significant portion and song ended naturally
                val lastNoteTime = if (midiNotes.isNotEmpty()) {
                    midiNotes.maxOf { it.startTime + it.duration }
                } else 0L

                // Only show score if we're very close to the actual end (95% instead of 70%)
                // and this wasn't a manual pause
                if (currentTimeMs > lastNoteTime * 0.95) {
                    Log.d("MidiPlayer", "Playback stopped after significant progress, showing score")
                    showScoreDialog = true
                }
            }
            // Reset the manual pause flag when playback stops
            wasManuallyPaused = false
        } else if (isPlaybackActive && !hasStartedPlaying) {
            hasStartedPlaying = true
        }
    }

    LaunchedEffect(lastInteractionTime) {
        delay(3000)
        showTopBar = false
    }

    DisposableEffect(Unit) {
        onDispose {
            // Stop recording if active when leaving the screen
            if (isRecording) {
                recordingManager.stopRecording()
                Log.d("MidiPlayer", "Stopped recording - screen disposed")
            }

            playbackManager.cleanup()
            metronomeManager.cleanup()
        }
    }

    LaunchedEffect(currentBpm) {
        metronomeManager.updateBpm(currentBpm ?: 120)
    }

    LaunchedEffect(isPlaybackActive, metronomeEnabled, settings.metronomeVolume) {
        if (isPlaybackActive && metronomeEnabled) {
            metronomeManager.start(currentBpm ?: 120, metronomeBeatCount, volume = settings.metronomeVolume)
        } else if (!isPlaybackActive && isMetronomeRunning) {
            metronomeManager.stop()
        }
    }

    LaunchedEffect(midiFile, currentBpm) {
        try {
            val uri = Uri.parse(midiFile.path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Always parse with the original BPM to get correct absolute times
                val originalBpm = midiFile.originalBpm ?: 120
                val notes = MidiParser.parseMidiNotes(inputStream, originalBpm)
                midiNotes = notes

                // Calculate song duration based on the last note end time
                if (notes.isNotEmpty()) {
                    val lastNoteEndTime = notes.maxOf { it.startTime + it.duration }
                    songDurationMs = lastNoteEndTime
                    Log.d("MidiPlayer", "Song duration calculated: $songDurationMs ms")

                    // Set initial loop end to song duration if not already set via playbackManager
                    if (loopEndMs == 0L) {
                        playbackManager.setLoopPoints(0L, songDurationMs)
                    }

                    val minNote = notes.minOf { it.note }
                    val maxNote = notes.maxOf { it.note }

                    val paddedMin = (minNote - 2).coerceAtLeast(21)
                    val paddedMax = (maxNote + 2).coerceAtMost(108)

                    sessionStartTimeMs = System.currentTimeMillis()

                    // Calculate key width based on available screen width
                    val whiteKeyCount = (paddedMin..paddedMax).count { isWhiteKey(it) }
                    val availableWidth = screenWidth - horizontalPadding
                    val keyWidth = (availableWidth.toFloat() / whiteKeyCount).coerceIn(20f, 60f)

                    pianoConfig = PianoConfiguration(
                        minNote = paddedMin,
                        maxNote = paddedMax,
                        keyWidth = keyWidth
                    )
                }

                while (countdownSeconds > 0) {
                    delay(1000)
                    countdownSeconds--
                }
                showCountdown = false

                delay(100)

                playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                isPreLoading = false
            }
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Error loading MIDI file", e)
        }
    }

    LaunchedEffect(isLoopEnabled, loopStartMs, loopEndMs) {
        playbackManager.toggleLoopMode(isLoopEnabled)
        playbackManager.setLoopPoints(loopStartMs, loopEndMs)
    }

    if (pianoConfig == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    lastInteractionTime = System.currentTimeMillis()
                    showTopBar = true
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
            ) {
                if (showTopBar) { // Only render the TopAppBar when visible
                    CenterAlignedTopAppBar(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = 1f // Always fully opaque when rendered
                            },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color(0xFF1A1A1A)
                        ),
                        title = {
                            Text(
                                text = midiFile.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            // Left side icons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            // Check if we have a recording to save
                                            if (isRecording || recordingManager.hasRecordedEvents()) {
                                                Log.d("MidiPlayer", "Saving recording before navigation back...")

                                                // Stop recording if still active
                                                if (isRecording) {
                                                    recordingManager.stopRecording()
                                                    isRecording = false
                                                    Log.d("MidiPlayer", "Stopped recording - navigation back")
                                                }

                                                // Create and save the recording (events are still available)
                                                val recording = recordingManager.createRecording(
                                                    originalMidiFilePath = midiFile.path,
                                                    originalMidiFileName = midiFile.name,
                                                    bpm = currentBpm ?: 120,
                                                    handMode = currentHandMode,
                                                    score = null // No score since we're leaving early
                                                )

                                                recording?.let { rec ->
                                                    Log.d("MidiPlayer", "Created recording with ${rec.recordedEvents.size} events for navigation back")
                                                    try {
                                                        recordingRepository.saveRecording(rec)
                                                        Log.d("MidiPlayer", "MIDI recording saved successfully on navigation back with ${rec.recordedEvents.size} events")

                                                        // Verify it was saved
                                                        val allRecordings = recordingRepository.allRecordings.first()
                                                        Log.d("MidiPlayer", "Total recordings in repository after navigation back: ${allRecordings.size}")

                                                    } catch (e: Exception) {
                                                        Log.e("MidiPlayer", "Failed to save recording on navigation back", e)
                                                    }
                                                } ?: Log.d("MidiPlayer", "No recording created - no events available")
                                            }

                                            playbackManager.cleanup()
                                            onBackPressed()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }

                                // Hand mode selector
                                Box {
                                    var handMenuExpanded by remember { mutableStateOf(false) }

                                    TextButton(
                                        onClick = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            handMenuExpanded = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (currentHandMode) {
                                                HandMode.BOTH_HANDS -> Icons.Default.PanoramaHorizontal
                                                HandMode.LEFT_HAND_ONLY -> Icons.Default.SwipeLeft
                                                HandMode.RIGHT_HAND_ONLY -> Icons.Default.SwipeRight
                                            },
                                            contentDescription = "Hand Mode",
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = when (currentHandMode) {
                                                HandMode.BOTH_HANDS -> stringResource(R.string.both)
                                                HandMode.LEFT_HAND_ONLY -> stringResource(R.string.left)
                                                HandMode.RIGHT_HAND_ONLY -> stringResource(R.string.right)
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = handMenuExpanded,
                                        onDismissRequest = { handMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.both_hands)) },
                                            onClick = {
                                                currentHandMode = HandMode.BOTH_HANDS
                                                handMenuExpanded = false
                                                // Reset and restart playback with the new hand mode
                                                playbackManager.resetPlayback()
                                                playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.PanoramaHorizontal,
                                                    contentDescription = stringResource(R.string.both_hands)
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.left_hand)) },
                                            onClick = {
                                                currentHandMode = HandMode.LEFT_HAND_ONLY
                                                handMenuExpanded = false
                                                playbackManager.resetPlayback()
                                                playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.SwipeLeft,
                                                    contentDescription = stringResource(R.string.left_hand)
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.right_hand)) },
                                            onClick = {
                                                currentHandMode = HandMode.RIGHT_HAND_ONLY
                                                handMenuExpanded = false
                                                playbackManager.resetPlayback()
                                                playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.SwipeRight,
                                                    contentDescription = stringResource(R.string.right_hand)
                                                )
                                            }
                                        )
                                    }
                                }

                                // BPM button
                                TextButton(
                                    onClick = {
                                        lastInteractionTime = System.currentTimeMillis()
                                        showBpmDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "BPM",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        "${currentBpm ?: 0}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                Surface(
                                    color = when(settings.difficultyLevel) {
                                        DifficultyLevel.EASY -> successAccentColor()
                                        DifficultyLevel.MEDIUM -> WarmGold60
                                        DifficultyLevel.HARD -> AccentRose
                                        DifficultyLevel.EXPERT -> RoyalPurple40
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = settings.difficultyLevel.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        },
                        actions = {
                            // Right side icons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .height(48.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            lastInteractionTime = System.currentTimeMillis()
                                        }
                                    }
                            ) {
                                IconButton(
                                    onClick = {
                                        lastInteractionTime = System.currentTimeMillis()
                                        if (isRecording) {
                                            recordingManager.stopRecording()
                                            isRecording = false
                                            Log.d("MidiPlayer", "Stopped recording manually")
                                        } else {
                                            if (isConnected) {
                                                recordingManager.startRecording()
                                                isRecording = true
                                                Log.d("MidiPlayer", "Started recording manually")
                                            }
                                        }
                                    },
                                    enabled = isConnected
                                ) {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecording) stringResource(R.string.stop_recording) else stringResource(R.string.start_recording),
                                        tint = when {
                                            !isConnected -> Color.Gray
                                            isRecording -> Color.Red
                                            else -> Color.White
                                        }
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        lastInteractionTime = System.currentTimeMillis()
                                        metronomeEnabled = !metronomeEnabled

                                        if (metronomeEnabled) {
                                            if (isPlaybackActive) {
                                                metronomeManager.start(currentBpm ?: 120, metronomeBeatCount, volume = settings.metronomeVolume)
                                            }
                                        } else {
                                            metronomeManager.stop()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = stringResource(R.string.metronome),
                                        modifier = Modifier.size(20.dp),
                                        tint = if (metronomeEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                                    )
                                }

                                // Restart button
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            lastInteractionTime = System.currentTimeMillis()

                                            // Save recording before restarting if there's one
                                            if (isRecording || recordingManager.getRecordingDuration() > 0) {
                                                Log.d("MidiPlayer", "Saving recording before restart...")

                                                // Stop recording if still active
                                                if (isRecording) {
                                                    recordingManager.stopRecording()
                                                    isRecording = false
                                                    Log.d("MidiPlayer", "Stopped recording - restart")
                                                }

                                                // Create and save the recording
                                                val recording = recordingManager.createRecording(
                                                    originalMidiFilePath = midiFile.path,
                                                    originalMidiFileName = midiFile.name,
                                                    bpm = currentBpm ?: 120,
                                                    handMode = currentHandMode,
                                                    score = null // No score since we're restarting
                                                )

                                                recording?.let { rec ->
                                                    Log.d("MidiPlayer", "Created recording with ${rec.recordedEvents.size} events for restart")
                                                    try {
                                                        recordingRepository.saveRecording(rec)
                                                        Log.d("MidiPlayer", "MIDI recording saved successfully on restart with ${rec.recordedEvents.size} events")
                                                    } catch (e: Exception) {
                                                        Log.e("MidiPlayer", "Failed to save recording on restart", e)
                                                    }
                                                } ?: Log.d("MidiPlayer", "No recording to save on restart")
                                            }

                                            playbackManager.resetPlayback()
                                            playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Restart"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        lastInteractionTime = System.currentTimeMillis()
                                        if (isPlaybackActive) {
                                            wasManuallyPaused = true
                                            playbackManager.pausePlayback()
                                        } else {
                                            wasManuallyPaused = false
                                            if (currentTimeMs > 0) {
                                                playbackManager.resumePlayback(midiFile)
                                            } else {
                                                playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isPlaybackActive)
                                            Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaybackActive)
                                            stringResource(R.string.pause) else stringResource(R.string.play)
                                    )
                                }
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                NoteFallVisualizer(
                    modifier = Modifier.fillMaxSize(),
                    notes = when (currentHandMode) {
                        HandMode.LEFT_HAND_ONLY -> midiNotes.filter { it.isLeftHand }
                        HandMode.RIGHT_HAND_ONLY -> midiNotes.filter { !it.isLeftHand }
                        HandMode.BOTH_HANDS -> midiNotes
                    },
                    currentTimeMs = currentTimeMs,
                    isPlaying = isPlaybackActive,
                    bpm = currentBpm ?: 120,
                    pianoConfig = pianoConfig!!,
                    isPreLoading = isPreLoading,
                    playbackManager = playbackManager,
                    correctlyPlayedNotes = correctlyPlayedNotes,
                    pressedKeys = pressedKeys,
                    settings = settings, // Pass settings to visualizer
                    onNoteProcessed = {
                        // Make sure we're tracking processed notes
                        totalNotesPlayed++
                    }
                )

                if (showScoreDialog) {
                    val sessionDurationMs = System.currentTimeMillis() - sessionStartTimeMs
                    Dialog(
                        onDismissRequest = {
                            if (!isSavingPerformance) {
                                scope.launch {
                                    isSavingPerformance = true

                                    try {
                                        // Stop recording if still active (backup safety)
                                        if (isRecording) {
                                            recordingManager.stopRecording()
                                            isRecording = false
                                            Log.d("MidiPlayer", "Stopped recording in dialog dismissal")
                                        }

                                        // Save performance data
                                        val performanceRecord = PerformanceRecord(
                                            midiFilePath = midiFile.path,
                                            midiFileName = midiFile.name,
                                            timestamp = System.currentTimeMillis(),
                                            score = score.value,
                                            notesHit = correctlyPlayedNotes.value.size,
                                            notesMissed = totalNotesInSong - correctlyPlayedNotes.value.size,
                                            totalNotes = totalNotesInSong,
                                            bpm = currentBpm ?: 120,
                                            handMode = currentHandMode,
                                            durationMs = sessionDurationMs,
                                            notesPlayed = processedNotes.value.mapIndexed { index, note ->
                                                PlayedNote(
                                                    noteValue = note.note,
                                                    wasCorrect = note.note in correctlyPlayedNotes.value,
                                                    timestamp = System.currentTimeMillis() - (processedNotes.value.size - index) * 100L,
                                                    isLeftHand = note.isLeftHand
                                                )
                                            }.take(200)
                                        )

                                        // Save performance record
                                        performanceRepository.savePerformanceRecord(performanceRecord)
                                        Log.d("MidiPlayer", "Performance record saved successfully")

                                        val recording = recordingManager.createRecording(
                                            originalMidiFilePath = midiFile.path,
                                            originalMidiFileName = midiFile.name,
                                            bpm = currentBpm ?: 120,
                                            handMode = currentHandMode,
                                            score = score.value
                                        )

                                        recording?.let { rec ->
                                            Log.d("MidiPlayer", "Created recording with ${rec.recordedEvents.size} events, duration: ${rec.durationMs}ms")
                                            Log.d("MidiPlayer", "Recording details:")
                                            Log.d("MidiPlayer", "  - MIDI file: ${rec.originalMidiFileName}")
                                            Log.d("MidiPlayer", "  - Path: ${rec.originalMidiFilePath}")
                                            Log.d("MidiPlayer", "  - BPM: ${rec.bpm}")
                                            Log.d("MidiPlayer", "  - Hand mode: ${rec.handMode}")
                                            Log.d("MidiPlayer", "  - Score: ${rec.score}")

                                            try {
                                                recordingRepository.saveRecording(rec)
                                                Log.d("MidiPlayer", "MIDI recording saved successfully with ${rec.recordedEvents.size} events")

                                                // Verify it was saved by checking the repository
                                                val allRecordings = recordingRepository.allRecordings.first()
                                                Log.d("MidiPlayer", "Total recordings in repository after save: ${allRecordings.size}")

                                            } catch (e: Exception) {
                                                Log.e("MidiPlayer", "Failed to save recording", e)
                                            }
                                        } ?: Log.d("MidiPlayer", "No recording to save - recording manager returned null")

                                    } catch (e: Exception) {
                                        Log.e("MidiPlayer", "Failed to save performance/recording", e)
                                    } finally {
                                        isSavingPerformance = false
                                        showScoreDialog = false
                                        isRecording = false  // Ensure recording is stopped

                                        // Reset all tracking variables
                                        correctlyPlayedNotes.value = emptySet()
                                        missedNotes.value = emptySet()
                                        processedNotes.value = emptySet()
                                        totalNotesPlayed = 0
                                        hasStartedPlaying = false
                                        isNearEndOfSong = false
                                        endOfSongTimerStarted = false
                                    }
                                }
                            }
                        }
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.performance_results),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                if (recordingManager.getRecordingDuration() > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = stringResource(R.string.recording),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.recording_saved_format, formatRecordingTime(recordingManager.getRecordingDuration())),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                // Show loading indicator while saving
                                if (isSavingPerformance) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.saving_progress),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    // Existing score display content
                                    Box(
                                        modifier = Modifier
                                            .size(90.dp)
                                            .padding(vertical = 4.dp)
                                            .clip(CircleShape)
                                            .background(
                                                color = when(score.value) {
                                                    in 90..100 -> successAccentColor()
                                                    in 80..89 -> WarmGold60
                                                    in 70..79 -> WarmGold80
                                                    in 60..69 -> AccentRose.copy(alpha = 0.7f)
                                                    else -> MaterialTheme.colorScheme.error
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${score.value}%",
                                            color = Color.White,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        CompactStatisticItem(
                                            label = stringResource(R.string.notes_hit),
                                            value = "${correctlyPlayedNotes.value.size}",
                                            modifier = Modifier.weight(1f)
                                        )

                                        CompactStatisticItem(
                                            label = stringResource(R.string.notes_missed),
                                            value = "${missedNotes.value.size}",
                                            modifier = Modifier.weight(1f)
                                        )

                                        CompactStatisticItem(
                                            label = stringResource(R.string.total),
                                            value = "$totalNotesInSong",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Grade display
                                    val grade = when(score.value) {
                                        in 95..100 -> "A+"
                                        in 90..94 -> "A"
                                        in 85..89 -> "B+"
                                        in 80..84 -> "B"
                                        in 75..79 -> "C+"
                                        in 70..74 -> "C"
                                        in 60..69 -> "D"
                                        else -> "Keep practicing!"
                                    }

                                    Text(
                                        text = if (score.value >= 60) "Grade: $grade" else grade,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = when(score.value) {
                                            in 90..100 -> Color(0xFF4CAF50)
                                            in 80..89 -> Color(0xFF8BC34A)
                                            in 70..79 -> Color(0xFFFFC107)
                                            in 60..69 -> Color(0xFFFF9800)
                                            else -> Color(0xFFF44336)
                                        }
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Action buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                scope.launch {
                                                    isSavingPerformance = true

                                                    try {
                                                        // Save the current performance
                                                        val performanceRecord = PerformanceRecord(
                                                            midiFilePath = midiFile.path,
                                                            midiFileName = midiFile.name,
                                                            timestamp = System.currentTimeMillis(),
                                                            score = score.value,
                                                            notesHit = correctlyPlayedNotes.value.size,
                                                            notesMissed = totalNotesInSong - correctlyPlayedNotes.value.size,
                                                            totalNotes = totalNotesInSong,
                                                            bpm = currentBpm ?: 120,
                                                            handMode = currentHandMode,
                                                            durationMs = sessionDurationMs,
                                                            notesPlayed = processedNotes.value.mapIndexed { index, note ->
                                                                PlayedNote(
                                                                    noteValue = note.note,
                                                                    wasCorrect = note.note in correctlyPlayedNotes.value,
                                                                    timestamp = System.currentTimeMillis() - (processedNotes.value.size - index) * 100L,
                                                                    isLeftHand = note.isLeftHand
                                                                )
                                                            }.take(200)
                                                        )

                                                        // Save performance record
                                                        performanceRepository.savePerformanceRecord(performanceRecord)
                                                        Log.d("MidiPlayer", "Performance record saved before retry")

                                                        // ALSO SAVE THE RECORDING (this was missing from retry too!)
                                                        Log.d("MidiPlayer", "Checking for recording to save before retry...")
                                                        val recording = recordingManager.createRecording(
                                                            originalMidiFilePath = midiFile.path,
                                                            originalMidiFileName = midiFile.name,
                                                            bpm = currentBpm ?: 120,
                                                            handMode = currentHandMode,
                                                            score = score.value
                                                        )

                                                        recording?.let { rec ->
                                                            Log.d("MidiPlayer", "Created recording with ${rec.recordedEvents.size} events for retry button")
                                                            try {
                                                                recordingRepository.saveRecording(rec)
                                                                Log.d("MidiPlayer", "MIDI recording saved successfully before retry with ${rec.recordedEvents.size} events")
                                                            } catch (e: Exception) {
                                                                Log.e("MidiPlayer", "Failed to save recording before retry", e)
                                                            }
                                                        } ?: Log.d("MidiPlayer", "No recording to save before retry")

                                                    } catch (e: Exception) {
                                                        Log.e("MidiPlayer", "Failed to save performance record before retry", e)
                                                    }

                                                    // Stop recording before restarting
                                                    if (isRecording) {
                                                        recordingManager.stopRecording()
                                                        isRecording = false
                                                        Log.d("MidiPlayer", "Stopped recording before retry")
                                                    }

                                                    isSavingPerformance = false
                                                    showScoreDialog = false

                                                    // Reset and restart
                                                    correctlyPlayedNotes.value = emptySet()
                                                    missedNotes.value = emptySet()
                                                    processedNotes.value = emptySet()
                                                    totalNotesPlayed = 0
                                                    hasStartedPlaying = false
                                                    isNearEndOfSong = false
                                                    endOfSongTimerStarted = false

                                                    // Restart playback
                                                    playbackManager.resetPlayback()
                                                    playbackManager.startPlayback(midiFile, currentBpm ?: 120, 0L, midiNotes, currentHandMode)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = stringResource(R.string.try_again),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                stringResource(R.string.try_again),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }

                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                scope.launch {
                                                    isSavingPerformance = true

                                                    try {
                                                        // Save the current performance before going back
                                                        val performanceRecord = PerformanceRecord(
                                                            midiFilePath = midiFile.path,
                                                            midiFileName = midiFile.name,
                                                            timestamp = System.currentTimeMillis(),
                                                            score = score.value,
                                                            notesHit = correctlyPlayedNotes.value.size,
                                                            notesMissed = totalNotesInSong - correctlyPlayedNotes.value.size,
                                                            totalNotes = totalNotesInSong,
                                                            bpm = currentBpm ?: 120,
                                                            handMode = currentHandMode,
                                                            durationMs = sessionDurationMs,
                                                            notesPlayed = processedNotes.value.mapIndexed { index, note ->
                                                                PlayedNote(
                                                                    noteValue = note.note,
                                                                    wasCorrect = note.note in correctlyPlayedNotes.value,
                                                                    timestamp = System.currentTimeMillis() - (processedNotes.value.size - index) * 100L,
                                                                    isLeftHand = note.isLeftHand
                                                                )
                                                            }.take(200)
                                                        )

                                                        // Save performance record
                                                        performanceRepository.savePerformanceRecord(performanceRecord)
                                                        Log.d("MidiPlayer", "Performance record saved before going back")

                                                        // ALSO SAVE THE RECORDING (this was missing!)
                                                        Log.d("MidiPlayer", "Checking for recording to save before going back...")
                                                        val recording = recordingManager.createRecording(
                                                            originalMidiFilePath = midiFile.path,
                                                            originalMidiFileName = midiFile.name,
                                                            bpm = currentBpm ?: 120,
                                                            handMode = currentHandMode,
                                                            score = score.value
                                                        )

                                                        recording?.let { rec ->
                                                            Log.d("MidiPlayer", "Created recording with ${rec.recordedEvents.size} events for back button")
                                                            try {
                                                                recordingRepository.saveRecording(rec)
                                                                Log.d("MidiPlayer", "MIDI recording saved successfully before going back with ${rec.recordedEvents.size} events")

                                                                // Verify it was saved
                                                                val allRecordings = recordingRepository.allRecordings.first()
                                                                Log.d("MidiPlayer", "Total recordings in repository after back button save: ${allRecordings.size}")

                                                            } catch (e: Exception) {
                                                                Log.e("MidiPlayer", "Failed to save recording before going back", e)
                                                            }
                                                        } ?: Log.d("MidiPlayer", "No recording to save before going back")

                                                    } catch (e: Exception) {
                                                        Log.e("MidiPlayer", "Failed to save performance record before going back", e)
                                                    }

                                                    isSavingPerformance = false
                                                    showScoreDialog = false
                                                    playbackManager.cleanup()
                                                    onBackPressed()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowBack,
                                                contentDescription = stringResource(R.string.back_to_library),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                stringResource(R.string.back),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Surface(
                            color = AccentRose.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.FiberManualRecord,
                                        contentDescription = stringResource(R.string.recording),
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.rec_format, formatRecordingTime(recordingDuration)),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.auto_stops_at_song_end),
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                if (metronomeEnabled) {
                    MetronomeVisualizer(
                        currentBeat = currentMetronomeBeat,
                        beatsPerMeasure = metronomeBeatCount,
                        isRunning = isMetronomeRunning && isPlaybackActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    )
                }

                if (showCountdown) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = countdownSeconds.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            color = Color.White
                        )
                    }
                }

                // Replace the LoopControl Box section in MidiPlayerScreen.kt
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showTopBar) {
                                Modifier
                                    .graphicsLayer { alpha = 1f }
                                    .pointerInput(Unit) {
                                        // Allow interactions when visible
                                        detectTapGestures { }
                                    }
                            } else {
                                Modifier
                                    .graphicsLayer { alpha = 0f }
                                    .pointerInput(Unit) {
                                        // Block ALL interactions when hidden
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                // Consume the event to prevent it from reaching child components
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                            }
                        )
                ) {
                    if (showTopBar) { // Only render when visible to save performance
                        LoopControl(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            isLoopEnabled = isLoopEnabled,
                            loopStartMs = loopStartMs,
                            loopEndMs = loopEndMs,
                            songDurationMs = songDurationMs,
                            currentTimeMs = currentTimeMs,
                            onLoopToggled = { enabled ->
                                lastInteractionTime = System.currentTimeMillis() // Reset timer on interaction
                                playbackManager.toggleLoopMode(enabled)
                            },
                            onSetLoopStart = {
                                lastInteractionTime = System.currentTimeMillis() // Reset timer on interaction
                                Log.d("MidiPlayer", "Setting loop start to current time: $currentTimeMs")
                                val endPoint = if (loopEndMs <= currentTimeMs) songDurationMs else loopEndMs
                                playbackManager.setLoopPoints(currentTimeMs, endPoint)
                                playbackManager.toggleLoopMode(true)
                            },
                            onSetLoopEnd = {
                                lastInteractionTime = System.currentTimeMillis() // Reset timer on interaction
                                // Only set end if it's after start
                                if (currentTimeMs > loopStartMs) {
                                    Log.d("MidiPlayer", "Setting loop end to current time: $currentTimeMs")
                                    playbackManager.setLoopPoints(loopStartMs, currentTimeMs)
                                    playbackManager.toggleLoopMode(true)
                                }
                            },
                            onSeekTo = { position ->
                                lastInteractionTime = System.currentTimeMillis() // Reset timer on interaction
                                Log.d("MidiPlayer", "Seeking to position: $position")
                                playbackManager.seekTo(position)
                            }
                        )
                    }
                }

                EnhancedPianoLayout(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(160.dp),
                    pianoConfig = pianoConfig!!,
                    pressedKeys = pressedKeys,
                    currentNotes = activeNotes,
                    syncedNotes = emptySet(),
                    showKeyNames = settings.difficultyLevel.showKeyNames && settings.showKeyNames,
                    onNotePressed = { /* Optional: handle virtual key presses */ }
                )
            }
        }

        // BPM Dialog
        if (showBpmDialog) {
            var tempBpm by remember { mutableStateOf(currentBpm?.toString() ?: "") }
            AlertDialog(
                onDismissRequest = { showBpmDialog = false },
                title = { Text(stringResource(R.string.set_bpm)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.original_bpm, midiFile.originalBpm?.toString() ?: stringResource(R.string.bmp_unknown)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = tempBpm,
                            onValueChange = { newValue ->
                                if (newValue.isEmpty() || newValue.toIntOrNull() != null) {
                                    tempBpm = newValue
                                }
                            },
                            label = { Text(stringResource(R.string.current_bpm)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            tempBpm.toIntOrNull()?.let { newBpm ->
                                currentBpm = newBpm
                                scope.launch {
                                    repository.updateMidiBpm(
                                        midiFile.copy(currentBpm = newBpm)
                                    )
                                }
                                playbackManager.resetPlayback()
                                playbackManager.startPlayback(midiFile.copy(currentBpm = newBpm), newBpm, 0L, midiNotes, currentHandMode)
                                metronomeManager.updateBpm(newBpm)
                            }
                            showBpmDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBpmDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

data class MidiNote(
    val note: Int,
    val isLeftHand: Boolean,
    val startTime: Long,
    val duration: Long = 0L,
    val velocity: Int = 64  // Changed from Velocity to Int for better MIDI compatibility
)

@Composable
fun EnhancedPianoLayout(
    modifier: Modifier = Modifier,
    pianoConfig: PianoConfiguration,
    pressedKeys: Set<Int>,
    currentNotes: List<MidiNote>,
    syncedNotes: Set<Int>,
    showKeyNames: Boolean = false,
    onNotePressed: (Int) -> Unit
) {
    val totalWhiteKeys = (pianoConfig.minNote..pianoConfig.maxNote)
        .count { isWhiteKey(it) }
    val totalWidth = pianoConfig.keyWidth.dp * totalWhiteKeys

    Box(
        modifier = modifier
            .width(totalWidth)
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF333333))
            .padding(4.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        // White keys
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Start
        ) {
            (pianoConfig.minNote..pianoConfig.maxNote).forEach { note ->
                if (isWhiteKey(note)) {
                    EnhancedWhiteKey(
                        modifier = Modifier.width(pianoConfig.keyWidth.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note && note in pressedKeys },
                        showKeyName = showKeyNames,
                        onPressed = onNotePressed
                    )
                }
            }
        }

        // Black keys
        Box(modifier = Modifier.fillMaxSize()) {
            (pianoConfig.minNote..pianoConfig.maxNote).forEach { note ->
                if (!isWhiteKey(note)) {
                    val xPos = calculateNotePosition(note, pianoConfig.minNote, pianoConfig.keyWidth, true)
                    EnhancedBlackKey(
                        modifier = Modifier.offset(x = xPos.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note && note in pressedKeys },
                        showKeyName = showKeyNames,
                        onPressed = onNotePressed
                    )
                }
            }
        }
    }
}

@Composable
fun EnhancedWhiteKey(
    modifier: Modifier = Modifier,
    note: Int,
    isPhysicallyPressed: Boolean = false,
    isHighlighted: Boolean = false,
    showKeyName: Boolean = false,
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }

    // Combine physical and virtual press states
    val isPressed = isPhysicallyPressed || isVirtuallyPressed

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val animatedColor by animateColorAsState(
        targetValue = when {
            isPhysicallyPressed -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.onBackground
        },
        animationSpec = tween(durationMillis = 50)
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .scale(animatedScale)
            .background(
                animatedColor,
                RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFFBDBDBD),
                shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isVirtuallyPressed = true
                        onPressed(note)
                        tryAwaitRelease()
                        isVirtuallyPressed = false
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (showKeyName) {
            Text(
                text = getNoteNameForMidiNote(note),
                fontSize = 10.sp,
                color = Color.Black.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
fun EnhancedBlackKey(
    modifier: Modifier = Modifier,
    note: Int,
    isPhysicallyPressed: Boolean = false,
    isHighlighted: Boolean = false,
    showKeyName: Boolean = false,
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }

    // Combine physical and virtual press states
    val isPressed = isPhysicallyPressed || isVirtuallyPressed

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val animatedColor by animateColorAsState(
        targetValue = when {
            isPhysicallyPressed -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 50)
    )

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight(0.62f)
            .scale(animatedScale)
            .background(
                animatedColor,
                RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF616161),
                shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isVirtuallyPressed = true
                        onPressed(note)
                        tryAwaitRelease()
                        isVirtuallyPressed = false
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (showKeyName) {
            Text(
                text = getNoteNameForMidiNote(note),
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}