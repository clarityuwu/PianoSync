package io.pianosync.midi.ui.screens.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import io.pianosync.midi.R
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.ui.theme.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Enhanced MIDI File Card with beautiful gradient backgrounds and music-themed colors
 *
 * Features:
 * - Intelligent gradient selection based on file characteristics and performance
 * - Smooth press/hover animations with spring physics
 * - Recent activity indicators with subtle pulsing
 * - Enhanced text with subtle shadows for better readability
 * - Progress indicators with glow effects
 * - Performance trend visualization
 *
 * Usage: Replace the existing MidiFileCard in your HomeScreen composable
 */

/**
 * Get a gradient based on the MIDI file's characteristics using the new gradient system
 */
private fun getGradientForMidiFile(midiFile: MidiFile, recentPerformances: List<PerformanceRecord>): Brush {
    // Use the new gradient selector system for intelligent gradient selection
    return midiFile.getGradient(recentPerformances)
}

/**
 * A card component that displays MIDI file information with gradient backgrounds and progress indicators
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
    var isPressed by remember { mutableStateOf(false) }

    // Calculate progress metrics
    val progressData = calculateProgressData(recentPerformances)
    val lastPlayedTime = recentPerformances.firstOrNull()?.timestamp

    // Get the gradient for this card
    val cardGradient = getGradientForMidiFile(midiFile, recentPerformances)

    // Animation states
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardScale"
    )

    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardElevation"
    )

    // Subtle pulsing animation for cards with recent activity
    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val hasRecentActivity = recentPerformances.isNotEmpty() &&
            (System.currentTimeMillis() - (lastPlayedTime ?: 0)) < 24 * 60 * 60 * 1000 // 24 hours

    val pulseAlpha by pulseAnimation.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

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
            .width(180.dp)
            .height(220.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(
                elevation = animatedElevation,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteDialog = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // Handled by shadow
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(cardGradient)
                .graphicsLayer {
                    // Apply pulse effect for recent activity
                    if (hasRecentActivity) {
                        alpha = pulseAlpha
                    }
                }
        ) {
            // Add a subtle shimmer overlay for premium feel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.1f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.05f)
                            ),
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(1f, 1f)
                        )
                    )
            )

            // Add subtle dark overlay for better text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = 0.12f)
                    )
            )

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
                    // Enhanced music note icon with glow effect
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                Color.White.copy(alpha = 0.25f),
                                RoundedCornerShape(18.dp)
                            )
                            .shadow(
                                elevation = 2.dp,
                                shape = RoundedCornerShape(18.dp),
                                ambientColor = Color.White.copy(alpha = 0.3f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }

                    // Progress indicator (if there are performances)
                    if (recentPerformances.isNotEmpty()) {
                        ProgressIndicatorBadge(
                            progress = progressData.averageScore,
                            bestScore = progressData.bestScore
                        )
                    }
                }

                // File name with enhanced styling and subtle text shadow
                Text(
                    text = midiFile.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                            blurRadius = 2f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.weight(1f))

                // BPM info with enhanced styling and subtle glow
                Surface(
                    color = Color.White.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .shadow(
                            elevation = 1.dp,
                            shape = RoundedCornerShape(12.dp),
                            ambientColor = Color.White.copy(alpha = 0.2f)
                        )
                ) {
                    Text(
                        text = midiFile.currentBpm?.let {
                            stringResource(R.string.midi_bpm_format, it)
                        } ?: stringResource(R.string.midi_bpm_unknown),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.3f),
                                offset = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                                blurRadius = 1f
                            )
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Practice stats or import date with enhanced styling
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.4f),
                                offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                                blurRadius = 2f
                            )
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Add recent activity indicator
            if (hasRecentActivity) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(8.dp)
                        .background(
                            Color(0xFF4CAF50),
                            CircleShape
                        )
                        .shadow(
                            elevation = 2.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                        )
                )
            }
        }

        // Handle press state
        LaunchedEffect(isPressed) {
            if (isPressed) {
                kotlinx.coroutines.delay(150)
                isPressed = false
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
        progress >= 90 -> Color.White
        progress >= 75 -> WarmGold20
        progress >= 60 -> AccentRose.copy(alpha = 0.8f)
        else -> Color.White.copy(alpha = 0.7f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Circular progress indicator with enhanced styling
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(42.dp)
        ) {
            // Background circle with glow
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.White.copy(alpha = 0.25f),
                        RoundedCornerShape(21.dp)
                    )
                    .shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(21.dp),
                        ambientColor = Color.White.copy(alpha = 0.3f)
                    )
            )

            CircularProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxSize(),
                color = progressColor,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp
            )

            Text(
                text = "$progress%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        blurRadius = 1f
                    )
                ),
                color = Color.White
            )
        }

        // Best score indicator (if different from average)
        if (bestScore > progress) {
            Text(
                text = "Best: $bestScore%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        blurRadius = 1f
                    )
                ),
                color = Color.White.copy(alpha = 0.9f)
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
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                        blurRadius = 2f
                    )
                ),
                color = Color.White.copy(alpha = 0.95f)
            )

            // Trend indicator with enhanced glow effect
            if (trend != ProgressTrend.STABLE) {
                Spacer(modifier = Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            when (trend) {
                                ProgressTrend.IMPROVING -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                                ProgressTrend.DECLINING -> Color(0xFFF44336).copy(alpha = 0.2f)
                                ProgressTrend.STABLE -> Color.Transparent
                            },
                            CircleShape
                        )
                        .shadow(
                            elevation = if (trend != ProgressTrend.STABLE) 1.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = when (trend) {
                                ProgressTrend.IMPROVING -> Color(0xFF4CAF50).copy(alpha = 0.3f)
                                ProgressTrend.DECLINING -> Color(0xFFF44336).copy(alpha = 0.3f)
                                ProgressTrend.STABLE -> Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (trend) {
                            ProgressTrend.IMPROVING -> Icons.Default.PlayArrow
                            ProgressTrend.DECLINING -> Icons.Default.PlayArrow
                            ProgressTrend.STABLE -> Icons.Default.PlayArrow
                        },
                        contentDescription = "Trend",
                        tint = when (trend) {
                            ProgressTrend.IMPROVING -> Color(0xFF4CAF50)
                            ProgressTrend.DECLINING -> Color(0xFFF44336)
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
        }

        // Last played time with enhanced styling
        lastPlayedTime?.let { timestamp ->
            Text(
                text = "Last played ${formatRelativeTime(timestamp)}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                        blurRadius = 2f
                    )
                ),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Data classes and helper functions (unchanged)
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