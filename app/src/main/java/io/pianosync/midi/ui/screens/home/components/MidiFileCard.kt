package io.pianosync.midi.ui.screens.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pianosync.midi.R
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.ui.theme.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * A card component that displays MIDI file information with progress indicators
 *
 * @param midiFile The MIDI file to display
 * @param recentPerformances Recent performance records for this file
 * @param onClick Callback invoked when the card is clicked
 * @param onDelete Callback invoked when deletion is confirmed
 * @param modifier Optional modifier for the component
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MidiFileCard(
    midiFile: MidiFile,
    recentPerformances: List<PerformanceRecord> = emptyList(),
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Calculate progress metrics
    val progressData = calculateProgressData(recentPerformances)
    val lastPlayedTime = recentPerformances.firstOrNull()?.timestamp

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = {
                Text(stringResource(R.string.delete_dialog_message, midiFile.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.delete_dialog_cancel))
                }
            }
        )
    }

    Card(
        modifier = modifier
            .width(180.dp) // Slightly wider to accommodate new content
            .height(220.dp) // Slightly taller
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteDialog = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = cardBackgroundColor(),
            contentColor = cardContentColor()
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with icon and progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .offset(y = (-3).dp), // Move icon up by 4dp
                    tint = MaterialTheme.colorScheme.primary
                )

                // Progress indicator (if there are performances)
                if (recentPerformances.isNotEmpty()) {
                    ProgressIndicatorBadge(
                        progress = progressData.averageScore,
                        bestScore = progressData.bestScore
                    )
                }
            }

            // File name
            Text(
                text = midiFile.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            // BPM info
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = midiFile.currentBpm?.let {
                        stringResource(R.string.midi_bpm_format, it)
                    } ?: stringResource(R.string.midi_bpm_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Practice stats or import date
            if (recentPerformances.isNotEmpty()) {
                PracticeStatsSection(
                    sessionCount = recentPerformances.size,
                    lastPlayedTime = lastPlayedTime,
                    trend = progressData.trend
                )
            } else {
                // Show import date if no practice data
                Text(
                    text = "Imported ${formatDate(midiFile.dateImported)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ProgressIndicatorBadge(
    progress: Int,
    bestScore: Int,
    modifier: Modifier = Modifier
) {
    val progressColor = when {
        progress >= 90 -> successAccentColor()
        progress >= 75 -> WarmGold60
        progress >= 60 -> AccentRose.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.outline
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Circular progress indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp)
        ) {
            CircularProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxSize(),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                strokeWidth = 3.dp
            )

            Text(
                text = "$progress%",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = progressColor
            )
        }

        // Best score indicator (if different from average)
        if (bestScore > progress) {
            Text(
                text = "Best: $bestScore%",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun PracticeStatsSection(
    sessionCount: Int,
    lastPlayedTime: Long?,
    trend: ProgressTrend,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // Session count with trend indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$sessionCount session${if (sessionCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            // Trend indicator
            if (trend != ProgressTrend.STABLE) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = when (trend) {
                        ProgressTrend.IMPROVING -> Icons.Default.PlayArrow
                        ProgressTrend.DECLINING -> Icons.Default.PlayArrow
                        ProgressTrend.STABLE -> Icons.Default.PlayArrow // Won't be shown
                    },
                    contentDescription = "Trend",
                    tint = when (trend) {
                        ProgressTrend.IMPROVING -> successAccentColor()
                        ProgressTrend.DECLINING -> AccentRose
                        ProgressTrend.STABLE -> Color.Gray
                    },
                    modifier = Modifier
                        .size(12.dp)
                        .then(
                            if (trend == ProgressTrend.DECLINING) {
                                Modifier.graphicsLayer(rotationZ = 180f)
                            } else Modifier
                        )
                )
            }
        }

        // Last played time
        lastPlayedTime?.let { timestamp ->
            Text(
                text = "Last played ${formatRelativeTime(timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Data classes and helper functions
data class ProgressData(
    val averageScore: Int,
    val bestScore: Int,
    val trend: ProgressTrend
)

enum class ProgressTrend {
    IMPROVING, DECLINING, STABLE
}

private fun calculateProgressData(performances: List<PerformanceRecord>): ProgressData {
    if (performances.isEmpty()) {
        return ProgressData(0, 0, ProgressTrend.STABLE)
    }

    val scores = performances.map { it.score }
    val averageScore = scores.average().roundToInt()
    val bestScore = scores.maxOrNull() ?: 0

    // Calculate trend based on recent vs older performances
    val trend = if (performances.size >= 3) {
        val recentAvg = performances.take(2).map { it.score }.average()
        val olderAvg = performances.drop(2).take(2).map { it.score }.average()

        when {
            recentAvg > olderAvg + 5 -> ProgressTrend.IMPROVING
            recentAvg < olderAvg - 5 -> ProgressTrend.DECLINING
            else -> ProgressTrend.STABLE
        }
    } else {
        ProgressTrend.STABLE
    }

    return ProgressData(averageScore, bestScore, trend)
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - timestamp

    val minutes = diffMs / (1000 * 60)
    val hours = diffMs / (1000 * 60 * 60)
    val days = diffMs / (1000 * 60 * 60 * 24)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        days < 30 -> "${(days / 7)}w ago"
        else -> "over a month ago"
    }
}

private fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    return DateFormat.getDateInstance(DateFormat.SHORT).format(date)
}