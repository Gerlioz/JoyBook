package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val OutfitFontFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.SemiBold),
    Font(R.font.outfit, FontWeight.Bold)
)

val LoraFontFamily = FontFamily(
    Font(R.font.lora, FontWeight.Normal),
    Font(R.font.lora, FontWeight.Medium),
    Font(R.font.lora, FontWeight.SemiBold),
    Font(R.font.lora, FontWeight.Bold)
)

fun buildJoyBookTypography(scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.85f, 1.35f)
    return Typography(
        displaySmall = TextStyle(
            fontFamily = LoraFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (32 * s).sp,
            lineHeight = (40 * s).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = LoraFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (24 * s).sp,
            lineHeight = (32 * s).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = LoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (20 * s).sp,
            lineHeight = (28 * s).sp
        ),
        titleLarge = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (20 * s).sp,
            lineHeight = (26 * s).sp
        ),
        titleMedium = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16 * s).sp,
            lineHeight = (22 * s).sp
        ),
        titleSmall = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (14 * s).sp,
            lineHeight = (20 * s).sp
        ),
        bodyLarge = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (16 * s).sp,
            lineHeight = (24 * s).sp
        ),
        bodyMedium = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (14 * s).sp,
            lineHeight = (20 * s).sp
        ),
        bodySmall = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (12 * s).sp,
            lineHeight = (16 * s).sp
        ),
        labelLarge = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (14 * s).sp,
            lineHeight = (20 * s).sp
        ),
        labelMedium = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (12 * s).sp,
            lineHeight = (16 * s).sp
        ),
        labelSmall = TextStyle(
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (11 * s).sp,
            lineHeight = (15 * s).sp
        )
    )
}
