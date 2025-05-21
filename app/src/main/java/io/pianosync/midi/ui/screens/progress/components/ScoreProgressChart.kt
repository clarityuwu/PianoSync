package io.pianosync.midi.ui.screens.progress.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pianosync.midi.ui.screens.progress.ScoreDataPoint

/**
 * A chart that visualizes score progress over time
 */
@Composable
fun ScoreProgressChart(
    data: List<ScoreDataPoint>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    if (data.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(16.dp)
    ) {
        // Chart title
        Text(
            text = "Score Progress",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // Y-axis labels (score percentages)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("100%", fontSize = 10.sp)
            Text("75%", fontSize = 10.sp)
            Text("50%", fontSize = 10.sp)
            Text("25%", fontSize = 10.sp)
            Text("0%", fontSize = 10.sp)
        }

        // Main chart canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 30.dp, top = 24.dp, bottom = 24.dp)
        ) {
            val width = size.width
            val height = size.height
            val horizontalStep = width / (data.size - 1).coerceAtLeast(1)

            // Draw grid lines
            val gridColor = Color.Gray.copy(alpha = 0.2f)
            val gridLineCount = 4
            val gridStep = height / gridLineCount

            repeat(gridLineCount + 1) { i ->
                val y = i * gridStep
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            // Create line path
            val path = Path()
            var startedPath = false

            // Find min and max score for proper scaling
            val minScore = data.minOfOrNull { it.score }?.coerceAtLeast(0f) ?: 0f
            val maxScore = data.maxOfOrNull { it.score }?.coerceAtMost(100f) ?: 100f

            // Use dynamic range if there's significant variation, otherwise use 0-100
            val effectiveMinScore = if (maxScore - minScore > 30f) minScore.coerceAtMost(50f) else 0f
            val effectiveMaxScore = if (maxScore - minScore > 30f) maxScore.coerceAtLeast(minScore + 30f) else 100f
            val scoreRange = (effectiveMaxScore - effectiveMinScore).coerceAtLeast(1f)

            data.forEachIndexed { index, point ->
                val x = index * horizontalStep
                // Calculate y position with proper normalization
                val normalizedScore = (point.score - effectiveMinScore) / scoreRange
                val y = height * (1f - normalizedScore.coerceIn(0f, 1f))

                if (!startedPath) {
                    path.moveTo(x, y)
                    startedPath = true
                } else {
                    path.lineTo(x, y)
                }

                // Draw points
                val pointColor = getScoreColor(point.score.toInt())
                drawCircle(
                    color = pointColor,
                    radius = 4f,
                    center = Offset(x, y)
                )
            }

            // Draw line connecting points
            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 2f)
            )
        }

        // X-axis labels (dates)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 30.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            data.forEach { point ->
                Text(
                    text = point.date,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

/**
 * Returns a color based on the score value
 */
fun getScoreColor(score: Int): Color {
    return when {
        score >= 90 -> Color(0xFF4CAF50) // A
        score >= 80 -> Color(0xFF8BC34A) // B
        score >= 70 -> Color(0xFFFFEB3B) // C
        score >= 60 -> Color(0xFFFF9800) // D
        else -> Color(0xFFF44336) // F
    }
}