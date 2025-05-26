// Theme.kt
package io.pianosync.midi.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    // Primary colors - Elegant Purples (make purple the primary for more variety)
    primary = RoyalPurple40,
    onPrimary = Color.White,
    primaryContainer = RoyalPurple80,
    onPrimaryContainer = RoyalPurple20,

    // Secondary colors - Rich Deep Blues
    secondary = DeepBlue40,
    onSecondary = Color.White,
    secondaryContainer = DeepBlue80,
    onSecondaryContainer = DeepBlue20,

    // Tertiary colors - Warm Golds
    tertiary = WarmGold60,
    onTertiary = Color.Black,
    tertiaryContainer = WarmGold90,
    onTertiaryContainer = WarmGold20,

    // Error colors
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Background and surface - Mix of colors for variety
    background = DarkBackground,
    onBackground = Color.White,
    surface = Color(0xFF1C1B2E), // Purple-tinted dark surface
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2438), // Purple-blue variant
    onSurfaceVariant = Color(0xFFD0C7E1), // Light purple tint

    // Outline and other
    outline = Color(0xFF4A4458), // Purple-tinted outline
    outlineVariant = Color(0xFF3D3A4F),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE8E1F5), // Light purple tint
    inverseOnSurface = Color(0xFF2F2B3A),
    inversePrimary = RoyalPurple60,
    surfaceTint = RoyalPurple40
)

private val LightColorScheme = lightColorScheme(
    // Primary colors - Elegant Purples
    primary = RoyalPurple60,
    onPrimary = Color.White,
    primaryContainer = RoyalPurple20,
    onPrimaryContainer = RoyalPurple90,

    // Secondary colors - Rich Deep Blues
    secondary = DeepBlue60,
    onSecondary = Color.White,
    secondaryContainer = DeepBlue20,
    onSecondaryContainer = DeepBlue90,

    // Tertiary colors - Warm Golds
    tertiary = WarmGold70,
    onTertiary = Color.White,
    tertiaryContainer = WarmGold20,
    onTertiaryContainer = WarmGold90,

    // Error colors
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Background and surface - Varied colors
    background = LightBackground,
    onBackground = Color.Black,
    surface = Color(0xFFF8F6FF), // Light purple tint
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2EFFA), // Purple-tinted variant
    onSurfaceVariant = Color(0xFF4A4458),

    // Outline and other
    outline = Color(0xFFB8B2C7), // Purple-tinted outline
    outlineVariant = Color(0xFFD5CFDF),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2F2B3A),
    inverseOnSurface = Color(0xFFF6F2FF),
    inversePrimary = RoyalPurple40,
    surfaceTint = RoyalPurple60
)

@Composable
fun PianoSyncTheme(
    darkTheme: Boolean = true, // Always default to dark theme for elegant look
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Helper composables for consistent theming
@Composable
fun cardBackgroundColor() = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)

@Composable
fun cardContentColor() = MaterialTheme.colorScheme.onSurface

@Composable
fun pianoKeyAccentColor() = MaterialTheme.colorScheme.tertiary

@Composable
fun leftHandNoteColor() = AccentSky

@Composable
fun rightHandNoteColor() = AccentRose

@Composable
fun successAccentColor() = AccentTeal

@Composable
fun highlightAccentColor() = WarmGold60

@Composable
fun subtleAccentColor() = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

// Additional color functions for more variety in UI
@Composable
fun navigationAccentColor() = DeepBlue40 // For navigation elements

@Composable
fun settingsAccentColor() = RoyalPurple40 // For settings icons and accents

@Composable
fun progressAccentColor() = WarmGold60 // For progress indicators

@Composable
fun chipBackgroundColor() = MaterialTheme.colorScheme.secondaryContainer

@Composable
fun chipContentColor() = MaterialTheme.colorScheme.onSecondaryContainer

@Composable
fun buttonSecondaryColor() = MaterialTheme.colorScheme.secondary

@Composable
fun buttonTertiaryColor() = MaterialTheme.colorScheme.tertiary

@Composable
fun surfaceElevatedColor() = MaterialTheme.colorScheme.surfaceVariant

@Composable
fun outlineAccentColor() = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)