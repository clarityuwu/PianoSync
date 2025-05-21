package io.pianosync.midi.ui.screens.player.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Visual representation of metronome beats
 *
 * @param currentBeat The current beat position (0-based)
 * @param beatsPerMeasure Total beats in a measure
 * @param isRunning Whether the metronome is currently running
 * @param modifier Optional modifier for customization
 */
@Composable
fun MetronomeVisualizer(
    currentBeat: Int,
    beatsPerMeasure: Int = 4,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(beatsPerMeasure) { beatIndex ->
            val isCurrentBeat = beatIndex == currentBeat && isRunning

            // Animate the beat indicator
            val scale by animateFloatAsState(
                targetValue = if (isCurrentBeat) 1.3f else 1.0f,
                animationSpec = tween(
                    durationMillis = if (isCurrentBeat) 100 else 200
                ),
                label = "BeatScale"
            )

            // Different color for first beat of measure
            val beatColor = when {
                !isRunning -> Color.Gray.copy(alpha = 0.3f)
                isCurrentBeat && beatIndex == 0 -> MaterialTheme.colorScheme.primary
                isCurrentBeat -> MaterialTheme.colorScheme.secondary
                beatIndex == 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(12.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(beatColor)
                    .border(
                        width = 1.dp,
                        color = if (beatIndex == 0)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            )
        }
    }
}