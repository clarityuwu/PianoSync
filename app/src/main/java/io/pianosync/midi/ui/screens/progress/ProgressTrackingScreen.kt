package io.pianosync.midi.ui.screens.progress

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.data.manager.RecordingPlaybackManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.MidiRecording
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.data.model.PlayedNote
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.data.repository.MidiRecordingRepository
import io.pianosync.midi.data.repository.PerformanceRepository
import io.pianosync.midi.ui.screens.player.HandMode
import io.pianosync.midi.ui.screens.progress.components.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import io.pianosync.midi.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressTrackingScreen(
    midiFileRepository: MidiFileRepository,
    performanceRepository: PerformanceRepository,
    recordingRepository: MidiRecordingRepository, // Add this parameter
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<MidiFile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val recordingPlaybackManager = remember { RecordingPlaybackManager(context) }
    var allRecordings by remember { mutableStateOf<List<MidiRecording>>(emptyList()) }
    var showRecordingDialog by remember { mutableStateOf<MidiRecording?>(null) }
    var recordingToSave by remember { mutableStateOf<MidiRecording?>(null) }
    var saveRecordingTitle by remember { mutableStateOf("") }

    // Use collectAsState to automatically refresh when data changes
    val midiFiles by midiFileRepository.midiFiles.collectAsState(initial = emptyList())
    val allPerformances by performanceRepository.performanceHistory.collectAsState(initial = emptyList())

    // Derived state that updates when allPerformances changes
    val performanceData by remember {
        derivedStateOf {
            allPerformances.groupBy { it.midiFilePath }
                .mapValues { (_, records) ->
                    records.sortedByDescending { it.timestamp }.take(5)
                }
        }
    }

    // Load recordings data - use the passed recordingRepository
    LaunchedEffect(refreshTrigger) {
        isLoading = true
        kotlinx.coroutines.delay(100)
        allRecordings = recordingRepository.allRecordings.first()
        isLoading = false

        // Debug logging
        android.util.Log.d("ProgressTracking", "Loaded ${allRecordings.size} recordings")
        allRecordings.forEach { recording ->
            android.util.Log.d("ProgressTracking", "Recording: ${recording.originalMidiFileName} - ${recording.timestamp}")
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.progress_tracking)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            refreshTrigger++
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Overall progress section
                item {
                    OverallProgressSection(allPerformances)
                }

                // MIDI File selection
                item {
                    FileSelectionChips(
                        midiFiles = midiFiles,
                        selectedFile = selectedFile,
                        onFileSelected = { selectedFile = it }
                    )
                }

                // Selected file performance details or all files summary
                if (selectedFile != null) {
                    val filePerformances = allPerformances.filter { it.midiFilePath == selectedFile?.path }

                    item {
                        FilePerformanceDetails(
                            midiFile = selectedFile!!,
                            performances = filePerformances
                        )
                    }

                    item {
                        Text(
                            text = stringResource(R.string.recent_practice_sessions),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(filePerformances.take(5)) { performance ->
                        PracticeSessionCard(performance)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (filePerformances.isEmpty()) {
                        item {
                            EmptyStateMessage("No practice data yet for this piece.")
                        }
                    }

                    // Recordings section for selected file
                    val fileRecordings = allRecordings.filter { it.originalMidiFilePath == selectedFile?.path }

                    if (fileRecordings.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.midi_recordings_format, fileRecordings.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(fileRecordings.take(5)) { recording ->
                            MidiRecordingCard(
                                recording = recording,
                                recordingRepository = recordingRepository,
                                onPlay = { showRecordingDialog = recording },
                                onSave = {
                                    if (!recording.isSaved) {
                                        recordingToSave = recording
                                        saveRecordingTitle = ""
                                    }
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        recordingRepository.deleteRecording(recording.id)
                                        allRecordings = recordingRepository.allRecordings.first()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        item {
                            EmptyStateMessage(stringResource(R.string.no_recordings_yet))
                        }
                    }
                } else {
                    // Show progress for all files
                    item {
                        Text(
                            text = stringResource(R.string.all_files_summary), // Updated
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    item {
                        AllFilesSummary(performanceData, midiFiles)
                    }

                    // Show all recordings when no specific file is selected
                    if (allRecordings.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.recent_recordings_count_format, allRecordings.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(allRecordings.take(10)) { recording ->
                            MidiRecordingCard(
                                recording = recording,
                                recordingRepository = recordingRepository, // Add this line
                                onPlay = { showRecordingDialog = recording },
                                onSave = {
                                    if (!recording.isSaved) {
                                        recordingToSave = recording
                                        saveRecordingTitle = ""
                                    }
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        recordingRepository.deleteRecording(recording.id)
                                        allRecordings = recordingRepository.allRecordings.first()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    showRecordingDialog?.let { recording ->
        RecordingPlaybackDialog(
            recording = recording,
            playbackManager = recordingPlaybackManager,
            onDismiss = {
                showRecordingDialog = null
                recordingPlaybackManager.stopPlayback()
            }
        )
    }

    recordingToSave?.let { recording ->
        SaveRecordingDialog(
            recording = recording,
            title = saveRecordingTitle,
            onTitleChange = { saveRecordingTitle = it },
            onSave = {
                coroutineScope.launch {
                    recordingRepository.saveRecordingPermanently(recording.id, saveRecordingTitle)
                    allRecordings = recordingRepository.allRecordings.first()
                    recordingToSave = null
                    saveRecordingTitle = ""
                }
            },
            onDismiss = {
                recordingToSave = null
                saveRecordingTitle = ""
            }
        )
    }
}


@Composable
fun FilePerformanceDetails(
    midiFile: MidiFile,
    performances: List<PerformanceRecord>
) {
    val sortedPerformances = performances.sortedByDescending { it.timestamp }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = midiFile.name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Performance stats
            if (sortedPerformances.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Best score
                    val bestScore = sortedPerformances.maxOfOrNull { it.score } ?: 0
                    FileStatItem(
                        value = "$bestScore%",
                        label = stringResource(R.string.best_score), // Updated
                        color = getScoreColor(bestScore)
                    )

                    // Most recent score
                    val recentScore = sortedPerformances.first().score
                    FileStatItem(
                        value = "$recentScore%",
                        label = stringResource(R.string.last_score), // Updated
                        color = getScoreColor(recentScore)
                    )

                    // Total practice time
                    val totalTime = sortedPerformances.sumOf { it.durationMs }
                    FileStatItem(
                        value = formatDuration(totalTime),
                        label = stringResource(R.string.practice_time), // Updated
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Score progress chart
                if (sortedPerformances.size >= 2) {
                    val chartData = prepareChartData(sortedPerformances)
                    ScoreProgressChart(chartData)
                }
            } else {
                EmptyStateMessage(stringResource(R.string.no_practice_data_piece)) // Updated
            }
        }
    }
}

@Composable
fun AllFilesSummary(
    performanceData: Map<String, List<PerformanceRecord>>,
    midiFiles: List<MidiFile>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (performanceData.isEmpty()) {
            EmptyStateMessage(stringResource(R.string.no_practice_data_start)) // Updated
        } else {
            midiFiles.forEach { midiFile ->
                val filePerformances = performanceData[midiFile.path] ?: emptyList()

                if (filePerformances.isNotEmpty()) {
                    FileSummaryCard(midiFile, filePerformances)
                }
            }
        }
    }
}

@Composable
fun FileSummaryCard(
    midiFile: MidiFile,
    performances: List<PerformanceRecord>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = midiFile.name,
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Performance stats
                Column {
                    val bestScore = performances.maxOfOrNull { it.score } ?: 0
                    val lastScore = performances.first().score
                    val improvement = lastScore - (performances.lastOrNull()?.score ?: 0)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.best_last_scores_format, bestScore, lastScore), // Updated
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (performances.size >= 2 && improvement != 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (improvement > 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = "Trend",
                                tint = if (improvement > 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Number of sessions
                    Text(
                        text = stringResource(R.string.sessions_practice_format, performances.size, formatDuration(performances.sumOf { it.durationMs })), // Updated
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Mini score graph remains the same
                if (performances.size >= 2) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.height(24.dp)
                    ) {
                        performances.asReversed().take(5).forEach { performance ->
                            val height = (performance.score / 100f) * 24f

                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(height.dp)
                                    .background(
                                        getScoreColor(performance.score),
                                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PracticeSessionCard(performance: PerformanceRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with date and score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Date and time
                Text(
                    text = formatDateTime(performance.timestamp),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Score badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = getScoreColor(performance.score),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.score_percentage_format, performance.score), // Updated
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SessionStat(
                    value = performance.notesHit.toString(),
                    label = stringResource(R.string.notes_hit), // Updated
                    color = Color(0xFF4CAF50)
                )

                SessionStat(
                    value = performance.notesMissed.toString(),
                    label = stringResource(R.string.missed), // Updated
                    color = Color(0xFFF44336)
                )

                SessionStat(
                    value = "${performance.bpm}",
                    label = stringResource(R.string.bpm), // Updated
                    color = Color(0xFF2196F3)
                )

                SessionStat(
                    value = when(performance.handMode) {
                        HandMode.LEFT_HAND_ONLY -> stringResource(R.string.left) // Updated
                        HandMode.RIGHT_HAND_ONLY -> stringResource(R.string.right) // Updated
                        HandMode.BOTH_HANDS -> stringResource(R.string.both) // Updated
                    },
                    label = stringResource(R.string.hand_mode), // Updated
                    color = Color(0xFF9C27B0)
                )
            }

            // Notes visualization if available
            if (performance.notesPlayed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.notes_played_label), // Updated
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                NotesPlayedVisualizer(performance.notesPlayed.take(100))
            }
        }
    }
}

@Composable
fun NotesPlayedVisualizer(notes: List<PlayedNote>) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(notes) { note ->
            val correctColor = if (note.isLeftHand) Color(0xFF03A9F4) else Color(0xFF4CAF50)
            val incorrectColor = if (note.isLeftHand) Color(0xFF7986CB) else Color(0xFFE57373)

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (note.wasCorrect) correctColor else incorrectColor)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun SessionStat(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Surface(
            color = color.copy(alpha = 0.1f),
            shape = CircleShape,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FileStatItem(
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )

        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun EmptyStateMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.MusicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Helper functions
private fun getScoreColor(score: Int): Color {
    return when {
        score >= 90 -> Color(0xFF4CAF50) // A
        score >= 80 -> Color(0xFF8BC34A) // B
        score >= 70 -> Color(0xFFFFEB3B) // C
        score >= 60 -> Color(0xFFFF9800) // D
        else -> Color(0xFFF44336) // F
    }
}

private fun formatDateTime(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy - h:mm a", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = (durationMs / (1000 * 60 * 60))

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}

private fun calculateRecentTrend(performances: List<PerformanceRecord>): Int {
    if (performances.size < 2) return 0

    val sortedPerformances = performances.sortedByDescending { it.timestamp }
    val last5Performances = sortedPerformances.take(5)

    if (last5Performances.size < 2) return 0

    // Calculate the average of the oldest 2 and newest 2 performances
    val newestAvg = (last5Performances[0].score + last5Performances.getOrElse(1) { last5Performances[0] }.score) / 2
    val oldestAvg = (last5Performances[last5Performances.size - 1].score + last5Performances.getOrElse(last5Performances.size - 2) { last5Performances[last5Performances.size - 1] }.score) / 2

    return (newestAvg - oldestAvg)
}

private fun prepareChartData(performances: List<PerformanceRecord>): List<ScoreDataPoint> {
    val sortedPerformances = performances
        .sortedBy { it.timestamp }
        .takeLast(7) // Last 7 performances

    val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())

    return sortedPerformances.map { performance ->
        ScoreDataPoint(
            date = dateFormat.format(Date(performance.timestamp)),
            score = performance.score.toFloat()
        )
    }
}

data class ScoreDataPoint(val date: String, val score: Float)


@Composable
fun MidiRecordingCard(
    recording: MidiRecording,
    recordingRepository: MidiRecordingRepository,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (recording.isSaved) recording.title else stringResource(R.string.recording),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = formatDateTime(recording.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (recording.isSaved) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.saved),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Recording stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.duration_format, formatDuration(recording.durationMs)),
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(R.string.bpm_hand_mode_format, recording.bpm, getHandModeString(recording.handMode)),
                    style = MaterialTheme.typography.bodySmall
                )

                recording.score?.let { score ->
                    Text(
                        text = stringResource(R.string.score_format, score),
                        style = MaterialTheme.typography.bodySmall,
                        color = getScoreColor(score)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.play))
                }

                if (!recording.isSaved) {
                    OutlinedButton(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.save),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.save))
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val exportedFile = recordingRepository.exportRecordingAsMidiFile(
                                    context = context,
                                    recording = recording,
                                    fileName = "${recording.displayName}.mid"
                                )

                                exportedFile?.let { file ->
                                    val exportDirectory = file.parentFile?.absolutePath ?: "Unknown location"
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.recording_exported_format, exportDirectory),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } ?: run {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.export_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.export),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.export))
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.Red
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingPlaybackDialog(
    recording: MidiRecording,
    playbackManager: RecordingPlaybackManager,
    onDismiss: () -> Unit
) {
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val currentTime by playbackManager.currentTimeMs.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.play_recording),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = recording.displayName,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = if (recording.durationMs > 0) {
                        (currentTime.toFloat() / recording.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Time display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(currentTime),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatDuration(recording.durationMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playback controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { playbackManager.seekTo(0) }
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.restart))
                    }

                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                playbackManager.pausePlayback()
                            } else {
                                if (currentTime > 0) {
                                    playbackManager.resumePlayback()
                                } else {
                                    playbackManager.startPlayback(recording)
                                }
                            }
                        }
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                        )
                    }

                    IconButton(
                        onClick = { playbackManager.stopPlayback() }
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
fun SaveRecordingDialog(
    recording: MidiRecording,
    title: String,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_recording)) },
        text = {
            Column {
                Text(stringResource(R.string.give_recording_name))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.recording_title)) },
                    placeholder = { Text(stringResource(R.string.recording_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun OverallProgressSection(
    performances: List<PerformanceRecord>,
    modifier: Modifier = Modifier
) {
    // Calculate overall stats
    val totalSessions = performances.size
    val totalPieces = performances.map { it.midiFilePath }.distinct().size
    val averageScore = if (performances.isNotEmpty()) {
        performances.sumOf { it.score } / performances.size
    } else 0
    val totalPlayTime = performances.sumOf { it.durationMs }
    val recentTrend = calculateRecentTrend(performances)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.your_practice_progress),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    icon = Icons.Default.MusicNote,
                    value = totalPieces.toString(),
                    label = stringResource(R.string.pieces)
                )

                StatItem(
                    icon = Icons.Default.History,
                    value = totalSessions.toString(),
                    label = stringResource(R.string.sessions)
                )

                StatItem(
                    icon = Icons.Default.Score,
                    value = "$averageScore%",
                    label = stringResource(R.string.avg_score)
                )

                StatItem(
                    icon = Icons.Default.Timer,
                    value = formatDuration(totalPlayTime),
                    label = stringResource(R.string.practice_time)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Score progress chart for recent performances
            if (performances.isNotEmpty()) {
                val chartData = prepareChartData(performances)
                ScoreProgressChart(chartData)

                Spacer(modifier = Modifier.height(8.dp))

                // Performance trend indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = when {
                            recentTrend > 0 -> Icons.Default.TrendingUp
                            recentTrend < 0 -> Icons.Default.TrendingDown
                            else -> Icons.Default.TrendingFlat
                        },
                        contentDescription = "Trend",
                        tint = when {
                            recentTrend > 0 -> Color(0xFF4CAF50)
                            recentTrend < 0 -> Color(0xFFF44336)
                            else -> Color.Gray
                        }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = when {
                            recentTrend > 0 -> stringResource(R.string.improvement_format, recentTrend)
                            recentTrend < 0 -> stringResource(R.string.decline_format, recentTrend)
                            else -> stringResource(R.string.no_change)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else {
                EmptyStateMessage(stringResource(R.string.no_practice_data_start))
            }
        }
    }
}

@Composable
fun FileSelectionChips(
    midiFiles: List<MidiFile>,
    selectedFile: MidiFile?,
    onFileSelected: (MidiFile?) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.select_piece_details),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedFile == null,
                    onClick = { onFileSelected(null) },
                    label = { Text(stringResource(R.string.all_pieces)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Album,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            items(midiFiles) { file ->
                FilterChip(
                    selected = selectedFile?.path == file.path,
                    onClick = { onFileSelected(file) },
                    label = { Text(file.name) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

// Helper function to get localized hand mode string
@Composable
private fun getHandModeString(handMode: HandMode): String {
    return when (handMode) {
        HandMode.BOTH_HANDS -> stringResource(R.string.both)
        HandMode.LEFT_HAND_ONLY -> stringResource(R.string.left)
        HandMode.RIGHT_HAND_ONLY -> stringResource(R.string.right)
    }
}