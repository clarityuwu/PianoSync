package io.pianosync.midi.ui.screens.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.min

@Composable
fun LoopControl(
    modifier: Modifier = Modifier,
    isLoopEnabled: Boolean,
    loopStartMs: Long,
    loopEndMs: Long,
    songDurationMs: Long,
    currentTimeMs: Long,
    onLoopToggled: (Boolean) -> Unit,
    onSetLoopStart: () -> Unit,
    onSetLoopEnd: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    // Ensure we have a valid song duration
    val safeSongDuration = max(1L, songDurationMs)

    // Calculate the percentages
    val progress = min(1f, max(0f, currentTimeMs.toFloat() / safeSongDuration))
    val loopStartPct = min(1f, max(0f, loopStartMs.toFloat() / safeSongDuration))
    val loopEndPct = min(1f, max(0f, loopEndMs.toFloat() / safeSongDuration))

    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Time display and controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Current time
            Text(
                text = formatTime(currentTimeMs),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )

            // Loop controls
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Set loop start button
                IconButton(
                    onClick = onSetLoopStart,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(
                        text = "A",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLoopEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                    )
                }

                // Loop toggle button
                IconButton(
                    onClick = { onLoopToggled(!isLoopEnabled) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isLoopEnabled) Icons.Default.Loop else Icons.Default.Piano,
                        contentDescription = "Toggle Loop",
                        tint = if (isLoopEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                    )
                }

                // Set loop end button
                IconButton(
                    onClick = onSetLoopEnd,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(
                        text = "B",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLoopEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Total duration
            Text(
                text = formatTime(songDurationMs),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Progress bar with loop markers
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)  // Increased height to accommodate larger markers
                .padding(vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val seekPosition = (offset.x / size.width * safeSongDuration).toLong()
                        onSeekTo(seekPosition)
                    }
                }
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
                    .align(Alignment.Center)
                    .zIndex(1f) // Ensure it's under other elements
            )

            // Loop region (if enabled)
            if (isLoopEnabled && loopStartMs < loopEndMs) {
                // Create a box that starts at the loop start position
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp) // Thicker for better visibility
                        .align(Alignment.Center)
                        .zIndex(2f) // Above background, below markers
                ) {
                    // Inner box to represent the loop region
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(loopEndPct - loopStartPct)
                            .offset(x = (loopStartPct * 100).dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }

            // Main progress bar - simple percentage-based width
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
                    .align(Alignment.CenterStart)
                    .zIndex(3f) // Above loop region, below markers/thumb
            )

            // Loop Start Marker (A) - ENLARGED
            if (isLoopEnabled && loopStartMs > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(loopStartPct)
                        .align(Alignment.CenterStart)
                        .zIndex(4f) // Above most elements
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp) // Larger marker
                            .align(Alignment.CenterEnd)
                            .shadow(2.dp, CircleShape)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(1.dp, Color.White, CircleShape) // White border for contrast
                    ) {
                        Text(
                            text = "A",
                            fontSize = 12.sp, // Larger text
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            // Loop End Marker (B) - ENLARGED
            if (isLoopEnabled && loopEndMs > 0 && loopEndMs < songDurationMs) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(loopEndPct)
                        .align(Alignment.CenterStart)
                        .zIndex(4f) // Above most elements
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp) // Larger marker
                            .align(Alignment.CenterEnd)
                            .shadow(2.dp, CircleShape)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(1.dp, Color.White, CircleShape) // White border for contrast
                    ) {
                        Text(
                            text = "B",
                            fontSize = 12.sp, // Larger text
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            // Thumb indicator - place with highest z-index so it's always on top
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .align(Alignment.CenterStart)
                    .zIndex(5f) // Always on top
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp) // Slightly larger than before
                        .align(Alignment.CenterEnd)
                        .shadow(3.dp, CircleShape) // More shadow for depth
                        .background(Color.White, CircleShape)
                        .border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                )
            }
        }
    }
}

// Helper function to format milliseconds into mm:ss format
fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}