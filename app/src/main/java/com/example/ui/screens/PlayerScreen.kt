package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.RealtimeAudioLevel
import com.example.data.local.AudiobookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.data.preferences.UserSettings
import com.example.data.repository.formatDurationShort
import com.example.data.storage.BookTitleCleaner
import com.example.ui.components.AudiobookCoverArt
import com.example.ui.components.CustomizablePlayerControls
import com.example.ui.components.CustomizableProgressBar
import com.example.ui.components.FormatBadgePill
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private fun calculateCoverSwipeDeltaMs(
    dragPx: Float,
    stageWidthPx: Float,
    rewindSeconds: Int,
    forwardSeconds: Int
): Long {
    val width = stageWidthPx.coerceAtLeast(240f)
    val normalized = (dragPx / (width * 0.75f)).coerceIn(-1f, 1f)
    if (abs(normalized) < 0.03f) return 0L
    val baseStepSec = if (normalized >= 0f) forwardSeconds else rewindSeconds
    // Smooth curve: 1x configured step for a short swipe, scaling up to 6x for a full-width swipe
    val factor = 1f + (abs(normalized) * 5f)
    val sign = if (normalized >= 0f) 1L else -1L
    return sign * (baseStepSec * 1000f * factor).roundToLong()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    book: AudiobookEntity?,
    chapters: List<ChapterEntity>,
    bookmarks: List<BookmarkEntity>,
    waveformAmplitudes: List<Float>,
    realtimeAudioLevel: RealtimeAudioLevel,
    isPlaying: Boolean,
    currentPositionMs: Long,
    voiceBoostEnabled: Boolean,
    sleepRemainingMs: Long,
    stopAtEndOfChapter: Boolean,
    settings: UserSettings,
    onBackToLibrary: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onAddBookmark: (String) -> Unit,
    onOpenSleepSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackToLibrary)

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkNoteInput by remember { mutableStateOf("") }

    // Touch gesture states (horizontal swipe seek & double-tap seek)
    var isSwipeSeeking by remember { mutableStateOf(false) }
    var swipeDragPx by remember { mutableFloatStateOf(0f) }
    var swipeStageWidthPx by remember { mutableFloatStateOf(800f) }
    var swipeStartPositionMs by remember { mutableLongStateOf(0L) }
    var quickGestureBadgeText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(quickGestureBadgeText) {
        if (quickGestureBadgeText != null) {
            delay(950L)
            quickGestureBadgeText = null
        }
    }

    if (book == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Выберите аудиокнигу в библиотеке")
        }
        return
    }

    val safeDuration = book.durationMs.coerceAtLeast(1L)
    val currentChapter = chapters.lastOrNull { currentPositionMs >= it.startTimeMs } ?: chapters.firstOrNull()

    // Up-to-date references for gesture callbacks so pointerInput never restarts during 80ms playback ticks
    val latestPositionMs by rememberUpdatedState(currentPositionMs)
    val latestSafeDuration by rememberUpdatedState(safeDuration)
    val latestRewindSeconds by rememberUpdatedState(settings.rewindSeconds)
    val latestForwardSeconds by rememberUpdatedState(settings.forwardSeconds)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnRewind by rememberUpdatedState(onRewind)
    val latestOnForward by rememberUpdatedState(onForward)
    val latestOnPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnBackToLibrary by rememberUpdatedState(onBackToLibrary)

    // Calculate swipe delta in milliseconds from horizontal drag gesture
    val swipeDeltaMs: Long = if (isSwipeSeeking) {
        calculateCoverSwipeDeltaMs(
            dragPx = swipeDragPx,
            stageWidthPx = swipeStageWidthPx,
            rewindSeconds = settings.rewindSeconds,
            forwardSeconds = settings.forwardSeconds
        )
    } else {
        0L
    }
    val anchorPosMs = if (isSwipeSeeking) swipeStartPositionMs else currentPositionMs
    val gestureTargetPosMs = (anchorPosMs + swipeDeltaMs).coerceIn(0L, safeDuration)
    val gestureTargetChapter = chapters.lastOrNull { gestureTargetPosMs >= it.startTimeMs } ?: currentChapter

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        onUserInteraction()
                    }
                }
            }
            .testTag("player_screen")
    ) {
        val viewportHeight = maxHeight

        // Ambient cover-color blur & glow in the background behind all UI elements (zero layout impact)
        if (settings.coverBlurGlowEnabled) {
            com.example.ui.components.CoverArtBlurHalo(
                coverArtStyle = book.coverArtStyle,
                coverImagePath = book.coverImagePath,
                modifier = Modifier
                    .matchParentSize()
                    .testTag("player_cover_blur_halo")
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Navigation & Audio Codec Info Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackToLibrary,
                    modifier = Modifier.testTag("player_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К библиотеке")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FormatBadgePill(format = book.audioFormat)
                    Text(
                        text = "${book.bitrateKbps} кбит/с • ${book.sampleRateHz / 1000} кГц",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("player_customize_ui_button")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Настроить кнопки и интерфейс")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Top Secondary Action Icons Row (Speed, Sleep Timer, Bookmarks, Voice Boost, Chapters) above Cover Art
            com.example.ui.components.PlayerTopActionButtonsBar(
                playbackSpeed = book.playbackSpeed.coerceIn(0.5f, 2.0f),
                sleepTimerActive = sleepRemainingMs > 0L || stopAtEndOfChapter,
                voiceBoostEnabled = voiceBoostEnabled,
                onOpenSpeedDialog = { showSpeedSheet = true },
                onOpenSleepSheet = onOpenSleepSheet,
                onAddBookmark = {
                    bookmarkNoteInput = ""
                    showBookmarkDialog = true
                },
                onToggleVoiceBoost = onToggleVoiceBoost,
                onOpenChaptersSheet = { showChaptersSheet = true }
            )

            // Increased spacing between top buttons and cover art
            Spacer(modifier = Modifier.height(20.dp))

            // Interactive Touch Gesture Stage around Cover Art (Horizontal Swipes, Vertical Swipe-Down & Double-Tap)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_gesture_stage")
                    .pointerInput(book.id) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                val third = size.width / 3f
                                when {
                                    tapOffset.x < third -> {
                                        latestOnRewind()
                                        quickGestureBadgeText = "⏪ -${latestRewindSeconds} сек"
                                    }
                                    tapOffset.x > third * 2f -> {
                                        latestOnForward()
                                        quickGestureBadgeText = "⏩ +${latestForwardSeconds} сек"
                                    }
                                    else -> {
                                        val wasPlaying = latestIsPlaying
                                        latestOnPlayPause()
                                        quickGestureBadgeText = if (wasPlaying) "⏸ Пауза" else "▶ Воспроизведение"
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(book.id) {
                        var totalDx = 0f
                        var totalDy = 0f
                        var gestureAxisLocked = 0 // 0 = undecided, 1 = horizontal seek, 2 = vertical swipe down
                        val axisLockThresholdPx = 10.dp.toPx()
                        val minSeekCommitPx = 16.dp.toPx()
                        val swipeDownThresholdPx = 52.dp.toPx()

                        detectDragGestures(
                            onDragStart = {
                                totalDx = 0f
                                totalDy = 0f
                                gestureAxisLocked = 0
                                swipeStageWidthPx = size.width.toFloat().coerceAtLeast(240f)
                                swipeStartPositionMs = latestPositionMs
                                isSwipeSeeking = false
                                swipeDragPx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDx += dragAmount.x
                                totalDy += dragAmount.y
                                swipeStageWidthPx = size.width.toFloat().coerceAtLeast(240f)

                                if (gestureAxisLocked == 0) {
                                    if (abs(totalDx) > axisLockThresholdPx && abs(totalDx) >= abs(totalDy) * 0.8f) {
                                        gestureAxisLocked = 1
                                        swipeStartPositionMs = latestPositionMs
                                        isSwipeSeeking = true
                                    } else if (totalDy > axisLockThresholdPx && totalDy > abs(totalDx) * 1.25f) {
                                        gestureAxisLocked = 2
                                    }
                                }

                                if (gestureAxisLocked == 1) {
                                    swipeDragPx = totalDx
                                }
                            },
                            onDragEnd = {
                                if (gestureAxisLocked == 2 && totalDy > swipeDownThresholdPx && totalDy > abs(totalDx)) {
                                    isSwipeSeeking = false
                                    swipeDragPx = 0f
                                    gestureAxisLocked = 0
                                    latestOnBackToLibrary()
                                } else if (gestureAxisLocked == 1 && abs(swipeDragPx) >= minSeekCommitPx) {
                                    val deltaMs = calculateCoverSwipeDeltaMs(
                                        dragPx = swipeDragPx,
                                        stageWidthPx = size.width.toFloat().coerceAtLeast(240f),
                                        rewindSeconds = latestRewindSeconds,
                                        forwardSeconds = latestForwardSeconds
                                    )
                                    if (deltaMs != 0L) {
                                        val finalTarget = (swipeStartPositionMs + deltaMs).coerceIn(0L, latestSafeDuration)
                                        latestOnSeekTo(finalTarget)
                                        val secDiff = deltaMs / 1000L
                                        val signPrefix = if (secDiff >= 0) "+" else ""
                                        quickGestureBadgeText = "Перемотка: $signPrefix${secDiff} сек"
                                    }
                                }
                                isSwipeSeeking = false
                                swipeDragPx = 0f
                                gestureAxisLocked = 0
                            },
                            onDragCancel = {
                                isSwipeSeeking = false
                                swipeDragPx = 0f
                                gestureAxisLocked = 0
                            }
                        )
                    }
            ) {
                AudiobookCoverArt(
                    coverArtStyle = book.coverArtStyle,
                    audioFormat = book.audioFormat,
                    frameStyle = settings.coverFrameStyle,
                    isPlaying = isPlaying,
                    showFormatBadge = false,
                    coverImagePath = book.coverImagePath,
                    modifier = Modifier
                        .size(280.dp)
                        .testTag("player_cover_art")
                )

                // Live Horizontal Swipe Seek Overlay HUD
                androidx.compose.animation.AnimatedVisibility(
                    visible = isSwipeSeeking && abs(swipeDragPx) > 16f,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xD910121B),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag("swipe_seek_hud")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (swipeDeltaMs >= 0L) Icons.Default.FastForward else Icons.Default.FastRewind,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val secDelta = swipeDeltaMs / 1000L
                                Text(
                                    text = if (secDelta >= 0) "+${secDelta} сек" else "${secDelta} сек",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatDurationShort(gestureTargetPosMs)} / ${formatDurationShort(safeDuration)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (gestureTargetChapter != null) {
                                Text(
                                    text = gestureTargetChapter.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Quick Double-Tap / Swipe Completion Feedback Pill
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isSwipeSeeking && quickGestureBadgeText != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xD910121B),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = quickGestureBadgeText.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val displayAuthor = remember(book.author) { BookTitleCleaner.cleanAuthorName(book.author) }
            val displayTitle = remember(book.title, displayAuthor) {
                BookTitleCleaner.cleanBookTitle(book.title, displayAuthor).ifBlank { book.title }
            }

            // Tight block with zero spacing between Title, Current Chapter, and Author
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Book Title
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Current Chapter Pill (directly below Book Title and above Author, with zero spacing)
                if (currentChapter != null) {
                    Surface(
                        onClick = { showChaptersSheet = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.testTag("current_chapter_pill")
                    ) {
                        Text(
                            text = "${currentChapter.title} (${currentChapter.chapterIndex + 1}/${chapters.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }

                // Clickable Author (opens other audiobooks by this author in Library if available)
                Text(
                    text = displayAuthor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAuthorClick(displayAuthor) }
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .testTag("player_author_button")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Graphic Equalizer / Sound Intensity SeekBar
            CustomizableProgressBar(
                currentPositionMs = currentPositionMs,
                durationMs = book.durationMs,
                chapters = chapters,
                waveformAmplitudes = waveformAmplitudes,
                realtimeAudioLevel = realtimeAudioLevel,
                style = settings.progressBarStyle,
                isPlaying = isPlaying,
                showChapterRemaining = settings.showChapterTimeRemaining,
                onSeekTo = onSeekTo
            )

            // Push playback transport controls lower down on the screen for ergonomic thumb reach
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(20.dp))

            // Customizable Transport Controls
            CustomizablePlayerControls(
                isPlaying = isPlaying,
                playbackSpeed = book.playbackSpeed.coerceIn(0.5f, 2.0f),
                sleepTimerActive = sleepRemainingMs > 0L || stopAtEndOfChapter,
                sleepRemainingMs = sleepRemainingMs,
                voiceBoostEnabled = voiceBoostEnabled,
                settings = settings,
                onPlayPause = onPlayPause,
                onRewind = onRewind,
                onForward = onForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onOpenSpeedDialog = { showSpeedSheet = true },
                onOpenSleepSheet = onOpenSleepSheet,
                onAddBookmark = {
                    bookmarkNoteInput = ""
                    showBookmarkDialog = true
                },
                onToggleVoiceBoost = onToggleVoiceBoost,
                onOpenChaptersSheet = { showChaptersSheet = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Bookmarks Strip for Current Book
            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Закладки в этой книге (${bookmarks.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            bookmarks.take(4).forEach { bm ->
                                Surface(
                                    onClick = { onSeekTo(bm.positionMs) },
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${formatDurationShort(bm.positionMs)} • ${bm.note}",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Speed Selection Modal Sheet (0.5x to 2.0x)
    if (showSpeedSheet) {
        val currentClampedSpeed = book.playbackSpeed.coerceIn(0.5f, 2.0f)
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Скорость воспроизведения (0.5x – 2.0x)", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${String.format("%.2f", currentClampedSpeed)}x",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = currentClampedSpeed,
                    onValueChange = { raw ->
                        val stepped = ((raw * 20f).roundToInt() / 20f).coerceIn(0.5f, 2.0f)
                        onSpeedChange(stepped)
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 29,
                    modifier = Modifier.testTag("modal_playback_speed_slider")
                )
                Spacer(modifier = Modifier.height(8.dp))
                val speedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    speedPresets.forEach { preset ->
                        FilterChip(
                            selected = abs(currentClampedSpeed - preset) < 0.03f,
                            onClick = { onSpeedChange(preset) },
                            label = { Text("${preset}x") }
                        )
                    }
                }
            }
        }
    }

    // Chapters Modal Sheet
    if (showChaptersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChaptersSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Главы аудиокниги (${chapters.size})", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (book.audioFormat.equals("M4B", ignoreCase = true)) {
                                "Встроенная таблица глав контейнера M4B"
                            } else {
                                "Навигация по сегментам и главам (${book.audioFormat})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FormatBadgePill(format = book.audioFormat)
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chapters, key = { it.id }) { chapter ->
                        val isCurrent = currentChapter?.id == chapter.id
                        Surface(
                            onClick = {
                                onSeekTo(chapter.startTimeMs)
                                showChaptersSheet = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sheet_chapter_item_${chapter.chapterIndex}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isCurrent) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = chapter.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "Длительность: ${formatDurationShort(chapter.durationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = formatDurationShort(chapter.startTimeMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Bookmark Dialog
    if (showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarkDialog = false },
            title = { Text("Новая аудиозакладка") },
            text = {
                Column {
                    Text(
                        text = "Позиция: ${formatDurationShort(currentPositionMs)} (${currentChapter?.title ?: "Книга"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = bookmarkNoteInput,
                        onValueChange = { bookmarkNoteInput = it },
                        label = { Text("Заметка к закладке") },
                        placeholder = { Text("Например: Любимая цитата...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bookmark_note_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddBookmark(bookmarkNoteInput)
                        showBookmarkDialog = false
                    },
                    modifier = Modifier.testTag("confirm_add_bookmark_button")
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun AudioChaptersSegmentCarousel(
    audioFormat: String,
    chapters: List<ChapterEntity>,
    currentChapter: ChapterEntity?,
    currentPositionMs: Long,
    onSelectChapter: (ChapterEntity) -> Unit,
    onOpenAllChapters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("m4b_chapters_carousel")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FormatListNumbered,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Главы книги (${chapters.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = onOpenAllChapters,
                    modifier = Modifier.testTag("open_all_chapters_button")
                ) {
                    Text("Все главы")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chapters.forEach { chapter ->
                    val isCurrent = currentChapter?.id == chapter.id
                    val chapterProgress = when {
                        currentPositionMs <= chapter.startTimeMs -> 0f
                        currentPositionMs >= chapter.startTimeMs + chapter.durationMs -> 1f
                        else -> ((currentPositionMs - chapter.startTimeMs).toFloat() /
                            chapter.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    }

                    Surface(
                        onClick = { onSelectChapter(chapter) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = if (isCurrent) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else null,
                        modifier = Modifier
                            .width(176.dp)
                            .testTag("chapter_chip_${chapter.chapterIndex}")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ГЛАВА ${chapter.chapterIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = formatDurationShort(chapter.startTimeMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { chapterProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedControlCard(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val clamped = currentSpeed.coerceIn(0.5f, 2.0f)
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("playback_speed_card")
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Скорость воспроизведения (0.5x – 2.0x)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${String.format("%.2f", clamped).trimEnd('0').trimEnd('.')}x",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("playback_speed_value_label")
                    )
                }
            }

            Slider(
                value = clamped,
                onValueChange = { rawValue ->
                    val stepped = ((rawValue * 20f).roundToInt() / 20f).coerceIn(0.5f, 2.0f)
                    onSpeedChange(stepped)
                },
                valueRange = 0.5f..2.0f,
                steps = 29,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playback_speed_slider")
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = abs(clamped - preset) < 0.03f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedChange(preset) },
                        label = { Text("${preset}x") },
                        modifier = Modifier.testTag("speed_preset_${preset}x")
                    )
                }
            }
        }
    }
}
