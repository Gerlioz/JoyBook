package com.example.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.R
import com.example.data.local.JoyBookDatabase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class JoyBookPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var voiceBoostEnabled: Boolean = false
    private lateinit var sleepTimerManager: SleepTimerManager
    private val amplitudeProcessor = Media3AmplitudeAudioProcessor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundProgressSyncJob: Job? = null
    private var activeBookId: Long = -1L
    private var activeCoverImagePath: String? = null
    private var activeCoverArtStyle: Int = 0
    private var segmentStartOffsetsMs: LongArray = LongArray(0)
    private var chapterStartOffsetsMs: LongArray = LongArray(0)

    private val rewindPlayerSessionCommand = SessionCommand(COMMAND_REWIND_NOTIFICATION, Bundle.EMPTY)
    private val closePlayerSessionCommand = SessionCommand(COMMAND_CLOSE_PLAYER, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()

        ensureNotificationChannel()

        val notificationProvider = object : DefaultMediaNotificationProvider(
            applicationContext,
            { NOTIFICATION_ID },
            NOTIFICATION_CHANNEL_ID,
            R.string.app_name
        ) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                // Force only the 3 core transport buttons from super (Previous Chapter, Play/Pause, Next Chapter)
                val forcedCommands = Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    )
                    .build()

                val coreTransportButtons = super.getMediaButtons(
                    session,
                    forcedCommands,
                    ImmutableList.of(),
                    showPauseButton
                )

                // Symmetric 5-button layout: [Rewind -15s, Prev Chapter, Play/Pause, Next Chapter, Close Player]
                // Compact view uses the 3 core transport buttons (indices 1, 2, 3) so Play/Pause is strictly centered
                // in both compact and expanded notification shade views.
                return ImmutableList.builder<CommandButton>()
                    .add(buildRewindCommandButton())
                    .addAll(coreTransportButtons)
                    .add(buildCloseCommandButton())
                    .build()
            }
        }.apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
        }
        setMediaNotificationProvider(notificationProvider)

        // Configure extractors for accurate seeking across MP3, M4A, M4B, AMR, and WAV formats
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        // Deep 3-minute RAM preload buffer so CPU Doze or storage latency never interrupts audio
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 1_200,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(amplitudeProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setPauseAtEndOfMediaItems(false)
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    acquirePlaybackWakeLock()
                    startBackgroundProgressPersistLoop()
                } else {
                    RealtimeAudioSpectrumBus.reset()
                    persistCurrentPositionToDatabase()
                    if (player.playbackState != Player.STATE_BUFFERING && !player.playWhenReady) {
                        releasePlaybackWakeLock()
                    }
                    backgroundProgressSyncJob?.cancel()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                persistCurrentPositionToDatabase()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    persistCurrentPositionToDatabase()
                    releasePlaybackWakeLock()
                }
            }
        })

        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            setupLoudnessEnhancer(player.audioSessionId)
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        exoPlayer = player

        sleepTimerManager = SleepTimerManager(
            scope = serviceScope,
            playerProvider = { exoPlayer },
            onStateChanged = { timerState ->
                mediaSession?.setSessionExtras(buildSleepExtrasBundle(timerState))
            }
        )

        // Wrap ExoPlayer in a ForwardingPlayer that keeps Next/Previous chapter actions permanently enabled
        // and routes notification chapter skip buttons through our chapter offset table
        val forwardingPlayer = JoyBookNotificationPlayer(player)

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(JoyBookSessionCallback())
            .setBitmapLoader(JoyBookBitmapLoader())
            .setCustomLayout(
                ImmutableList.of(
                    buildRewindCommandButton(),
                    buildCloseCommandButton()
                )
            )
            .setSessionExtras(buildSleepExtrasBundle(sleepTimerManager.state.value))
            .build()
    }

    private fun buildRewindCommandButton(): CommandButton {
        return CommandButton.Builder()
            .setDisplayName("Назад 15 сек")
            .setSessionCommand(rewindPlayerSessionCommand)
            .setIconResId(R.drawable.ic_notification_rewind)
            .build()
    }

    private fun buildCloseCommandButton(): CommandButton {
        return CommandButton.Builder()
            .setDisplayName("Закрыть плеер")
            .setSessionCommand(closePlayerSessionCommand)
            .setIconResId(R.drawable.ic_notification_close)
            .build()
    }

    private inner class JoyBookBitmapLoader : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            val bmp = runCatching {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }.getOrNull() ?: loadActiveBookFallbackBitmap()
            return Futures.immediateFuture(bmp)
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            val bmp = runCatching {
                if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    BitmapFactory.decodeFile(uri.path)
                } else {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }.getOrNull() ?: loadActiveBookFallbackBitmap()
            return Futures.immediateFuture(bmp)
        }

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
            val data = metadata.artworkData
            if (data != null && data.isNotEmpty()) {
                val decoded = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
                if (decoded != null) {
                    return Futures.immediateFuture(decoded)
                }
            }
            val uri = metadata.artworkUri
            if (uri != null) {
                return loadBitmap(uri)
            }
            return Futures.immediateFuture(loadActiveBookFallbackBitmap())
        }

        private fun loadActiveBookFallbackBitmap(): Bitmap {
            val path = activeCoverImagePath
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists() && file.length() > 0L) {
                    val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    if (decoded != null) return decoded
                }
            }
            return AudioPlayerController.generateProceduralNotificationArtwork(activeCoverArtStyle)
        }
    }

    private inner class JoyBookNotificationPlayer(
        delegate: ExoPlayer
    ) : ForwardingPlayer(delegate) {

        private fun augmentCommands(base: Player.Commands): Player.Commands {
            return base.buildUpon()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_STOP
                )
                .build()
        }

        override fun getAvailableCommands(): Player.Commands {
            return augmentCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (
                command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                command == Player.COMMAND_PLAY_PAUSE ||
                command == Player.COMMAND_STOP
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }

        override fun seekToNext() {
            skipToNextChapterFromNotification()
        }

        override fun seekToNextMediaItem() {
            skipToNextChapterFromNotification()
        }

        override fun seekToPrevious() {
            skipToPreviousChapterFromNotification()
        }

        override fun seekToPreviousMediaItem() {
            skipToPreviousChapterFromNotification()
        }
    }

    private fun getCurrentTotalBookPositionMs(): Long {
        val player = exoPlayer ?: return 0L
        val windowIdx = player.currentMediaItemIndex
        val posInItem = player.currentPosition.coerceAtLeast(0L)
        val segmentOffset = if (windowIdx in segmentStartOffsetsMs.indices) {
            segmentStartOffsetsMs[windowIdx]
        } else {
            0L
        }
        return segmentOffset + posInItem
    }

    private fun seekToTotalBookPositionMs(bookPositionMs: Long) {
        val player = exoPlayer ?: return
        val clampedPos = bookPositionMs.coerceAtLeast(0L)
        if (segmentStartOffsetsMs.size > 1 && player.mediaItemCount > 1) {
            val targetIdx = segmentStartOffsetsMs.indexOfLast { clampedPos >= it }
                .coerceIn(0, player.mediaItemCount - 1)
            val offsetInSegment = (clampedPos - segmentStartOffsetsMs[targetIdx]).coerceAtLeast(0L)
            player.seekTo(targetIdx, offsetInSegment)
        } else {
            val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            player.seekTo(clampedPos.coerceIn(0L, duration))
        }
        persistCurrentPositionToDatabase()
    }

    private fun skipToNextChapterFromNotification() {
        val player = exoPlayer ?: return
        val currentBookPosMs = getCurrentTotalBookPositionMs()
        val chapters = chapterStartOffsetsMs
        if (chapters.size > 1) {
            val nextChapterStart = chapters.firstOrNull { it > currentBookPosMs + 1_500L }
            if (nextChapterStart != null) {
                seekToTotalBookPositionMs(nextChapterStart)
                return
            }
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            persistCurrentPositionToDatabase()
        } else {
            // At the final chapter: step forward by seekForwardIncrement so the button remains responsive
            val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val nextPos = (player.currentPosition + player.seekForwardIncrement).coerceAtMost(duration)
            player.seekTo(nextPos)
            persistCurrentPositionToDatabase()
        }
    }

    private fun skipToPreviousChapterFromNotification() {
        val player = exoPlayer ?: return
        val currentBookPosMs = getCurrentTotalBookPositionMs()
        val chapters = chapterStartOffsetsMs
        if (chapters.isNotEmpty()) {
            val currentIdx = chapters.indexOfLast { currentBookPosMs >= it }.coerceAtLeast(0)
            val currentChapterStart = chapters[currentIdx]
            val targetStart = if (currentBookPosMs - currentChapterStart > 4_000L || currentIdx == 0) {
                currentChapterStart
            } else {
                chapters[currentIdx - 1]
            }
            seekToTotalBookPositionMs(targetStart)
            return
        }
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            persistCurrentPositionToDatabase()
        } else {
            player.seekTo(0L)
            persistCurrentPositionToDatabase()
        }
    }

    private fun loadChaptersFromDatabaseIfNeeded(bookId: Long) {
        if (bookId <= 0L) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = JoyBookDatabase.getInstance(applicationContext).dao()
                val dbChapters = dao.getChaptersForBookOnce(bookId)
                if (dbChapters.isNotEmpty()) {
                    val offsets = dbChapters.map { it.startTimeMs }.sorted().distinct().toLongArray()
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        if (activeBookId == bookId && chapterStartOffsetsMs.isEmpty()) {
                            chapterStartOffsetsMs = offsets
                        }
                    }
                }
            }
        }
    }

    private fun closePlayerAndDismissNotification() {
        persistCurrentPositionToDatabase()
        backgroundProgressSyncJob?.cancel()
        if (::sleepTimerManager.isInitialized) {
            sleepTimerManager.cancelTimer()
        }
        RealtimeAudioSpectrumBus.reset()
        releasePlaybackWakeLock()

        runCatching {
            val closedExtras = buildSleepExtrasBundle(sleepTimerManager.state.value).apply {
                putBoolean(EXTRA_PLAYER_CLOSED, true)
                putLong(EXTRA_PLAYER_CLOSED_AT, System.currentTimeMillis())
            }
            mediaSession?.setSessionExtras(closedExtras)
        }

        runCatching {
            exoPlayer?.playWhenReady = false
            exoPlayer?.pause()
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }

        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        }

        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_PLAYER) {
            closePlayerAndDismissNotification()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        if (intent != null) {
            val passedBookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
            if (passedBookId > 0L) {
                activeBookId = passedBookId
                loadChaptersFromDatabaseIfNeeded(passedBookId)
            }
            val offsets = intent.getLongArrayExtra(EXTRA_SEGMENT_OFFSETS_MS)
            if (offsets != null) {
                segmentStartOffsetsMs = offsets
            }
            val chapterOffsets = intent.getLongArrayExtra(EXTRA_CHAPTER_OFFSETS_MS)
            if (chapterOffsets != null && chapterOffsets.isNotEmpty()) {
                chapterStartOffsetsMs = chapterOffsets
            }
        }
        // Keep the symmetric custom layout attached to the session
        mediaSession?.setCustomLayout(
            ImmutableList.of(
                buildRewindCommandButton(),
                buildCloseCommandButton()
            )
        )
        // Return START_STICKY so Android OS keeps the service alive in background memory
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Keep playing uninterrupted if user swipes the Activity from Recents while audio is active
        if (player != null && (player.isPlaying || player.playWhenReady) && player.mediaItemCount > 0) {
            persistCurrentPositionToDatabase()
            return
        }
        persistCurrentPositionToDatabase()
        releasePlaybackWakeLock()
        stopSelf()
    }

    override fun onTrimMemory(level: Int) {
        // Persist current playback position whenever the OS signals memory pressure
        persistCurrentPositionToDatabase()
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        persistCurrentPositionToDatabase()
        backgroundProgressSyncJob?.cancel()
        releasePlaybackWakeLock()
        if (::sleepTimerManager.isInitialized) {
            sleepTimerManager.cancelTimer()
        }
        serviceScope.cancel()
        runCatching {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Воспроизведение аудиокниг JoyBook",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Фоновое воспроизведение аудиокниг и управление на экране блокировки"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }
    }

    private fun acquirePlaybackWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "JoyBook::AudioPlaybackWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Hold partial CPU wake lock up to 6 hours per continuous play session
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releasePlaybackWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun startBackgroundProgressPersistLoop() {
        backgroundProgressSyncJob?.cancel()
        backgroundProgressSyncJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                persistCurrentPositionToDatabase()
            }
        }
    }

    private fun persistCurrentPositionToDatabase() {
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0) return

        // Resolve book ID either from activeBookId or from MediaItem.mediaId
        val currentMediaId = player.currentMediaItem?.mediaId.orEmpty()
        val resolvedBookId = if (activeBookId > 0L) {
            activeBookId
        } else {
            currentMediaId.substringBefore("_file_").toLongOrNull() ?: -1L
        }
        if (resolvedBookId <= 0L) return

        val totalBookPositionMs = getCurrentTotalBookPositionMs()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = JoyBookDatabase.getInstance(applicationContext).dao()
                dao.updateProgress(
                    id = resolvedBookId,
                    positionMs = totalBookPositionMs,
                    timestamp = System.currentTimeMillis()
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildSleepExtrasBundle(state: SleepTimerState): Bundle {
        return Bundle().apply {
            putBoolean(EXTRA_SLEEP_ACTIVE, state.isActive)
            putLong(EXTRA_SLEEP_REMAINING_MS, state.remainingMs)
            putLong(EXTRA_SLEEP_TOTAL_MS, state.totalDurationMs)
            putLong(EXTRA_SLEEP_COMPLETED_AT, state.lastCompletedAtMillis)
        }
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        runCatching {
            loudnessEnhancer?.release()
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(if (voiceBoostEnabled) 750 else 0)
                    enabled = voiceBoostEnabled
                }
            }
        }
    }

    private fun applyVoiceBoost(enabled: Boolean) {
        voiceBoostEnabled = enabled
        runCatching {
            val sessionId = exoPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            if (loudnessEnhancer == null && sessionId != C.AUDIO_SESSION_ID_UNSET) {
                setupLoudnessEnhancer(sessionId)
            } else {
                loudnessEnhancer?.setTargetGain(if (enabled) 750 else 0)
                loudnessEnhancer?.enabled = enabled
            }
        }
    }

    private inner class JoyBookSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_SET_VOICE_BOOST, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_PLAYBACK_SPEED, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_START_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_SLEEP_FADE_OUT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SYNC_BOOK_CONTEXT, Bundle.EMPTY))
                .add(rewindPlayerSessionCommand)
                .add(closePlayerSessionCommand)
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_STOP
                )
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(
                    ImmutableList.of(
                        buildRewindCommandButton(),
                        buildCloseCommandButton()
                    )
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_REWIND_NOTIFICATION -> {
                    val player = exoPlayer
                    if (player != null) {
                        val nextPos = (getCurrentTotalBookPositionMs() - player.seekBackIncrement).coerceAtLeast(0L)
                        seekToTotalBookPositionMs(nextPos)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_CLOSE_PLAYER -> {
                    closePlayerAndDismissNotification()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SYNC_BOOK_CONTEXT -> {
                    val bookId = args.getLong(EXTRA_BOOK_ID, -1L)
                    if (bookId > 0L) activeBookId = bookId
                    val coverPath = args.getString(EXTRA_COVER_IMAGE_PATH)
                    if (!coverPath.isNullOrBlank()) {
                        activeCoverImagePath = coverPath
                    }
                    activeCoverArtStyle = args.getInt(EXTRA_COVER_ART_STYLE, activeCoverArtStyle)
                    val offsets = args.getLongArray(EXTRA_SEGMENT_OFFSETS_MS)
                    segmentStartOffsetsMs = offsets ?: LongArray(0)
                    val chapterOffsets = args.getLongArray(EXTRA_CHAPTER_OFFSETS_MS)
                    if (chapterOffsets != null && chapterOffsets.isNotEmpty()) {
                        chapterStartOffsetsMs = chapterOffsets
                    } else if (bookId > 0L) {
                        loadChaptersFromDatabaseIfNeeded(bookId)
                    }
                    session.setCustomLayout(
                        ImmutableList.of(
                            buildRewindCommandButton(),
                            buildCloseCommandButton()
                        )
                    )
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SET_VOICE_BOOST -> {
                    val enabled = args.getBoolean(EXTRA_VOICE_BOOST_ENABLED, false)
                    applyVoiceBoost(enabled)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SET_PLAYBACK_SPEED -> {
                    val requestedSpeed = args.getFloat(EXTRA_PLAYBACK_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
                    exoPlayer?.setPlaybackParameters(PlaybackParameters(requestedSpeed, 1.0f))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_START_SLEEP_TIMER -> {
                    val durationMs = args.getLong(EXTRA_SLEEP_DURATION_MS, 0L)
                    val fadeOut = args.getBoolean(EXTRA_SLEEP_FADE_OUT, true)
                    val autoRewindMs = args.getLong(EXTRA_SLEEP_AUTO_REWIND_MS, 15_000L)
                    sleepTimerManager.startTimer(
                        durationMs = durationMs,
                        fadeOutEnabled = fadeOut,
                        autoRewindMs = autoRewindMs
                    )
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            buildSleepExtrasBundle(sleepTimerManager.state.value)
                        )
                    )
                }

                COMMAND_EXTEND_SLEEP_TIMER -> {
                    val additionalMs = args.getLong(EXTRA_SLEEP_DURATION_MS, 5 * 60_000L)
                    sleepTimerManager.extendTimer(additionalMs)
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            buildSleepExtrasBundle(sleepTimerManager.state.value)
                        )
                    )
                }

                COMMAND_CANCEL_SLEEP_TIMER -> {
                    sleepTimerManager.cancelTimer()
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            buildSleepExtrasBundle(sleepTimerManager.state.value)
                        )
                    )
                }

                COMMAND_SET_SLEEP_FADE_OUT -> {
                    val enabled = args.getBoolean(EXTRA_SLEEP_FADE_OUT, true)
                    sleepTimerManager.setFadeOutEnabled(enabled)
                    return Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            buildSleepExtrasBundle(sleepTimerManager.state.value)
                        )
                    )
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "joybook_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_PLAYBACK = "com.joybook.app.ACTION_START_PLAYBACK"
        const val ACTION_CLOSE_PLAYER = "com.joybook.app.ACTION_CLOSE_PLAYER"
        const val COMMAND_REWIND_NOTIFICATION = "com.joybook.app.COMMAND_REWIND_NOTIFICATION"
        const val COMMAND_CLOSE_PLAYER = "com.joybook.app.COMMAND_CLOSE_PLAYER"
        const val COMMAND_SYNC_BOOK_CONTEXT = "com.joybook.app.COMMAND_SYNC_BOOK_CONTEXT"
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_COVER_IMAGE_PATH = "extra_cover_image_path"
        const val EXTRA_COVER_ART_STYLE = "extra_cover_art_style"
        const val EXTRA_SEGMENT_OFFSETS_MS = "extra_segment_offsets_ms"
        const val EXTRA_CHAPTER_OFFSETS_MS = "extra_chapter_offsets_ms"

        const val COMMAND_SET_VOICE_BOOST = "com.joybook.app.COMMAND_SET_VOICE_BOOST"
        const val EXTRA_VOICE_BOOST_ENABLED = "extra_voice_boost_enabled"

        const val COMMAND_SET_PLAYBACK_SPEED = "com.joybook.app.COMMAND_SET_PLAYBACK_SPEED"
        const val EXTRA_PLAYBACK_SPEED = "extra_playback_speed"

        const val COMMAND_START_SLEEP_TIMER = "com.joybook.app.COMMAND_START_SLEEP_TIMER"
        const val COMMAND_EXTEND_SLEEP_TIMER = "com.joybook.app.COMMAND_EXTEND_SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER = "com.joybook.app.COMMAND_CANCEL_SLEEP_TIMER"
        const val COMMAND_SET_SLEEP_FADE_OUT = "com.joybook.app.COMMAND_SET_SLEEP_FADE_OUT"

        const val EXTRA_SLEEP_DURATION_MS = "extra_sleep_duration_ms"
        const val EXTRA_SLEEP_FADE_OUT = "extra_sleep_fade_out"
        const val EXTRA_SLEEP_AUTO_REWIND_MS = "extra_sleep_auto_rewind_ms"

        const val EXTRA_SLEEP_ACTIVE = "extra_sleep_active"
        const val EXTRA_SLEEP_REMAINING_MS = "extra_sleep_remaining_ms"
        const val EXTRA_SLEEP_TOTAL_MS = "extra_sleep_total_ms"
        const val EXTRA_SLEEP_COMPLETED_AT = "extra_sleep_completed_at"
        const val EXTRA_PLAYER_CLOSED = "extra_player_closed"
        const val EXTRA_PLAYER_CLOSED_AT = "extra_player_closed_at"
    }
}
