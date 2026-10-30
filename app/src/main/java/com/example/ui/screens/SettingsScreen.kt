package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.preferences.AccentPalette
import com.example.data.preferences.ControlButtonShape
import com.example.data.preferences.ControlButtonSize
import com.example.data.preferences.CoverFrameStyle
import com.example.data.preferences.LibraryLayoutMode
import com.example.data.preferences.ProgressBarStyle
import com.example.data.preferences.ShakeSensitivity
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.UserSettings
import com.example.ui.theme.LocalIsAmoledTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    libraryBookCount: Int = 0,
    isIgnoringBatteryOptimizations: Boolean = false,
    onRequestIgnoreBatteryOptimizations: () -> Unit = {},
    onBackToLibrary: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentPaletteChange: (AccentPalette) -> Unit,
    onRewindSecondsChange: (Int) -> Unit,
    onForwardSecondsChange: (Int) -> Unit,
    onButtonSizeChange: (ControlButtonSize) -> Unit,
    onButtonShapeChange: (ControlButtonShape) -> Unit,
    onShowChapterSkipChange: (Boolean) -> Unit,
    onSwapSeekButtonsChange: (Boolean) -> Unit,
    onShowSpeedButtonChange: (Boolean) -> Unit,
    onShowSleepButtonChange: (Boolean) -> Unit,
    onShowBookmarkButtonChange: (Boolean) -> Unit,
    onShowVoiceBoostButtonChange: (Boolean) -> Unit,
    onCoverFrameStyleChange: (CoverFrameStyle) -> Unit,
    onCoverBlurGlowChange: (Boolean) -> Unit = {},
    onProgressBarStyleChange: (ProgressBarStyle) -> Unit,
    onLibraryLayoutModeChange: (LibraryLayoutMode) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onShowFormatBadgesChange: (Boolean) -> Unit,
    onShowChapterRemainingChange: (Boolean) -> Unit,
    onShakeToResumeSleepChange: (Boolean) -> Unit = {},
    onShakeSensitivityChange: (ShakeSensitivity) -> Unit = {},
    onClearLibrary: () -> Unit = {},
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackToLibrary)
    val isAmoled = LocalIsAmoledTheme.current
    val cardBorder = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    var showClearLibraryDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        item {
            Column {
                Text(
                    text = "Настройки JoyBook",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Фоновый режим без выгрузки, темы (включая AMOLED), кнопки и интерфейс",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // SECTION 0: ФОНОВЫЙ РЕЖИМ И ЗАЩИТА ОТ ВЫГРУЗКИ ИЗ ПАМЯТИ
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("background_optimization_card")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Фоновый режим и защита от выгрузки",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Непрерывное воспроизведение при выключенном экране и в фоне",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isIgnoringBatteryOptimizations) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isIgnoringBatteryOptimizations) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.BatteryChargingFull
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isIgnoringBatteryOptimizations) {
                                        "Работа в фоне без ограничений активна"
                                    } else {
                                        "Защита от энергосбережения системы"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = "• Приоритетный сервис START_STICKY + MediaSession\n" +
                                    "• Удержание процессора (Partial WakeLock) при чтении\n" +
                                    "• 3-минутный RAM-буфер предзагрузки аудио и автосохранение каждые 5 сек",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!isIgnoringBatteryOptimizations) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = onRequestIgnoreBatteryOptimizations,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ignore_battery_optimizations_button")
                                ) {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Отключить ограничения фона для JoyBook")
                                }
                            }
                        }
                    }
                }
            }
        }

        // SECTION 1: ТЕМЫ ОФОРМЛЕНИЯ (Light, Dark, True AMOLED, System + Accent Palettes)
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Темы оформления",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    ThemeMode.entries.forEach { mode ->
                        val isSelected = settings.themeMode == mode
                        val modeIcon = when (mode) {
                            ThemeMode.LIGHT -> Icons.Default.LightMode
                            ThemeMode.DARK -> Icons.Default.DarkMode
                            ThemeMode.AMOLED -> Icons.Default.Contrast
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                        }

                        Surface(
                            onClick = { onThemeModeChange(mode) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = if (mode == ThemeMode.AMOLED) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("theme_mode_${mode.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (mode) {
                                                    ThemeMode.AMOLED -> Color.Black
                                                    ThemeMode.DARK -> Color(0xFF181B28)
                                                    ThemeMode.LIGHT -> Color(0xFFFAF6F0)
                                                    ThemeMode.SYSTEM -> MaterialTheme.colorScheme.surface
                                                }
                                            )
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = modeIcon,
                                            contentDescription = null,
                                            tint = if (mode == ThemeMode.LIGHT) Color(0xFFB26200) else Color(0xFFFFB74D),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = mode.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Выбрано",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Цветовая палитра акцентов",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AccentPalette.entries.forEach { palette ->
                            val selected = settings.accentPalette == palette
                            Surface(
                                onClick = { onAccentPaletteChange(palette) },
                                shape = RoundedCornerShape(50),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = Color(palette.primaryHex)
                                ),
                                modifier = Modifier.testTag("palette_${palette.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(palette.primaryHex))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = palette.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // SECTION 2: НАСТРОЙКА КНОПОК УПРАВЛЕНИЯ
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SmartButton,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Настройка кнопок управления",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Rewind Seconds Step
                    Text(
                        text = "Шаг перемотки назад (-${settings.rewindSeconds} сек)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.rewindSeconds == sec,
                                onClick = { onRewindSecondsChange(sec) },
                                label = { Text("-${sec}с") },
                                modifier = Modifier.testTag("rewind_step_${sec}s")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Forward Seconds Step
                    Text(
                        text = "Шаг перемотки вперёд (+${settings.forwardSeconds} сек)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 30, 45, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.forwardSeconds == sec,
                                onClick = { onForwardSecondsChange(sec) },
                                label = { Text("+${sec}с") },
                                modifier = Modifier.testTag("forward_step_${sec}s")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Button Size
                    Text(
                        text = "Размер кнопок плеера",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlButtonSize.entries.forEach { size ->
                            FilterChip(
                                selected = settings.controlButtonSize == size,
                                onClick = { onButtonSizeChange(size) },
                                label = { Text(size.label) },
                                modifier = Modifier.testTag("btn_size_${size.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Button Shape
                    Text(
                        text = "Форма кнопок управления",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlButtonShape.entries.forEach { shape ->
                            FilterChip(
                                selected = settings.controlButtonShape == shape,
                                onClick = { onButtonShapeChange(shape) },
                                label = { Text(shape.label) },
                                modifier = Modifier.testTag("btn_shape_${shape.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Кнопки переключения глав (⏮ / ⏭)",
                        subtitle = "Показывать быстрый переход к началу и следующей главе",
                        checked = settings.showChapterSkipButtons,
                        onCheckedChange = onShowChapterSkipChange
                    )

                    SettingsToggleRow(
                        title = "Поменять местами кнопки перемотки",
                        subtitle = "Разместить перемотку вперёд слева, а назад — справа",
                        checked = settings.swapSeekButtons,
                        onCheckedChange = onSwapSeekButtonsChange
                    )

                    SettingsToggleRow(
                        title = "Кнопка скорости на экране плеера",
                        subtitle = "Быстрое изменение скорости 0.5x – 2.0x",
                        checked = settings.showSpeedButtonOnPlayer,
                        onCheckedChange = onShowSpeedButtonChange
                    )

                    SettingsToggleRow(
                        title = "Кнопка таймера сна на плеере",
                        subtitle = "Показывать индикатор и вызов таймера сна",
                        checked = settings.showSleepTimerButtonOnPlayer,
                        onCheckedChange = onShowSleepButtonChange
                    )

                    SettingsToggleRow(
                        title = "Кнопка создания закладки",
                        subtitle = "Мгновенное сохранение позиции с заметкой",
                        checked = settings.showBookmarkButtonOnPlayer,
                        onCheckedChange = onShowBookmarkButtonChange
                    )

                    SettingsToggleRow(
                        title = "Кнопка усиления голоса (+7.5 дБ)",
                        subtitle = "Повышение разборчивости тихих аудиокниг",
                        checked = settings.showVoiceBoostButtonOnPlayer,
                        onCheckedChange = onShowVoiceBoostButtonChange
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Потрясти телефон для продолжения (Таймер сна)",
                        subtitle = "Сбросить сработавший таймер сна и продолжить чтение встряхиванием",
                        checked = settings.shakeToResumeSleepEnabled,
                        onCheckedChange = onShakeToResumeSleepChange
                    )

                    if (settings.shakeToResumeSleepEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Сила тряски для сброса таймера сна",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShakeSensitivity.entries.forEach { level ->
                                FilterChip(
                                    selected = settings.shakeSensitivity == level,
                                    onClick = { onShakeSensitivityChange(level) },
                                    label = { Text(level.label) },
                                    modifier = Modifier.testTag("settings_shake_${level.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        }

        // SECTION 3: НАСТРОЙКА ИНТЕРФЕЙСА
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = cardBorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Настройка интерфейса",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Cover Frame Style
                    Text("Оформление обложки аудиокниги", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoverFrameStyle.entries.forEach { style ->
                            FilterChip(
                                selected = settings.coverFrameStyle == style,
                                onClick = { onCoverFrameStyleChange(style) },
                                label = { Text(style.label) },
                                modifier = Modifier.testTag("cover_style_${style.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress Bar Style
                    Text("Вид шкалы прогресса", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressBarStyle.entries.forEach { style ->
                            FilterChip(
                                selected = settings.progressBarStyle == style,
                                onClick = { onProgressBarStyleChange(style) },
                                label = { Text(style.label) },
                                modifier = Modifier.testTag("progress_style_${style.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Library View Mode
                    Text("Режим отображения библиотеки", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryLayoutMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.libraryLayoutMode == mode,
                                onClick = { onLibraryLayoutModeChange(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("library_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Font Scale
                    Text("Масштаб шрифта интерфейса", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    val fontScales = listOf(0.9f to "90%", 1.0f to "100%", 1.15f to "115%", 1.25f to "125%")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fontScales.forEach { (scaleVal, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(settings.fontScale - scaleVal) < 0.03f,
                                onClick = { onFontScaleChange(scaleVal) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("font_scale_$label")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Размытие за обложкой в цветах книги",
                        subtitle = "Мягкое цветное свечение за обложкой и вокруг неё на экране Плеера",
                        checked = settings.coverBlurGlowEnabled,
                        onCheckedChange = onCoverBlurGlowChange,
                        testTag = "toggle_cover_blur_glow"
                    )

                    SettingsToggleRow(
                        title = "Показывать значки аудиоформатов",
                        subtitle = "Отображать бейджи MP3, M4A, M4B, AMR, WAV на карточках",
                        checked = settings.showFormatBadges,
                        onCheckedChange = onShowFormatBadgesChange
                    )

                    SettingsToggleRow(
                        title = "Показывать остаток времени текущей главы",
                        subtitle = "Выводить обратный отсчёт до конца главы под шкалой прогресса",
                        checked = settings.showChapterTimeRemaining,
                        onCheckedChange = onShowChapterRemainingChange
                    )
                }
            }
        }

        // Clear library & Reset defaults buttons
        item {
            OutlinedButton(
                onClick = { showClearLibraryDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_library_button")
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (libraryBookCount > 0) {
                        "Очистить библиотеку ($libraryBookCount)"
                    } else {
                        "Очистить библиотеку"
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onResetDefaults,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reset_settings_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сбросить настройки по умолчанию")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showClearLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showClearLibraryDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Очистить библиотеку?") },
            text = {
                Text(
                    "Все добавленные аудиокниги и закладки будут удалены из списка библиотеки приложения. " +
                        "Сами аудиофайлы в памяти вашего устройства останутся нетронутыми."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearLibraryDialog = false
                        onClearLibrary()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_clear_library_button")
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
