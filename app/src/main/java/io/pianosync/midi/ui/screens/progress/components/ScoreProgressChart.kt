package io.pianosync.midi.ui.screens.progress.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pianosync.midi.ui.screens.progress.ScoreDataPoint
import kotlinx.coroutines.delay

/**
 * A chart that visualizes score progress over time with smooth drawing animations
 */
@Composable
fun ScoreProgressChart(
    data: List<ScoreDataPoint>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    if (data.isEmpty()) return

    // Animation states
    var animationStarted by remember { mutableStateOf(false) }

    // Grid animation - animates from 0f to 1f over 300ms
    val gridProgress by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "gridAnimation"
    )

    // Line drawing animation - starts after grid, takes 800ms
    val lineProgress by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 800,
            delayMillis = 200,
            easing = EaseInOutCubic
        ),
        label = "lineAnimation"
    )

    // Points animation - starts after line begins, staggers each point
    val pointsProgress by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 600,
            delayMillis = 400,
            easing = EaseOutBack
        ),
        label = "pointsAnimation"
    )

    // Start animation when component loads
    LaunchedEffect(data) {
        delay(100) // Small delay for smoother appearance
        animationStarted = true
    }

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
                .padding(end = 12.dp, top = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("100%", fontSize = 10.sp)
            Text("75%", fontSize = 10.sp)
            Text("50%", fontSize = 10.sp)
            Text("25%", fontSize = 10.sp)
            Text("0%", fontSize = 10.sp)
        }

        // Main chart canvas with animations
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 30.dp, top = 24.dp, bottom = 24.dp)
        ) {
            val width = size.width
            val height = size.height
            val horizontalStep = width / (data.size - 1).coerceAtLeast(1)

            // Draw grid lines with animation
            val gridColor = Color.Gray.copy(alpha = 0.2f * gridProgress)
            val gridLineCount = 4
            val gridStep = height / gridLineCount

            repeat(gridLineCount + 1) { i ->
                val y = i * gridStep
                val lineWidth = width * gridProgress
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(lineWidth, y),
                    strokeWidth = 1f
                )
            }

            // Find min and max score for proper scaling
            val minScore = data.minOfOrNull { it.score }?.coerceAtLeast(0f) ?: 0f
            val maxScore = data.maxOfOrNull { it.score }?.coerceAtMost(100f) ?: 100f

            // Use dynamic range if there's significant variation, otherwise use 0-100
            val effectiveMinScore = if (maxScore - minScore > 30f) minScore.coerceAtMost(50f) else 0f
            val effectiveMaxScore = if (maxScore - minScore > 30f) maxScore.coerceAtLeast(minScore + 30f) else 100f
            val scoreRange = (effectiveMaxScore - effectiveMinScore).coerceAtLeast(1f)

            // Calculate all point positions
            val points = data.mapIndexed { index, point ->
                val x = index * horizontalStep
                val normalizedScore = (point.score - effectiveMinScore) / scoreRange
                val y = height * (1f - normalizedScore.coerceIn(0f, 1f))
                Offset(x, y)
            }

            // Draw animated line path
            if (points.isNotEmpty() && lineProgress > 0f) {
                val animatedPath = Path()

                // Calculate how many points to include based on animation progress
                val totalPathLength = data.size - 1
                val currentPathLength = (totalPathLength * lineProgress).coerceIn(0f, totalPathLength.toFloat())
                val completePoints = currentPathLength.toInt()
                val partialProgress = currentPathLength - completePoints

                // Add complete points
                if (completePoints >= 0 && completePoints < points.size) {
                    animatedPath.moveTo(points[0].x, points[0].y)

                    for (i in 1..completePoints.coerceAtMost(points.size - 1)) {
                        animatedPath.lineTo(points[i].x, points[i].y)
                    }

                    // Add partial segment if needed
                    if (partialProgress > 0f && completePoints + 1 < points.size) {
                        val startPoint = points[completePoints]
                        val endPoint = points[completePoints + 1]
                        val partialX = startPoint.x + (endPoint.x - startPoint.x) * partialProgress
                        val partialY = startPoint.y + (endPoint.y - startPoint.y) * partialProgress
                        animatedPath.lineTo(partialX, partialY)
                    }
                }

                // Draw the animated path with a subtle glow effect
                drawPath(
                    path = animatedPath,
                    color = primaryColor.copy(alpha = 0.3f),
                    style = Stroke(width = 6f)
                )
                drawPath(
                    path = animatedPath,
                    color = primaryColor,
                    style = Stroke(width = 2f)
                )
            }

            // Draw animated data points
            if (pointsProgress > 0f) {
                points.forEachIndexed { index, point ->
                    // Calculate individual point animation delay
                    val pointDelay = index.toFloat() / points.size.toFloat()
                    val pointProgress = ((pointsProgress - pointDelay) / (1f - pointDelay)).coerceIn(0f, 1f)

                    if (pointProgress > 0f) {
                        val pointColor = getScoreColor(data[index].score.toInt())
                        val animatedRadius = 4f * pointProgress
                        val animatedAlpha = pointProgress

                        // Draw point shadow/glow
                        drawCircle(
                            color = pointColor.copy(alpha = 0.3f * animatedAlpha),
                            radius = animatedRadius * 1.8f,
                            center = point
                        )

                        // Draw main point
                        drawCircle(
                            color = pointColor.copy(alpha = animatedAlpha),
                            radius = animatedRadius,
                            center = point
                        )

                        // Draw inner highlight
                        drawCircle(
                            color = Color.White.copy(alpha = 0.6f * animatedAlpha),
                            radius = animatedRadius * 0.4f,
                            center = point
                        )
                    }
                }
            }
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