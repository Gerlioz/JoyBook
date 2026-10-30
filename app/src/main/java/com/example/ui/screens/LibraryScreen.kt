package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AudiobookEntity
import com.example.data.preferences.CoverFrameStyle
import com.example.data.preferences.LibraryLayoutMode
import com.example.data.preferences.UserSettings
import com.example.data.repository.formatDurationShort
import com.example.data.repository.formatDurationVerbose
import com.example.data.storage.BookTitleCleaner
import com.example.ui.components.AudiobookCoverArt
import com.example.ui.components.FormatBadgePill
import com.example.ui.theme.LocalIsAmoledTheme

@Composable
fun LibraryScreen(
    books: List<AudiobookEntity>,
    activeBook: AudiobookEntity?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    selectedFormat: String,
    searchQuery: String,
    sleepRemainingMs: Long,
    stopAtEndOfChapter: Boolean,
    settings: UserSettings,
    onSelectFormat: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCycleLayoutMode: () -> Unit,
    onSelectBook: (AudiobookEntity) -> Unit,
    onTogglePlayActive: () -> Unit,
    onToggleFavorite: (AudiobookEntity) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onImportAudioClick: () -> Unit,
    onOpenStorageSearch: () -> Unit,
    onClearLibrary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isAmoled = LocalIsAmoledTheme.current
    val formats = listOf("ALL" to "Все форматы", "M4B" to "M4B", "MP3" to "MP3", "M4A" to "M4A", "WAV" to "WAV", "AMR" to "AMR")
    var showClearDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить библиотеку?") },
            text = {
                Text(
                    "Удалить все книги из списка библиотеки приложения? " +
                        "Аудиофайлы в памяти устройства не будут удалены."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearLibrary()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("library_confirm_clear_button")
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "JoyBook",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "MP3 · M4A · M4B · AMR · WAV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search in Phone Storage button
                    FilledTonalIconButton(
                        onClick = onOpenStorageSearch,
                        modifier = Modifier.testTag("storage_search_button")
                    ) {
                        Icon(Icons.Default.ManageSearch, contentDescription = "Поиск файлов в памяти")
                    }

                    // Sleep timer button
                    Surface(
                        onClick = onOpenSleepTimer,
                        shape = RoundedCornerShape(50),
                        color = if (sleepRemainingMs > 0L || stopAtEndOfChapter) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.testTag("header_sleep_timer_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Таймер сна",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            if (sleepRemainingMs > 0L || stopAtEndOfChapter) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (stopAtEndOfChapter) "Глава" else formatDurationShort(sleepRemainingMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Layout switcher button
                    IconButton(
                        onClick = onCycleLayoutMode,
                        modifier = Modifier.testTag("cycle_library_layout_button")
                    ) {
                        Icon(
                            imageVector = when (settings.libraryLayoutMode) {
                                LibraryLayoutMode.GRID -> Icons.Default.GridView
                                LibraryLayoutMode.DETAILED_LIST -> Icons.Default.ViewAgenda
                                LibraryLayoutMode.COMPACT_LIST -> Icons.Default.ViewHeadline
                            },
                            contentDescription = "Изменить вид библиотеки"
                        )
                    }

                    // Import local audio button
                    FilledIconButton(
                        onClick = onImportAudioClick,
                        modifier = Modifier.testTag("import_audiobook_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Импортировать аудиокнигу")
                    }
                }
            }
        }

        // Continue Listening Hero Card
        if (activeBook != null) {
            item {
                val activeProgress = if (activeBook.durationMs > 0) {
                    (currentPositionMs.toFloat() / activeBook.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Card(
                    onClick = { onSelectBook(activeBook) },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("continue_listening_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPlaying) "СЕЙЧАС ИГРАЕТ" else "ПРОДОЛЖИТЬ ПРОСЛУШИВАНИЕ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (settings.showFormatBadges) {
                                FormatBadgePill(format = activeBook.audioFormat)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val heroCoverWidth = if (settings.coverFrameStyle == CoverFrameStyle.CD_CASE) 85.dp else 76.dp
                            AudiobookCoverArt(
                                coverArtStyle = activeBook.coverArtStyle,
                                audioFormat = "",
                                frameStyle = settings.coverFrameStyle,
                                isPlaying = isPlaying,
                                showFormatBadge = false,
                                coverImagePath = activeBook.coverImagePath,
                                modifier = Modifier.size(width = heroCoverWidth, height = 76.dp)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val heroAuthor = BookTitleCleaner.cleanAuthorName(activeBook.author)
                                val heroTitle = BookTitleCleaner.cleanBookTitle(activeBook.title, heroAuthor).ifBlank { activeBook.title }
                                Text(
                                    text = heroTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = heroAuthor,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { activeProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formatDurationShort(currentPositionMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${(activeProgress * 100).toInt()}% • ${formatDurationVerbose(activeBook.durationMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            FilledIconButton(
                                onClick = onTogglePlayActive,
                                shape = RoundedCornerShape(percent = settings.controlButtonShape.cornerPercent),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("hero_play_pause_button")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Search and Format Filter Bar
        item {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Поиск по названию или автору...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить поиск")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    formats.forEach { (code, label) ->
                        FilterChip(
                            selected = selectedFormat == code,
                            onClick = { onSelectFormat(code) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("format_chip_$code")
                        )
                    }
                    if (books.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { showClearDialog = true },
                            label = {
                                Text(
                                    text = "Очистить библиотеку",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            modifier = Modifier.testTag("library_clear_all_button")
                        )
                    }
                }

                if (searchQuery.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = onOpenStorageSearch,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_in_storage_suggestion")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ManageSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Искать «$searchQuery» во внутренней памяти телефона",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Books list / grid according to user setting
        if (books.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "Ничего не найдено по запросу «$searchQuery»" else "Книги выбранного формата не найдены",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Найдите аудиофайлы MP3, M4A, M4B, AMR или WAV в памяти устройства или выберите файлы вручную.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Button(
                                onClick = onOpenStorageSearch,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("empty_state_scan_storage_button")
                            ) {
                                Icon(Icons.Default.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Найти в памяти")
                            }
                            OutlinedButton(
                                onClick = onImportAudioClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("empty_state_import_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать файл")
                            }
                        }
                    }
                }
            }
        } else {
            when (settings.libraryLayoutMode) {
                LibraryLayoutMode.GRID -> {
                    val rows = books.chunked(2)
                    items(rows) { rowBooks ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowBooks.forEach { book ->
                                GridAudiobookCard(
                                    book = book,
                                    isActive = activeBook?.id == book.id,
                                    settings = settings,
                                    isAmoled = isAmoled,
                                    onClick = { onSelectBook(book) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowBooks.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                LibraryLayoutMode.DETAILED_LIST -> {
                    items(books, key = { it.id }) { book ->
                        DetailedAudiobookCard(
                            book = book,
                            isActive = activeBook?.id == book.id,
                            settings = settings,
                            isAmoled = isAmoled,
                            onClick = { onSelectBook(book) },
                            onToggleFavorite = { onToggleFavorite(book) }
                        )
                    }
                }

                LibraryLayoutMode.COMPACT_LIST -> {
                    items(books, key = { it.id }) { book ->
                        CompactAudiobookRow(
                            book = book,
                            isActive = activeBook?.id == book.id,
                            settings = settings,
                            onClick = { onSelectBook(book) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailedAudiobookCard(
    book: AudiobookEntity,
    isActive: Boolean,
    settings: UserSettings,
    isAmoled: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = if (isActive || isAmoled) {
            BorderStroke(
                width = 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outlineVariant
            )
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book_card_${book.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val detailedCoverWidth = if (settings.coverFrameStyle == CoverFrameStyle.CD_CASE) 94.dp else 84.dp
            AudiobookCoverArt(
                coverArtStyle = book.coverArtStyle,
                audioFormat = book.audioFormat,
                frameStyle = settings.coverFrameStyle,
                showFormatBadge = settings.showFormatBadges,
                coverImagePath = book.coverImagePath,
                modifier = Modifier.size(width = detailedCoverWidth, height = 84.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                val displayAuthor = BookTitleCleaner.cleanAuthorName(book.author)
                val displayTitle = BookTitleCleaner.cleanBookTitle(book.title, displayAuthor).ifBlank { book.title }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayAuthor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${book.audioFormat} • ${book.bitrateKbps} кбит/с",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LinearProgressIndicator(
                        progress = { book.progressFraction },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = formatDurationVerbose(book.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Избранное",
                    tint = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GridAudiobookCard(
    book: AudiobookEntity,
    isActive: Boolean,
    settings: UserSettings,
    isAmoled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = if (isActive || isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else null,
        modifier = modifier.testTag("grid_book_card_${book.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AudiobookCoverArt(
                coverArtStyle = book.coverArtStyle,
                audioFormat = book.audioFormat,
                frameStyle = settings.coverFrameStyle,
                showFormatBadge = settings.showFormatBadges,
                coverImagePath = book.coverImagePath,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            Spacer(modifier = Modifier.height(10.dp))
            val displayAuthor = BookTitleCleaner.cleanAuthorName(book.author)
            val displayTitle = BookTitleCleaner.cleanBookTitle(book.title, displayAuthor).ifBlank { book.title }
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayAuthor,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { book.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
private fun CompactAudiobookRow(
    book: AudiobookEntity,
    isActive: Boolean,
    settings: UserSettings,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("compact_book_row_${book.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val compactCoverWidth = if (settings.coverFrameStyle == CoverFrameStyle.CD_CASE) 52.dp else 46.dp
            AudiobookCoverArt(
                coverArtStyle = book.coverArtStyle,
                audioFormat = "",
                frameStyle = settings.coverFrameStyle,
                showFormatBadge = false,
                coverImagePath = book.coverImagePath,
                modifier = Modifier.size(width = compactCoverWidth, height = 46.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val displayAuthor = BookTitleCleaner.cleanAuthorName(book.author)
                val displayTitle = BookTitleCleaner.cleanBookTitle(book.title, displayAuthor).ifBlank { book.title }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$displayAuthor • ${formatDurationVerbose(book.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (settings.showFormatBadges) {
                FormatBadgePill(format = book.audioFormat)
            }
        }
    }
}
