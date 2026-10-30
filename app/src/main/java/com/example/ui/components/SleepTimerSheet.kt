package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.preferences.ShakeSensitivity
import com.example.data.repository.formatDurationShort
import java.util.Calendar
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerModalSheet(
    remainingSleepMs: Long,
    stopAtEndOfChapter: Boolean,
    smartInactivityEnabled: Boolean = false,
    inactivityTimeoutMinutes: Int = 20,
    sleepFadeOutEnabled: Boolean,
    shakeToResumeEnabled: Boolean = true,
    shakeSensitivity: ShakeSensitivity = ShakeSensitivity.MEDIUM,
    onStartTimerMinutes: (Int) -> Unit,
    onStartEndOfChapter: () -> Unit,
    onToggleSmartInactivity: (Boolean, Int) -> Unit = { _, _ -> },
    onToggleStopAtEndOfChapter: () -> Unit = onStartEndOfChapter,
    onExtendFiveMinutes: () -> Unit,
    onCancelTimer: () -> Unit,
    onToggleFadeOut: (Boolean) -> Unit,
    onToggleShakeToResume: (Boolean) -> Unit = {},
    onShakeSensitivityChange: (ShakeSensitivity) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Быстрый выбор, 1: Длительность (Ч:М), 2: Будильник отключения (В ЧЧ:ММ)

    var customHours by remember { mutableIntStateOf(0) }
    var customMinutes by remember { mutableIntStateOf(25) }

    val nowCal = remember { Calendar.getInstance() }
    var targetHour by remember { mutableIntStateOf((nowCal.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var targetMinute by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("sleep_timer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Таймер сна",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Умный таймер сна",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Остановка по главам, бездействию или времени",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Timer Status Banner
            if (remainingSleepMs > 0L || stopAtEndOfChapter || smartInactivityEnabled) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val statusTitle = when {
                                    stopAtEndOfChapter -> "Остановка в конце текущей главы"
                                    smartInactivityEnabled && remainingSleepMs <= 0L -> "Таймер бездействия активен"
                                    else -> "До остановки осталось"
                                }
                                val statusValue = when {
                                    stopAtEndOfChapter -> "Конец главы"
                                    smartInactivityEnabled && remainingSleepMs <= 0L -> "$inactivityTimeoutMinutes мин покоя"
                                    else -> formatDurationShort(remainingSleepMs)
                                }
                                Text(
                                    text = statusTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = statusValue,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (remainingSleepMs > 0L && !stopAtEndOfChapter) {
                                    OutlinedButton(
                                        onClick = onExtendFiveMinutes,
                                        modifier = Modifier.testTag("extend_sleep_5m_button")
                                    ) {
                                        Text("+5 мин")
                                    }
                                }
                                Button(
                                    onClick = onCancelTimer,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    modifier = Modifier.testTag("cancel_sleep_timer_button")
                                ) {
                                    Text("Сброс")
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Smart Modes Card (End of chapter & Inactivity)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("smart_sleep_detection_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Интеллектуальное обнаружение сна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. End of chapter detection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleStopAtEndOfChapter() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Остановка в конце текущей главы",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Воспроизведение встанет на паузу точно на границе главы",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = stopAtEndOfChapter,
                            onCheckedChange = { onToggleStopAtEndOfChapter() },
                            modifier = Modifier.testTag("stop_at_end_of_chapter_switch")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    // 2. Smart inactivity detection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSmartInactivity(!smartInactivityEnabled, inactivityTimeoutMinutes) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Таймер бездействия",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Пауза, если вы уснули и не трогаете телефон. Любое нажатие или перемотка сбрасывает счётчик.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = smartInactivityEnabled,
                            onCheckedChange = { onToggleSmartInactivity(it, inactivityTimeoutMinutes) },
                            modifier = Modifier.testTag("smart_inactivity_switch")
                        )
                    }

                    if (smartInactivityEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Период бездействия до паузы:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(10, 15, 20, 30, 45).forEach { mins ->
                                FilterChip(
                                    selected = inactivityTimeoutMinutes == mins,
                                    onClick = { onToggleSmartInactivity(true, mins) },
                                    label = { Text("$mins мин") },
                                    modifier = Modifier.testTag("inactivity_chip_${mins}m")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 3: Shake phone to reset sleep timer and resume playback
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleShakeToResume(!shakeToResumeEnabled) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Потрясти телефон для продолжения",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Если сработал таймер сна, встряхните телефон, чтобы сбросить таймер и продолжить чтение",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = shakeToResumeEnabled,
                            onCheckedChange = onToggleShakeToResume,
                            modifier = Modifier.testTag("shake_to_resume_switch")
                        )
                    }

                    if (shakeToResumeEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Сила тряски для срабатывания:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShakeSensitivity.entries.forEach { level ->
                                FilterChip(
                                    selected = shakeSensitivity == level,
                                    onClick = { onShakeSensitivityChange(level) },
                                    label = { Text(level.label) },
                                    modifier = Modifier.testTag("shake_sensitivity_${level.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Mode Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Пресеты") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Выбор времени") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("К часам ЧЧ:ММ") }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            when (selectedTab) {
                0 -> {
                    Text(
                        text = "Быстрый запуск таймера",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val presets = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        presets.forEach { mins ->
                            val label = if (mins >= 60) {
                                val h = mins / 60
                                val m = mins % 60
                                if (m == 0) "$h ч" else "$h ч $m м"
                            } else {
                                "$mins мин"
                            }
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onStartTimerMinutes(mins)
                                    onDismiss()
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                },
                                modifier = Modifier.testTag("sleep_preset_${mins}m")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            onStartEndOfChapter()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sleep_end_of_chapter_button")
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Остановить после окончания текущей главы")
                    }
                }

                1 -> {
                    Text(
                        text = "Точный выбор времени (часы и минуты)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val totalCustomMinutes = (customHours * 60 + customMinutes).coerceAtLeast(1)
                            Text(
                                text = if (customHours > 0) "$customHours ч $customMinutes мин" else "$customMinutes мин",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Hours selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Часы: $customHours ч", style = MaterialTheme.typography.bodyLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (customHours > 0) customHours-- }
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Меньше часов")
                                    }
                                    Text(
                                        text = "$customHours",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    IconButton(
                                        onClick = { if (customHours < 8) customHours++ }
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Больше часов")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Minutes slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Минуты: $customMinutes мин", style = MaterialTheme.typography.bodyLarge)
                                Text("1–59 мин", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Slider(
                                value = customMinutes.toFloat(),
                                onValueChange = { customMinutes = it.roundToInt().coerceIn(0, 59) },
                                valueRange = 0f..59f,
                                modifier = Modifier.testTag("custom_sleep_minutes_slider")
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    onStartTimerMinutes(totalCustomMinutes)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("start_custom_duration_sleep_button")
                            ) {
                                Text("Запустить таймер на $totalCustomMinutes мин")
                            }
                        }
                    }
                }

                2 -> {
                    Text(
                        text = "Выключить плеер в заданное время",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val currentCal = Calendar.getInstance()
                    val nowMinutesOfDay = currentCal.get(Calendar.HOUR_OF_DAY) * 60 + currentCal.get(Calendar.MINUTE)
                    val targetMinutesOfDay = targetHour * 60 + targetMinute
                    val diffMinutes = if (targetMinutesOfDay > nowMinutesOfDay) {
                        targetMinutesOfDay - nowMinutesOfDay
                    } else {
                        (24 * 60 - nowMinutesOfDay) + targetMinutesOfDay
                    }.coerceAtLeast(1)

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = String.format("%02d:%02d", targetHour, targetMinute),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Плеер остановится через ${diffMinutes / 60} ч ${diffMinutes % 60} мин",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Hour and Minute adjusters
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Час", style = MaterialTheme.typography.labelMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { targetHour = (targetHour + 23) % 24 }) {
                                            Icon(Icons.Default.Remove, contentDescription = "Час назад")
                                        }
                                        Text(
                                            text = String.format("%02d", targetHour),
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        IconButton(onClick = { targetHour = (targetHour + 1) % 24 }) {
                                            Icon(Icons.Default.Add, contentDescription = "Час вперёд")
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Минуты", style = MaterialTheme.typography.labelMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { targetMinute = (targetMinute + 55) % 60 }) {
                                            Icon(Icons.Default.Remove, contentDescription = "-5 минут")
                                        }
                                        Text(
                                            text = String.format("%02d", targetMinute),
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        IconButton(onClick = { targetMinute = (targetMinute + 5) % 60 }) {
                                            Icon(Icons.Default.Add, contentDescription = "+5 минут")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    onStartTimerMinutes(diffMinutes)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("start_clock_sleep_button")
                            ) {
                                Text(String.format("Остановить в %02d:%02d", targetHour, targetMinute))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Smooth Fade-Out Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleFadeOut(!sleepFadeOutEnabled) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Плавное затухание звука",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Постепенно снижать громкость за 30 секунд до сна",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = sleepFadeOutEnabled,
                    onCheckedChange = onToggleFadeOut
                )
            }
        }
    }
}
