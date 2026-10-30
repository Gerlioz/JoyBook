package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.preferences.CoverFrameStyle
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp

@Composable
fun AudiobookCoverArt(
    coverArtStyle: Int,
    audioFormat: String,
    coverImagePath: String? = null,
    frameStyle: CoverFrameStyle = CoverFrameStyle.ROUNDED_SQUARE,
    isPlaying: Boolean = false,
    showFormatBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinylRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "discAngle"
    )

    val shape = when (frameStyle) {
        CoverFrameStyle.ROUNDED_SQUARE -> RoundedCornerShape(22.dp)
        CoverFrameStyle.VINYL_DISC, CoverFrameStyle.CIRCLE -> CircleShape
        CoverFrameStyle.BOOK_SPINE -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp)
        CoverFrameStyle.CD_CASE -> RoundedCornerShape(6.dp)
    }

    val appliedRotation = if (frameStyle == CoverFrameStyle.VINYL_DISC && isPlaying) rotation else 0f

    val coverBitmap = remember(coverImagePath) {
        if (!coverImagePath.isNullOrBlank()) {
            try {
                val file = File(coverImagePath)
                if (file.exists() && file.length() > 0L) {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(coverImagePath, boundsOpts)
                    val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 640) {
                        sampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize.coerceAtLeast(1)
                    }
                    BitmapFactory.decodeFile(coverImagePath, decodeOpts)?.asImageBitmap()
                } else null
            } catch (t: Throwable) {
                null
            }
        } else null
    }

    val baseModifier = if (frameStyle == CoverFrameStyle.VINYL_DISC || frameStyle == CoverFrameStyle.CIRCLE) {
        modifier.aspectRatio(1f)
    } else {
        modifier
    }

    val isCircular = frameStyle == CoverFrameStyle.VINYL_DISC || frameStyle == CoverFrameStyle.CIRCLE
    val borderColor = if (frameStyle == CoverFrameStyle.CD_CASE) {
        Color.White.copy(alpha = 0.42f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }

    BoxWithConstraints(
        modifier = baseModifier
            .shadow(10.dp, shape)
            .clip(shape)
            .border(if (frameStyle == CoverFrameStyle.CD_CASE) 1.25.dp else 1.dp, borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        val cdSpineWidthDp: Dp = if (frameStyle == CoverFrameStyle.CD_CASE) {
            (maxWidth * 0.115f).coerceIn(4.5.dp, 16.dp)
        } else {
            0.dp
        }
        val cdRimInsetDp: Dp = if (frameStyle == CoverFrameStyle.CD_CASE) {
            (maxHeight * 0.022f).coerceIn(1.dp, 3.dp)
        } else {
            0.dp
        }

        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = cdSpineWidthDp,
                        top = cdRimInsetDp,
                        end = cdRimInsetDp,
                        bottom = cdRimInsetDp
                    )
                    .rotate(appliedRotation)
            )

            if (frameStyle == CoverFrameStyle.VINYL_DISC) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(appliedRotation)
                ) {
                    drawVinylOverlay()
                }
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = cdSpineWidthDp,
                        top = cdRimInsetDp,
                        end = cdRimInsetDp,
                        bottom = cdRimInsetDp
                    )
                    .rotate(appliedRotation)
            ) {
                when (coverArtStyle % 5) {
                    0 -> drawCosmicNebulaCover()
                    1 -> drawNoirStreetlampCover()
                    2 -> drawEmeraldForestCover()
                    3 -> drawChronometerCover()
                    else -> drawPolarAuroraCover()
                }

                if (frameStyle == CoverFrameStyle.VINYL_DISC) {
                    drawVinylOverlay()
                }
            }
        }

        if (frameStyle == CoverFrameStyle.CD_CASE) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCdCaseOverlay(
                    spineWidth = cdSpineWidthDp.toPx(),
                    rimInset = cdRimInsetDp.toPx()
                )
            }
        }

        if (frameStyle == CoverFrameStyle.BOOK_SPINE) {
            // Hardcover spine groove on left edge
            val bookSpineWidth = (maxWidth * 0.08f).coerceIn(5.dp, 14.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(bookSpineWidth)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.18f),
                                Color.Black.copy(alpha = 0.35f)
                            )
                        )
                    )
            )
        }

        if (showFormatBadge && audioFormat.isNotBlank()) {
            FormatBadgePill(
                format = audioFormat,
                modifier = Modifier
                    .align(if (isCircular) Alignment.TopCenter else Alignment.TopEnd)
                    .padding(if (isCircular) 8.dp else 8.dp)
            )
        }
    }
}

@Composable
fun FormatBadgePill(
    format: String,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (format.uppercase()) {
        "M4B" -> Color(0xFFFFB74D)
        "MP3" -> Color(0xFF64B5F6)
        "M4A" -> Color(0xFF81C784)
        "WAV" -> Color(0xFFBA68C8)
        "AMR" -> Color(0xFFFF8A65)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier,
        color = Color(0xCC10121A),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.7f))
    ) {
        Text(
            text = format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun DrawScope.drawCosmicNebulaCover() {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0E0B26), Color(0xFF2B1644), Color(0xFF692B47)),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    )
    // Glowing amber nebula core
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFB74D).copy(alpha = 0.65f), Color.Transparent),
            center = Offset(size.width * 0.62f, size.height * 0.38f),
            radius = size.minDimension * 0.48f
        ),
        center = Offset(size.width * 0.62f, size.height * 0.38f),
        radius = size.minDimension * 0.48f
    )
    // Planet & orbital rings
    val planetCenter = Offset(size.width * 0.62f, size.height * 0.38f)
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFCC80), Color(0xFFD84315)),
            start = Offset(planetCenter.x - 30f, planetCenter.y - 30f),
            end = Offset(planetCenter.x + 40f, planetCenter.y + 40f)
        ),
        radius = size.minDimension * 0.17f,
        center = planetCenter
    )
    drawCircle(
        color = Color(0xFFFFE0B2).copy(alpha = 0.45f),
        radius = size.minDimension * 0.27f,
        center = planetCenter,
        style = Stroke(width = size.minDimension * 0.018f)
    )
    // Crystalline mountain ridge silhouette
    val cliff = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, size.height * 0.70f)
        lineTo(size.width * 0.28f, size.height * 0.56f)
        lineTo(size.width * 0.48f, size.height * 0.74f)
        lineTo(size.width * 0.76f, size.height * 0.64f)
        lineTo(size.width, size.height * 0.80f)
        lineTo(size.width, size.height)
        close()
    }
    drawPath(cliff, color = Color(0xFF090718))
}

private fun DrawScope.drawNoirStreetlampCover() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF090D16), Color(0xFF162133), Color(0xFF1F2937))
        )
    )
    val lampCenter = Offset(size.width * 0.36f, size.height * 0.32f)
    // Warm golden halo in fog
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFD54F).copy(alpha = 0.75f),
                Color(0xFFFF8F00).copy(alpha = 0.25f),
                Color.Transparent
            ),
            center = lampCenter,
            radius = size.minDimension * 0.52f
        ),
        center = lampCenter,
        radius = size.minDimension * 0.52f
    )
    // Lamp post & lantern
    drawRect(
        color = Color(0xFF0B0E14),
        topLeft = Offset(lampCenter.x - size.width * 0.018f, lampCenter.y),
        size = Size(size.width * 0.036f, size.height * 0.68f)
    )
    drawCircle(
        color = Color(0xFFFFF8E1),
        radius = size.minDimension * 0.06f,
        center = lampCenter
    )
    // Wet cobblestone reflections
    for (i in 0..4) {
        val y = size.height * (0.76f + i * 0.045f)
        drawRoundRect(
            color = Color(0xFFFFCA28).copy(alpha = 0.32f - i * 0.05f),
            topLeft = Offset(lampCenter.x - size.width * (0.12f - i * 0.015f), y),
            size = Size(size.width * (0.24f - i * 0.03f), size.height * 0.016f),
            cornerRadius = CornerRadius(8f, 8f)
        )
    }
}

private fun DrawScope.drawEmeraldForestCover() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF07211D), Color(0xFF0E3A2F), Color(0xFF1B4D3E))
        )
    )
    // Magical golden canopy glow
    val canopyCenter = Offset(size.width * 0.5f, size.height * 0.38f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF81C784).copy(alpha = 0.55f), Color(0xFFFFD54F).copy(alpha = 0.2f), Color.Transparent),
            center = canopyCenter,
            radius = size.minDimension * 0.46f
        ),
        center = canopyCenter,
        radius = size.minDimension * 0.46f
    )
    // Tree trunk silhouette
    val trunk = Path().apply {
        moveTo(size.width * 0.38f, size.height)
        quadraticTo(size.width * 0.46f, size.height * 0.65f, size.width * 0.45f, size.height * 0.42f)
        lineTo(size.width * 0.55f, size.height * 0.42f)
        quadraticTo(size.width * 0.54f, size.height * 0.65f, size.width * 0.62f, size.height)
        close()
    }
    drawPath(trunk, color = Color(0xFF051411))
    // Fireflies
    val fireflyOffsets = listOf(
        Offset(0.25f, 0.30f), Offset(0.72f, 0.28f), Offset(0.68f, 0.55f),
        Offset(0.30f, 0.60f), Offset(0.50f, 0.24f)
    )
    fireflyOffsets.forEach { pos ->
        drawCircle(
            color = Color(0xFFFFE082),
            radius = size.minDimension * 0.018f,
            center = Offset(size.width * pos.x, size.height * pos.y)
        )
    }
}

private fun DrawScope.drawChronometerCover() {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF1F140E), Color(0xFF3E2723), Color(0xFF140F1D))
        )
    )
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    // Concentric clockwork rings
    drawCircle(
        color = Color(0xFFFFB74D).copy(alpha = 0.45f),
        radius = size.minDimension * 0.36f,
        center = center,
        style = Stroke(width = size.minDimension * 0.02f)
    )
    drawCircle(
        color = Color(0xFFFFCC80).copy(alpha = 0.25f),
        radius = size.minDimension * 0.26f,
        center = center,
        style = Stroke(width = size.minDimension * 0.012f)
    )
    for (tick in 0 until 12) {
        val angle = Math.toRadians((tick * 30).toDouble())
        val innerR = size.minDimension * 0.30f
        val outerR = size.minDimension * 0.35f
        drawLine(
            color = Color(0xFFFFE0B2).copy(alpha = 0.7f),
            start = Offset(center.x + (innerR * cos(angle)).toFloat(), center.y + (innerR * sin(angle)).toFloat()),
            end = Offset(center.x + (outerR * cos(angle)).toFloat(), center.y + (outerR * sin(angle)).toFloat()),
            strokeWidth = 3f
        )
    }
    drawCircle(color = Color(0xFFFFB74D), radius = size.minDimension * 0.04f, center = center)
}

private fun DrawScope.drawPolarAuroraCover() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF06152D), Color(0xFF0D3B4C), Color(0xFF102538))
        )
    )
    // Aurora ribbon
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF4DD0E1).copy(alpha = 0.55f), Color(0xFF69F0AE).copy(alpha = 0.25f), Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.3f),
            radius = size.minDimension * 0.5f
        ),
        center = Offset(size.width * 0.5f, size.height * 0.3f),
        radius = size.minDimension * 0.5f
    )
    // Ice peaks
    val peaks = Path().apply {
        moveTo(0f, size.height)
        lineTo(size.width * 0.22f, size.height * 0.58f)
        lineTo(size.width * 0.45f, size.height * 0.78f)
        lineTo(size.width * 0.72f, size.height * 0.52f)
        lineTo(size.width, size.height * 0.75f)
        lineTo(size.width, size.height)
        close()
    }
    drawPath(peaks, color = Color(0xFF0A1929))
}

private fun DrawScope.drawVinylOverlay() {
    val center = Offset(size.width / 2f, size.height / 2f)
    for (i in 1..4) {
        drawCircle(
            color = Color.White.copy(alpha = 0.09f),
            radius = size.minDimension * (0.22f + i * 0.06f),
            center = center,
            style = Stroke(width = 1.5f)
        )
    }
    drawCircle(
        color = Color(0xFF121212),
        radius = size.minDimension * 0.14f,
        center = center
    )
    drawCircle(
        color = Color(0xFFFFB74D),
        radius = size.minDimension * 0.11f,
        center = center,
        style = Stroke(width = 2f)
    )
    drawCircle(
        color = Color.Black,
        radius = size.minDimension * 0.035f,
        center = center
    )
}

private fun DrawScope.drawCdCaseOverlay(
    spineWidth: Float,
    rimInset: Float
) {
    // 1. Left transparent polycarbonate spine with inner dark tray backing and vertical ribs
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF1A1E29).copy(alpha = 0.88f),
                Color(0xFF2D3548).copy(alpha = 0.72f),
                Color.White.copy(alpha = 0.28f),
                Color(0xFF121620).copy(alpha = 0.85f)
            ),
            startX = 0f,
            endX = spineWidth
        ),
        topLeft = Offset.Zero,
        size = Size(spineWidth, size.height)
    )

    // Vertical ribs inside the left clear spine
    val ribCount = if (spineWidth < 8.dp.toPx()) 2 else 4
    val ribStep = spineWidth / (ribCount + 1)
    for (i in 1..ribCount) {
        val x = ribStep * i
        drawLine(
            color = Color.White.copy(alpha = if (i % 2 == 0) 0.28f else 0.14f),
            start = Offset(x, rimInset),
            end = Offset(x, size.height - rimInset),
            strokeWidth = 0.8.dp.toPx()
        )
    }

    // Top and bottom hinge blocks on the spine
    val hingeHeight = (size.height * 0.11f).coerceIn(4.dp.toPx(), 24.dp.toPx())
    val hingeHorizontalPad = (spineWidth * 0.12f).coerceAtLeast(0.8.dp.toPx())
    drawRect(
        color = Color.White.copy(alpha = 0.24f),
        topLeft = Offset(hingeHorizontalPad, rimInset * 1.4f),
        size = Size((spineWidth - hingeHorizontalPad * 2f).coerceAtLeast(1f), hingeHeight)
    )
    drawRect(
        color = Color.White.copy(alpha = 0.24f),
        topLeft = Offset(hingeHorizontalPad, size.height - rimInset * 1.4f - hingeHeight),
        size = Size((spineWidth - hingeHorizontalPad * 2f).coerceAtLeast(1f), hingeHeight)
    )

    // Sharp specular separator line between spine and front lid
    val sepStroke = (size.width * 0.006f).coerceIn(0.8.dp.toPx(), 1.5.dp.toPx())
    drawLine(
        color = Color.White.copy(alpha = 0.55f),
        start = Offset(spineWidth, 0f),
        end = Offset(spineWidth, size.height),
        strokeWidth = sepStroke
    )
    drawLine(
        color = Color.Black.copy(alpha = 0.42f),
        start = Offset(spineWidth + sepStroke, 0f),
        end = Offset(spineWidth + sepStroke, size.height),
        strokeWidth = (sepStroke * 0.7f).coerceAtLeast(0.6.dp.toPx())
    )

    // 2. Subtle inner CD disc ring & hub impression visible through the clear case
    val lidCenter = Offset(
        x = spineWidth + (size.width - spineWidth) * 0.5f,
        y = size.height * 0.5f
    )
    val lidMinDim = minOf(size.width - spineWidth, size.height)
    drawCircle(
        color = Color.White.copy(alpha = 0.11f),
        radius = lidMinDim * 0.44f,
        center = lidCenter,
        style = Stroke(width = 0.9.dp.toPx())
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.15f),
        radius = lidMinDim * 0.13f,
        center = lidCenter,
        style = Stroke(width = 1.dp.toPx())
    )

    // 3. Top and bottom plastic booklet stopper tabs (scaled to cover size)
    val tabWidth = (lidMinDim * 0.14f).coerceIn(5.dp.toPx(), 30.dp.toPx())
    val tabHeight = (size.height * 0.015f).coerceIn(1.2.dp.toPx(), 3.2.dp.toPx())
    val tabX1 = spineWidth + (size.width - spineWidth) * 0.24f
    val tabX2 = spineWidth + (size.width - spineWidth) * 0.64f
    listOf(tabX1, tabX2).forEach { tabX ->
        // Top tab
        drawRoundRect(
            color = Color.White.copy(alpha = 0.34f),
            topLeft = Offset(tabX, rimInset),
            size = Size(tabWidth, tabHeight),
            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        )
        // Bottom tab
        drawRoundRect(
            color = Color.White.copy(alpha = 0.34f),
            topLeft = Offset(tabX, size.height - rimInset - tabHeight),
            size = Size(tabWidth, tabHeight),
            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        )
    }

    // 4. Diagonal glossy plastic reflection across the jewel case lid
    val glossPath = Path().apply {
        moveTo(spineWidth, 0f)
        lineTo(spineWidth + (size.width - spineWidth) * 0.55f, 0f)
        lineTo(spineWidth, size.height * 0.62f)
        close()
    }
    drawPath(
        path = glossPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.06f),
                Color.Transparent
            ),
            start = Offset(spineWidth, 0f),
            end = Offset(size.width * 0.65f, size.height * 0.65f)
        )
    )

    // Secondary lower-right subtle plastic reflection
    val bottomGloss = Path().apply {
        moveTo(size.width, size.height * 0.55f)
        lineTo(size.width, size.height)
        lineTo(spineWidth + (size.width - spineWidth) * 0.48f, size.height)
        close()
    }
    drawPath(
        path = bottomGloss,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.11f)
            ),
            start = Offset(size.width * 0.5f, size.height * 0.5f),
            end = Offset(size.width, size.height)
        )
    )

    // 5. Inner clear acrylic bevel frame around the entire jewel case
    drawRoundRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(rimInset, rimInset),
        size = Size(size.width - rimInset * 2f, size.height - rimInset * 2f),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        style = Stroke(width = 0.85.dp.toPx())
    )
}

data class CoverGlowPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color
)

/**
 * Renders an atmospheric blurred color aura and soft-blurred cover projection in the background layer
 * of the Player screen behind all UI elements, without affecting layout measurements or shifting buttons.
 */
@Composable
fun CoverArtBlurHalo(
    coverArtStyle: Int,
    coverImagePath: String?,
    modifier: Modifier = Modifier
) {
    val palette = remember(coverImagePath, coverArtStyle) {
        extractCoverGlowPalette(coverImagePath, coverArtStyle)
    }

    val blurredImageBitmap = remember(coverImagePath) {
        createSoftBlurredCoverBitmap(coverImagePath)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        // Multi-layered ambient radial color glow in the screen background behind all elements
        Canvas(modifier = Modifier.fillMaxSize()) {
            val coverCenterY = 258.dp.toPx().coerceAtMost(size.height * 0.45f)
            val radius = (size.width * 0.68f).coerceAtLeast(220.dp.toPx())

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.primary.copy(alpha = 0.50f),
                        palette.secondary.copy(alpha = 0.26f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.42f, coverCenterY - 18.dp.toPx()),
                    radius = radius
                ),
                center = Offset(size.width * 0.42f, coverCenterY - 18.dp.toPx()),
                radius = radius
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.secondary.copy(alpha = 0.46f),
                        palette.tertiary.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.58f, coverCenterY + 22.dp.toPx()),
                    radius = radius * 0.95f
                ),
                center = Offset(size.width * 0.58f, coverCenterY + 22.dp.toPx()),
                radius = radius * 0.95f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.tertiary.copy(alpha = 0.36f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, coverCenterY),
                    radius = radius * 1.12f
                ),
                center = Offset(size.width * 0.5f, coverCenterY),
                radius = radius * 1.12f
            )
        }

        // Softly blurred & radially feathered cover image projection directly behind the 280.dp cover
        if (blurredImageBitmap != null) {
            Image(
                bitmap = blurredImageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.78f,
                modifier = Modifier
                    .padding(top = 68.dp)
                    .size(380.dp)
            )
        }
    }
}

private fun extractCoverGlowPalette(coverImagePath: String?, coverArtStyle: Int): CoverGlowPalette {
    if (!coverImagePath.isNullOrBlank()) {
        runCatching {
            val file = File(coverImagePath)
            if (file.exists() && file.length() > 0L) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(coverImagePath, bounds)
                var sample = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / sample > 64) {
                    sample *= 2
                }
                val small = BitmapFactory.decodeFile(
                    coverImagePath,
                    BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                )
                if (small != null && small.width > 2 && small.height > 2) {
                    val c1 = sampleRegionColor(small, 0, 0, small.width / 2, small.height / 2)
                    val c2 = sampleRegionColor(small, small.width / 4, small.height / 4, (small.width * 3) / 4, (small.height * 3) / 4)
                    val c3 = sampleRegionColor(small, small.width / 2, small.height / 2, small.width, small.height)
                    return CoverGlowPalette(
                        primary = boostGlowSaturation(c1),
                        secondary = boostGlowSaturation(c2),
                        tertiary = boostGlowSaturation(c3)
                    )
                }
            }
        }
    }

    return when (coverArtStyle % 5) {
        0 -> CoverGlowPalette(Color(0xFFFFB74D), Color(0xFF9C27B0), Color(0xFF3F51B5))
        1 -> CoverGlowPalette(Color(0xFFFFD54F), Color(0xFFFF8F00), Color(0xFF1E88E5))
        2 -> CoverGlowPalette(Color(0xFF66BB6A), Color(0xFF26A69A), Color(0xFFFFCA28))
        3 -> CoverGlowPalette(Color(0xFFFFB74D), Color(0xFFFF7043), Color(0xFF8D6E63))
        else -> CoverGlowPalette(Color(0xFF4DD0E1), Color(0xFF69F0AE), Color(0xFF42A5F5))
    }
}

private fun sampleRegionColor(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Color {
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var weightSum = 0L
    val stepX = ((x1 - x0) / 6).coerceAtLeast(1)
    val stepY = ((y1 - y0) / 6).coerceAtLeast(1)
    var y = y0.coerceIn(0, bmp.height - 1)
    while (y < y1.coerceAtMost(bmp.height)) {
        var x = x0.coerceIn(0, bmp.width - 1)
        while (x < x1.coerceAtMost(bmp.width)) {
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val saturationWeight = ((maxC - minC) + 28).toLong()
            rSum += r * saturationWeight
            gSum += g * saturationWeight
            bSum += b * saturationWeight
            weightSum += saturationWeight
            x += stepX
        }
        y += stepY
    }
    if (weightSum <= 0L) return Color(0xFFFFB74D)
    return Color(
        red = ((rSum / weightSum).toInt().coerceIn(0, 255)) / 255f,
        green = ((gSum / weightSum).toInt().coerceIn(0, 255)) / 255f,
        blue = ((bSum / weightSum).toInt().coerceIn(0, 255)) / 255f,
        alpha = 1f
    )
}

private fun boostGlowSaturation(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
        hsv
    )
    hsv[1] = (hsv[1] * 1.35f).coerceIn(0.32f, 0.95f)
    hsv[2] = (hsv[2] * 1.25f).coerceIn(0.48f, 0.96f)
    val argb = android.graphics.Color.HSVToColor(hsv)
    return Color(argb)
}

private fun createSoftBlurredCoverBitmap(coverImagePath: String?): ImageBitmap? {
    if (coverImagePath.isNullOrBlank()) return null
    return runCatching {
        val file = File(coverImagePath)
        if (!file.exists() || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(coverImagePath, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > 96) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            coverImagePath,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        ) ?: return null

        val gridSize = 60
        // Center-pad inside a 60x60 grid so the blur expands smoothly outside the cover edges
        val padded = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888)
        val innerSize = 42
        val scaledInner = Bitmap.createScaledBitmap(decoded, innerSize, innerSize, true)
        val padOffset = (gridSize - innerSize) / 2
        val canvas = android.graphics.Canvas(padded)
        canvas.drawBitmap(scaledInner, padOffset.toFloat(), padOffset.toFloat(), null)

        val pixels = IntArray(gridSize * gridSize)
        padded.getPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize)
        val temp = IntArray(gridSize * gridSize)
        val radius = 7

        // 3 passes of separable horizontal + vertical box blur -> smooth Gaussian-like halo
        repeat(3) {
            // Horizontal pass
            for (y in 0 until gridSize) {
                val rowOffset = y * gridSize
                for (x in 0 until gridSize) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, gridSize - 1)
                        val px = pixels[rowOffset + nx]
                        a += (px ushr 24) and 0xFF
                        r += (px ushr 16) and 0xFF
                        g += (px ushr 8) and 0xFF
                        b += px and 0xFF
                        count++
                    }
                    temp[rowOffset + x] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
            // Vertical pass
            for (x in 0 until gridSize) {
                for (y in 0 until gridSize) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -radius..radius) {
                        val ny = (y + dy).coerceIn(0, gridSize - 1)
                        val px = temp[ny * gridSize + x]
                        a += (px ushr 24) and 0xFF
                        r += (px ushr 16) and 0xFF
                        g += (px ushr 8) and 0xFF
                        b += px and 0xFF
                        count++
                    }
                    pixels[y * gridSize + x] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
        }

        // Smooth radial alpha feather at the outer perimeter so there are zero hard edges
        val center = (gridSize - 1) / 2f
        val maxR = center
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                val dx = x - center
                val dy = y - center
                val dist = kotlin.math.sqrt(dx * dx + dy * dy) / maxR
                val radialMask = (1f - (dist * dist)).coerceIn(0f, 1f)
                val idx = y * gridSize + x
                val px = pixels[idx]
                val origA = (px ushr 24) and 0xFF
                val newA = (origA * radialMask).toInt().coerceIn(0, 255)
                pixels[idx] = (newA shl 24) or (px and 0x00FFFFFF)
            }
        }

        padded.setPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize)
        padded.asImageBitmap()
    }.getOrNull()
}


