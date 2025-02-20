package io.pianosync.midi.ui.screens.player
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.pianosync.midi.data.manager.MidiPlaybackManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.parser.MidiParser
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.rememberMidiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun NoteFallVisualizer(
    modifier: Modifier = Modifier,
    notes: List<MidiNote>,
    currentTimeMs: Long,
    isPlaying: Boolean,
    bpm: Int,
    pianoConfig: PianoConfiguration,
    pressedKeys: Set<Int>
) {
    val noteHeight = 16.dp
    val millisecondsOnScreen = remember(bpm) {
        (20000L * (bpm.toFloat() / 120f)).toLong().coerceAtLeast(12000L)
    }

    // Calculate width based on actual note range
    val totalWhiteKeys = (pianoConfig.minNote..pianoConfig.maxNote)
        .count { isWhiteKey(it) }
    val totalWidth = pianoConfig.keyWidth.dp * totalWhiteKeys

    // Calculate the dynamic height of the visualizer
    val configuration = LocalConfiguration.current
    val visualizerHeight = configuration.screenHeightDp.dp - 160.dp // Subtract the height of the piano

    val playLinePosition = visualizerHeight - noteHeight

    // Calculate note Y position - start at top (0) and move down to playLinePosition
    fun timeToYPosition(noteTime: Long): Float {
        val timeDiff = noteTime - currentTimeMs
        val normalizedTime = timeDiff.toFloat() / millisecondsOnScreen.toFloat()
        val invertedProgress = 1 - normalizedTime
        return (invertedProgress * (visualizerHeight.value - noteHeight.value))
            .coerceIn(0f, visualizerHeight.value)
    }

    fun calculateNoteXPosition(note: Int): Float {
        val whiteKeysBefore = (pianoConfig.minNote until note)
            .count { isWhiteKey(it) }
        return if (isWhiteKey(note)) {
            whiteKeysBefore * pianoConfig.keyWidth
        } else {
            val prevWhiteKey = (note - 1 downTo pianoConfig.minNote)
                .first { isWhiteKey(it) }
            val whiteKeysBeforePrev = (pianoConfig.minNote until prevWhiteKey)
                .count { isWhiteKey(it) }
            (whiteKeysBeforePrev * pianoConfig.keyWidth) + (pianoConfig.keyWidth * 0.7f)
        }
    }

    val visibleNotes = notes.filter { note ->
        val timeDiff = note.startTime - currentTimeMs
        timeDiff in -1000..(millisecondsOnScreen * 2) &&
                note.note in pianoConfig.minNote..pianoConfig.maxNote
    }

    // Debug logging
    LaunchedEffect(currentTimeMs / 1000) { // Log every second
        Log.d("NoteFallVisualizer", """
            Total notes: ${notes.size}
            Visible notes: ${visibleNotes.size}
            Current time: $currentTimeMs
            First note time: ${notes.firstOrNull()?.startTime}
            Last note time: ${notes.lastOrNull()?.startTime}
            Window: ${currentTimeMs - 500} to ${currentTimeMs + millisecondsOnScreen}
        """.trimIndent())
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .width(totalWidth)
            .horizontalScroll(rememberScrollState())
    ) {
        // Play line at the bottom
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

        // Draw notes falling from top
        visibleNotes.forEach { note ->
            val yPos = timeToYPosition(note.startTime)
            val xPos = calculateNoteXPosition(note.note)

            val noteWidth = if (isWhiteKey(note.note)) {
                (pianoConfig.keyWidth * 0.8f).dp
            } else {
                (pianoConfig.keyWidth * 0.6f).dp
            }

            val isCurrentlyPressed = note.note in pressedKeys
            val isWithinPlayingWindow = abs(note.startTime - currentTimeMs) <= 100

            val noteColor = when {
                isCurrentlyPressed && isWithinPlayingWindow -> Color(0xFF4CAF50)  // Green for correct hits
                abs(note.startTime - currentTimeMs) <= 100 -> Color(0xFFFFEB3B)   // Yellow for current notes
                note.isLeftHand -> Color(0xFF2196F3).copy(alpha = 0.9f)          // Blue for left hand
                else -> Color(0xFFE91E63).copy(alpha = 0.9f)                     // Pink for right hand
            }

            Box(
                modifier = Modifier
                    .offset(
                        x = xPos.dp,
                        y = yPos.dp
                    )
                    .width(noteWidth)
                    .height(noteHeight)
                    .background(
                        color = noteColor,
                        shape = RoundedCornerShape(2.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

data class PianoConfiguration(
    val minNote: Int,
    val maxNote: Int,
    val keyWidth: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiPlayerScreen(
    midiFile: MidiFile,
    repository: MidiFileRepository,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val midiManager = rememberMidiManager()
    val scope = rememberCoroutineScope()
    var isInterfaceVisible by remember { mutableStateOf(true) }
    var currentBpm by remember { mutableStateOf(midiFile.bpm) }
    var showBpmDialog by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(3) }
    val playbackManager = remember { MidiPlaybackManager(context, midiManager) }
    val pressedKeys by midiManager.pressedKeys.collectAsState()
    val isPlaybackActive by playbackManager.isPlaying.collectAsState()
    val currentTimeMs by playbackManager.currentTimeMs.collectAsState()

    // State for notes and range
    var midiNotes by remember { mutableStateOf<List<MidiNote>>(emptyList()) }
    var pianoConfig by remember { mutableStateOf<PianoConfiguration?>(null) }
    var startTimeOffset by remember { mutableStateOf(0L) }

    // Calculate initial piano configuration from MIDI file
    LaunchedEffect(midiFile, currentBpm) {
        try {
            val uri = Uri.parse(midiFile.path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val notes = MidiParser.parseMidiNotes(inputStream, currentBpm ?: 120)
                if (notes.isNotEmpty()) {
                    val minNote = notes.minOf { it.note }
                    val maxNote = notes.maxOf { it.note }

                    // Find start time offset
                    startTimeOffset = notes.minOf { it.startTime } - 2000 // 2 seconds before first note

                    // Add minimal padding (2 notes) for visual context
                    val paddedMin = (minNote - 2).coerceAtLeast(21)
                    val paddedMax = (maxNote + 2).coerceAtMost(108)

                    val screenWidth = context.resources.displayMetrics.widthPixels /
                            context.resources.displayMetrics.density
                    val whiteKeyCount = (paddedMin..paddedMax).count { isWhiteKey(it) }
                    val keyWidth = (screenWidth / whiteKeyCount).coerceIn(30f, 60f)

                    Log.d("MidiPlayer", "Note range: $minNote to $maxNote")
                    Log.d("MidiPlayer", "Start time offset: $startTimeOffset")

                    pianoConfig = PianoConfiguration(
                        minNote = paddedMin,
                        maxNote = paddedMax,
                        keyWidth = keyWidth
                    )
                }
                midiNotes = notes
            }
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Error loading MIDI file", e)
        }
    }

    // Start playback after countdown
    LaunchedEffect(Unit) {
        while (countdownSeconds > 0) {
            delay(1000)
            countdownSeconds--
        }
        delay(500)
        playbackManager.startPlayback(midiFile, currentBpm ?: 120, startTimeOffset)
    }

    if (pianoConfig == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    // Load MIDI notes
    LaunchedEffect(midiFile, currentBpm) {
        try {
            val uri = Uri.parse(midiFile.path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                midiNotes = MidiParser.parseMidiNotes(inputStream, currentBpm ?: 120)
                Log.d("MidiPlayer", "Loaded ${midiNotes.size} notes")
            }
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Error loading MIDI file", e)
        }
    }

    LaunchedEffect(countdownSeconds) {
        if (countdownSeconds == 0) {
            delay(500) // Small delay after countdown
            isInterfaceVisible = false
        }
    }

    // Reset playback when BPM changes
    fun resetPlayback() {
        playbackManager.resetPlayback()
        playbackManager.startPlayback(midiFile, currentBpm ?: 120)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Main content
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = midiFile.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(48.dp)
                    ) {
                        TextButton(
                            onClick = { showBpmDialog = true },
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
                        IconButton(
                            onClick = {
                                if (isPlaybackActive) {
                                    playbackManager.stopPlayback()
                                } else {
                                    playbackManager.startPlayback(midiFile, currentBpm ?: 120)
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

            // Note visualizer and piano
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                NoteFallVisualizer(
                    modifier = Modifier
                        .fillMaxSize(),
                    notes = midiNotes,
                    currentTimeMs = currentTimeMs + startTimeOffset,
                    isPlaying = isPlaybackActive,
                    bpm = currentBpm ?: 120,
                    pianoConfig = pianoConfig!!,
                    pressedKeys = pressedKeys
                )

                PianoLayout(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(160.dp),
                    pianoConfig = pianoConfig!!,
                    pressedKeys = pressedKeys,
                    currentNotes = midiNotes.filter { note ->
                        abs(note.startTime - currentTimeMs) <= 150
                    },
                    syncedNotes = emptySet(),
                    onNotePressed = { }
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
                    OutlinedTextField(
                        value = tempBpm,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.toIntOrNull() != null) {
                                tempBpm = newValue
                            }
                        },
                        label = { Text("Beats Per Minute") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            tempBpm.toIntOrNull()?.let { newBpm ->
                                currentBpm = newBpm
                                // Reset timing when BPM changes
                                resetPlayback()
                                scope.launch {
                                    repository.updateMidiBpm(midiFile.copy(bpm = newBpm))
                                }
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
    val duration: Long
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
            .border(2.dp, Color(0xFF333333))
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
                    WhiteKey(
                        modifier = Modifier.width(pianoConfig.keyWidth.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note },
                        color = if (note in pressedKeys) Color(0xFF81C784) else Color.White,
                        onPressed = onNotePressed
                    )
                }
            }
        }

        // Black keys
        Box(modifier = Modifier.fillMaxSize()) {
            (pianoConfig.minNote..pianoConfig.maxNote).forEach { note ->
                if (!isWhiteKey(note)) {
                    val whiteKeysBefore = (pianoConfig.minNote until note).count { isWhiteKey(it) }
                    val xOffset = whiteKeysBefore * pianoConfig.keyWidth + (pianoConfig.keyWidth * 0.7f)

                    BlackKey(
                        modifier = Modifier.offset(x = xOffset.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        isHighlighted = currentNotes.any { it.note == note },
                        color = if (note in pressedKeys) Color(0xFF81C784) else Color(0xFF202020),
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
    color: Color = Color.White,
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }
    val isPressed = isPhysicallyPressed || isVirtuallyPressed || isHighlighted

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .background(
                if (isPressed) color else Color.White,
                RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFFE0E0E0),
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
    color: Color = Color(0xFF202020),
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }
    val isPressed = isPhysicallyPressed || isVirtuallyPressed || isHighlighted

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight(0.62f)
            .background(
                if (isPressed) color else Color(0xFF202020),
                RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF404040),
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
