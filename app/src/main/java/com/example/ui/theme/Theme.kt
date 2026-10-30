package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.data.preferences.AccentPalette
import com.example.data.preferences.ThemeMode

val LocalIsAmoledTheme = staticCompositionLocalOf { false }

@Composable
fun JoyBookTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentPalette: AccentPalette = AccentPalette.AMBER,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val isAmoled = themeMode == ThemeMode.AMOLED

    val palette = getPaletteColors(accentPalette)

    val colorScheme = when {
        isAmoled -> darkColorScheme(
            primary = palette.primaryDark,
            onPrimary = palette.onPrimaryDark,
            primaryContainer = palette.primaryContainerDark.copy(alpha = 0.85f),
            onPrimaryContainer = palette.onPrimaryContainerDark,
            secondary = palette.secondaryDark,
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF161820),
            onSecondaryContainer = AmoledOnBackground,
            tertiary = palette.primaryDark,
            background = AmoledBackground,
            onBackground = AmoledOnBackground,
            surface = AmoledSurface,
            onSurface = AmoledOnBackground,
            surfaceVariant = AmoledSurfaceVariant,
            onSurfaceVariant = AmoledOnSurfaceVariant,
            surfaceContainer = Color(0xFF0A0B0F),
            surfaceContainerHigh = Color(0xFF13141B),
            surfaceContainerHighest = Color(0xFF1B1D26),
            outline = Color(0xFF2C2E3B),
            outlineVariant = Color(0xFF1D1E28)
        )
        isDark -> darkColorScheme(
            primary = palette.primaryDark,
            onPrimary = palette.onPrimaryDark,
            primaryContainer = palette.primaryContainerDark,
            onPrimaryContainer = palette.onPrimaryContainerDark,
            secondary = palette.secondaryDark,
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF282C3E),
            onSecondaryContainer = MidnightOnBackground,
            tertiary = palette.primaryDark,
            background = MidnightBackground,
            onBackground = MidnightOnBackground,
            surface = MidnightSurface,
            onSurface = MidnightOnBackground,
            surfaceVariant = MidnightSurfaceVariant,
            onSurfaceVariant = MidnightOnSurfaceVariant,
            surfaceContainer = Color(0xFF1B1F2E),
            surfaceContainerHigh = Color(0xFF23283B),
            surfaceContainerHighest = Color(0xFF2D334A),
            outline = Color(0xFF3E445B),
            outlineVariant = Color(0xFF2B3042)
        )
        else -> lightColorScheme(
            primary = palette.primaryLight,
            onPrimary = palette.onPrimaryLight,
            primaryContainer = palette.primaryContainerLight,
            onPrimaryContainer = palette.onPrimaryContainerLight,
            secondary = palette.secondaryLight,
            onSecondary = Color.White,
            secondaryContainer = PaperSurfaceVariant,
            onSecondaryContainer = PaperOnBackground,
            tertiary = palette.primaryLight,
            background = PaperBackground,
            onBackground = PaperOnBackground,
            surface = PaperSurface,
            onSurface = PaperOnBackground,
            surfaceVariant = PaperSurfaceVariant,
            onSurfaceVariant = PaperOnSurfaceVariant,
            surfaceContainer = Color(0xFFF1E9DC),
            surfaceContainerHigh = Color(0xFFE9DFCF),
            surfaceContainerHighest = Color(0xFFE0D4C1),
            outline = Color(0xFF8C8170),
            outlineVariant = Color(0xFFD5C9B4)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalIsAmoledTheme provides isAmoled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildJoyBookTypography(fontScale),
            content = content
        )
    }
}
