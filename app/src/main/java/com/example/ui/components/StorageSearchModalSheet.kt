package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.repository.formatDurationShort
import com.example.data.storage.ScannedBookGroup
import com.example.ui.theme.LocalIsAmoledTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSearchModalSheet(
    sheetState: SheetState,
    isScanning: Boolean,
    isImporting: Boolean,
    scannedBookGroups: List<ScannedBookGroup>,
    selectedGroupIds: Set<String>,
    selectedFolderName: String?,
    searchQuery: String,
    selectedFormat: String,
    selectedMinDurationMs: Long,
    selectedFolder: String,
    availableFolders: List<String>,
    onDismissRequest: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFormatChange: (String) -> Unit,
    onMinDurationChange: (Long) -> Unit,
    onFolderChange: (String) -> Unit,
    onToggleSelectGroup: (ScannedBookGroup) -> Unit,
    onSelectAllGroups: () -> Unit,
    onClearGroupSelection: () -> Unit,
    onPickFolderClick: () -> Unit,
    onClearFolder: () -> Unit,
    onRescanFolder: () -> Unit,
    onPickFilesClick: () -> Unit,
    onImportGroup: (ScannedBookGroup) -> Unit,
    onImportSelectedGroups: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAmoled = LocalIsAmoledTheme.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = if (isAmoled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .testTag("storage_search_modal_sheet")
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ManageSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Поиск аудиокниг",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Группировка частей одной книги • без сканирования всей памяти",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.testTag("close_storage_sheet_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dedicated Folder Selector Banner (Scoped to folder only)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedFolderName != null) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ),
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folder_scope_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = if (selectedFolderName != null) "Папка поиска:" else "Папка не выбрана",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = selectedFolderName ?: "Нажмите, чтобы выбрать папку",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedFolderName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selectedFolderName != null) {
                            IconButton(
                                onClick = onRescanFolder,
                                enabled = !isScanning,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить папку",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onPickFolderClick,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("pick_folder_button")
                        ) {
                            Text(
                                text = if (selectedFolderName != null) "Сменить" else "Выбрать папку",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Recommended Folder Hierarchy Tip Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folder_hierarchy_hint_card")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryBooks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Иерархия: Корневая папка / Автор / Название книги / *.mp3. Каждая книга в своей папке, книги без тегов группируются и именуются по папкам.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Search Bar Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Поиск по названию книги, автору или файлу...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("storage_search_input")
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Format Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("ALL" to "Все форматы", "M4B" to "M4B", "MP3" to "MP3", "M4A" to "M4A", "WAV" to "WAV", "AMR" to "AMR").forEach { (code, label) ->
                    val isSelected = selectedFormat == code
                    FilterChip(
                        selected = isSelected,
                        onClick = { onFormatChange(code) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }

                listOf(
                    0L to "Любая длина",
                    60_000L to "> 1 мин",
                    300_000L to "> 5 мин",
                    600_000L to "> 10 мин"
                ).forEach { (durMs, label) ->
                    val isSelected = selectedMinDurationMs == durMs
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMinDurationChange(durMs) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Summary and Select All bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val newCount = scannedBookGroups.count { !it.isAlreadyInLibrary }
                val totalFiles = scannedBookGroups.sumOf { it.fileCount }
                Text(
                    text = "Найдено книг: ${scannedBookGroups.size} ($totalFiles файлов) • Новых: $newCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (scannedBookGroups.isNotEmpty()) {
                        TextButton(onClick = onSelectAllGroups) {
                            Text("Выбрать все", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (selectedGroupIds.isNotEmpty()) {
                        TextButton(onClick = onClearGroupSelection) {
                            Text("Сбросить (${selectedGroupIds.size})", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }

            // Results List or Empty State
            if (scannedBookGroups.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = if (selectedFolderName == null) {
                                "Выберите папку для поиска"
                            } else if (searchQuery.isNotBlank()) {
                                "Ничего не найдено по запросу «$searchQuery»"
                            } else {
                                "В папке «$selectedFolderName» нет аудиофайлов"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = if (selectedFolderName == null) {
                                "Поиск выполняется только в указанной вами папке. Если книга состоит из нескольких файлов (глав), они автоматически объединятся в одну книгу."
                            } else {
                                "Поддерживаются форматы MP3, M4B, M4A, AMR и WAV. Файлы одной книги автоматически группируются."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Button(
                                onClick = onPickFolderClick,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("empty_state_pick_folder_button")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать папку")
                            }

                            OutlinedButton(
                                onClick = onPickFilesClick,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Выбрать файлы")
                            }
                        }
                    }
                }
            } else {
                // Grouped Audiobooks List (guaranteed unique keys)
                val distinctGroups = remember(scannedBookGroups) {
                    scannedBookGroups.distinctBy { it.groupId }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("scanned_book_groups_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = distinctGroups,
                        key = { it.groupId }
                    ) { group ->
                        ScannedBookGroupCard(
                            group = group,
                            isSelected = selectedGroupIds.contains(group.groupId),
                            onToggleSelect = { onToggleSelectGroup(group) },
                            onQuickImport = { onImportGroup(group) },
                            isAmoled = isAmoled
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Import Action Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Button(
                    onClick = onImportSelectedGroups,
                    enabled = selectedGroupIds.isNotEmpty() && !isImporting,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("import_selected_files_button")
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импорт в библиотеку JoyBook...")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        val text = if (selectedGroupIds.isEmpty()) {
                            "Выберите аудиокниги для добавления"
                        } else {
                            "Импортировать в библиотеку (${selectedGroupIds.size})"
                        }
                        Text(text)
                    }
                }
            }
        }
    }
}

/**
 * Compact grouped book item view showing aggregated book tags, multi-file badge,
 * and expandable constituent chapter/part list.
 */
@Composable
fun ScannedBookGroupCard(
    group: ScannedBookGroup,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onQuickImport: () -> Unit,
    isAmoled: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val coverBitmap = remember(group.coverImagePath) {
        val path = group.coverImagePath
        if (!path.isNullOrBlank()) {
            try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, boundsOpts)
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 160) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(path, decodeOpts)?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        } else null
    }

    val primaryFormat = group.formats.firstOrNull() ?: "MP3"

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else if (isAmoled) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        } else null,
        modifier = modifier
            .fillMaxWidth()
            .testTag("scanned_book_${group.groupId}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !group.isAlreadyInLibrary) { onToggleSelect() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Checkbox or already-in-library indicator
                if (group.isAlreadyInLibrary) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Уже в библиотеке",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Cover Art thumbnail (46x46 dp) from tags
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (primaryFormat) {
                                "M4B" -> MaterialTheme.colorScheme.primaryContainer
                                "MP3" -> MaterialTheme.colorScheme.secondaryContainer
                                "M4A" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = "Обложка ${group.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (group.isMultiFile) Icons.Default.LibraryBooks else Icons.Default.AudioFile,
                            contentDescription = null,
                            tint = when (primaryFormat) {
                                "M4B" -> MaterialTheme.colorScheme.primary
                                "MP3" -> MaterialTheme.colorScheme.secondary
                                "M4A" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Compact Metadata Column
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (group.isMultiFile) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "${group.fileCount} файлов • $primaryFormat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (primaryFormat) {
                                    "M4B" -> MaterialTheme.colorScheme.primary
                                    "MP3" -> MaterialTheme.colorScheme.secondary
                                    "M4A" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = primaryFormat,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (primaryFormat) {
                                        "M4B" -> MaterialTheme.colorScheme.onPrimary
                                        "MP3" -> MaterialTheme.colorScheme.onSecondary
                                        "M4A" -> MaterialTheme.colorScheme.onTertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = group.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        val sizeMb = String.format("%.1f", group.totalSizeBytes / (1024.0 * 1024.0))
                        Text(
                            text = "• ${formatDurationShort(group.totalDurationMs)} • $sizeMb МБ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Actions Column
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (group.isMultiFile) {
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Скрыть файлы" else "Показать файлы",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (group.isAlreadyInLibrary) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "В библиотеке",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onQuickImport,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("quick_import_${group.groupId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Добавить",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Expandable Constituent Files/Chapters List
            AnimatedVisibility(
                visible = isExpanded && group.isMultiFile,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))
                    Text(
                        text = "Составные части аудиокниги (${group.fileCount} файлов):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    group.files.forEachIndexed { index, file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "#${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = file.title.ifBlank { file.displayName },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val partMb = String.format("%.1f", file.sizeBytes / (1024.0 * 1024.0))
                            Text(
                                text = "${formatDurationShort(file.durationMs)} • $partMb МБ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
