package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.preferences.AccentPalette

// Light (Warm Paper) Surfaces
val PaperBackground = Color(0xFFFAF6F0)
val PaperSurface = Color(0xFFF3ECE1)
val PaperSurfaceVariant = Color(0xFFE7DEC8)
val PaperOnBackground = Color(0xFF1F1B16)
val PaperOnSurfaceVariant = Color(0xFF5C5346)

// Dark (Deep Midnight) Surfaces
val MidnightBackground = Color(0xFF10121B)
val MidnightSurface = Color(0xFF181B28)
val MidnightSurfaceVariant = Color(0xFF232738)
val MidnightOnBackground = Color(0xFFF2EFE9)
val MidnightOnSurfaceVariant = Color(0xFFA9AFC4)

// True AMOLED (#000000) Surfaces
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF070709)
val AmoledSurfaceVariant = Color(0xFF121318)
val AmoledOnBackground = Color(0xFFF7F5F0)
val AmoledOnSurfaceVariant = Color(0xFF9E9EAB)

data class PaletteColors(
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val secondaryDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val secondaryLight: Color
)

fun getPaletteColors(palette: AccentPalette): PaletteColors = when (palette) {
    AccentPalette.AMBER -> PaletteColors(
        primaryDark = Color(0xFFFFB74D),
        onPrimaryDark = Color(0xFF2E1A00),
        primaryContainerDark = Color(0xFF4F3000),
        onPrimaryContainerDark = Color(0xFFFFDDB3),
        secondaryDark = Color(0xFFFF8A65),
        primaryLight = Color(0xFFB26200),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFDDB3),
        onPrimaryContainerLight = Color(0xFF391E00),
        secondaryLight = Color(0xFF8C4A27)
    )
    AccentPalette.EMERALD -> PaletteColors(
        primaryDark = Color(0xFF4DB6AC),
        onPrimaryDark = Color(0xFF002521),
        primaryContainerDark = Color(0xFF004D45),
        onPrimaryContainerDark = Color(0xFFB2DFDB),
        secondaryDark = Color(0xFF81C784),
        primaryLight = Color(0xFF00695C),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFB2DFDB),
        onPrimaryContainerLight = Color(0xFF00201C),
        secondaryLight = Color(0xFF2E7D32)
    )
    AccentPalette.INDIGO -> PaletteColors(
        primaryDark = Color(0xFF9575CD),
        onPrimaryDark = Color(0xFF1E0E3B),
        primaryContainerDark = Color(0xFF3B2463),
        onPrimaryContainerDark = Color(0xFFE5D6FF),
        secondaryDark = Color(0xFF7986CB),
        primaryLight = Color(0xFF512DA8),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFE5D6FF),
        onPrimaryContainerLight = Color(0xFF1B0347),
        secondaryLight = Color(0xFF3949AB)
    )
    AccentPalette.ROSE -> PaletteColors(
        primaryDark = Color(0xFFF06292),
        onPrimaryDark = Color(0xFF3B0018),
        primaryContainerDark = Color(0xFF680F32),
        onPrimaryContainerDark = Color(0xFFFFD9E2),
        secondaryDark = Color(0xFFFF8A80),
        primaryLight = Color(0xFFAD1457),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFD9E2),
        onPrimaryContainerLight = Color(0xFF3E001D),
        secondaryLight = Color(0xFFC62828)
    )
    AccentPalette.CYAN -> PaletteColors(
        primaryDark = Color(0xFF4DD0E1),
        onPrimaryDark = Color(0xFF00262B),
        primaryContainerDark = Color(0xFF004953),
        onPrimaryContainerDark = Color(0xFFB2EBF2),
        secondaryDark = Color(0xFF64B5F6),
        primaryLight = Color(0xFF00838F),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFB2EBF2),
        onPrimaryContainerLight = Color(0xFF001F24),
        secondaryLight = Color(0xFF1565C0)
    )
}
