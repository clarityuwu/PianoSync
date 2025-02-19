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
    fixedPianoRange: Pair<Int, Int>,
    keyWidth: Dp,
    pressedKeys: Set<Int>
) {
    val visualizerHeight = 400.dp
    val noteHeight = 16.dp
    val millisecondsOnScreen = 3000L
    val playLinePosition = 350.dp

    // Calculate total width based on the number of white keys in range
    val totalWhiteKeys = (fixedPianoRange.first..fixedPianoRange.second).count { isWhiteKey(it) }
    val totalWidth = keyWidth * totalWhiteKeys

    fun timeToYPosition(noteTime: Long): Float {
        val timeDiff = noteTime - currentTimeMs
        val position = playLinePosition.value - ((timeDiff.toFloat() / millisecondsOnScreen.toFloat()) * visualizerHeight.value)
        return position.coerceIn(0f, visualizerHeight.value)
    }

    fun calculateNoteXPosition(note: Int): Float {
        val whiteKeysBefore = (fixedPianoRange.first until note).count { isWhiteKey(it) }
        return if (isWhiteKey(note)) {
            whiteKeysBefore * keyWidth.value
        } else {
            // Position black keys between white keys
            val prevWhiteKey = (note - 1 downTo fixedPianoRange.first).first { isWhiteKey(it) }
            val whiteKeysBeforePrev = (fixedPianoRange.first until prevWhiteKey).count { isWhiteKey(it) }
            (whiteKeysBeforePrev * keyWidth.value) + (keyWidth.value * 0.7f)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .width(totalWidth)
            .horizontalScroll(rememberScrollState())
    ) {
        // Show only notes within the visible window and note range
        val visibleNotes = notes.filter { note ->
            val timeDiff = note.startTime - currentTimeMs
            timeDiff in -1000..millisecondsOnScreen &&
                    note.note in fixedPianoRange.first..fixedPianoRange.second
        }

        // Play line
        Box(
            modifier = Modifier
                .offset(y = playLinePosition)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.7f))
        )

        // Draw notes
        visibleNotes.forEach { note ->
            val yPos = timeToYPosition(note.startTime)
            val xPos = calculateNoteXPosition(note.note)

            val noteWidth = if (isWhiteKey(note.note)) {
                (keyWidth.value * 0.8f).dp
            } else {
                (keyWidth.value * 0.6f).dp
            }

            val isCurrentlyPressed = note.note in pressedKeys
            val isWithinPlayingWindow = abs(note.startTime - currentTimeMs) <= 150

            val noteColor = when {
                isCurrentlyPressed && isWithinPlayingWindow -> Color(0xFF4CAF50)
                abs(note.startTime - currentTimeMs) <= 150 -> Color(0xFFFFEB3B)
                note.isLeftHand -> Color(0xFF2196F3).copy(alpha = 0.9f)
                else -> Color(0xFFE91E63).copy(alpha = 0.9f)
            }

            Box(
                modifier = Modifier
                    .offset(
                        x = xPos.dp,
                        y = yPos.dp - noteHeight
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

    // State for note range
    var noteRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var midiNotes by remember { mutableStateOf<List<MidiNote>>(emptyList()) }
    var syncedNotes by remember { mutableStateOf(emptySet<Int>()) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    val pianoConfig = remember(midiFile) {
        try {
            val uri = Uri.parse(midiFile.path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val notes = MidiParser.parseMidiNotes(inputStream, midiFile.bpm ?: 120)
                if (notes.isNotEmpty()) {
                    val minNote = notes.minOf { it.note }
                    val maxNote = notes.maxOf { it.note }
                    // Extend to nearest octave boundaries
                    val extendedMin = (minNote - (minNote % 12))
                    val extendedMax = (maxNote + (12 - (maxNote % 12)))
                    val totalWhiteKeys = (extendedMin..extendedMax).count { isWhiteKey(it) }

                    // Calculate key width to fit all keys
                    val keyWidth = (screenWidth.value / totalWhiteKeys)
                    Triple(extendedMin, extendedMax, keyWidth.dp)
                } else {
                    Triple(48, 72, 40.dp) // Default range if no notes found
                }
            } ?: Triple(48, 72, 40.dp)
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Error calculating piano range", e)
            Triple(48, 72, 40.dp) // Default range if loading fails
        }
    }

    val (minNote, maxNote, keyWidth) = pianoConfig
    Log.d("MidiPlayer", "Piano range: $minNote to $maxNote, key width: $keyWidth")

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

    // Add effect to monitor playback state
    LaunchedEffect(isPlaybackActive, currentTimeMs) {
        if (isPlaybackActive) {
            Log.d("MidiPlayer", "Playback active, time: $currentTimeMs")
            Log.d("MidiPlayer", "Current visible notes: ${
                midiNotes.count { note ->
                    abs(note.startTime - currentTimeMs) <= 150
                }
            }")
        }
    }

    LaunchedEffect(Unit) {
        repeat(3) {
            delay(1000)
            countdownSeconds--
        }
        playbackManager.startPlayback(midiFile, currentBpm ?: 120)
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
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!isInterfaceVisible) {
                        isInterfaceVisible = true
                        // Auto-hide after 3 seconds
                        scope.launch {
                            delay(3000)
                            isInterfaceVisible = false
                        }
                    }
                }
            }
    ) {
        // Only show Scaffold when interface is visible
        AnimatedVisibility(
            visible = isInterfaceVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Scaffold(
                topBar = {
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
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
        // Show countdown overlay
        AnimatedVisibility(
            visible = countdownSeconds > 0,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            ) {
                Text(
                    text = countdownSeconds.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        NoteFallVisualizer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(400.dp),
            notes = midiNotes,
            currentTimeMs = currentTimeMs,
            isPlaying = isPlaybackActive,
            bpm = currentBpm ?: 120,
            fixedPianoRange = Pair(minNote, maxNote),
            keyWidth = keyWidth,
            pressedKeys = pressedKeys
        )

        PianoLayout(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(160.dp),
            noteRange = Pair(minNote, maxNote),
            keyWidth = keyWidth,
            pressedKeys = pressedKeys,
            currentNotes = midiNotes.filter { note ->
                abs(note.startTime - currentTimeMs) <= 150
            },
            syncedNotes = syncedNotes,
            onNotePressed = { }
        )

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
    noteRange: Pair<Int, Int>,
    keyWidth: Dp,
    pressedKeys: Set<Int>,
    currentNotes: List<MidiNote>,
    syncedNotes: Set<Int>,
    onNotePressed: (Int) -> Unit
) {
    val (minNote, maxNote) = noteRange
    val totalWhiteKeys = (minNote..maxNote).count { isWhiteKey(it) }
    val totalWidth = keyWidth * totalWhiteKeys

    Box(
        modifier = modifier
            .width(totalWidth)
            .background(Color(0xFF1A1A1A))
            .border(2.dp, Color(0xFF333333))
            .padding(4.dp)
    ) {
        // White keys
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Start
        ) {
            (minNote..maxNote).forEach { note ->
                if (isWhiteKey(note)) {
                    WhiteKey(
                        modifier = Modifier.width(keyWidth),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
                        color = if (note in pressedKeys) Color(0xFF81C784) else Color.White,
                        onPressed = onNotePressed
                    )
                }
            }
        }

        // Black keys
        Box(modifier = Modifier.fillMaxSize()) {
            (minNote..maxNote).forEach { note ->
                if (!isWhiteKey(note)) {
                    val whiteKeysBefore = (minNote until note).count { isWhiteKey(it) }
                    val xOffset = whiteKeysBefore * keyWidth.value + (keyWidth.value * 0.7f)

                    BlackKey(
                        modifier = Modifier.offset(x = xOffset.dp),
                        note = note,
                        isPhysicallyPressed = note in pressedKeys,
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
    color: Color = Color.White,
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }
    val isPressed = isPhysicallyPressed || isVirtuallyPressed

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
    color: Color = Color(0xFF202020),
    onPressed: (Int) -> Unit
) {
    var isVirtuallyPressed by remember { mutableStateOf(false) }
    val isPressed = isPhysicallyPressed || isVirtuallyPressed

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