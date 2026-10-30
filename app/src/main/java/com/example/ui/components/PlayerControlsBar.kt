package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.AudioWaveformAnalyzer
import com.example.audio.RealtimeAudioLevel
import com.example.data.local.ChapterEntity
import com.example.data.preferences.ProgressBarStyle
import com.example.data.preferences.UserSettings
import com.example.data.repository.formatDurationShort
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CustomizableProgressBar(
    currentPositionMs: Long,
    durationMs: Long,
    chapters: List<ChapterEntity>,
    waveformAmplitudes: List<Float>,
    realtimeAudioLevel: RealtimeAudioLevel,
    style: ProgressBarStyle,
    isPlaying: Boolean,
    showChapterRemaining: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val playbackFraction = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val effectiveFraction = if (isScrubbing) scrubFraction else playbackFraction
    val effectivePositionMs = (effectiveFraction * safeDuration).toLong()

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val currentChapter = chapters.lastOrNull { effectivePositionMs >= it.startTimeMs } ?: chapters.firstOrNull()
    val chapterRemainingMs = if (currentChapter != null) {
        ((currentChapter.startTimeMs + currentChapter.durationMs) - effectivePositionMs).coerceAtLeast(0L)
    } else {
        (safeDuration - effectivePositionMs).coerceAtLeast(0L)
    }

    val basePeaks = if (waveformAmplitudes.isNotEmpty()) {
        waveformAmplitudes
    } else {
        List(AudioWaveformAnalyzer.DEFAULT_PEAK_COUNT) { 0.35f }
    }

    // Fast, responsive linear interpolation over 70ms matching speech cadence and PCM amplitude peaks
    val animatedPeaks = basePeaks.mapIndexed { idx, targetPeak ->
        val animated by animateFloatAsState(
            targetValue = targetPeak.coerceIn(0.08f, 1.0f),
            animationSpec = tween(durationMillis = 70, easing = LinearEasing),
            label = "smoothWavePeak_$idx"
        )
        animated
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when (style) {
            ProgressBarStyle.WAVEFORM -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("waveform_progress_bar")
                        .pointerInput(safeDuration) {
                            detectTapGestures { offset ->
                                val targetFrac = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeekTo((targetFrac * safeDuration).toLong())
                            }
                        }
                        .pointerInput(safeDuration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isScrubbing = true
                                    scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    val targetMs = (scrubFraction * safeDuration).toLong()
                                    isScrubbing = false
                                    onSeekTo(targetMs)
                                },
                                onDragCancel = {
                                    isScrubbing = false
                                }
                            )
                        }
                ) {
                    val peakCount = animatedPeaks.size
                    val slotWidth = size.width / peakCount
                    val barWidth = (slotWidth * 0.58f).coerceAtLeast(2.5f)
                    val activeBarIndex = ((effectiveFraction * (peakCount - 1)).roundToInt())
                        .coerceIn(0, peakCount - 1)

                    val scrubOffsetSteps = if (isScrubbing) (scrubFraction * peakCount * 3).toInt() else 0

                    for (i in 0 until peakCount) {
                        val barFraction = i.toFloat() / (peakCount - 1).coerceAtLeast(1).toFloat()
                        val lookupIdx = (i + scrubOffsetSteps) % peakCount
                        val peakRatio = animatedPeaks[lookupIdx].coerceIn(0.08f, 1.0f)

                        val barHeight = (size.height * peakRatio).coerceIn(size.height * 0.10f, size.height * 0.98f)
                        val topY = (size.height - barHeight) / 2f
                        val leftX = i * slotWidth + (slotWidth - barWidth) / 2f

                        val isPlayed = barFraction <= effectiveFraction
                        val isPlayhead = i == activeBarIndex

                        val barColor = when {
                            isPlayhead -> secondaryColor
                            isPlayed -> primaryColor
                            else -> inactiveColor
                        }

                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(leftX, topY),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }

            ProgressBarStyle.CHAPTER_SEGMENTS -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .testTag("chapter_segments_progress_bar")
                        .pointerInput(safeDuration) {
                            detectTapGestures { offset ->
                                val target = ((offset.x / size.width).coerceIn(0f, 1f) * safeDuration).toLong()
                                onSeekTo(target)
                            }
                        }
                        .pointerInput(safeDuration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isScrubbing = true
                                    scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    val targetMs = (scrubFraction * safeDuration).toLong()
                                    isScrubbing = false
                                    onSeekTo(targetMs)
                                },
                                onDragCancel = { isScrubbing = false }
                            )
                        }
                ) {
                    val gapPx = 6.dp.toPx()
                    val list = if (chapters.isNotEmpty()) chapters else listOf(
                        ChapterEntity(0, 0, 0, "Книга", 0L, safeDuration)
                    )
                    list.forEachIndexed { index, ch ->
                        val startFrac = (ch.startTimeMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                        val endFrac = ((ch.startTimeMs + ch.durationMs).toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                        val startX = startFrac * size.width + (if (index > 0) gapPx / 2f else 0f)
                        val endX = endFrac * size.width - (if (index < list.lastIndex) gapPx / 2f else 0f)
                        val segWidth = (endX - startX).coerceAtLeast(4f)
                        val trackHeight = 10.dp.toPx()
                        val topY = (size.height - trackHeight) / 2f

                        drawRoundRect(
                            color = inactiveColor,
                            topLeft = Offset(startX, topY),
                            size = Size(segWidth, trackHeight),
                            cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                        )

                        if (effectiveFraction > startFrac) {
                            val fillFrac = ((effectiveFraction - startFrac) / (endFrac - startFrac).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                            drawRoundRect(
                                color = primaryColor,
                                topLeft = Offset(startX, topY),
                                size = Size(segWidth * fillFrac, trackHeight),
                                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                            )
                        }
                    }

                    // Playhead indicator
                    drawCircle(
                        color = secondaryColor,
                        radius = 8.dp.toPx(),
                        center = Offset(effectiveFraction * size.width, size.height / 2f)
                    )
                }
            }

            ProgressBarStyle.CLASSIC_LINE -> {
                Slider(
                    value = effectiveFraction,
                    onValueChange = { newFrac ->
                        isScrubbing = true
                        scrubFraction = newFrac
                    },
                    onValueChangeFinished = {
                        val targetMs = (scrubFraction * safeDuration).toLong()
                        isScrubbing = false
                        onSeekTo(targetMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("classic_line_progress_bar")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDurationShort(effectivePositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (showChapterRemaining && currentChapter != null) {
                Text(
                    text = if (isScrubbing) {
                        "Перемотка: ${formatDurationShort(effectivePositionMs)}"
                    } else {
                        "До конца главы: -${formatDurationShort(chapterRemainingMs)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "-${formatDurationShort((safeDuration - effectivePositionMs).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizablePlayerControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    sleepTimerActive: Boolean,
    sleepRemainingMs: Long,
    voiceBoostEnabled: Boolean,
    settings: UserSettings,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenSpeedDialog: () -> Unit,
    onOpenSleepSheet: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onOpenChaptersSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonShape = RoundedCornerShape(percent = settings.controlButtonShape.cornerPercent)
    val mainSize = settings.controlButtonSize.mainButtonDp.dp
    val secSize = settings.controlButtonSize.secondaryButtonDp.dp
    val chapterBtnSize = (settings.controlButtonSize.secondaryButtonDp - 8).coerceAtLeast(48).dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Primary transport controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.showChapterSkipButtons) {
                FilledTonalIconButton(
                    onClick = onPreviousChapter,
                    shape = buttonShape,
                    modifier = Modifier
                        .size(chapterBtnSize)
                        .testTag("prev_chapter_button")
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущая глава")
                }
            }

            // Left seek button (Rewind unless swapped)
            val leftIsForward = settings.swapSeekButtons
            FilledTonalIconButton(
                onClick = if (leftIsForward) onForward else onRewind,
                shape = buttonShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .size(secSize)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, buttonShape)
                    .testTag(if (leftIsForward) "forward_button" else "rewind_button")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (leftIsForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                        contentDescription = if (leftIsForward) "Вперёд ${settings.forwardSeconds} сек" else "Назад ${settings.rewindSeconds} сек",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (leftIsForward) "+${settings.forwardSeconds}с" else "-${settings.rewindSeconds}с",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Center Play/Pause Button
            FilledIconButton(
                onClick = onPlayPause,
                shape = buttonShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .size(mainSize)
                    .testTag("play_pause_button")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    modifier = Modifier.size((settings.controlButtonSize.mainButtonDp * 0.5f).dp)
                )
            }

            // Right seek button (Forward unless swapped)
            val rightIsForward = !settings.swapSeekButtons
            FilledTonalIconButton(
                onClick = if (rightIsForward) onForward else onRewind,
                shape = buttonShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .size(secSize)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, buttonShape)
                    .testTag(if (rightIsForward) "forward_button" else "rewind_button")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (rightIsForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                        contentDescription = if (rightIsForward) "Вперёд ${settings.forwardSeconds} сек" else "Назад ${settings.rewindSeconds} сек",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (rightIsForward) "+${settings.forwardSeconds}с" else "-${settings.rewindSeconds}с",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (settings.showChapterSkipButtons) {
                FilledTonalIconButton(
                    onClick = onNextChapter,
                    shape = buttonShape,
                    modifier = Modifier
                        .size(chapterBtnSize)
                        .testTag("next_chapter_button")
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Следующая глава")
                }
            }
        }
    }
}

/**
 * Top action buttons row (Speed, Sleep Timer, Bookmarks, Voice Boost, Chapters) positioned
 * at the top of the Player screen directly above the cover art.
 */
@Composable
fun PlayerTopActionButtonsBar(
    playbackSpeed: Float,
    sleepTimerActive: Boolean,
    voiceBoostEnabled: Boolean,
    onOpenSpeedDialog: () -> Unit,
    onOpenSleepSheet: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onOpenChaptersSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("player_secondary_actions_row"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speed button (icon only)
        FilledTonalIconButton(
            onClick = onOpenSpeedDialog,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (playbackSpeed != 1.0f) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("player_speed_chip")
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = "Скорость воспроизведения",
                tint = if (playbackSpeed != 1.0f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        }

        // Sleep Timer button (icon only)
        FilledTonalIconButton(
            onClick = onOpenSleepSheet,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (sleepTimerActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("player_sleep_timer_chip")
        ) {
            Icon(
                imageVector = Icons.Default.Bedtime,
                contentDescription = "Таймер сна",
                tint = if (sleepTimerActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        }

        // Bookmark button (icon only)
        FilledTonalIconButton(
            onClick = onAddBookmark,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("player_bookmark_chip")
        ) {
            Icon(
                imageVector = Icons.Default.BookmarkAdd,
                contentDescription = "Добавить закладку",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        // Voice Boost button (icon only)
        FilledTonalIconButton(
            onClick = onToggleVoiceBoost,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (voiceBoostEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("player_voice_boost_chip")
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Усиление голоса",
                tint = if (voiceBoostEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        }

        // Chapters button (icon only)
        FilledTonalIconButton(
            onClick = onOpenChaptersSheet,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("player_chapters_chip")
        ) {
            Icon(
                imageVector = Icons.Default.FormatListNumbered,
                contentDescription = "Список глав",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
