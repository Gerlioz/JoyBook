package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "joybook_user_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val REWIND_SECONDS = intPreferencesKey("rewind_seconds")
        val FORWARD_SECONDS = intPreferencesKey("forward_seconds")
        val BUTTON_SIZE = stringPreferencesKey("button_size")
        val BUTTON_SHAPE = stringPreferencesKey("button_shape")
        val SHOW_CHAPTER_SKIP = booleanPreferencesKey("show_chapter_skip")
        val SHOW_SPEED_BTN = booleanPreferencesKey("show_speed_btn")
        val SHOW_BOOKMARK_BTN = booleanPreferencesKey("show_bookmark_btn")
        val SHOW_SLEEP_BTN = booleanPreferencesKey("show_sleep_btn")
        val SHOW_VOICE_BOOST_BTN = booleanPreferencesKey("show_voice_boost_btn")
        val SWAP_SEEK_BUTTONS = booleanPreferencesKey("swap_seek_buttons")
        val COVER_FRAME_STYLE = stringPreferencesKey("cover_frame_style")
        val COVER_BLUR_GLOW = booleanPreferencesKey("cover_blur_glow")
        val PROGRESS_BAR_STYLE = stringPreferencesKey("progress_bar_style")
        val LIBRARY_LAYOUT_MODE = stringPreferencesKey("library_layout_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val SHOW_FORMAT_BADGES = booleanPreferencesKey("show_format_badges")
        val SHOW_CHAPTER_REMAINING = booleanPreferencesKey("show_chapter_remaining")
        val SLEEP_TIMER_FADE_OUT = booleanPreferencesKey("sleep_timer_fade_out")
        val AUTO_REWIND_ON_SLEEP = intPreferencesKey("auto_rewind_on_sleep")
        val SHAKE_TO_RESUME_SLEEP = booleanPreferencesKey("shake_to_resume_sleep")
        val SHAKE_SENSITIVITY = stringPreferencesKey("shake_sensitivity")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK,
            accentPalette = prefs[Keys.ACCENT_PALETTE]?.let {
                runCatching { AccentPalette.valueOf(it) }.getOrNull()
            } ?: AccentPalette.AMBER,
            rewindSeconds = prefs[Keys.REWIND_SECONDS] ?: 15,
            forwardSeconds = prefs[Keys.FORWARD_SECONDS] ?: 30,
            controlButtonSize = prefs[Keys.BUTTON_SIZE]?.let {
                runCatching { ControlButtonSize.valueOf(it) }.getOrNull()
            } ?: ControlButtonSize.STANDARD,
            controlButtonShape = prefs[Keys.BUTTON_SHAPE]?.let {
                runCatching { ControlButtonShape.valueOf(it) }.getOrNull()
            } ?: ControlButtonShape.CIRCLE,
            showChapterSkipButtons = prefs[Keys.SHOW_CHAPTER_SKIP] ?: true,
            showSpeedButtonOnPlayer = prefs[Keys.SHOW_SPEED_BTN] ?: true,
            showBookmarkButtonOnPlayer = prefs[Keys.SHOW_BOOKMARK_BTN] ?: true,
            showSleepTimerButtonOnPlayer = prefs[Keys.SHOW_SLEEP_BTN] ?: true,
            showVoiceBoostButtonOnPlayer = prefs[Keys.SHOW_VOICE_BOOST_BTN] ?: true,
            swapSeekButtons = prefs[Keys.SWAP_SEEK_BUTTONS] ?: false,
            coverFrameStyle = prefs[Keys.COVER_FRAME_STYLE]?.let {
                runCatching { CoverFrameStyle.valueOf(it) }.getOrNull()
            } ?: CoverFrameStyle.ROUNDED_SQUARE,
            coverBlurGlowEnabled = prefs[Keys.COVER_BLUR_GLOW] ?: true,
            progressBarStyle = prefs[Keys.PROGRESS_BAR_STYLE]?.let {
                runCatching { ProgressBarStyle.valueOf(it) }.getOrNull()
            } ?: ProgressBarStyle.WAVEFORM,
            libraryLayoutMode = prefs[Keys.LIBRARY_LAYOUT_MODE]?.let {
                runCatching { LibraryLayoutMode.valueOf(it) }.getOrNull()
            } ?: LibraryLayoutMode.DETAILED_LIST,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            showFormatBadges = prefs[Keys.SHOW_FORMAT_BADGES] ?: true,
            showChapterTimeRemaining = prefs[Keys.SHOW_CHAPTER_REMAINING] ?: true,
            sleepTimerFadeOut = prefs[Keys.SLEEP_TIMER_FADE_OUT] ?: true,
            autoRewindOnSleepSec = prefs[Keys.AUTO_REWIND_ON_SLEEP] ?: 15,
            shakeToResumeSleepEnabled = prefs[Keys.SHAKE_TO_RESUME_SLEEP] ?: true,
            shakeSensitivity = prefs[Keys.SHAKE_SENSITIVITY]?.let {
                runCatching { ShakeSensitivity.valueOf(it) }.getOrNull()
            } ?: ShakeSensitivity.MEDIUM
        )
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateAccentPalette(palette: AccentPalette) {
        context.dataStore.edit { it[Keys.ACCENT_PALETTE] = palette.name }
    }

    suspend fun updateRewindSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.REWIND_SECONDS] = seconds }
    }

    suspend fun updateForwardSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.FORWARD_SECONDS] = seconds }
    }

    suspend fun updateButtonSize(size: ControlButtonSize) {
        context.dataStore.edit { it[Keys.BUTTON_SIZE] = size.name }
    }

    suspend fun updateButtonShape(shape: ControlButtonShape) {
        context.dataStore.edit { it[Keys.BUTTON_SHAPE] = shape.name }
    }

    suspend fun updateShowChapterSkip(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_CHAPTER_SKIP] = show }
    }

    suspend fun updateShowSpeedButton(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SPEED_BTN] = show }
    }

    suspend fun updateShowBookmarkButton(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_BOOKMARK_BTN] = show }
    }

    suspend fun updateShowSleepButton(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SLEEP_BTN] = show }
    }

    suspend fun updateShowVoiceBoostButton(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_VOICE_BOOST_BTN] = show }
    }

    suspend fun updateSwapSeekButtons(swap: Boolean) {
        context.dataStore.edit { it[Keys.SWAP_SEEK_BUTTONS] = swap }
    }

    suspend fun updateCoverFrameStyle(style: CoverFrameStyle) {
        context.dataStore.edit { it[Keys.COVER_FRAME_STYLE] = style.name }
    }

    suspend fun updateCoverBlurGlow(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COVER_BLUR_GLOW] = enabled }
    }

    suspend fun updateProgressBarStyle(style: ProgressBarStyle) {
        context.dataStore.edit { it[Keys.PROGRESS_BAR_STYLE] = style.name }
    }

    suspend fun updateLibraryLayoutMode(mode: LibraryLayoutMode) {
        context.dataStore.edit { it[Keys.LIBRARY_LAYOUT_MODE] = mode.name }
    }

    suspend fun updateFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun updateShowFormatBadges(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_FORMAT_BADGES] = show }
    }

    suspend fun updateShowChapterRemaining(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_CHAPTER_REMAINING] = show }
    }

    suspend fun updateSleepTimerFadeOut(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_TIMER_FADE_OUT] = enabled }
    }

    suspend fun updateShakeToResumeSleep(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHAKE_TO_RESUME_SLEEP] = enabled }
    }

    suspend fun updateShakeSensitivity(sensitivity: ShakeSensitivity) {
        context.dataStore.edit { it[Keys.SHAKE_SENSITIVITY] = sensitivity.name }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}
