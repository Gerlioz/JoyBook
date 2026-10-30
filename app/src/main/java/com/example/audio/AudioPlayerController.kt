package com.example.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.data.local.AudiobookEntity
import com.example.data.local.ChapterEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

data class ParsedAudioMetadata(
    val title: String,
    val author: String,
    val format: String,
    val durationMs: Long,
    val bitrateKbps: Int
)

data class PlaybackFileSegment(
    val fileUri: String,
    val startOffsetInBookMs: Long,
    val durationMs: Long,
    val title: String
)

class AudioPlayerController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private var currentLoadedBookId: Long? = null
    private var activeBookChapters: List<ChapterEntity> = emptyList()
    private var activePlaylistFiles: List<PlaybackFileSegment> = emptyList()
    private var isBuiltInLoopingAudio: Boolean = false
    private var voiceBoostEnabled: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var lastErrorCallback: ((String) -> Unit)? = null

    private val _serviceIsPlaying = MutableStateFlow(false)
    val serviceIsPlaying: StateFlow<Boolean> = _serviceIsPlaying.asStateFlow()

    private val _servicePlaybackEnded = MutableStateFlow(false)
    val servicePlaybackEnded: StateFlow<Boolean> = _servicePlaybackEnded.asStateFlow()

    private val _servicePlaybackSpeed = MutableStateFlow(1.0f)
    val servicePlaybackSpeed: StateFlow<Float> = _servicePlaybackSpeed.asStateFlow()

    private val _servicePositionChanged = MutableStateFlow<Long?>(null)
    val servicePositionChanged: StateFlow<Long?> = _servicePositionChanged.asStateFlow()

    private val _sessionSleepTimerState = MutableStateFlow(SleepTimerState())
    val sessionSleepTimerState: StateFlow<SleepTimerState> = _sessionSleepTimerState.asStateFlow()

    private fun startStickyPlaybackService(
        bookId: Long,
        segmentOffsets: LongArray,
        chapterOffsets: LongArray = LongArray(0)
    ) {
        runCatching {
            val intent = android.content.Intent(context, JoyBookPlaybackService::class.java).apply {
                action = JoyBookPlaybackService.ACTION_START_PLAYBACK
                putExtra(JoyBookPlaybackService.EXTRA_BOOK_ID, bookId)
                putExtra(JoyBookPlaybackService.EXTRA_SEGMENT_OFFSETS_MS, segmentOffsets)
                putExtra(JoyBookPlaybackService.EXTRA_CHAPTER_OFFSETS_MS, chapterOffsets)
            }
            context.startService(intent)
        }
    }

    private fun ensureControllerConnected() {
        if (controllerFuture != null) return
        runCatching {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, JoyBookPlaybackService::class.java)
            )
            val future = MediaController.Builder(
                context,
                sessionToken
            ).setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    parseSleepTimerExtras(extras)
                }

                override fun onDisconnected(controller: MediaController) {
                    _serviceIsPlaying.value = false
                    mediaController = null
                    controllerFuture = null
                }
            }).buildAsync()

            controllerFuture = future
            future.addListener(
                {
                    runCatching {
                        val controller = future.get()
                        mediaController = controller
                        parseSleepTimerExtras(controller.sessionExtras)
                        _serviceIsPlaying.value = controller.isPlaying
                        _servicePlaybackSpeed.value = controller.playbackParameters.speed.coerceIn(0.5f, 2.0f)
                        controller.addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                _serviceIsPlaying.value = isPlaying
                                if (isPlaying) {
                                    _servicePlaybackEnded.value = false
                                }
                            }

                            override fun onPositionDiscontinuity(
                                oldPosition: Player.PositionInfo,
                                newPosition: Player.PositionInfo,
                                reason: Int
                            ) {
                                getExternalPlayerPositionOrNull()?.let { pos ->
                                    _servicePositionChanged.value = pos
                                }
                            }

                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                getExternalPlayerPositionOrNull()?.let { pos ->
                                    _servicePositionChanged.value = pos
                                }
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    _servicePlaybackEnded.value = true
                                } else if (playbackState == Player.STATE_IDLE && controller.mediaItemCount == 0) {
                                    _serviceIsPlaying.value = false
                                } else if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                                    _servicePlaybackEnded.value = false
                                }
                            }

                            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                                _servicePlaybackSpeed.value = playbackParameters.speed.coerceIn(0.5f, 2.0f)
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                lastErrorCallback?.invoke(
                                    "Ошибка ExoPlayer (${error.errorCodeName}): ${error.localizedMessage ?: "неизвестная ошибка"}"
                                )
                            }
                        })
                        val actions = ArrayList(pendingActions)
                        pendingActions.clear()
                        actions.forEach { action -> action(controller) }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    private fun parseSleepTimerExtras(extras: Bundle) {
        if (extras.isEmpty) return
        if (extras.getBoolean(JoyBookPlaybackService.EXTRA_PLAYER_CLOSED, false)) {
            _serviceIsPlaying.value = false
            currentLoadedBookId = null
        }
        val isActive = extras.getBoolean(JoyBookPlaybackService.EXTRA_SLEEP_ACTIVE, false)
        val remainingMs = extras.getLong(JoyBookPlaybackService.EXTRA_SLEEP_REMAINING_MS, 0L)
        val totalMs = extras.getLong(JoyBookPlaybackService.EXTRA_SLEEP_TOTAL_MS, 0L)
        val completedAt = extras.getLong(JoyBookPlaybackService.EXTRA_SLEEP_COMPLETED_AT, 0L)

        _sessionSleepTimerState.value = _sessionSleepTimerState.value.copy(
            isActive = isActive,
            remainingMs = remainingMs,
            totalDurationMs = totalMs,
            lastCompletedAtMillis = completedAt
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val existing = mediaController
        if (existing != null && existing.isConnected) {
            action(existing)
        } else {
            if (existing != null && !existing.isConnected) {
                mediaController = null
                controllerFuture = null
            }
            pendingActions.add(action)
            ensureControllerConnected()
        }
    }

    fun startBookPlayback(
        book: AudiobookEntity,
        chapters: List<ChapterEntity> = emptyList(),
        sampleFile: File?,
        onError: (String) -> Unit
    ): Boolean {
        lastErrorCallback = onError
        _servicePlaybackEnded.value = false
        return try {
            val switchingBook = currentLoadedBookId != book.id
            currentLoadedBookId = book.id
            activeBookChapters = chapters
            isBuiltInLoopingAudio = book.isBuiltIn
            currentSpeed = book.playbackSpeed.coerceIn(0.5f, 2.0f)
            _servicePlaybackSpeed.value = currentSpeed

            val distinctFileUris = chapters.mapNotNull { it.fileUri }.distinct()
            val precomputedOffsets = if (!book.isBuiltIn && distinctFileUris.size > 1) {
                distinctFileUris.map { uriStr ->
                    chapters.filter { it.fileUri == uriStr }.minOfOrNull { it.startTimeMs } ?: 0L
                }.sorted().toLongArray()
            } else {
                LongArray(0)
            }
            val chapterOffsets = chapters.map { it.startTimeMs }.sorted().distinct().toLongArray()

            // Explicitly start the service with START_STICKY so Android does not kill it when Activity is backgrounded
            startStickyPlaybackService(book.id, precomputedOffsets, chapterOffsets)

            withController { controller ->
                try {
                    if (switchingBook || controller.mediaItemCount == 0) {
                        val artworkBytes = loadNotificationArtworkBytes(book)
                        val coverUri = book.coverImagePath
                            ?.takeIf { it.isNotBlank() && File(it).exists() }
                            ?.let { Uri.fromFile(File(it)) }

                        if (!book.isBuiltIn && distinctFileUris.size > 1) {
                            // Multi-file audiobook playlist (one MediaItem per physical file)
                            val fileSegments = distinctFileUris.mapIndexed { idx, uriStr ->
                                val fileChapters = chapters.filter { it.fileUri == uriStr }
                                val fileStart = fileChapters.minOfOrNull { it.startTimeMs } ?: 0L
                                val fileDuration = fileChapters.sumOf { it.durationMs }.coerceAtLeast(1_000L)
                                val fileFirstChapterTitle = fileChapters.firstOrNull()?.title ?: "Глава ${idx + 1}"
                                PlaybackFileSegment(
                                    fileUri = uriStr,
                                    startOffsetInBookMs = fileStart,
                                    durationMs = fileDuration,
                                    title = fileFirstChapterTitle
                                )
                            }.sortedBy { it.startOffsetInBookMs }
                            activePlaylistFiles = fileSegments

                            val cleanAuthor = com.example.data.storage.BookTitleCleaner.cleanAuthorName(book.author)
                            val cleanTitle = com.example.data.storage.BookTitleCleaner.cleanBookTitle(book.title, cleanAuthor).ifBlank { book.title }

                            val mediaItems = fileSegments.mapIndexed { idx, segment ->
                                val uri = Uri.parse(segment.fileUri)
                                val mimeType = resolveMimeType(book.audioFormat)
                                val metadataBuilder = MediaMetadata.Builder()
                                    .setTitle(cleanTitle)
                                    .setSubtitle(segment.title)
                                    .setArtist(cleanAuthor)
                                    .setAlbumTitle(segment.title)
                                    .setTrackNumber(idx + 1)
                                if (artworkBytes != null) {
                                    metadataBuilder.setArtworkData(
                                        artworkBytes,
                                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
                                    )
                                }
                                if (coverUri != null) {
                                    metadataBuilder.setArtworkUri(coverUri)
                                }
                                MediaItem.Builder()
                                    .setMediaId("${book.id}_file_$idx")
                                    .setUri(uri)
                                    .setMimeType(mimeType)
                                    .setMediaMetadata(metadataBuilder.build())
                                    .build()
                            }

                            val targetIdx = fileSegments.indexOfLast { book.currentPositionMs >= it.startOffsetInBookMs }
                                .coerceIn(0, fileSegments.size - 1)
                            val targetSegment = fileSegments[targetIdx]
                            val offsetInFile = (book.currentPositionMs - targetSegment.startOffsetInBookMs).coerceAtLeast(0L)

                            controller.repeatMode = Player.REPEAT_MODE_OFF
                            controller.setMediaItems(mediaItems, targetIdx, offsetInFile)
                            controller.prepare()
                        } else {
                            activePlaylistFiles = emptyList()
                            val uri = if (book.isBuiltIn && sampleFile != null && sampleFile.exists()) {
                                Uri.fromFile(sampleFile)
                            } else {
                                Uri.parse(book.fileUri)
                            }

                            val mimeType = if (book.isBuiltIn) {
                                MimeTypes.AUDIO_WAV
                            } else {
                                resolveMimeType(book.audioFormat)
                            }

                            val cleanAuthor = com.example.data.storage.BookTitleCleaner.cleanAuthorName(book.author)
                            val cleanTitle = com.example.data.storage.BookTitleCleaner.cleanBookTitle(book.title, cleanAuthor).ifBlank { book.title }
                            val metadataBuilder = MediaMetadata.Builder()
                                .setTitle(cleanTitle)
                                .setArtist(cleanAuthor)
                                .setAlbumTitle("JoyBook • ${book.audioFormat}")
                            if (artworkBytes != null) {
                                metadataBuilder.setArtworkData(
                                    artworkBytes,
                                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                                )
                            }
                            if (coverUri != null) {
                                metadataBuilder.setArtworkUri(coverUri)
                            }

                            val mediaItem = MediaItem.Builder()
                                .setMediaId(book.id.toString())
                                .setUri(uri)
                                .setMimeType(mimeType)
                                .setMediaMetadata(metadataBuilder.build())
                                .build()

                            controller.repeatMode = if (book.isBuiltIn) {
                                Player.REPEAT_MODE_ONE
                            } else {
                                Player.REPEAT_MODE_OFF
                            }

                            val startPositionMs = if (!book.isBuiltIn && book.currentPositionMs > 0L) {
                                book.currentPositionMs
                            } else {
                                0L
                            }

                            controller.setMediaItem(mediaItem, startPositionMs)
                            controller.prepare()
                        }
                    }

                    // Sync book ID, cover path, segment offsets, and chapter offsets to service for notification controls & DB saving
                    runCatching {
                        val syncArgs = Bundle().apply {
                            putLong(JoyBookPlaybackService.EXTRA_BOOK_ID, book.id)
                            putString(JoyBookPlaybackService.EXTRA_COVER_IMAGE_PATH, book.coverImagePath.orEmpty())
                            putInt(JoyBookPlaybackService.EXTRA_COVER_ART_STYLE, book.coverArtStyle)
                            putLongArray(
                                JoyBookPlaybackService.EXTRA_SEGMENT_OFFSETS_MS,
                                activePlaylistFiles.map { it.startOffsetInBookMs }.toLongArray()
                            )
                            putLongArray(
                                JoyBookPlaybackService.EXTRA_CHAPTER_OFFSETS_MS,
                                chapterOffsets
                            )
                        }
                        controller.sendCustomCommand(
                            SessionCommand(JoyBookPlaybackService.COMMAND_SYNC_BOOK_CONTEXT, Bundle.EMPTY),
                            syncArgs
                        )
                    }

                    controller.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))
                    if (!_sessionSleepTimerState.value.isActive) {
                        controller.volume = currentVolume
                    }
                    sendVoiceBoostCommand(controller, voiceBoostEnabled)
                    controller.play()
                } catch (e: Exception) {
                    onError("Не удалось воспроизвести аудиофайл (${book.audioFormat}): ${e.localizedMessage ?: "ошибка кодека"}")
                }
            }
            true
        } catch (e: Exception) {
            onError("Не удалось запустить фоновый сервис воспроизведения: ${e.localizedMessage ?: ""}")
            false
        }
    }

    private fun resolveMimeType(audioFormat: String): String? = when (audioFormat.uppercase()) {
        "MP3" -> MimeTypes.AUDIO_MPEG
        "M4A", "M4B" -> MimeTypes.AUDIO_MP4
        "AMR" -> MimeTypes.AUDIO_AMR
        "WAV" -> MimeTypes.AUDIO_WAV
        else -> null
    }

    fun pause() {
        withController { controller ->
            runCatching { controller.pause() }
        }
    }

    fun closePlayer() {
        _serviceIsPlaying.value = false
        currentLoadedBookId = null
        activePlaylistFiles = emptyList()
        activeBookChapters = emptyList()
        runCatching {
            val existing = mediaController
            if (existing != null && existing.isConnected) {
                existing.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_CLOSE_PLAYER, Bundle.EMPTY),
                    Bundle.EMPTY
                )
            } else {
                val intent = android.content.Intent(context, JoyBookPlaybackService::class.java).apply {
                    action = JoyBookPlaybackService.ACTION_CLOSE_PLAYER
                }
                context.startService(intent)
            }
        }
    }

    private fun loadNotificationArtworkBytes(book: AudiobookEntity): ByteArray? {
        return runCatching {
            var bitmap: Bitmap? = null
            val coverPath = book.coverImagePath
            if (!coverPath.isNullOrBlank()) {
                val file = File(coverPath)
                if (file.exists() && file.length() > 0L) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(coverPath, bounds)
                    var sample = 1
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    while (maxDim / sample > 480) {
                        sample *= 2
                    }
                    bitmap = BitmapFactory.decodeFile(
                        coverPath,
                        BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                    )
                }
            }

            if (bitmap == null && book.fileUri.isNotBlank()) {
                val uri = runCatching { Uri.parse(book.fileUri) }.getOrNull()
                if (uri != null) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            val embedded = retriever.embeddedPicture
                            if (embedded != null && embedded.isNotEmpty()) {
                                bitmap = BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
                            }
                        } finally {
                            retriever.release()
                        }
                    }
                    if (bitmap == null) {
                        runCatching {
                            val mp4 = M4bChapterParser.extractMp4Metadata(context, uri, includeCover = true)
                            if (mp4.coverBytes != null && mp4.coverBytes.isNotEmpty()) {
                                bitmap = BitmapFactory.decodeByteArray(mp4.coverBytes, 0, mp4.coverBytes.size)
                            }
                        }
                    }
                }
            }

            val finalBitmap = if (bitmap != null) {
                val targetSize = 384
                if (bitmap!!.width > targetSize || bitmap!!.height > targetSize) {
                    Bitmap.createScaledBitmap(bitmap!!, targetSize, targetSize, true)
                } else {
                    bitmap!!
                }
            } else {
                generateProceduralNotificationArtwork(book.coverArtStyle)
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
            out.toByteArray()
        }.getOrNull()
    }

    companion object {
        fun generateProceduralNotificationArtwork(coverArtStyle: Int): Bitmap {
            val size = 384
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val colors = when (coverArtStyle % 5) {
                0 -> intArrayOf(0xFF0E0B26.toInt(), 0xFF2B1644.toInt(), 0xFFFFB74D.toInt())
                1 -> intArrayOf(0xFF090D16.toInt(), 0xFF162133.toInt(), 0xFFFFD54F.toInt())
                2 -> intArrayOf(0xFF07211D.toInt(), 0xFF1B4D3E.toInt(), 0xFF81C784.toInt())
                3 -> intArrayOf(0xFF1F140E.toInt(), 0xFF3E2723.toInt(), 0xFFFFB74D.toInt())
                else -> intArrayOf(0xFF06152D.toInt(), 0xFF0D3B4C.toInt(), 0xFF4DD0E1.toInt())
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(colors[0], colors[1]),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size * 0.52f, size * 0.42f, size * 0.45f,
                    intArrayOf(colors[2], 0x00000000),
                    null,
                    Shader.TileMode.CLAMP
                )
                alpha = 185
            }
            canvas.drawCircle(size * 0.52f, size * 0.42f, size * 0.45f, glowPaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = colors[2]
                alpha = 140
            }
            canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.28f, ringPaint)
            return bmp
        }
    }

    fun resume() {
        currentLoadedBookId?.let { id ->
            startStickyPlaybackService(
                id,
                activePlaylistFiles.map { it.startOffsetInBookMs }.toLongArray(),
                activeBookChapters.map { it.startTimeMs }.sorted().distinct().toLongArray()
            )
        }
        withController { controller ->
            runCatching { controller.play() }
        }
    }

    fun seekTo(positionMs: Long) {
        withController { controller ->
            runCatching {
                if (isBuiltInLoopingAudio) {
                    val duration = controller.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    val loopLen = duration.coerceAtLeast(1000L)
                    controller.seekTo(positionMs % loopLen)
                    return@withController
                }

                if (activePlaylistFiles.isNotEmpty()) {
                    val targetIdx = activePlaylistFiles.indexOfLast { positionMs >= it.startOffsetInBookMs }
                        .coerceIn(0, activePlaylistFiles.size - 1)
                    val segment = activePlaylistFiles[targetIdx]
                    val offset = (positionMs - segment.startOffsetInBookMs).coerceAtLeast(0L)
                    controller.seekTo(targetIdx, offset)
                } else {
                    val duration = controller.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    controller.seekTo(positionMs.coerceIn(0L, duration))
                }
            }
        }
    }

    fun applyPlaybackSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.5f, 2.0f)
        _servicePlaybackSpeed.value = currentSpeed
        withController { controller ->
            runCatching {
                val params = PlaybackParameters(currentSpeed, 1.0f)
                controller.setPlaybackParameters(params)
                val args = Bundle().apply {
                    putFloat(JoyBookPlaybackService.EXTRA_PLAYBACK_SPEED, currentSpeed)
                }
                controller.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_SET_PLAYBACK_SPEED, Bundle.EMPTY),
                    args
                )
            }
        }
    }

    fun setVolumeMultiplier(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        withController { controller ->
            runCatching {
                controller.volume = currentVolume
            }
        }
    }

    fun setVoiceBoost(enabled: Boolean) {
        voiceBoostEnabled = enabled
        withController { controller ->
            sendVoiceBoostCommand(controller, enabled)
        }
    }

    private fun sendVoiceBoostCommand(controller: MediaController, enabled: Boolean) {
        runCatching {
            val args = Bundle().apply {
                putBoolean(JoyBookPlaybackService.EXTRA_VOICE_BOOST_ENABLED, enabled)
            }
            controller.sendCustomCommand(
                SessionCommand(JoyBookPlaybackService.COMMAND_SET_VOICE_BOOST, Bundle.EMPTY),
                args
            )
        }
    }

    // Media3 Session Sleep Timer Commands
    fun startSessionSleepTimer(
        durationMs: Long,
        fadeOutEnabled: Boolean,
        autoRewindMs: Long
    ) {
        _sessionSleepTimerState.value = _sessionSleepTimerState.value.copy(
            isActive = true,
            remainingMs = durationMs,
            totalDurationMs = durationMs,
            fadeOutEnabled = fadeOutEnabled,
            autoRewindMs = autoRewindMs
        )
        withController { controller ->
            runCatching {
                val args = Bundle().apply {
                    putLong(JoyBookPlaybackService.EXTRA_SLEEP_DURATION_MS, durationMs)
                    putBoolean(JoyBookPlaybackService.EXTRA_SLEEP_FADE_OUT, fadeOutEnabled)
                    putLong(JoyBookPlaybackService.EXTRA_SLEEP_AUTO_REWIND_MS, autoRewindMs)
                }
                controller.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_START_SLEEP_TIMER, Bundle.EMPTY),
                    args
                )
            }
        }
    }

    fun extendSessionSleepTimer(additionalMs: Long) {
        val current = _sessionSleepTimerState.value
        val nextRemaining = current.remainingMs + additionalMs
        _sessionSleepTimerState.value = current.copy(
            isActive = true,
            remainingMs = nextRemaining,
            totalDurationMs = maxOf(current.totalDurationMs, nextRemaining)
        )
        withController { controller ->
            runCatching {
                val args = Bundle().apply {
                    putLong(JoyBookPlaybackService.EXTRA_SLEEP_DURATION_MS, additionalMs)
                }
                controller.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_EXTEND_SLEEP_TIMER, Bundle.EMPTY),
                    args
                )
            }
        }
    }

    fun cancelSessionSleepTimer() {
        _sessionSleepTimerState.value = _sessionSleepTimerState.value.copy(
            isActive = false,
            remainingMs = 0L,
            totalDurationMs = 0L
        )
        withController { controller ->
            runCatching {
                controller.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY),
                    Bundle.EMPTY
                )
            }
        }
    }

    fun setSessionSleepFadeOut(enabled: Boolean) {
        _sessionSleepTimerState.value = _sessionSleepTimerState.value.copy(fadeOutEnabled = enabled)
        withController { controller ->
            runCatching {
                val args = Bundle().apply {
                    putBoolean(JoyBookPlaybackService.EXTRA_SLEEP_FADE_OUT, enabled)
                }
                controller.sendCustomCommand(
                    SessionCommand(JoyBookPlaybackService.COMMAND_SET_SLEEP_FADE_OUT, Bundle.EMPTY),
                    args
                )
            }
        }
    }

    fun getExternalPlayerPositionOrNull(): Long? {
        val controller = mediaController ?: return null
        if (isBuiltInLoopingAudio) return null
        return runCatching {
            if (activePlaylistFiles.isNotEmpty()) {
                val windowIdx = controller.currentMediaItemIndex
                val segment = activePlaylistFiles.getOrNull(windowIdx)
                if (segment != null) {
                    segment.startOffsetInBookMs + controller.currentPosition.coerceAtLeast(0L)
                } else {
                    controller.currentPosition.takeIf { it >= 0L }
                }
            } else {
                controller.currentPosition.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    fun release() {
        runCatching {
            pendingActions.clear()
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
            mediaController = null
            currentLoadedBookId = null
        }
    }

    fun extractMetadataFromUri(uri: Uri): ParsedAudioMetadata {
        var displayName = "Аудиокнига"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }
        }

        val extension = displayName.substringAfterLast('.', "").uppercase()
        val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""
        val detectedFormat = when {
            extension in listOf("MP3", "M4A", "M4B", "AMR", "WAV") -> extension
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
            mimeType.contains("m4b") -> "M4B"
            mimeType.contains("mp4") || mimeType.contains("m4a") -> "M4A"
            mimeType.contains("amr") -> "AMR"
            mimeType.contains("wav") -> "WAV"
            else -> "MP3"
        }

        val cleanFileName = displayName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Импортированная книга" }

        var title = cleanFileName
        var author = "Локальный аудиофайл ($detectedFormat)"
        var durationMs = 1_800_000L // fallback 30 min
        var bitrateKbps = 128

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            if (!artist.isNullOrBlank()) {
                author = artist
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { durationMs = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { bitrateKbps = (it / 1000).coerceIn(12, 1411) }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        return ParsedAudioMetadata(
            title = title,
            author = author,
            format = detectedFormat,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps
        )
    }
}
