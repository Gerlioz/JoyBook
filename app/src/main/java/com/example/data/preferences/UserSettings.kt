package com.example.data.preferences

enum class ThemeMode(val label: String, val subtitle: String) {
    SYSTEM("Системная", "Следует настройкам устройства"),
    LIGHT("Светлая (Бумага)", "Тёплый бумажный оттенок для чтения днём"),
    DARK("Тёмная (Полночь)", "Мягкий тёмно-синий графит для вечера"),
    AMOLED("AMOLED Чёрная", "Абсолютно чёрный #000000 для OLED-экранов")
}

enum class AccentPalette(
    val label: String,
    val primaryHex: Long,
    val secondaryHex: Long
) {
    AMBER("Янтарный Уют", 0xFFFFB74D, 0xFFFF8A65),
    EMERALD("Изумрудный Лес", 0xFF4DB6AC, 0xFF81C784),
    INDIGO("Космический Индиго", 0xFF9575CD, 0xFF7986CB),
    ROSE("Малиновый Закат", 0xFFF06292, 0xFFFF8A80),
    CYAN("Океанический Бриз", 0xFF4DD0E1, 0xFF64B5F6)
}

enum class CoverFrameStyle(val label: String) {
    ROUNDED_SQUARE("Скруглённый квадрат"),
    VINYL_DISC("Виниловый диск (вращение)"),
    BOOK_SPINE("Классический переплёт"),
    CIRCLE("Минималистичный круг"),
    CD_CASE("Прозрачный кейс компакт-диска")
}

enum class ShakeSensitivity(val label: String, val thresholdG: Float) {
    LOW("Слабый", 1.35f),
    MEDIUM("Умеренно", 1.80f),
    HIGH("Сильно", 2.45f)
}

enum class ProgressBarStyle(val label: String) {
    WAVEFORM("Анимированная звуковая волна"),
    CLASSIC_LINE("Классическая гладкая линия"),
    CHAPTER_SEGMENTS("Сегменты по главам")
}

enum class LibraryLayoutMode(val label: String) {
    GRID("Сетка обложек (2 колонки)"),
    DETAILED_LIST("Подробные карточки"),
    COMPACT_LIST("Компактный список")
}

enum class ControlButtonSize(val label: String, val mainButtonDp: Int, val secondaryButtonDp: Int) {
    COMPACT("Компактные", 60, 48),
    STANDARD("Стандартные", 72, 54),
    LARGE("Крупные (Авто / Ночь)", 84, 62)
}

enum class ControlButtonShape(val label: String, val cornerPercent: Int) {
    CIRCLE("Круглые (Пилюля)", 50),
    SOFT_SQUARE("Мягкий квадрат", 26),
    SQUIRCLE("Классический блок", 16)
}

data class UserSettings(
    // Themes
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPalette: AccentPalette = AccentPalette.AMBER,
    // Controls customization
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val controlButtonSize: ControlButtonSize = ControlButtonSize.STANDARD,
    val controlButtonShape: ControlButtonShape = ControlButtonShape.CIRCLE,
    val showChapterSkipButtons: Boolean = true,
    val showSpeedButtonOnPlayer: Boolean = true,
    val showBookmarkButtonOnPlayer: Boolean = true,
    val showSleepTimerButtonOnPlayer: Boolean = true,
    val showVoiceBoostButtonOnPlayer: Boolean = true,
    val swapSeekButtons: Boolean = false,
    // Interface customization
    val coverFrameStyle: CoverFrameStyle = CoverFrameStyle.ROUNDED_SQUARE,
    val coverBlurGlowEnabled: Boolean = true,
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.WAVEFORM,
    val libraryLayoutMode: LibraryLayoutMode = LibraryLayoutMode.DETAILED_LIST,
    val fontScale: Float = 1.0f,
    val showFormatBadges: Boolean = true,
    val showChapterTimeRemaining: Boolean = true,
    // Sleep timer options
    val sleepTimerFadeOut: Boolean = true,
    val autoRewindOnSleepSec: Int = 15,
    val shakeToResumeSleepEnabled: Boolean = true,
    val shakeSensitivity: ShakeSensitivity = ShakeSensitivity.MEDIUM
)
