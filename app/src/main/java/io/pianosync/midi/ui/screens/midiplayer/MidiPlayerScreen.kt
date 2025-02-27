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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.data.manager.MidiConnectionManager
import io.pianosync.midi.data.manager.MidiPlaybackManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.parser.MidiParser
import io.pianosync.midi.data.repository.MidiFileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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
    onNoteProcessed: () -> Unit
) {
    // Fixed offset to compensate for the playback delay
    val PLAYBACK_OFFSET_MS = 4500L  // 5 seconds offset
    val CORRECT_NOTE_WINDOW = 300L

    val noteHeight = 16.dp
    val futureTimeWindow = 8000L
    val pastTimeWindow = 2000L
    val configuration = LocalConfiguration.current
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
            // Find notes that are currently at the play line (using the offset)
            val notesAtPlayLine = notes.filter { note ->
                val playbackTime = (note.startTime / speedRatio).toLong() + PLAYBACK_OFFSET_MS
                val timeDiff = currentTimeMs - playbackTime
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
                    }
                }
            }

            // Also check for notes that have passed the play line without being played
            val passedNotes = notes.filter { note ->
                val playbackTime = (note.startTime / speedRatio).toLong() + PLAYBACK_OFFSET_MS
                val timeDiff = currentTimeMs - playbackTime
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
            .background(Color(0xFF1A1A1A))
            .width(totalWidth)
            .horizontalScroll(rememberScrollState())
    ) {
        // Play line
        Box(
            modifier = Modifier
                .offset(y = playLinePosition)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    color = Color.White.copy(alpha = 0.7f),
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
                                if (isBlackKey) Color(0xFFE91E63) else Color(0xFFE91E63)
                            } else {
                                if (isBlackKey) Color(0xFF2196F3) else Color(0xFF2196F3)
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

@Composable
fun StatisticItem(
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiPlayerScreen(
    midiFile: MidiFile,
    repository: MidiFileRepository,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTopBar by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var countdownSeconds by remember { mutableStateOf(3) }
    var showCountdown by remember { mutableStateOf(true) }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val horizontalPadding = 16 // Total horizontal padding
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentBpm by remember { mutableStateOf(midiFile.currentBpm) }
    var showBpmDialog by remember { mutableStateOf(false) }
    val midiConnectionManager = remember { MidiConnectionManager.getInstance(context) }
    val pressedKeys by midiConnectionManager.pressedKeys.collectAsState()
    val playbackManager = remember { MidiPlaybackManager(context, midiConnectionManager) }
    val isPlaybackActive by playbackManager.isPlaying.collectAsState()
    var currentTimeMs by remember { mutableStateOf(0L) }
    var isPreLoading by remember { mutableStateOf(true) }
    var midiNotes by remember { mutableStateOf<List<MidiNote>>(emptyList()) }
    var pianoConfig by remember { mutableStateOf<PianoConfiguration?>(null) }

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

    LaunchedEffect(midiNotes) {
        if (midiNotes.isNotEmpty()) {
            totalNotesInSong = midiNotes.size
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

                // Check if we're near the end of the song
                if (currentTimeMs > estimatedDuration * 0.95) {
                    isNearEndOfSong = true
                    Log.d("MidiPlayer", "Near end of song detected at $currentTimeMs / $estimatedDuration")
                }
            }
            delay(100) // Check every 100ms instead of every frame
        }
    }

// This handles the completion timer separately
    LaunchedEffect(isNearEndOfSong) {
        if (isNearEndOfSong && !endOfSongTimerStarted) {
            endOfSongTimerStarted = true
            Log.d("MidiPlayer", "Starting end of song timer")

            // Wait for 2 seconds
            delay(2000)

            // If we're still near the end, show the dialog
            if (isPlaybackActive) {
                Log.d("MidiPlayer", "Song completion timer finished, showing score dialog")
                playbackManager.pausePlayback()
                showScoreDialog = true
            }

            // Reset tracking variables
            isNearEndOfSong = false
            endOfSongTimerStarted = false
        }
    }

// Add a direct completion detection based on playback state
    LaunchedEffect(isPlaybackActive) {
        if (!isPlaybackActive && hasStartedPlaying && !isPreLoading && !showScoreDialog) {
            hasStartedPlaying = false

            // Check if we've played a significant portion
            val lastNoteTime = if (midiNotes.isNotEmpty()) {
                midiNotes.maxOf { it.startTime + it.duration }
            } else 0L

            if (currentTimeMs > lastNoteTime * 0.7) {
                Log.d("MidiPlayer", "Playback stopped after significant progress, showing score")
                showScoreDialog = true
            }
        } else if (isPlaybackActive && !hasStartedPlaying) {
            hasStartedPlaying = true
        }
    }

    LaunchedEffect(lastInteractionTime) {
        delay(3000)
        showTopBar = false
    }

    // Cleanup when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            playbackManager.cleanup()
        }
    }

    // Initial MIDI loading and visualization setup
    LaunchedEffect(midiFile, currentBpm) {
        try {
            val uri = Uri.parse(midiFile.path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Always parse with the original BPM to get correct absolute times
                val originalBpm = midiFile.originalBpm ?: 120
                val notes = MidiParser.parseMidiNotes(inputStream, originalBpm)
                if (notes.isNotEmpty()) {
                    val minNote = notes.minOf { it.note }
                    val maxNote = notes.maxOf { it.note }

                    val paddedMin = (minNote - 2).coerceAtLeast(21)
                    val paddedMax = (maxNote + 2).coerceAtMost(108)

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
                midiNotes = notes

                while (countdownSeconds > 0) {
                    delay(1000)
                    countdownSeconds--
                }
                showCountdown = false

                delay(100)

                playbackManager.startPlayback(midiFile, currentBpm ?: 120)
                isPreLoading = false
            }
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Error loading MIDI file", e)
        }
    }

    // Update current time from playback manager
    LaunchedEffect(Unit) {
        playbackManager.currentTimeMs.collect { time ->
            if (!isPreLoading) {
                currentTimeMs = time
            }
        }
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
                    .background(Color(0xFF1A1A1A)) // Match the dark background color
            ) {
                CenterAlignedTopAppBar(
                    modifier = Modifier.graphicsLayer {
                        alpha = if (showTopBar) 1f else 0f
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color(0xFF1A1A1A) // Match the dark background color
                    ),
                    title = {
                        Text(
                            text = midiFile.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                playbackManager.cleanup()
                                onBackPressed()
                            }
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
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
                            // BPM Button
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
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "${currentBpm ?: 0}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            // Restart Button
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    playbackManager.resetPlayback()
                                    playbackManager.startPlayback(midiFile, currentBpm ?: 120)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Restart"
                                )
                            }

                            // Play/Pause Button
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    if (isPlaybackActive) {
                                        playbackManager.pausePlayback()
                                    } else {
                                        if (currentTimeMs > 0) {
                                            playbackManager.resumePlayback(midiFile)
                                        } else {
                                            playbackManager.startPlayback(midiFile, currentBpm ?: 120)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlaybackActive)
                                        Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaybackActive)
                                        "Pause" else "Play"
                                )
                            }
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                NoteFallVisualizer(
                    modifier = Modifier.fillMaxSize(),
                    notes = midiNotes,
                    currentTimeMs = currentTimeMs,
                    isPlaying = isPlaybackActive,
                    bpm = currentBpm ?: 120,
                    pianoConfig = pianoConfig!!,
                    isPreLoading = isPreLoading,
                    playbackManager = playbackManager,
                    correctlyPlayedNotes = correctlyPlayedNotes,
                    pressedKeys = pressedKeys,
                    onNoteProcessed = {
                        // Make sure we're tracking processed notes
                        totalNotesPlayed++
                    }
                )

                if (showScoreDialog) {
                    Dialog(
                        onDismissRequest = {
                            showScoreDialog = false
                            // Reset states for potential replay
                            correctlyPlayedNotes.value = emptySet()
                            missedNotes.value = emptySet()
                            processedNotes.value = emptySet()
                            totalNotesPlayed = 0
                        }
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Performance Results",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                // Big score display
                                Box(
                                    modifier = Modifier
                                        .size(120.dp)
                                        .padding(vertical = 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${score.value}%",
                                        color = Color.White,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Statistics
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    StatisticItem(
                                        label = "Notes Hit",
                                        value = "${correctlyPlayedNotes.value.size}",
                                        modifier = Modifier.weight(1f)
                                    )

                                    StatisticItem(
                                        label = "Notes Missed",
                                        value = "${missedNotes.value.size}",
                                        modifier = Modifier.weight(1f)
                                    )

                                    StatisticItem(
                                        label = "Total Notes",
                                        value = "$totalNotesInSong",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Grade based on score
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
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = when(score.value) {
                                        in 90..100 -> Color(0xFF4CAF50) // Green for A grades
                                        in 80..89 -> Color(0xFF8BC34A) // Light Green for B grades
                                        in 70..79 -> Color(0xFFFFC107) // Amber for C grades
                                        in 60..69 -> Color(0xFFFF9800) // Orange for D grades
                                        else -> Color(0xFFF44336) // Red for failing
                                    }
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = {
                                            showScoreDialog = false
                                            // Reset and restart
                                            correctlyPlayedNotes.value = emptySet()
                                            missedNotes.value = emptySet()
                                            processedNotes.value = emptySet()
                                            totalNotesPlayed = 0

                                            // Restart playback
                                            playbackManager.resetPlayback()
                                            playbackManager.startPlayback(midiFile, currentBpm ?: 120)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Retry",
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Try Again")
                                    }

                                    Button(
                                        onClick = {
                                            showScoreDialog = false
                                            playbackManager.cleanup()
                                            onBackPressed()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowBack,
                                            contentDescription = "Back to Library",
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Back to Library")
                                    }
                                }
                            }
                        }
                    }
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

                PianoLayout(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(160.dp),
                    pianoConfig = pianoConfig!!,
                    pressedKeys = pressedKeys,
                    currentNotes = activeNotes,
                    syncedNotes = emptySet(),
                    onNotePressed = { /* Optional: handle virtual key presses */ }
                )
            }
        }

        // BPM Dialog
        if (showBpmDialog) {
            var tempBpm by remember { mutableStateOf(currentBpm?.toString() ?: "") }
            AlertDialog(
                onDismissRequest = { showBpmDialog = false },
                title = { Text("Set BPM") },
                text = {
                    Column {
                        Text(
                            "Original BPM: ${midiFile.originalBpm ?: "Unknown"}",
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
                            label = { Text("Current BPM") },
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
                                playbackManager.startPlayback(
                                    midiFile.copy(currentBpm = newBpm),
                                    newBpm
                                )
                            }
                            showBpmDialog = false
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBpmDialog = false }) {
                        Text("Cancel")
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

private fun isWhiteKey(note: Int): Boolean {
    return when (note % 12) {
        0, 2, 4, 5, 7, 9, 11 -> true // C, D, E, F, G, A, B
        else -> false
    }
}

@Composable
fun PianoLayout(
    modifier: Modifier = Modifier,
    pianoConfig: PianoConfiguration,
    pressedKeys: Set<Int>,
    currentNotes: List<MidiNote>,
    syncedNotes: Set<Int>,
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
                    val xPos = calculateNotePosition(note, pianoConfig.minNote, pianoConfig.keyWidth, false)
                    WhiteKey(
                        modifier = Modifier.width(pianoConfig.keyWidth.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note && note in pressedKeys },
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
                    BlackKey(
                        modifier = Modifier.offset(x = xPos.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note && note in pressedKeys },
                        onPressed = onNotePressed
                    )
                }
            }
        }
    }
}

@Composable
fun WhiteKey(
    modifier: Modifier = Modifier,
    note: Int,
    isPhysicallyPressed: Boolean = false,
    isHighlighted: Boolean = false,
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
            isPhysicallyPressed -> Color(0xFFE0E0E0) // Soft gray for physical press
            else -> Color.White // Default white
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
            }
    )
}

@Composable
fun BlackKey(
    modifier: Modifier = Modifier,
    note: Int,
    isPhysicallyPressed: Boolean = false,
    isHighlighted: Boolean = false,
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
            isPhysicallyPressed -> Color(0xFF424242) // Darker gray for physical press
            else -> Color(0xFF202020) // Default dark color
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
            }
    )
}