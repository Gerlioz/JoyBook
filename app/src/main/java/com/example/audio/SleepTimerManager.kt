package com.example.audio

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val lastConfiguredDurationMs: Long = 15 * 60_000L,
    val fadeOutEnabled: Boolean = true,
    val autoRewindMs: Long = 15_000L,
    val lastCompletedAtMillis: Long = 0L
)

/**
 * Manages the sleep timer countdown directly bound to a Media3 [Player] (ExoPlayer) instance
 * inside [JoyBookPlaybackService], ensuring reliable volume fade-out and automatic playback
 * pause even when the application UI is in the background.
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val playerProvider: () -> Player?,
    private val onStateChanged: (SleepTimerState) -> Unit
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    fun startTimer(
        durationMs: Long,
        fadeOutEnabled: Boolean = _state.value.fadeOutEnabled,
        autoRewindMs: Long = _state.value.autoRewindMs
    ) {
        val validDuration = durationMs.coerceAtLeast(1_000L)
        countdownJob?.cancel()

        // Restore full player volume when starting or restarting the sleep timer
        playerProvider()?.volume = 1.0f

        updateState(
            SleepTimerState(
                isActive = true,
                remainingMs = validDuration,
                totalDurationMs = validDuration,
                lastConfiguredDurationMs = validDuration,
                fadeOutEnabled = fadeOutEnabled,
                autoRewindMs = autoRewindMs,
                lastCompletedAtMillis = _state.value.lastCompletedAtMillis
            )
        )

        countdownJob = scope.launch {
            while (isActive && _state.value.remainingMs > 0L) {
                delay(1_000L)
                val nextRemaining = (_state.value.remainingMs - 1_000L).coerceAtLeast(0L)
                val player = playerProvider()

                // Smoothly attenuate ExoPlayer volume over the final 30 seconds
                if (_state.value.fadeOutEnabled && nextRemaining in 1L..FADE_OUT_WINDOW_MS) {
                    val fadeVolume = (nextRemaining.toFloat() / FADE_OUT_WINDOW_MS.toFloat())
                        .coerceIn(MIN_FADE_VOLUME, 1.0f)
                    player?.volume = fadeVolume
                }

                if (nextRemaining == 0L) {
                    onTimerExpired(player)
                    break
                } else {
                    updateState(_state.value.copy(remainingMs = nextRemaining))
                }
            }
        }
    }

    fun extendTimer(additionalMs: Long) {
        val extra = additionalMs.coerceAtLeast(1_000L)
        val current = _state.value
        if (!current.isActive || current.remainingMs <= 0L) {
            startTimer(extra, current.fadeOutEnabled, current.autoRewindMs)
            return
        }
        playerProvider()?.volume = 1.0f
        val newRemaining = current.remainingMs + extra
        val newTotal = maxOf(current.totalDurationMs, newRemaining)
        updateState(
            current.copy(
                remainingMs = newRemaining,
                totalDurationMs = newTotal,
                lastConfiguredDurationMs = newTotal
            )
        )
    }

    /**
     * Resets the sleep timer back to its full configured duration and resumes playback if paused
     * when the user shakes the phone.
     */
    fun resetAndResumeOnShake(): Boolean {
        val current = _state.value
        val targetDuration = current.totalDurationMs
            .takeIf { it > 0L }
            ?: current.lastConfiguredDurationMs.takeIf { it > 0L }
            ?: (15 * 60_000L)

        startTimer(
            durationMs = targetDuration,
            fadeOutEnabled = current.fadeOutEnabled,
            autoRewindMs = current.autoRewindMs
        )
        playerProvider()?.let { player ->
            player.volume = 1.0f
            if (!player.isPlaying && player.mediaItemCount > 0) {
                player.play()
            }
        }
        return true
    }

    fun setFadeOutEnabled(enabled: Boolean) {
        val current = _state.value
        if (!enabled) {
            playerProvider()?.volume = 1.0f
        }
        updateState(current.copy(fadeOutEnabled = enabled))
    }

    fun cancelTimer() {
        countdownJob?.cancel()
        countdownJob = null
        playerProvider()?.volume = 1.0f
        updateState(
            _state.value.copy(
                isActive = false,
                remainingMs = 0L,
                totalDurationMs = 0L
            )
        )
    }

    private fun onTimerExpired(player: Player?) {
        countdownJob = null
        val rewindMs = _state.value.autoRewindMs
        player?.let { exoPlayer ->
            if (exoPlayer.isPlaying || exoPlayer.playWhenReady) {
                exoPlayer.pause()
            }
            if (rewindMs > 0L && exoPlayer.currentPosition > 0L) {
                val targetPos = (exoPlayer.currentPosition - rewindMs).coerceAtLeast(0L)
                exoPlayer.seekTo(targetPos)
            }
            exoPlayer.volume = 1.0f
        }

        updateState(
            _state.value.copy(
                isActive = false,
                remainingMs = 0L,
                totalDurationMs = 0L,
                lastCompletedAtMillis = System.currentTimeMillis()
            )
        )
    }

    private fun updateState(newState: SleepTimerState) {
        _state.value = newState
        onStateChanged(newState)
    }

    companion object {
        const val FADE_OUT_WINDOW_MS = 30_000L
        const val MIN_FADE_VOLUME = 0.08f
    }
}
