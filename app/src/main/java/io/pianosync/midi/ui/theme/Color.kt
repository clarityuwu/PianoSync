// Color.kt
package io.pianosync.midi.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Rich Deep Blues
val DeepBlue90 = Color(0xFF1A237E) // Very deep blue
val DeepBlue80 = Color(0xFF283593) // Rich navy
val DeepBlue70 = Color(0xFF303F9F) // Deep indigo
val DeepBlue60 = Color(0xFF3F51B5) // Classic indigo
val DeepBlue50 = Color(0xFF5C7CFA) // Medium bright blue
val DeepBlue40 = Color(0xFF5C6BC0) // Medium blue
val DeepBlue20 = Color(0xFF9FA8DA) // Light blue

// Elegant Purples
val RoyalPurple90 = Color(0xFF4A148C) // Deep royal purple
val RoyalPurple80 = Color(0xFF6A1B9A) // Rich purple
val RoyalPurple70 = Color(0xFF7B1FA2) // Elegant purple
val RoyalPurple60 = Color(0xFF8E24AA) // Medium purple
val RoyalPurple40 = Color(0xFFAB47BC) // Lighter purple
val RoyalPurple20 = Color(0xFFE1BEE7) // Soft purple

// Warm Golds
val WarmGold90 = Color(0xFFE65100) // Deep amber
val WarmGold80 = Color(0xFFFF8F00) // Rich gold
val WarmGold70 = Color(0xFFFF9800) // Classic gold
val WarmGold60 = Color(0xFFFFA726) // Medium gold
val WarmGold50 = Color(0xFFFFBF40) // Bright gold
val WarmGold40 = Color(0xFFFFB74D) // Light gold
val WarmGold20 = Color(0xFFFFE0B2) // Soft gold

// Accent Colors
val AccentCyan = Color(0xFF00BCD4) // Cyan for highlights
val AccentTeal = Color(0xFF009688) // Teal for success states
val AccentRose = Color(0xFFE91E63) // Rose for right hand notes
val AccentSky = Color(0xFF2196F3) // Sky blue for left hand notes

// Additional Music-themed Colors
val VibrantOrange = Color(0xFFFF6B35) // Energetic orange
val NaturalGreen = Color(0xFF66BB6A) // Fresh green
val DeepRose = Color(0xFFAD1457) // Rich rose
val SoftLavender = Color(0xFF9C88FF) // Gentle lavender
val WarmCoral = Color(0xFFFF8A80) // Coral accent
val CoolMint = Color(0xFF80CBC4) // Mint green

// Neutral Grays for Dark Theme
val DarkBackground = Color(0xFF0A0A0F) // Very dark with purple tint
val DarkSurface = Color(0xFF1A1A2E) // Dark surface with blue tint
val DarkSurfaceVariant = Color(0xFF242438) // Surface variant
val DarkOutline = Color(0xFF3A3A4A) // Subtle outlines

// Light Theme Grays
val LightBackground = Color(0xFFFAF9FF) // Off-white with purple tint
val LightSurface = Color(0xFFF5F4FA) // Light surface
val LightSurfaceVariant = Color(0xFFEFEEF5) // Surface variant
val LightOutline = Color(0xFFD0CFD8) // Light outlines

// Semantic Colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFF9800)
val ErrorRed = Color(0xFFF44336)
val InfoBlue = Color(0xFF2196F3)

/**
 * Music-themed gradient presets for consistent theming
 */
object MusicGradients {

    // Classical and Elegant
    val ClassicalPurple = Brush.linearGradient(
        colors = listOf(RoyalPurple70, RoyalPurple40),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val ElegantBlue = Brush.linearGradient(
        colors = listOf(DeepBlue80, DeepBlue50),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    // Warm and Inviting
    val JazzGold = Brush.linearGradient(
        colors = listOf(WarmGold80, WarmGold50),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val SunsetSymphony = Brush.linearGradient(
        colors = listOf(VibrantOrange, WarmGold60),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    // Expressive and Passionate
    val RomanticRose = Brush.linearGradient(
        colors = listOf(AccentRose, DeepRose),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val PassionateCoral = Brush.linearGradient(
        colors = listOf(WarmCoral, AccentRose),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    // Cool and Calm
    val OceanHarmony = Brush.linearGradient(
        colors = listOf(AccentTeal, AccentCyan),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val SpringMelody = Brush.linearGradient(
        colors = listOf(AccentTeal, NaturalGreen),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val CoolMintGradient: Brush = Brush.linearGradient(
        colors = listOf(CoolMint, AccentCyan),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    // Mysterious and Deep
    val MidnightSerenade = Brush.linearGradient(
        colors = listOf(RoyalPurple90, DeepBlue80),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    val DeepMystery = Brush.linearGradient(
        colors = listOf(DeepBlue90, RoyalPurple80),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    // Gentle and Dreamy
    val LavenderDream = Brush.linearGradient(
        colors = listOf(SoftLavender, RoyalPurple40),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )

    /**
     * Get all available music gradients as a list
     */
    fun getAllGradients(): List<Brush> = listOf(
        ClassicalPurple,
        JazzGold,
        ElegantBlue,
        RomanticRose,
        OceanHarmony,
        SunsetSymphony,
        MidnightSerenade,
        SpringMelody,
        PassionateCoral,
        CoolMintGradient,
        DeepMystery,
        LavenderDream
    )

    /**
     * Get gradient based on performance score
     */
    fun getGradientByScore(score: Int): Brush = when {
        score >= 90 -> JazzGold // Gold for excellent performance
        score >= 80 -> SpringMelody // Green for good performance
        score >= 70 -> ElegantBlue // Blue for decent performance
        score >= 60 -> LavenderDream // Lavender for fair performance
        else -> RomanticRose // Rose for needs improvement
    }

    /**
     * Get gradient based on mood/genre
     */
    fun getGradientByMood(mood: String): Brush = when (mood.lowercase()) {
        "classical", "elegant" -> ClassicalPurple
        "jazz", "warm" -> JazzGold
        "blues", "calm" -> ElegantBlue
        "romantic", "passionate" -> RomanticRose
        "ocean", "peaceful" -> OceanHarmony
        "energetic", "vibrant" -> SunsetSymphony
        "mysterious", "dark" -> MidnightSerenade
        "fresh", "nature" -> SpringMelody
        "dreamy", "soft" -> LavenderDream
        else -> ClassicalPurple
    }
}

/**
 * Helper function to create radial gradients for special effects
 */
object RadialGradients {

    val GoldenSpotlight = Brush.radialGradient(
        colors = listOf(WarmGold50, WarmGold80.copy(alpha = 0.6f), Color.Transparent),
        radius = 300f
    )

    val PurpleGlow = Brush.radialGradient(
        colors = listOf(RoyalPurple40, RoyalPurple80.copy(alpha = 0.6f), Color.Transparent),
        radius = 300f
    )

    val BlueRipple = Brush.radialGradient(
        colors = listOf(DeepBlue50, DeepBlue80.copy(alpha = 0.6f), Color.Transparent),
        radius = 300f
    )
}